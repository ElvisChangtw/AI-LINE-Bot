package com.elvischang.ailinebot.job;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Job;
import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.JobStatus;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.store.SendState;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobRecoverySchedulerTest extends IntegrationTestSupport {

    private static final String REPLY = "/v2/bot/message/reply";

    @Autowired
    JobRecoveryScheduler recovery;

    @Autowired
    JobRepository jobs;

    @Autowired
    MaterialRepository materials;

    private String factCheckJob() {
        long now = clock.millis();
        materials.insert(new Material("Cg1", "99", MaterialKind.TEXT, "x", false, MediaStatus.NONE, null, 0, now, now + 1_000_000));
        return jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "100", "99", "q", "rt-1", "qt-100", null), now);
    }

    private String finishedJob() {
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 60_000);
        jobs.complete(id, "結果", clock.millis(), 1);
        return id;
    }

    private Job job(String id) {
        return jobs.find(id).orElseThrow();
    }

    @Test
    void rerunsJobsWhoseLeaseExpired() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 1_000);
        clock.advance(Duration.ofSeconds(2));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
        assertThat(factChecks.calls()).containsExactly("99");
    }

    @Test
    void givesUpAfterMaxAttemptsAndSendsTheFailureNotice() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 1_000);
        jobs.release(id, "TIMEOUT", 1);
        jobs.claim(id, clock.millis(), 1_000);
        clock.advance(Duration.ofSeconds(2));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
        assertThat(job(id).status()).isEqualTo(JobStatus.FAILED);
        assertThat(factChecks.calls()).isEmpty();
        LINE.verify(postRequestedFor(urlEqualTo(REPLY))
                .withRequestBody(matchingJsonPath("$.messages[0].text", equalTo(BotReplies.JOB_FAILED))));
    }

    @Test
    void sendsFinishedResultsThatWereNeverSent() {
        stubReplyOk("900");
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 60_000);
        jobs.complete(id, "結果", clock.millis(), 1);
        clock.advance(Duration.ofSeconds(31));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
    }

    @Test
    void startupTreatsInFlightRepliesAsUnknownAndDoesNotResend() {
        String id = factCheckJob();
        jobs.claim(id, clock.millis(), 60_000);
        jobs.complete(id, "結果", clock.millis(), 1);
        jobs.setSendState(List.of(id), SendState.SENDING_REPLY);

        recovery.afterSingletonsInstantiated();
        recovery.onStartup();

        assertThat(job(id).sendState()).isEqualTo(SendState.UNKNOWN);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> LINE.verify(0, postRequestedFor(urlEqualTo(REPLY))));
    }

    @Test
    void startupReleasesCarriedResultsWhoseCarrierIsFinished() {
        String unknownCarrier = finishedJob();
        jobs.setSendState(List.of(unknownCarrier), SendState.UNKNOWN);
        String orphan = finishedJob();
        jobs.markWaiting(List.of(orphan), 100);
        jobs.attachWaiting("Cg1", unknownCarrier, 1, 0);
        String liveCarrier = finishedJob();
        String carried = finishedJob();
        jobs.markWaiting(List.of(carried), 200);
        jobs.attachWaiting("Cg1", liveCarrier, 1, 0);

        recovery.afterSingletonsInstantiated();

        Job released = job(orphan);
        assertThat(released.sendState()).isEqualTo(SendState.WAITING_NEXT);
        assertThat(released.carrierJobId()).isNull();
        assertThat(released.waitingSince()).isEqualTo(100L);
        assertThat(job(carried).sendState()).isEqualTo(SendState.CARRIED);
        assertThat(job(carried).carrierJobId()).isEqualTo(liveCarrier);
    }

    @Test
    void redispatchesStalePendingJobs() {
        stubReplyOk("900");
        String id = factCheckJob();
        clock.advance(Duration.ofSeconds(31));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(job(id).sendState()).isEqualTo(SendState.REPLIED));
        assertThat(factChecks.calls()).contains("99");
    }

    @Test
    void retriesPendingImageDownloads() {
        long now = clock.millis();
        materials.insert(new Material("Cg1", "300", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, now, now + 1_000_000));
        LINE.stubFor(get(urlEqualTo("/v2/bot/message/300/content")).willReturn(aResponse().withStatus(200).withBody("abc")));
        clock.advance(Duration.ofSeconds(61));

        recovery.sweep();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(materials.find("Cg1", "300").orElseThrow().mediaStatus()).isEqualTo(MediaStatus.STORED));
    }
}
