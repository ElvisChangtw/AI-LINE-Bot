package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UsageGuardTest extends IntegrationTestSupport {

    @Autowired
    UsageGuard guard;

    @Autowired
    SpendRepository spending;

    private void spend(Instant at, long microUsd) {
        spending.record(at.toEpochMilli(), microUsd);
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
