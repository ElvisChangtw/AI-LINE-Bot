package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest extends IntegrationTestSupport {

    @Autowired
    JobRepository jobs;

    private String finished(String group, long now) {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, group, "t", "m", "q", "rt", "qt", null), now);
        jobs.claim(id, now, 1000);
        jobs.complete(id, "result", now, 1);
        return id;
    }

    @Test
    void claimsOnlyOnce() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        assertThat(jobs.claim(id, 100, 1000)).isTrue();
        assertThat(jobs.claim(id, 100, 1000)).isFalse();
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.leaseUntil()).isEqualTo(1100L);
    }

    @Test
    void releasedJobCanBeClaimedAgain() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.release(id, "TIMEOUT", 1);
        assertThat(jobs.stalePending(101)).containsExactly(id);
        assertThat(jobs.claim(id, 200, 1000)).isTrue();
        assertThat(jobs.find(id).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void attachesOldestWaitingResultsOfTheSameGroupOnce() {
        String a = finished("Cg1", 100);
        String b = finished("Cg1", 100);
        String c = finished("Cg1", 100);
        String other = finished("Cg2", 100);
        jobs.markWaiting(List.of(a), 100);
        jobs.markWaiting(List.of(b), 200);
        jobs.markWaiting(List.of(c), 300);
        jobs.markWaiting(List.of(other), 100);

        assertThat(jobs.hasWaiting("Cg1", 0)).isTrue();
        assertThat(jobs.attachWaiting("Cg1", "carrier", 2, 0)).isEqualTo(2);
        assertThat(jobs.carried("carrier")).extracting(Job::id).containsExactly(a, b);
        assertThat(jobs.attachWaiting("Cg1", "carrier2", 5, 0)).isEqualTo(1);
        assertThat(jobs.attachWaiting("Cg1", "carrier3", 5, 0)).isZero();
        assertThat(jobs.find(other).orElseThrow().sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    @Test
    void ignoresWaitingResultsOlderThanTheCutoff() {
        String a = finished("Cg1", 100);
        jobs.markWaiting(List.of(a), 100);
        assertThat(jobs.hasWaiting("Cg1", 101)).isFalse();
        assertThat(jobs.attachWaiting("Cg1", "carrier", 5, 101)).isZero();
        assertThat(jobs.expireWaiting(101)).isEqualTo(1);
        assertThat(jobs.find(a).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
    }

    @Test
    void markWaitingKeepsTheFirstWaitingTimeAndDetachesFromCarrier() {
        String a = finished("Cg1", 100);
        jobs.markWaiting(List.of(a), 100);
        jobs.attachWaiting("Cg1", "carrier", 5, 0);
        jobs.markWaiting(List.of(a), 900);
        Job job = jobs.find(a).orElseThrow();
        assertThat(job.waitingSince()).isEqualTo(100L);
        assertThat(job.carrierJobId()).isNull();
        assertThat(job.sendState()).isEqualTo(SendState.WAITING_NEXT);
    }

    @Test
    void recoveryQueries() {
        String running = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(running, 100, 50);
        String done = finished("Cg1", 100);
        String sending = finished("Cg1", 100);
        jobs.setSendState(List.of(sending), SendState.SENDING_REPLY);

        assertThat(jobs.expiredLeases(151)).extracting(Job::id).containsExactly(running);
        assertThat(jobs.unsentFinished(101)).containsExactly(done);
        assertThat(jobs.resetSendingToUnknown()).isEqualTo(1);
        assertThat(jobs.find(sending).orElseThrow().sendState()).isEqualTo(SendState.UNKNOWN);
    }

    @Test
    void expireGroupAlsoStopsResultsThatWereNeverSent() {
        String unsent = finished("Cg1", 100);
        String other = finished("Cg2", 100);

        jobs.expireGroup("Cg1");

        assertThat(jobs.find(unsent).orElseThrow().sendState()).isEqualTo(SendState.EXPIRED);
        assertThat(jobs.find(other).orElseThrow().sendState()).isEqualTo(SendState.NOT_SENT);
    }

    @Test
    void clearsContentAndDeletesOldRows() {
        String a = finished("Cg1", 100);
        jobs.setSendState(List.of(a), SendState.REPLIED);
        assertThat(jobs.clearContent(101)).isEqualTo(1);
        assertThat(jobs.find(a).orElseThrow().resultText()).isNull();
        assertThat(jobs.deleteOlderThan(101)).isEqualTo(1);
        assertThat(jobs.find(a)).isEmpty();
    }

    @Test
    void clearContentKeepsWaitingResults() {
        String a = finished("Cg1", 100);
        jobs.markWaiting(List.of(a), 100);
        assertThat(jobs.clearContent(101)).isZero();
        assertThat(jobs.find(a).orElseThrow().resultText()).isEqualTo("result");
    }

    @Test
    void startSendingClaimsTheReplyOnce() {
        String a = finished("Cg1", 100);
        assertThat(jobs.startSending(a)).isTrue();
        assertThat(jobs.startSending(a)).isFalse();
        assertThat(jobs.find(a).orElseThrow().sendState()).isEqualTo(SendState.SENDING_REPLY);
    }

    @Test
    void markWaitingDoesNotReviveRepliedJob() {
        String a = finished("Cg1", 100);
        jobs.setSendState(List.of(a), SendState.REPLIED);
        jobs.markWaiting(List.of(a), 200);
        assertThat(jobs.find(a).orElseThrow().sendState()).isEqualTo(SendState.REPLIED);
    }

    @Test
    void releaseAfterCompleteIsNoOp() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.complete(id, "result", 100, 1);
        jobs.release(id, "TIMEOUT", 1);
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(job.errorCode()).isNull();
    }

    @Test
    void completeAfterReleaseIsNoOp() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.release(id, "TIMEOUT", 1);
        jobs.complete(id, "late", 200, 1);
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.resultText()).isNull();
    }

    @Test
    void staleAttemptCannotTouchANewerClaim() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.release(id, "LEASE_EXPIRED", 1);
        jobs.claim(id, 200, 1000);

        jobs.release(id, "X", 1);
        jobs.fail(id, "failed", "X", 300, 1);
        assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

        jobs.complete(id, "r", 400, 2);
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(job.resultText()).isEqualTo("r");
    }

    @Test
    void recordsFactCheckMetricsOnlyForTheCurrentAttempt() {
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "t", "m", "q", "rt", "qt", null), 100);
        jobs.claim(id, 100, 1000);
        jobs.recordFactCheck(id, 1, com.elvischang.ailinebot.factcheck.Verdict.INCORRECT,
                new com.elvischang.ailinebot.factcheck.Usage(10, 20, 3), 500);
        jobs.recordFactCheck(id, 2, com.elvischang.ailinebot.factcheck.Verdict.CORRECT,
                new com.elvischang.ailinebot.factcheck.Usage(1, 1, 1), 999);

        assertThat(jdbc.sql("SELECT verdict, input_tokens, output_tokens, search_calls, cost_micro_usd FROM job WHERE id = :id")
                .param("id", id).query((rs, n) -> rs.getString(1) + "/" + rs.getLong(2) + "/" + rs.getLong(3)
                        + "/" + rs.getInt(4) + "/" + rs.getLong(5)).single())
                .isEqualTo("INCORRECT/10/20/3/500");
        assertThat(jobs.sumCostSince(0)).isEqualTo(500);
        assertThat(jobs.sumCostSince(101)).isZero();
    }
}
