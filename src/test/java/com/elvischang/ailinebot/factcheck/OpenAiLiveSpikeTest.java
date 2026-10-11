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
                Duration.ofSeconds(40), 100_000, 500_000, 10_000, 2_000_000, 40_000_000, Duration.ofHours(24), "v12");
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
                        26_214_400, 2_097_152, Duration.ofSeconds(120), "ffmpeg", Duration.ofSeconds(60)),
                liveProps("gpt-6-luna"), org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());

        String text = transcriber.transcribe(java.nio.file.Path.of(
                getClass().getResource("/audio/scam-voice.m4a").toURI()), 6);

        System.out.println("transcript=" + text);
        assertThat(text).contains("健保卡");
    }

    @Test
    void readsCaptionsFromVideoFrames() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("frames");
        java.nio.file.Path video = dir.resolve("caption.mp4");
        java.nio.file.Files.copy(java.nio.file.Path.of(getClass().getResource("/video/caption.mp4").toURI()), video);
        com.elvischang.ailinebot.media.VideoFrames frames = new com.elvischang.ailinebot.media.VideoFrames(
                new com.elvischang.ailinebot.media.TranscribeProperties("gpt-transcribe", 4500, Duration.ofMinutes(5),
                        26_214_400, 2_097_152, Duration.ofSeconds(120), "ffmpeg", Duration.ofSeconds(60)));
        byte[] sheet = java.nio.file.Files.readAllBytes(frames.contactSheet(video, 12_000).orElseThrow());
        FactCheckProperties props = liveProps("gpt-6-luna");
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());

        ModelCheck check = checker.check(new FactCheckInput(DefaultFactCheckService.VIDEO_FRAME_ONLY_LABEL, sheet, "image/jpeg",
                "", LocalDate.now()));

        System.out.println("video verdict=" + check.result().overall() + " headline=" + check.result().headline());
        assertThat(check.result().overall()).isEqualTo(Verdict.INCORRECT);
    }
    @Test
    void answersAFollowUpInTheContextOfTheOriginalMessage() {
        FactCheckProperties props = liveProps("gpt-6-luna");
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());
        String material = com.elvischang.ailinebot.material.RecentMaterialPicker.asFollowUp(
                "每天早上空腹喝一杯熱檸檬水，可以殺死癌細胞，請轉發給你關心的人",
                "這個說法不對喔 ❌ 熱檸檬水不能殺死癌細胞，目前沒有研究證實。");

        ModelCheck check = checker.check(new FactCheckInput(material, null, null, "那喝冷的檸檬水呢？", LocalDate.now()));

        System.out.println("follow-up mode=" + check.result().mode() + " points=" + check.result().answerPoints());
        assertThat(check.result().mode()).isEqualTo(ReplyMode.ANSWER);
        assertThat(String.join("", check.result().answerPoints())).contains("癌");
    }
    @Test
    void usesTheRecentChatToAnswerOrToPointAtAMessage() {
        FactCheckProperties props = liveProps("gpt-6-luna");
        OpenAiResponsesFactChecker checker = new OpenAiResponsesFactChecker(props, new FactCheckPrompt(props),
                org.mockito.Mockito.mock(SpendRepository.class), java.time.Clock.systemUTC());
        String chat = """
                #1 20:01 阿姨：每天早上空腹喝一杯熱檸檬水，可以殺死癌細胞，請轉發給你關心的人
                #2 20:03 爸爸：週六晚上七點在老家吃飯，記得帶水果
                #3 20:05 小明：收到""";

        ModelCheck dinner = checker.check(new FactCheckInput(null, null, null, "爸爸說幾點吃飯？", LocalDate.now(), chat));
        ModelCheck rumour = checker.check(new FactCheckInput(null, null, null, "阿姨傳的那個是真的嗎", LocalDate.now(), chat));

        System.out.println("dinner mode=" + dinner.result().mode() + " points=" + dinner.result().answerPoints()
                + " headline=" + dinner.result().headline());
        System.out.println("rumour mode=" + rumour.result().mode() + " target=" + rumour.targetMessage());
        assertThat(dinner.result().mode()).isEqualTo(ReplyMode.ANSWER);
        assertThat(dinner.result().headline() + dinner.result().answerPoints()).containsAnyOf("七點", "7 點", "7點", "19");
        assertThat(rumour.result().mode()).isEqualTo(ReplyMode.NEED_MATERIAL);
        assertThat(rumour.targetMessage()).isEqualTo(1);
    }
}
