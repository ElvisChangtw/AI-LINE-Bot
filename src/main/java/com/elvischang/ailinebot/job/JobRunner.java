package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.delivery.ReplySender;
import com.elvischang.ailinebot.factcheck.FactCheckReply;
import com.elvischang.ailinebot.factcheck.FactCheckService;
import com.elvischang.ailinebot.factcheck.InvalidModelOutputException;
import com.elvischang.ailinebot.factcheck.OutOfCreditException;
import com.elvischang.ailinebot.factcheck.ReplyMode;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.line.SenderNames;
import com.elvischang.ailinebot.material.Questions;
import com.elvischang.ailinebot.material.RecentConversation;
import com.elvischang.ailinebot.media.MediaTranscriber;
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
    private static final int MAX_ABOUT_CHARS = 2000;
    private static final String IMAGE_ABOUT = "（一張圖片）";
    private static final String SILENT_VIDEO_ABOUT = "（一段沒有說話的影片）";

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final RecentMaterialPicker picker;
    private final RecentConversation conversation;
    private final SenderNames senderNames;
    private final MediaTranscriber transcriber;
    private final FactCheckService factCheckService;
    private final ImageGenerationService imageGenerationService;
    private final ReplySender replySender;
    private final BotProperties props;
    private final Clock clock;

    public JobRunner(JobRepository jobs, MaterialRepository materials, RecentMaterialPicker picker,
                     RecentConversation conversation, SenderNames senderNames,
                     MediaTranscriber transcriber, FactCheckService factCheckService,
                     ImageGenerationService imageGenerationService, ReplySender replySender,
                     BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.picker = picker;
        this.conversation = conversation;
        this.senderNames = senderNames;
        this.transcriber = transcriber;
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
        String question = Questions.normalize(job.userQuestion());
        if (job.materialMessageId() != null) {
            Optional<Material> found = materials.find(job.groupId(), job.materialMessageId());
            if (found.isEmpty()) {
                return BotReplies.QUOTE_NOT_FOUND;
            }
            return checkMaterial(job, found.get(), question);
        }
        FactCheckReply firstPass = null;
        // Only a question without a quote needs the chat to know what it refers to; checks of one message stay cacheable.
        RecentConversation.Snapshot chat = RecentConversation.Snapshot.EMPTY;
        if (!question.isBlank()) {
            chat = conversation.of(job.groupId(), job.triggerMessageId(), job.createdAt());
            firstPass = factCheckService.verify(job.groupId(), null, question, chat.text());
            if (firstPass.mode() != ReplyMode.NEED_MATERIAL) {
                return recorded(job, firstPass, question);
            }
        }
        RecentConversation.Snapshot seen = chat;
        Optional<RecentMaterialPicker.Picked> picked = seen.message(firstPass == null ? null : firstPass.targetMessage())
                .flatMap(target -> RecentMaterialPicker.at(seen.messages(), target));
        if (picked.isEmpty()) {
            // Only a real question can be about our own last answer; a bare "is this true" means the message above.
            picked = picker.pick(job.groupId(), job.triggerMessageId(), job.createdAt(), firstPass != null);
        }
        if (picked.isEmpty()) {
            return firstPass == null ? BotReplies.ASK_TO_QUOTE : recorded(job, firstPass, question);
        }
        return lookedBack(job, picked.get(), question);
    }

    private String lookedBack(Job job, RecentMaterialPicker.Picked picked, String question) {
        Material material = picked.material();
        String reply;
        try {
            reply = checkMaterial(job, material, question);
        } catch (InvalidModelOutputException e) {
            // We guessed the message; if the model still cannot use it, asking beats retrying the guess.
            log.info("look-back unusable jobId={} cause={}", job.id(), e.getMessage());
            return BotReplies.ASK_TO_QUOTE;
        }
        if (picked.ownReply()) {
            return reply;
        }
        String name = senderNames.displayName(job.groupId(), picked.senderId()).orElse(null);
        return BotReplies.lookedAt(name, material.createdAt(), snippetSource(material)) + reply;
    }

    private static String snippetSource(Material material) {
        return switch (material.kind()) {
            case AUDIO -> "語音訊息";
            case VIDEO -> "影片";
            default -> material.text();
        };
    }

    // Voice and video are transcribed first; anything that cannot be checked gets a plain notice.
    private String checkMaterial(Job job, Material material, String question) {
        if (material.kind() == MaterialKind.BOT_REPLY) {
            // A follow-up keeps pointing at what the discussion started from, so it never grows turn by turn.
            String about = materials.about(material.groupId(), material.messageId()).orElse(null);
            Material target = RecentMaterialPicker.asFollowUp(material, about);
            return recorded(job, factCheckService.verify(job.groupId(), target, question), about == null ? material.text() : about);
        }
        Material target = material;
        if (material.kind() == MaterialKind.AUDIO || material.kind() == MaterialKind.VIDEO) {
            MediaTranscriber.Prepared prepared;
            try {
                prepared = transcriber.prepare(material);
            } catch (OutOfCreditException e) {
                log.warn("transcription refused jobId={} reason=out_of_credit", job.id());
                return BotReplies.OUT_OF_CREDIT;
            }
            switch (prepared) {
                case MediaTranscriber.Prepared.Ready ready -> target = ready.material();
                case MediaTranscriber.Prepared.NotReady notReady -> {
                    return BotReplies.MEDIA_NOT_READY;
                }
                case MediaTranscriber.Prepared.TooLong tooLong -> {
                    return BotReplies.MEDIA_TOO_LONG;
                }
                case MediaTranscriber.Prepared.Gone gone -> {
                    return BotReplies.MEDIA_GONE;
                }
                case MediaTranscriber.Prepared.Unclear unclear -> {
                    return BotReplies.MEDIA_UNCLEAR;
                }
                case MediaTranscriber.Prepared.OverBudget overBudget -> {
                    return BotReplies.QUOTA_EXCEEDED;
                }
            }
        }
        String about = target.text() != null && !target.text().isBlank() ? target.text()
                : material.kind() == MaterialKind.VIDEO ? SILENT_VIDEO_ABOUT : IMAGE_ABOUT;
        return recorded(job, factCheckService.verify(job.groupId(), target, question), about);
    }

    private String recorded(Job job, FactCheckReply reply, String about) {
        if (reply.text() == null || reply.text().isBlank()) {
            throw new IllegalStateException("fact check produced no reply");
        }
        String capped = about == null || about.codePointCount(0, about.length()) <= MAX_ABOUT_CHARS
                ? about : about.substring(0, about.offsetByCodePoints(0, MAX_ABOUT_CHARS));
        jobs.recordFactCheck(job.id(), job.attempts(), reply.verdict(), reply.mode(), reply.usage(), reply.costMicroUsd(), capped);
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
