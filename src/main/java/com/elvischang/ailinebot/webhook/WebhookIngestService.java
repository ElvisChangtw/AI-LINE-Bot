package com.elvischang.ailinebot.webhook;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.job.ImageReceivedEvent;
import com.elvischang.ailinebot.job.JobReadyEvent;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.line.LineSources;
import com.elvischang.ailinebot.line.Mentions;
import com.elvischang.ailinebot.material.MaterialResolver;
import com.elvischang.ailinebot.material.Resolution;
import com.elvischang.ailinebot.material.Trigger;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.InboundEventRepository;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.linecorp.bot.webhook.model.AudioMessageContent;
import com.linecorp.bot.webhook.model.DeliveryContext;
import com.linecorp.bot.webhook.model.Event;
import com.linecorp.bot.webhook.model.ImageMessageContent;
import com.linecorp.bot.webhook.model.JoinEvent;
import com.linecorp.bot.webhook.model.LeaveEvent;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UnsendEvent;
import com.linecorp.bot.webhook.model.VideoMessageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final InboundEventRepository inboundEvents;
    private final MaterialRepository materials;
    private final JobRepository jobs;
    private final MaterialResolver resolver;
    private final MediaStore mediaStore;
    private final ApplicationEventPublisher publisher;
    private final BotProperties props;
    private final Clock clock;

    public WebhookIngestService(InboundEventRepository inboundEvents, MaterialRepository materials, JobRepository jobs,
                                MaterialResolver resolver, MediaStore mediaStore, ApplicationEventPublisher publisher,
                                BotProperties props, Clock clock) {
        this.inboundEvents = inboundEvents;
        this.materials = materials;
        this.jobs = jobs;
        this.resolver = resolver;
        this.mediaStore = mediaStore;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public void ingest(List<Event> events) {
        long now = clock.millis();
        for (Event event : events) {
            if (event instanceof MessageEvent message) {
                ingestMessage(message, now);
            } else if (event instanceof JoinEvent join) {
                ingestJoin(join, now);
            } else if (event instanceof UnsendEvent unsend) {
                ingestUnsend(unsend, now);
            } else if (event instanceof LeaveEvent leave) {
                ingestLeave(leave, now);
            }
        }
    }

    private void ingestMessage(MessageEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        String chatId = chat.get();
        String sender = LineSources.senderId(event.source()).orElse(null);
        String jobId = null;
        switch (event.message()) {
            case TextMessageContent text -> jobId = ingestText(chatId, text, event.replyToken(), sender, now);
            case ImageMessageContent image -> storeMedia(chatId, image.id(), MaterialKind.IMAGE, sender, now);
            case AudioMessageContent audio -> storeMedia(chatId, audio.id(), MaterialKind.AUDIO, sender, now);
            case VideoMessageContent video -> storeMedia(chatId, video.id(), MaterialKind.VIDEO, sender, now);
            default -> {
            }
        }
        carryWaitingResults(chatId, jobId, event.replyToken(), now);
    }

    private void ingestJoin(JoinEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        createJob(new NewJob(JobKind.NOTICE, chat.get(), null, null, null,
                event.replyToken(), null, BotReplies.JOIN_NOTICE), now);
    }

    private void ingestUnsend(UnsendEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        String messageId = event.unsend().messageId();
        materials.delete(chat.get(), messageId);
        deleteMedia(chat.get(), messageId);
    }

    private void ingestLeave(LeaveEvent event, long now) {
        Optional<String> chat = LineSources.chatId(event.source());
        if (chat.isEmpty() || !isFirstDelivery(event.webhookEventId(), event.deliveryContext(), now)) {
            return;
        }
        String chatId = chat.get();
        materials.deleteGroup(chatId).forEach(messageId -> deleteMedia(chatId, messageId));
        jobs.expireGroup(chatId);
    }

    private void deleteMedia(String chatId, String messageId) {
        try {
            mediaStore.delete(chatId, messageId);
        } catch (RuntimeException e) {
            log.warn("media delete failed groupHash={} messageId={} cause={}",
                    Hashes.shortHash(chatId), messageId, e.getClass().getSimpleName());
        }
    }

    private boolean isFirstDelivery(String webhookEventId, DeliveryContext context, long now) {
        boolean redelivery = context != null && Boolean.TRUE.equals(context.isRedelivery());
        return inboundEvents.insertIfAbsent(webhookEventId, now, redelivery);
    }

    private String ingestText(String chatId, TextMessageContent text, String replyToken, String sender, long now) {
        boolean mentioned = Mentions.mentionsBot(text);
        String body = mentioned ? Mentions.stripBotMentions(text) : text.text();
        materials.insert(new Material(chatId, text.id(), MaterialKind.TEXT, body, mentioned,
                MediaStatus.NONE, null, 0, now, expiry(now)), sender);
        if (!mentioned) {
            return null;
        }
        Resolution resolution = resolver.resolve(new Trigger(chatId, text.id(), body, text.quotedMessageId()), now);
        return createJob(toNewJob(resolution, chatId, text, body, replyToken), now);
    }

    private NewJob toNewJob(Resolution resolution, String chatId, TextMessageContent text, String body, String replyToken) {
        return switch (resolution) {
            case Resolution.FactCheck f -> new NewJob(JobKind.FACT_CHECK, chatId, text.id(),
                    f.material().messageId(), body, replyToken, text.quoteToken(), null);
            case Resolution.QuestionOnly q -> new NewJob(JobKind.FACT_CHECK, chatId, text.id(),
                    null, body, replyToken, text.quoteToken(), null);
            case Resolution.Imagine i -> new NewJob(JobKind.IMAGINE, chatId, text.id(),
                    null, i.prompt(), replyToken, text.quoteToken(), null);
            case Resolution.Notice n -> new NewJob(JobKind.NOTICE, chatId, text.id(),
                    null, null, replyToken, text.quoteToken(), n.text());
        };
    }

    private void storeMedia(String chatId, String messageId, MaterialKind kind, String sender, long now) {
        MediaStatus status = kind == MaterialKind.IMAGE ? MediaStatus.PENDING : MediaStatus.NONE;
        materials.insert(new Material(chatId, messageId, kind, null, false, status, null, 0, now, expiry(now)), sender);
        if (kind == MaterialKind.IMAGE) {
            publisher.publishEvent(new ImageReceivedEvent(chatId, messageId));
        }
    }

    private void carryWaitingResults(String chatId, String jobId, String replyToken, long now) {
        long notBefore = now - props.waitingTtl().toMillis();
        if (replyToken == null || !jobs.hasWaiting(chatId, notBefore)) {
            return;
        }
        int max = props.maxMessagesPerReply();
        if (jobId != null) {
            jobs.attachWaiting(chatId, jobId, max - 1, notBefore);
            return;
        }
        String deliveryId = jobs.insert(new NewJob(JobKind.DELIVERY, chatId, null, null, null,
                replyToken, null, null), now);
        jobs.attachWaiting(chatId, deliveryId, max, notBefore);
        publisher.publishEvent(new JobReadyEvent(deliveryId));
    }

    private String createJob(NewJob job, long now) {
        String id = jobs.insert(job, now);
        publisher.publishEvent(new JobReadyEvent(id));
        return id;
    }

    private long expiry(long now) {
        return now + props.materialTtl().toMillis();
    }
}
