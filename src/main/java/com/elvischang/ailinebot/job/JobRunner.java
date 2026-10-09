package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.delivery.ReplySender;
import com.elvischang.ailinebot.factcheck.FactCheckReply;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.service.ImageGenerationService;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
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
    private final FactCheckService factCheckService;
    private final ImageGenerationService imageGenerationService;
    private final ReplySender replySender;
    private final BotProperties props;
    private final Clock clock;

    public JobRunner(JobRepository jobs, MaterialRepository materials, FactCheckService factCheckService,
                     ImageGenerationService imageGenerationService, ReplySender replySender,
                     BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
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
        Optional<Material> material = materials.find(job.groupId(), job.materialMessageId());
        if (material.isEmpty()) {
            return BotReplies.QUOTE_NOT_FOUND;
        }
        FactCheckReply reply = factCheckService.verify(material.get(), job.userQuestion());
        if (reply.text() == null || reply.text().isBlank()) {
            throw new IllegalStateException("fact check produced no reply");
        }
        jobs.recordFactCheck(job.id(), job.attempts(), reply.verdict(), reply.usage(), reply.costMicroUsd());
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
