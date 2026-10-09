package com.elvischang.ailinebot.delivery;

import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ReplySenderTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    ReplySender sender;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    private String finished(JobKind kind, String quoteToken, String result, String replyToken) {
        long now = clock.millis();
        String id = jobs.insert(new NewJob(kind, "Cg1", "100", "99", "q", replyToken, quoteToken, null), now);
        jobs.claim(id, now, 60_000);
        jobs.complete(id, result, now, 1);
        return id;
    }

    private SendState state(String id) {
        return jobs.find(id).map(Job::sendState).orElseThrow();
    }

    private static void stubRejected(int priority, boolean onlyWithQuote) {
        var mapping = post(urlEqualTo(REPLY)).atPriority(priority);
        if (onlyWithQuote) {
            mapping = mapping.withRequestBody(containing("quoteToken"));
        }
        LINE.stubFor(mapping.willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"message\":\"Invalid\"}")));
    }

    @Test
    void repliesWithinDeadlineQuotingTheQuestionAndRecordsTheBotReply() {
        stubReplyOk("900");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.REPLIED);
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-1")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo("結果")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-100"))));
        assertThat(materials.find("Cg1", "900")).get().extracting(Material::kind).isEqualTo(MaterialKind.BOT_REPLY);
    }

    @Test
    void waitsForTheNextMessageWhenTheDeadlinePassed() {
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");
        clock.advance(Duration.ofSeconds(46));

        sender.send(id);

        LINE.verify(0, postRequestedFor(urlEqualTo(REPLY)));
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.sendState()).isEqualTo(SendState.WAITING_NEXT);
        assertThat(job.waitingSince()).isEqualTo(clock.millis());
    }

    @Test
    void retriesOnceWithoutQuoteTokenWhenRejected() {
        stubRejected(1, true);
        stubReplyOk("901");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.REPLIED);
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void waitsForTheNextMessageWhenRejectedTwice() {
        stubRejected(1, false);
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.WAITING_NEXT);
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void marksUnknownAndDoesNotRetryWhenTheResultIsUnclear() {
        LINE.stubFor(post(urlEqualTo(REPLY)).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.UNKNOWN);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void marksUnknownOnServerError() {
        LINE.stubFor(post(urlEqualTo(REPLY)).willReturn(aResponse().withStatus(500)));
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.UNKNOWN);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void connectionResetIsUnknownAndNotResent() {
        LINE.stubFor(post(urlEqualTo(REPLY)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.UNKNOWN);
        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void failedImagineIsRetriedWithoutQuote() {
        stubRejected(1, true);
        stubReplyOk("906");
        long now = clock.millis();
        String id = jobs.insert(new NewJob(JobKind.IMAGINE, "Cg1", "100", "99", "q", "rt-1", "qt-100", null), now);
        jobs.claim(id, now, 60_000);
        jobs.fail(id, BotReplies.JOB_FAILED, "X", now, 1);

        sender.send(id);

        assertThat(state(id)).isEqualTo(SendState.REPLIED);
        LINE.verify(2, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void deliveryJobCarriesWaitingResultsWithAPrefix() {
        stubReplyOk("902");
        String a = finished(JobKind.FACT_CHECK, "qt-a", "A", "rt-a");
        String b = finished(JobKind.NOTICE, "qt-b", "B", "rt-b");
        jobs.markWaiting(List.of(a, b), clock.millis());
        String delivery = finished(JobKind.DELIVERY, null, null, "rt-d");
        jobs.attachWaiting("Cg1", delivery, 5, 0);

        sender.send(delivery);

        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.replyToken", equalTo("rt-d")))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.DELAYED_PREFIX + "A")))
                .withRequestBody(matchingJsonPath("$.messages[0].quoteToken", equalTo("qt-a")))
                .withRequestBody(matchingJsonPath("$.messages[1].text", equalTo(BotReplies.DELAYED_PREFIX + "B"))));
        assertThat(List.of(state(a), state(b), state(delivery))).containsOnly(SendState.REPLIED);
    }

    @Test
    void lateDeliveryPutsCarriedResultsBackToWaiting() {
        String a = finished(JobKind.FACT_CHECK, "qt-a", "A", "rt-a");
        jobs.markWaiting(List.of(a), clock.millis());
        String delivery = finished(JobKind.DELIVERY, null, null, "rt-d");
        jobs.attachWaiting("Cg1", delivery, 5, 0);
        clock.advance(Duration.ofSeconds(46));

        sender.send(delivery);

        assertThat(state(a)).isEqualTo(SendState.WAITING_NEXT);
        assertThat(state(delivery)).isEqualTo(SendState.NOTHING_TO_SEND);
    }

    @Test
    void sendsAnImageForImagine() {
        stubReplyOk("903");
        String id = finished(JobKind.IMAGINE, "qt-100", "https://img.example/x.png", "rt-1");

        sender.send(id);

        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].type", equalTo("image")))
                .withRequestBody(matchingJsonPath("$.messages[0].originalContentUrl", equalTo("https://img.example/x.png"))));
    }

    @Test
    void sendingTwiceDoesNothingTheSecondTime() {
        stubReplyOk("904");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");

        sender.send(id);
        sender.send(id);

        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
    }

    @Test
    void concurrentSendsDeliverOnce() throws Exception {
        stubReplyOk("905");
        String id = finished(JobKind.FACT_CHECK, "qt-100", "結果", "rt-1");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                    pool.submit(() -> {
                        start.await();
                        sender.send(id);
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        sender.send(id);
                        return null;
                    }));
            start.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        LINE.verify(1, postRequestedFor(urlEqualTo(REPLY)));
        assertThat(state(id)).isEqualTo(SendState.REPLIED);
    }
}
