package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.delivery.ReplySender;
import com.elvischang.ailinebot.factcheck.FactCheckReply;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.factcheck.InvalidModelOutputException;
import com.elvischang.ailinebot.factcheck.ReplyMode;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.line.SenderNames;
import com.elvischang.ailinebot.material.RecentMaterialPicker;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final RecentMaterialPicker picker;
    private final SenderNames senderNames;
    private final FactCheckService factCheckService;
    private final ImageGenerationService imageGenerationService;
    private final ReplySender replySender;
    private final BotProperties props;
    private final Clock clock;

    public JobRunner(JobRepository jobs, MaterialRepository materials, RecentMaterialPicker picker, SenderNames senderNames, FactCheckService factCheckService,
                     ImageGenerationService imageGenerationService, ReplySender replySender,
                     BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.picker = picker;
        this.senderNames = senderNames;
        this.factCheckService = factCheckService;
        this.imageGenerationService = imageGenerationService;
        this.replySender = replySender;
        this.props = props;
        this.clock = clock;
    }

    public void run(String jobId) {
        try {
            if (jobs.claim(jobId, clock.millis(), props.jobLease().toMillis())) {
                Job job = jobs.find(jobId).orElseThrow();
                if (!compute(job)) {
                    return;
                }
            }
            replySender.send(jobId);
        } catch (RuntimeException e) {
            log.error("job crashed jobId={} cause={}", jobId, e.getClass().getSimpleName(), e);
        }
    }

    private boolean compute(Job job) {
        long started = clock.millis();
        try {
            String result = switch (job.kind()) {
                case NOTICE -> job.resultText();
                case DELIVERY -> null;
                case FACT_CHECK -> factCheck(job);
                case IMAGINE -> imagine(job.userQuestion());
            };
            jobs.complete(job.id(), result, clock.millis(), job.attempts());
            log.info("job done jobId={} kind={} groupHash={} attempt={} runMs={}",
                    job.id(), job.kind(), Hashes.shortHash(job.groupId()), job.attempts(), clock.millis() - started);
            return true;
        } catch (RuntimeException e) {
            String code = e.getClass().getSimpleName();
            if (job.attempts() >= props.jobMaxAttempts()) {
                jobs.fail(job.id(), BotReplies.JOB_FAILED, code, clock.millis(), job.attempts());
                log.warn("job failed jobId={} kind={} attempts={} cause={}", job.id(), job.kind(), job.attempts(), code);
                return true;
            }
            jobs.release(job.id(), code, job.attempts());
            log.warn("job will retry jobId={} kind={} attempts={} cause={}", job.id(), job.kind(), job.attempts(), code);
            return false;
        }
    }

    private String factCheck(Job job) {
        if (job.materialMessageId() != null) {
            Optional<Material> found = materials.find(job.groupId(), job.materialMessageId());
            if (found.isEmpty()) {
                return BotReplies.QUOTE_NOT_FOUND;
            }
            return recorded(job, factCheckService.verify(job.groupId(), found.get(), job.userQuestion()));
        }
        String question = job.userQuestion() == null ? "" : job.userQuestion();
        FactCheckReply firstPass = null;
        if (!question.isBlank()) {
            firstPass = factCheckService.verify(job.groupId(), null, question);
            if (firstPass.mode() != ReplyMode.NEED_MATERIAL) {
                return recorded(job, firstPass);
            }
        }
        Optional<RecentMaterialPicker.Picked> picked = picker.pick(job.groupId(), job.triggerMessageId(), job.createdAt());
        if (picked.isEmpty()) {
            return firstPass == null ? BotReplies.ASK_TO_QUOTE : recorded(job, firstPass);
        }
        return lookedBack(job, picked.get(), question);
    }

    private String lookedBack(Job job, RecentMaterialPicker.Picked picked, String question) {
        Material material = picked.material();
        if (material.kind() == MaterialKind.AUDIO || material.kind() == MaterialKind.VIDEO) {
            return BotReplies.MEDIA_UNSUPPORTED;
        }
        FactCheckReply reply;
        try {
            reply = factCheckService.verify(job.groupId(), material, question);
        } catch (InvalidModelOutputException e) {
            // We guessed the message; if the model still cannot use it, asking beats retrying the guess.
            log.info("look-back unusable jobId={} cause={}", job.id(), e.getMessage());
            return BotReplies.ASK_TO_QUOTE;
        }
        String name = senderNames.displayName(job.groupId(), picked.senderId()).orElse(null);
        return BotReplies.lookedAt(name, material.createdAt(), material.text()) + recorded(job, reply);
    }

    private String recorded(Job job, FactCheckReply reply) {
        if (reply.text() == null || reply.text().isBlank()) {
            throw new IllegalStateException("fact check produced no reply");
        }
        jobs.recordFactCheck(job.id(), job.attempts(), reply.verdict(), reply.mode(), reply.usage(), reply.costMicroUsd());
        return reply.text();
    }

    private String imagine(String prompt) {
        String url = imageGenerationService.generateImage(prompt);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("image generation returned no URL");
        }
        return url;
    }
}
