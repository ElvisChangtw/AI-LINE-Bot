package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCacheRepositoryTest extends IntegrationTestSupport {

    @Autowired
    VerificationCacheRepository cache;

    private final FactCheckResult result = new FactCheckResult(Verdict.INCORRECT, false, "重點",
            List.of(new FactCheckResult.Claim("c", Verdict.INCORRECT, "理由")), true,
            List.of(new FactCheckResult.Source("https://a.gov.tw/1", "t", "p", "")));

    @BeforeEach
    void clearCache() {
        jdbc.sql("DELETE FROM verification_cache").update();
    }

    @Test
    void returnsStoredResultsUntilTheyExpire() {
        long now = clock.millis();
        cache.put("k", result, now);

        assertThat(cache.get("k", now + Duration.ofHours(23).toMillis())).contains(result);
        assertThat(cache.get("k", now + Duration.ofHours(25).toMillis())).isEmpty();
        assertThat(cache.deleteExpired(now + Duration.ofHours(25).toMillis())).isEqualTo(1);
    }

    @Test
    void overwritesTheSameKey() {
        long now = clock.millis();
        cache.put("k", result, now);
        FactCheckResult newer = result.downgradedToInsufficient();
        cache.put("k", newer, now);

        assertThat(cache.get("k", now)).contains(newer);
    }
}
