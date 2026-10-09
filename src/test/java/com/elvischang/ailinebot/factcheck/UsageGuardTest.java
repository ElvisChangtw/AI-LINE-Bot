package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.store.JobKind;
import com.elvischang.ailinebot.store.JobRepository;
import com.elvischang.ailinebot.store.NewJob;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UsageGuardTest extends IntegrationTestSupport {

    @Autowired
    UsageGuard guard;

    @Autowired
    JobRepository jobs;

    private void spend(Instant at, long microUsd) {
        clock.set(at);
        String id = jobs.insert(new NewJob(JobKind.FACT_CHECK, "Cg1", "1", "1", "q", "rt", "qt", null), at.toEpochMilli());
        jobs.claim(id, at.toEpochMilli(), 60_000);
        jobs.recordFactCheck(id, 1, Verdict.CORRECT, new Usage(1, 1, 1), microUsd);
    }

    @Test
    void blocksAfterTheDailyLimitAndResetsAtTaipeiMidnight() {
        Instant tenPmTaipei = Instant.parse("2026-10-10T14:00:00Z");
        spend(tenPmTaipei, 2_000_000);
        assertThat(guard.allows(tenPmTaipei.toEpochMilli())).isFalse();

        Instant afterMidnightTaipei = Instant.parse("2026-10-10T16:05:00Z");
        assertThat(guard.allows(afterMidnightTaipei.toEpochMilli())).isTrue();
    }

    @Test
    void blocksAfterTheMonthlyLimit() {
        for (int day = 1; day <= 21; day++) {
            spend(Instant.parse(String.format("2026-10-%02dT02:00:00Z", day)), 1_999_999);
        }
        assertThat(guard.allows(Instant.parse("2026-10-22T02:00:00Z").toEpochMilli())).isFalse();
        assertThat(guard.allows(Instant.parse("2026-11-01T02:00:00Z").toEpochMilli())).isTrue();
    }
}
