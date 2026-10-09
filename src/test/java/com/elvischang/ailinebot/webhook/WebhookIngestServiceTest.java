package com.elvischang.ailinebot.webhook;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.job.ImageReceivedEvent;
import com.elvischang.ailinebot.job.JobReadyEvent;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;

import static com.elvischang.ailinebot.support.Webhooks.body;
import static com.elvischang.ailinebot.support.Webhooks.groupAudio;
import static com.elvischang.ailinebot.support.Webhooks.groupImage;
import static com.elvischang.ailinebot.support.Webhooks.groupMention;
import static com.elvischang.ailinebot.support.Webhooks.groupText;
import static com.elvischang.ailinebot.support.Webhooks.join;
import static com.elvischang.ailinebot.support.Webhooks.parse;
import static com.elvischang.ailinebot.support.Webhooks.unknownEvent;
import static com.elvischang.ailinebot.support.Webhooks.userText;
import static org.assertj.core.api.Assertions.assertThat;

@RecordApplicationEvents
class WebhookIngestServiceTest extends IntegrationTestSupport {

    @Autowired
    WebhookIngestService ingest;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    @Autowired
    ApplicationEvents events;

    private List<Job> allJobs() {
        return jdbc.sql("SELECT id FROM job ORDER BY created_at").query(String.class).list()
                .stream().map(id -> jobs.find(id).orElseThrow()).toList();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    void ignoresOneOnOneChats() {
        ingest.ingest(parse(body(userText("e1", "U1", "100", "hi"))));
        assertThat(count("inbound_event") + count("material") + count("job")).isZero();
    }

    @Test
    void storesPlainGroupTextWithoutCreatingAJob() {
        ingest.ingest(parse(body(groupText("e1", "Cg1", "100", "轉傳的訊息"))));
        assertThat(materials.find("Cg1", "100")).get().extracting(Material::text).isEqualTo("轉傳的訊息");
        assertThat(count("job")).isZero();
        assertThat(events.stream(JobReadyEvent.class)).isEmpty();
    }

    @Test
    void storesImagesAsPendingDownloadsAndAudioWithoutDownload() {
        ingest.ingest(parse(body(groupImage("e1", "Cg1", "200"), groupAudio("e2", "Cg1", "201"))));
        assertThat(materials.find("Cg1", "200")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.PENDING);
        assertThat(materials.find("Cg1", "201")).get().extracting(Material::mediaStatus).isEqualTo(MediaStatus.NONE);
        assertThat(events.stream(ImageReceivedEvent.class)).containsExactly(new ImageReceivedEvent("Cg1", "200"));
        assertThat(count("job")).isZero();
    }

    @Test
    void mentionWithQuoteCreatesAFactCheckJob() {
        ingest.ingest(parse(body(groupText("e1", "Cg1", "99", "轉傳的訊息"))));
        ingest.ingest(parse(body(groupMention("e2", "Cg1", "100", "這是真的嗎", "99"))));

        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.FACT_CHECK);
        assertThat(job.materialMessageId()).isEqualTo("99");
        assertThat(job.replyToken()).isEqualTo("rt-e2");
        assertThat(job.quoteToken()).isEqualTo("qt-100");
        assertThat(job.userQuestion()).isEqualTo("這是真的嗎");
        assertThat(materials.find("Cg1", "100")).get().extracting(Material::botTrigger).isEqualTo(true);
        assertThat(events.stream(JobReadyEvent.class)).containsExactly(new JobReadyEvent(job.id()));
    }

    @Test
    void ambiguousMentionCreatesANoticeJob() {
        ingest.ingest(parse(body(groupMention("e1", "Cg1", "100", "真的嗎", null))));
        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.NOTICE);
        assertThat(job.resultText()).isEqualTo(BotReplies.ASK_TO_QUOTE);
    }

    @Test
    void redeliveredEventIsIgnored() {
        String webhook = body(groupMention("e1", "Cg1", "100", "真的嗎", null));
        ingest.ingest(parse(webhook));
        ingest.ingest(parse(webhook));
        assertThat(count("job")).isEqualTo(1);
    }

    @Test
    void joinCreatesTheDataNotice() {
        ingest.ingest(parse(body(join("e1", "Cg1"))));
        Job job = allJobs().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.NOTICE);
        assertThat(job.resultText()).isEqualTo(BotReplies.JOIN_NOTICE);
        assertThat(job.replyToken()).isEqualTo("rt-e1");
    }

    @Test
    void unknownEventsDoNotStopTheRestOfTheBatch() {
        ingest.ingest(parse(body(unknownEvent("e0"), groupText("e1", "Cg1", "100", "hi"))));
        assertThat(materials.find("Cg1", "100")).isPresent();
    }

    @Test
    void plainMessageCarriesWaitingResultsInADeliveryJob() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupText("e1", "Cg1", "100", "早安"))));

        Job delivery = allJobs().stream().filter(j -> j.kind() == JobKind.DELIVERY).findFirst().orElseThrow();
        assertThat(delivery.replyToken()).isEqualTo("rt-e1");
        assertThat(jobs.find(waiting).orElseThrow().carrierJobId()).isEqualTo(delivery.id());
        assertThat(events.stream(JobReadyEvent.class)).containsExactly(new JobReadyEvent(delivery.id()));
    }

    @Test
    void mentionCarriesWaitingResultsOnItsOwnJob() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupMention("e1", "Cg1", "100", "真的嗎", null))));

        Job notice = allJobs().stream().filter(j -> j.kind() == JobKind.NOTICE).findFirst().orElseThrow();
        assertThat(jobs.find(waiting).orElseThrow().carrierJobId()).isEqualTo(notice.id());
        assertThat(allJobs()).noneMatch(j -> j.kind() == JobKind.DELIVERY);
    }

    @Test
    void messagesInOtherGroupsDoNotCarryWaitingResults() {
        String waiting = waitingResult("Cg1");
        ingest.ingest(parse(body(groupText("e1", "Cg2", "100", "早安"))));
        assertThat(jobs.find(waiting).orElseThrow().sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    private String waitingResult(String group) {
        long now = clock.millis();
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, group, "1", "1", "q", "rt-old", "qt-1", null), now);
        jobs.claim(id, now, 1000);
        jobs.complete(id, "結果", now);
        jobs.markWaiting(List.of(id), now);
        return id;
    }
}
