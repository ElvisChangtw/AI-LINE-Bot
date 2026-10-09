package com.elvischang.ailinebot.factcheck;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
class OpenAiLiveSpikeTest {

    static FactCheckProperties liveProps(String model) {
        return new FactCheckProperties("https://api.openai.com/v1", System.getenv("OPENAI_API_KEY"), model, "medium", 3,
                Duration.ofSeconds(40), 100_000, 500_000, 10_000, 2_000_000, 40_000_000, Duration.ofHours(24), "v1");
    }

    @Test
    void structuredOutputWorksTogetherWithWebSearch() {
        FactCheckProperties props = liveProps("gpt-6-luna");
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props));

        ModelCheck check = checker.check(new FactCheckInput(
                "被電話問到有沒有打疫苗？請不要按任何鍵。今早護理師按了1，手機就被屏蔽了，PayMe和網上銀行資訊都被轉移了。",
                null, null, "這是真的嗎", LocalDate.now()));

        System.out.println("verdict=" + check.result().overall()
                + " claims=" + check.result().claims().size()
                + " sources=" + check.result().sources().size()
                + " consulted=" + check.consultedUrls().size()
                + " usage=" + check.usage()
                + " costMicroUsd=" + check.usage().costMicroUsd(props));
        assertThat(check.consultedUrls()).isNotEmpty();
        assertThat(check.usage().searchCalls()).isBetween(1, props.maxToolCalls());
    }
}
