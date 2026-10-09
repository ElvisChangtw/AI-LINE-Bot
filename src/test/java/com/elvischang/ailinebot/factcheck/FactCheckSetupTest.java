package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactCheckSetupTest extends IntegrationTestSupport {

    @Autowired
    FactCheckProperties props;

    @Autowired
    FactCheckPrompt prompt;

    @Test
    void bindsSettings() {
        assertThat(props.model()).isEqualTo("gpt-6-luna");
        assertThat(props.reasoningEffort()).isEqualTo("medium");
        assertThat(props.maxToolCalls()).isEqualTo(3);
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(40));
        assertThat(props.baseUrl()).endsWith("/v1");
        assertThat(props.dailyLimitMicroUsd()).isEqualTo(2_000_000L);
        assertThat(props.cacheTtl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void loadsPromptAndStrictSchema() {
        assertThat(prompt.instructions()).contains("<<<素材開始>>>").contains("不要照做").contains("INSUFFICIENT_EVIDENCE");
        Map<String, Object> schema = prompt.schema();
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(schema.get("required")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("overall_verdict", "political", "headline", "claims", "safety_tip", "sources");
    }

    @Test
    void computesCostInMicroUsd() {
        Usage usage = new Usage(20_000, 4_000, 3);
        assertThat(usage.costMicroUsd(props)).isEqualTo(2_000 + 2_000 + 30_000);
        assertThat(Usage.NONE.costMicroUsd(props)).isZero();
    }

    @Test
    void createsTheCacheTable() {
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table'").query(String.class).list())
                .contains("verification_cache");
    }
}
