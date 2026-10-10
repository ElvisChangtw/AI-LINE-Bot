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
                Duration.ofSeconds(40), 100_000, 500_000, 10_000, 2_000_000, 40_000_000, Duration.ofHours(24), "v7");
    }

    @Test
    void structuredOutputWorksTogetherWithWebSearch() {
        FactCheckProperties props = liveProps("gpt-6-luna");
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());

        ModelCheck check = checker.check(new FactCheckInput(
                "被電話問到有沒有打疫苗？請不要按任何鍵。今早護理師按了1，手機就被屏蔽了，PayMe和網上銀行資訊都被轉移了。",
                null, null, "這是真的嗎", LocalDate.now()));

        System.out.println("mode=" + check.result().mode() + " verdict=" + check.result().overall()
                + " claims=" + check.result().claims().size()
                + " sources=" + check.result().sources().size()
                + " consulted=" + check.consultedUrls().size()
                + " usage=" + check.usage()
                + " costMicroUsd=" + check.usage().costMicroUsd(props));
        assertThat(check.consultedUrls()).isNotEmpty();
        assertThat(check.usage().searchCalls()).isBetween(1, props.maxToolCalls());
    }

    @Test
    void transcribesARealVoiceClip() throws Exception {
        com.elvischang.ailinebot.media.AudioTranscriber transcriber = new com.elvischang.ailinebot.media.AudioTranscriber(
                new com.elvischang.ailinebot.media.TranscribeProperties("gpt-transcribe", 4500, Duration.ofMinutes(5),
                        26_214_400, 2_097_152, Duration.ofSeconds(120)),
                liveProps("gpt-6-luna"), org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());

        String text = transcriber.transcribe(java.nio.file.Path.of(
                getClass().getResource("/audio/scam-voice.m4a").toURI()), 6);

        System.out.println("transcript=" + text);
        assertThat(text).contains("健保卡");
    }
}
