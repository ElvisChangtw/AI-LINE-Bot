package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.media.YouTubeWatcher;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultFactCheckServiceTest extends IntegrationTestSupport {

    @MockBean
    FactChecker checker;

    @Autowired
    DefaultFactCheckService service;

    @Autowired
    MaterialRepository materials;

    @Autowired
    MediaStore mediaStore;

    @Autowired
    SpendRepository spendRepository;

    @Autowired
    VerificationCacheRepository cache;

    @Autowired
    UsageGuard usageGuard;

    @Autowired
    FactCheckProperties props;

    @MockBean
    YouTubeWatcher youTube;

    private static final FactCheckResult RESULT = FactCheckResult.factCheck(Verdict.INCORRECT, false, "舊謠言",
            List.of(new FactCheckResult.Claim("c", Verdict.INCORRECT, "按 1 不會讓手機被控制")), true,
            List.of(new FactCheckResult.Source("https://tfc-taiwan.org.tw/?p=108215", "t", "台灣事實查核中心", ""),
                    new FactCheckResult.Source("https://example.com/made-up", "t", "假", "")));

    private FactCheckReply ask(Material material, String question) {
        return service.verify(material.groupId(), material, question);
    }

    private ModelCheck answer(ReplyMode mode) {
        FactCheckResult result = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "帶傘", List.of(), false,
                List.of(new FactCheckResult.Source("https://www.cwa.gov.tw/a", "t", "中央氣象署", "")), mode, true,
                mode == ReplyMode.ANSWER ? List.of("降雨機率 70%") : List.of());
        return new ModelCheck(result, Set.of("https://www.cwa.gov.tw/a"), new Usage(1_000, 100, 1));
    }

    private Material text(String group, String id, String body) {
        long now = clock.millis();
        Material m = new Material(group, id, MaterialKind.TEXT, body, false, MediaStatus.NONE, null, 0, now, now + 1_000_000);
        materials.insert(m);
        return m;
    }

    @Test
    void checksFiltersFormatsAndCaches() {
        when(checker.check(any())).thenReturn(new ModelCheck(RESULT, Set.of("https://tfc-taiwan.org.tw/?p=108215"), new Usage(20_000, 4_000, 3)));

        FactCheckReply first = ask(text("Cg1", "1", "按1 手機被屏蔽 https://x.example"), "真的嗎");
        FactCheckReply second = ask(text("Cg2", "2", "按1\u3000手機被屏蔽 https://x.example"), " 真的嗎\u3000");

        assertThat(first.text()).startsWith("這是假的喔，舊謠言")
                .contains(BotReplies.LINK_NOT_OPENED)
                .contains("https://tfc-taiwan.org.tw/?p=108215")
                .doesNotContain("example.com/made-up");
        assertThat(first.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(first.mode()).isEqualTo(ReplyMode.FACT_CHECK);
        assertThat(first.costMicroUsd()).isEqualTo(2_000 + 2_000 + 30_000);
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.costMicroUsd()).isZero();
        verify(checker, times(1)).check(any());
    }

    private final List<Path> writtenFiles = new ArrayList<>();

    @AfterEach
    void deleteWrittenFiles() throws Exception {
        for (Path file : writtenFiles) {
            Files.deleteIfExists(file);
        }
    }

    private Material storedImage(String id, String sha) throws Exception {
        long now = clock.millis();
        Material m = new Material("Cg1", id, MaterialKind.IMAGE, null, false, MediaStatus.STORED, sha, 0, now, now + 1_000_000);
        materials.insert(m);
        var file = mediaStore.pathFor("Cg1", id);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, (byte) id.hashCode()});
        writtenFiles.add(file);
        return m;
    }

    private void answerWithResult() {
        when(checker.check(any())).thenReturn(new ModelCheck(RESULT, Set.of("https://tfc-taiwan.org.tw/?p=108215"), Usage.NONE));
    }

    @Test
    void mediaIsCheckedFromItsTranscriptWithTheVideoFrame() throws Exception {
        answerWithResult();
        long now = clock.millis();
        Material voice = new Material("Cg1", "501", MaterialKind.AUDIO, "健保卡要停用了", false, MediaStatus.STORED, null, 0, now, now + 1);
        storedImage("502", "sha502");
        Material video = new Material("Cg1", "502", MaterialKind.VIDEO, "吃苦瓜治癌", false, MediaStatus.STORED, "sha502", 0, now, now + 1);

        ask(voice, "");
        ask(video, "");
        ask(video, "");

        ArgumentCaptor<FactCheckInput> sent = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker, times(2)).check(sent.capture());
        assertThat(sent.getAllValues().get(0).materialText()).isEqualTo(DefaultFactCheckService.VOICE_LABEL + "健保卡要停用了");
        assertThat(sent.getAllValues().get(0).image()).isNull();
        assertThat(sent.getAllValues().get(1).materialText()).isEqualTo(DefaultFactCheckService.VIDEO_LABEL + "吃苦瓜治癌");
        assertThat(sent.getAllValues().get(1).image()).isNotNull();
    }

    @Test
    void differentImagesAndATextMatchingAnImageShaNeverShareACacheEntry() throws Exception {
        answerWithResult();

        ask(storedImage("401", "sha401"), "真的嗎");
        ask(storedImage("402", "sha402"), "真的嗎");
        ask(storedImage("403", "x"), "真的嗎");
        ask(text("Cg1", "404", "x"), "真的嗎");

        verify(checker, times(4)).check(any());
    }

    @Test
    void anotherModelDoesNotReuseTheCachedAnswer() {
        answerWithResult();
        FactCheckProperties otherModel = new FactCheckProperties(props.baseUrl(), props.apiKey(), "other-model",
                props.reasoningEffort(), props.maxToolCalls(), props.requestTimeout(), props.inputMicroUsdPerMillion(),
                props.outputMicroUsdPerMillion(), props.searchMicroUsdPerCall(), props.dailyLimitMicroUsd(),
                props.monthlyLimitMicroUsd(), props.cacheTtl(), props.promptVersion());
        DefaultFactCheckService otherService = new DefaultFactCheckService(checker, cache, usageGuard, mediaStore, youTube,
                otherModel, clock);

        ask(text("Cg1", "1", "按1 手機被屏蔽"), "真的嗎");
        otherService.verify("Cg2", text("Cg2", "2", "按1 手機被屏蔽"), "真的嗎");

        verify(checker, times(2)).check(any());
    }

    @Test
    void storedImageWithoutAHashIsTreatedAsGone() throws Exception {
        answerWithResult();

        assertThat(ask(storedImage("405", null), "真的嗎").text()).isEqualTo(BotReplies.IMAGE_GONE);
        assertThat(ask(storedImage("406", " "), "真的嗎").text()).isEqualTo(BotReplies.IMAGE_GONE);

        verify(checker, never()).check(any());
    }

    @Test
    void insufficientEvidenceIsNotCached() {
        when(checker.check(any())).thenReturn(new ModelCheck(RESULT, Set.of(), Usage.NONE));

        FactCheckReply first = ask(text("Cg1", "1", "剛出現的新謠言"), "真的嗎");
        ask(text("Cg2", "2", "剛出現的新謠言"), "真的嗎");

        assertThat(first.verdict()).isEqualTo(Verdict.INSUFFICIENT_EVIDENCE);
        verify(checker, times(2)).check(any());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM verification_cache").query(Integer.class).single()).isZero();
    }

    @Test
    void refusesWhenTheBudgetIsSpent() {
        spendRepository.record(clock.millis(), 2_000_000L);

        FactCheckReply reply = ask(text("Cg1", "1", "內容"), "真的嗎");

        assertThat(reply.text()).isEqualTo(BotReplies.QUOTA_EXCEEDED);
        verify(checker, never()).check(any());
    }

    @Test
    void tellsTheGroupWhenTheCreditBalanceIsEmpty() {
        when(checker.check(any())).thenThrow(new OutOfCreditException(new RuntimeException("429")));

        FactCheckReply reply = ask(text("Cg1", "1", "內容"), "真的嗎");

        assertThat(reply.text()).isEqualTo(BotReplies.OUT_OF_CREDIT);
    }

    @Test
    void sendsStoredImagesAndWaitsForPendingOnes() throws Exception {
        when(checker.check(any())).thenReturn(new ModelCheck(RESULT, Set.of("https://tfc-taiwan.org.tw/?p=108215"), Usage.NONE));
        long now = clock.millis();
        Material pending = new Material("Cg1", "300", MaterialKind.IMAGE, null, false, MediaStatus.PENDING, null, 0, now, now + 1_000_000);
        Material stored = new Material("Cg1", "301", MaterialKind.IMAGE, null, false, MediaStatus.STORED, "sha301", 0, now, now + 1_000_000);
        materials.insert(pending);
        materials.insert(stored);
        var file = mediaStore.pathFor("Cg1", "301");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2});

        assertThat(ask(pending, "真的嗎").text()).isEqualTo(BotReplies.IMAGE_NOT_READY);
        ask(stored, "真的嗎");

        ArgumentCaptor<FactCheckInput> input = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker).check(input.capture());
        assertThat(input.getValue().imageMimeType()).isEqualTo("image/png");
        assertThat(input.getValue().materialText()).isNull();
        Files.deleteIfExists(file);
    }

    @Test
    void differentQuestionsAboutTheSameMaterialDoNotShareACacheEntry() {
        answerWithResult();

        ask(text("Cg1", "1", "按1 手機被屏蔽"), "真的嗎");
        ask(text("Cg2", "2", "按1 手機被屏蔽"), "我要不要報警");

        verify(checker, times(2)).check(any());
    }

    @Test
    void aQuestionWithoutMaterialIsSentOnItsOwn() {
        when(checker.check(any())).thenReturn(answer(ReplyMode.ANSWER));

        FactCheckReply reply = service.verify("Cg1", null, "明天台北會下雨嗎");

        ArgumentCaptor<FactCheckInput> input = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker).check(input.capture());
        assertThat(input.getValue().materialText()).isNull();
        assertThat(input.getValue().image()).isNull();
        assertThat(input.getValue().userQuestion()).isEqualTo("明天台北會下雨嗎");
        assertThat(reply.text()).startsWith("帶傘");
        assertThat(reply.mode()).isEqualTo(ReplyMode.ANSWER);
        assertThat(reply.verdict()).isNull();
        assertThat(reply.costMicroUsd()).isEqualTo(100 + 50 + 10_000);
    }

    @Test
    void aBareQuestionThatLeansOnTheChatIsNotCachedAndPassesTheTargetOn() {
        ModelCheck pointed = new ModelCheck(answer(ReplyMode.NEED_MATERIAL).result(), Set.of(), Usage.NONE, 3);
        ModelCheck checked = new ModelCheck(RESULT, Set.of("https://tfc-taiwan.org.tw/?p=108215"), Usage.NONE);
        when(checker.check(any())).thenReturn(checked, checked, pointed);

        service.verify("Cg1", null, "阿姨說的對嗎", "#1 08:00 阿姨：按1手機被屏蔽");
        service.verify("Cg1", null, "阿姨說的對嗎", "#1 09:00 阿姨：明天放颱風假");
        FactCheckReply asked = service.verify("Cg1", null, "那個呢", "#1 08:00 阿姨：按1手機被屏蔽");

        verify(checker, times(3)).check(any());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM verification_cache").query(Integer.class).single()).isZero();
        assertThat(asked.targetMessage()).isEqualTo(3);
    }

    @Test
    void aYouTubeLinkIsCheckedFromWhatGeminiSawInTheVideo() {
        when(youTube.summary("dQw4w9WgXcQ")).thenReturn(java.util.Optional.of("講者說喝熱檸檬水可以殺死癌細胞"));
        when(youTube.label()).thenReturn("（影片整理）\n");
        answerWithResult();

        FactCheckReply watched = ask(text("Cg1", "1", "快看 https://youtu.be/dQw4w9WgXcQ"), "https://youtu.be/zzzzzzzzzzz");
        FactCheckReply secondVideo = ask(text("Cg1", "2", "https://youtu.be/dQw4w9WgXcQ 和 https://youtu.be/yyyyyyyyyyy"), "");

        ArgumentCaptor<FactCheckInput> input = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker, times(2)).check(input.capture());
        assertThat(input.getAllValues().getFirst().materialText())
                .isEqualTo("快看 https://youtu.be/dQw4w9WgXcQ\n\n（影片整理）\n講者說喝熱檸檬水可以殺死癌細胞");
        verify(youTube, times(2)).summary("dQw4w9WgXcQ");
        assertThat(watched.text()).as("the question's link was web-search material, not opened")
                .contains(BotReplies.LINK_NOT_OPENED);
        assertThat(secondVideo.text()).as("the unwatched second video still counts").contains(BotReplies.LINK_NOT_OPENED);
    }

    @Test
    void anUnsourcedInsufficientAnswerKeepsItsReasonSoTheGroupLearnsTheVideoWasNotWatched() {
        when(youTube.summary("EvU4PxdM0pI")).thenReturn(java.util.Optional.empty());
        FactCheckResult unwatched = FactCheckResult.factCheck(Verdict.INSUFFICIENT_EVIDENCE, false, "",
                List.of(new FactCheckResult.Claim("c", Verdict.INSUFFICIENT_EVIDENCE, "看不到影片內容，搜尋也找不到相關報導")), false,
                List.of());
        when(checker.check(any())).thenReturn(new ModelCheck(unwatched, Set.of(), new Usage(1_000, 100, 2)));

        FactCheckReply reply = ask(text("Cg1", "1", "https://www.youtube.com/watch?v=EvU4PxdM0pI"), "合理嗎");

        assertThat(reply.text()).contains("看不到影片內容").doesNotContain(BotReplies.NOTHING_RELIABLE_FOUND);
    }

    @Test
    void answersAndRequestsForMaterialAreNeverCached() {
        when(checker.check(any())).thenReturn(answer(ReplyMode.ANSWER), answer(ReplyMode.ANSWER),
                answer(ReplyMode.NEED_MATERIAL), answer(ReplyMode.NEED_MATERIAL));

        service.verify("Cg1", null, "明天台北會下雨嗎");
        service.verify("Cg2", null, "明天台北會下雨嗎");
        assertThat(service.verify("Cg1", null, "這是真的嗎").text()).isEqualTo(BotReplies.ASK_TO_QUOTE);
        service.verify("Cg2", null, "這是真的嗎");

        verify(checker, times(4)).check(any());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM verification_cache").query(Integer.class).single()).isZero();
    }

    @Test
    void theNotOpenedNoteOnlyAppearsWhenNoLinkWasOpened() {
        when(checker.check(any())).thenReturn(answer(ReplyMode.ANSWER));

        assertThat(service.verify("Cg1", null, "這個 https://x.example 能點嗎").text()).contains(BotReplies.LINK_NOT_OPENED);
        assertThat(service.verify("Cg1", null, "這個 https://cwa.gov.tw/a/ 能信嗎").text()).doesNotContain(BotReplies.LINK_NOT_OPENED);
        assertThat(ask(text("Cg1", "7", "健保署公告 http://www.cwa.gov.tw/a?utm_source=line"), "").text())
                .doesNotContain(BotReplies.LINK_NOT_OPENED);
    }

    @Test
    void askingForMaterialItAlreadyHasSaysItCannotOpenItInsteadOfAskingToQuote() {
        when(checker.check(any())).thenReturn(answer(ReplyMode.NEED_MATERIAL));

        FactCheckReply reply = ask(text("Cg1", "1", "https://www.youtube.com/watch?v=EvU4PxdM0pI"), "這種常見嗎 影片的");

        assertThat(reply.text()).isEqualTo(BotReplies.CANNOT_SEE_MATERIAL);
        ArgumentCaptor<FactCheckInput> input = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker).check(input.capture());
        assertThat(input.getValue().materialText()).isEqualTo("https://www.youtube.com/watch?v=EvU4PxdM0pI\n\n"
                + DefaultFactCheckService.UNWATCHED_YOUTUBE);
    }

    @Test
    void aHighRiskAnswerWhoseSourcesWereMadeUpGetsTheFixedReply() {
        FactCheckResult madeUp = new FactCheckResult(Verdict.NOT_APPLICABLE, false, "沒問題", List.of(), false,
                List.of(new FactCheckResult.Source("https://made.up/x", "t", "假", "")), ReplyMode.ANSWER, true,
                List.of("可以一起吃"));
        when(checker.check(any())).thenReturn(new ModelCheck(madeUp, Set.of("https://www.mohw.gov.tw/a"), Usage.NONE));

        assertThat(service.verify("Cg1", null, "降血壓藥可以配葡萄柚嗎").text()).isEqualTo(BotReplies.HIGH_RISK_NO_SOURCE);
    }
}
