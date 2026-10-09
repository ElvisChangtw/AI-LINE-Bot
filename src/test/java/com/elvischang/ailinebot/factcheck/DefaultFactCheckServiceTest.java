package com.elvischang.ailinebot.factcheck;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.media.MediaStore;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.file.Files;
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

    private static final FactCheckResult RESULT = new FactCheckResult(Verdict.INCORRECT, false, "舊謠言",
            List.of(new FactCheckResult.Claim("c", Verdict.INCORRECT, "按 1 不會讓手機被控制")), true,
            List.of(new FactCheckResult.Source("https://tfc-taiwan.org.tw/?p=108215", "t", "台灣事實查核中心", ""),
                    new FactCheckResult.Source("https://example.com/made-up", "t", "假", "")));

    private Material text(String group, String id, String body) {
        long now = clock.millis();
        Material m = new Material(group, id, MaterialKind.TEXT, body, false, MediaStatus.NONE, null, 0, now, now + 1_000_000);
        materials.insert(m);
        return m;
    }

    @Test
    void checksFiltersFormatsAndCaches() {
        when(checker.check(any())).thenReturn(new ModelCheck(RESULT, Set.of("https://tfc-taiwan.org.tw/?p=108215"), new Usage(20_000, 4_000, 3)));

        FactCheckReply first = service.verify(text("Cg1", "1", "按1 手機被屏蔽 https://x.example"), "真的嗎");
        FactCheckReply second = service.verify(text("Cg2", "2", "按1   手機被屏蔽 https://x.example"), "這是真的？");

        assertThat(first.text()).startsWith("【查證結果】這是假消息，舊謠言")
                .contains(BotReplies.LINK_NOT_OPENED)
                .contains("https://tfc-taiwan.org.tw/?p=108215")
                .doesNotContain("example.com/made-up");
        assertThat(first.verdict()).isEqualTo(Verdict.INCORRECT);
        assertThat(first.costMicroUsd()).isEqualTo(2_000 + 2_000 + 30_000);
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.costMicroUsd()).isZero();
        verify(checker, times(1)).check(any());
    }

    @Test
    void refusesWhenTheBudgetIsSpent() {
        spendRepository.record(clock.millis(), 2_000_000L);

        FactCheckReply reply = service.verify(text("Cg1", "1", "內容"), "真的嗎");

        assertThat(reply.text()).isEqualTo(BotReplies.QUOTA_EXCEEDED);
        verify(checker, never()).check(any());
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

        assertThat(service.verify(pending, "真的嗎").text()).isEqualTo(BotReplies.IMAGE_NOT_READY);
        service.verify(stored, "真的嗎");

        ArgumentCaptor<FactCheckInput> input = ArgumentCaptor.forClass(FactCheckInput.class);
        verify(checker).check(input.capture());
        assertThat(input.getValue().imageMimeType()).isEqualTo("image/png");
        assertThat(input.getValue().materialText()).isNull();
        Files.deleteIfExists(file);
    }
}
