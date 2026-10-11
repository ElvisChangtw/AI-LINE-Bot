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
import com.linecorp.bot.messaging.model.ImageMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
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

@Component
public class ReplySender {

    private static final Logger log = LoggerFactory.getLogger(ReplySender.class);
    private static final Set<JobStatus> FINISHED = EnumSet.of(JobStatus.DONE, JobStatus.FAILED);

    private final JobRepository jobs;
    private final MaterialRepository materials;
    private final LineReplyClient client;
    private final BotProperties props;
    private final Clock clock;

    public ReplySender(JobRepository jobs, MaterialRepository materials, LineReplyClient client,
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
        LineReplyClient.ReplyResult outcome = reply(carrier, quoted);
        if (outcome.status() == LineReplyClient.Status.REJECTED && hasQuote(deliverables)) {
            outcome = reply(carrier, unquoted);
        }
        switch (outcome.status()) {
            case SENT -> {
                jobs.setSendState(allIds, SendState.REPLIED);
                recordBotReplies(carrier.groupId(), outcome.sentMessages(), quoted, deliverables, now);
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

    private static boolean isImage(Job job) {
        return job.kind() == JobKind.IMAGINE && job.status() == JobStatus.DONE;
    }

    private static boolean isQuoted(Job job) {
        return !isImage(job) && job.quoteToken() != null;
    }

    private static boolean hasQuote(List<Job> deliverables) {
        return deliverables.stream().anyMatch(ReplySender::isQuoted);
    }

    private static List<Message> messages(Job carrier, List<Job> deliverables, boolean withQuote) {
        return deliverables.stream()
                .map(job -> toMessage(job, !job.id().equals(carrier.id()), withQuote))
                .toList();
    }

    private LineReplyClient.ReplyResult reply(Job carrier, List<Message> messages) {
        LineReplyClient.ReplyResult result = client.reply(new ReplyMessageRequest(carrier.replyToken(), messages, false));
        if (result.status() != LineReplyClient.Status.SENT) {
            log.warn("reply not accepted jobId={} status={} code={}", carrier.id(), result.status(), result.httpCode());
        }
        return result;
    }

    private static Message toMessage(Job job, boolean delayed, boolean withQuote) {
        if (isImage(job)) {
            URI uri = URI.create(job.resultText());
            return new ImageMessage(uri, uri);
        }
        String text = delayed ? BotReplies.DELAYED_PREFIX + job.resultText() : job.resultText();
        return new TextMessage(null, null, text, null, withQuote && isQuoted(job) ? job.quoteToken() : null);
    }

    // LINE returns sentMessages in the order we sent them; quoted and unquoted versions carry the same text.
    private void recordBotReplies(String groupId, List<SentMessage> sent, List<Message> messages, List<Job> deliverables,
                                  long now) {
        long expiresAt = now + props.materialTtl().toMillis();
        for (int i = 0; i < sent.size(); i++) {
            String text = i < messages.size() && messages.get(i) instanceof TextMessage t ? t.text() : null;
            String about = i < deliverables.size() ? deliverables.get(i).about() : null;
            materials.insertBotReply(new Material(groupId, sent.get(i).id(), MaterialKind.BOT_REPLY, text, false,
                    MediaStatus.NONE, null, 0, now, expiresAt), about);
        }
    }
}
