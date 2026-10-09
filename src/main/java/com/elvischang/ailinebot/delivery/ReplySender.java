package com.elvischang.ailinebot.delivery;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.line.Hashes;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.SendState;
import com.linecorp.bot.client.base.Result;
import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.ReplyMessageResponse;
import com.linecorp.bot.messaging.model.SentMessage;
import com.linecorp.bot.messaging.model.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class ReplySender {

    private static final Logger log = LoggerFactory.getLogger(ReplySender.class);
    private static final Set<JobStatus> FINISHED = EnumSet.of(JobStatus.DONE, JobStatus.FAILED);

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final MessagingApiClient client;
    private final BotProperties props;
    private final Clock clock;

    public ReplySender(JobRepository jobs, MaterialRepository materials, MessagingApiClient client,
                       BotProperties props, Clock clock) {
        this.jobs = jobs;
        this.materials = materials;
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public void send(String jobId) {
        Job carrier = jobs.find(jobId).orElse(null);
        if (carrier == null || !FINISHED.contains(carrier.status()) || carrier.sendState() != SendState.NOT_SENT) {
            return;
        }
        if (!jobs.startSending(carrier.id())) {
            return;
        }
        List<Job> deliverables = deliverables(carrier);
        List<String> allIds = allIds(carrier, deliverables);
        if (deliverables.isEmpty()) {
            jobs.setSendState(allIds, SendState.NOTHING_TO_SEND);
            return;
        }
        long now = clock.millis();
        if (carrier.replyToken() == null || now >= carrier.receivedAt() + props.replyDeadline().toMillis()) {
            toWaiting(carrier, deliverables, now);
            log.info("delivery jobId={} groupHash={} results={} outcome=WAITING_NEXT",
                    carrier.id(), Hashes.shortHash(carrier.groupId()), deliverables.size());
            return;
        }
        List<Message> quoted = messages(carrier, deliverables, true);
        List<Message> unquoted = messages(carrier, deliverables, false);
        jobs.setSendState(allIds, SendState.SENDING_REPLY);
        ReplyOutcome outcome = reply(carrier, quoted);
        if (outcome.status() == ReplyStatus.REJECTED && hasQuote(deliverables)) {
            outcome = reply(carrier, unquoted);
        }
        switch (outcome.status()) {
            case SENT -> {
                jobs.setSendState(allIds, SendState.REPLIED);
                recordBotReplies(carrier.groupId(), outcome.sentMessages(), now);
            }
            case REJECTED -> toWaiting(carrier, deliverables, now);
            case UNKNOWN -> jobs.setSendState(allIds, SendState.UNKNOWN);
        }
        log.info("delivery jobId={} kind={} groupHash={} results={} waitMs={} outcome={}",
                carrier.id(), carrier.kind(), Hashes.shortHash(carrier.groupId()), deliverables.size(),
                now - carrier.receivedAt(), outcome.status());
    }

    private List<Job> deliverables(Job carrier) {
        List<Job> result = new ArrayList<>();
        if (carrier.kind() != JobKind.DELIVERY && carrier.resultText() != null) {
            result.add(carrier);
        }
        result.addAll(jobs.carried(carrier.id()));
        return List.copyOf(result);
    }

    private static List<String> allIds(Job carrier, List<Job> deliverables) {
        List<String> ids = new ArrayList<>();
        ids.add(carrier.id());
        deliverables.stream().map(Job::id).filter(id -> !id.equals(carrier.id())).forEach(ids::add);
        return List.copyOf(ids);
    }

    private void toWaiting(Job carrier, List<Job> deliverables, long now) {
        jobs.markWaiting(deliverables.stream().map(Job::id).toList(), now);
        if (carrier.kind() == JobKind.DELIVERY) {
            jobs.setSendState(List.of(carrier.id()), SendState.NOTHING_TO_SEND);
        }
    }

    private static boolean hasQuote(List<Job> deliverables) {
        return deliverables.stream().anyMatch(j -> j.kind() != JobKind.IMAGINE && j.quoteToken() != null);
    }

    private static List<Message> messages(Job carrier, List<Job> deliverables, boolean withQuote) {
        return deliverables.stream()
                .map(job -> toMessage(job, !job.id().equals(carrier.id()), withQuote))
                .toList();
    }

    private ReplyOutcome reply(Job carrier, List<Message> messages) {
        try {
            Result<ReplyMessageResponse> result = client
                    .replyMessage(new ReplyMessageRequest(carrier.replyToken(), messages, false))
                    .get(props.replyTimeout().toMillis(), TimeUnit.MILLISECONDS);
            ReplyMessageResponse body = result.body();
            List<SentMessage> sent = body == null || body.sentMessages() == null ? List.of() : body.sentMessages();
            return new ReplyOutcome(ReplyStatus.SENT, sent);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AbstractLineClientException ex && ex.getCode() >= 400 && ex.getCode() < 500) {
                log.warn("reply rejected jobId={} code={}", carrier.id(), ex.getCode());
                return new ReplyOutcome(ReplyStatus.REJECTED, List.of());
            }
            log.warn("reply result unknown jobId={} cause={}", carrier.id(), e.getCause().getClass().getSimpleName());
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        } catch (TimeoutException e) {
            log.warn("reply result unknown jobId={} cause=timeout", carrier.id());
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReplyOutcome(ReplyStatus.UNKNOWN, List.of());
        }
    }

    private static Message toMessage(Job job, boolean delayed, boolean withQuote) {
        if (job.kind() == JobKind.IMAGINE && job.status() == JobStatus.DONE) {
            URI uri = URI.create(job.resultText());
            return new ImageMessage(uri, uri);
        }
        String text = delayed ? BotReplies.DELAYED_PREFIX + job.resultText() : job.resultText();
        return new TextMessage(null, null, text, null, withQuote ? job.quoteToken() : null);
    }

    private void recordBotReplies(String groupId, List<SentMessage> sent, long now) {
        long expiresAt = now + props.materialTtl().toMillis();
        for (SentMessage message : sent) {
            materials.insert(new Material(groupId, message.id(), MaterialKind.BOT_REPLY, null, false,
                    MediaStatus.NONE, null, 0, now, expiresAt));
        }
    }

    private enum ReplyStatus { SENT, REJECTED, UNKNOWN }

    private record ReplyOutcome(ReplyStatus status, List<SentMessage> sentMessages) {
    }
}
