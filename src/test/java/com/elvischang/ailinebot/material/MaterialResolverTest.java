package com.elvischang.ailinebot.material;

import com.elvischang.ailinebot.delivery.BotReplies;
import com.elvischang.ailinebot.store.Material;
import com.elvischang.ailinebot.store.MaterialKind;
import com.elvischang.ailinebot.store.MaterialRepository;
import com.elvischang.ailinebot.store.MediaStatus;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialResolverTest extends IntegrationTestSupport {

    private static final long NOW = 10_000_000L;

    @Autowired
    MaterialResolver resolver;

    @Autowired
    MaterialRepository materials;

    private void put(String id, MaterialKind kind, MediaStatus media, boolean trigger, long at) {
        materials.insert(new Material("Cg1", id, kind, "t" + id, trigger, media, null, 0, at, at + 1_000_000_000L));
    }

    private Resolution resolve(String text, String quoted) {
        put("trigger", MaterialKind.TEXT, MediaStatus.NONE, true, NOW);
        return resolver.resolve(new Trigger("Cg1", "trigger", text, quoted), NOW);
    }

    @Test
    void usesQuotedTextMaterial() {
        put("99", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 5_000_000);
        assertThat(resolve("這是真的嗎", "99")).isInstanceOfSatisfying(Resolution.FactCheck.class,
                f -> assertThat(f.material().messageId()).isEqualTo("99"));
    }

    @Test
    void quotedMessageFromAnotherGroupIsNotFound() {
        materials.insert(new Material("Cg2", "99", MaterialKind.TEXT, "x", false, MediaStatus.NONE, null, 0, NOW, NOW + 1));
        assertThat(resolve("這是真的嗎", "99")).isEqualTo(new Resolution.Notice(BotReplies.QUOTE_NOT_FOUND));
    }

    @Test
    void quotedBotReplyAudioAndBrokenImagesGetNotices() {
        put("bot", MaterialKind.BOT_REPLY, MediaStatus.NONE, false, NOW);
        put("voice", MaterialKind.AUDIO, MediaStatus.NONE, false, NOW);
        put("gone", MaterialKind.IMAGE, MediaStatus.FAILED, false, NOW);
        put("big", MaterialKind.IMAGE, MediaStatus.TOO_LARGE, false, NOW);
        put("ok", MaterialKind.IMAGE, MediaStatus.PENDING, false, NOW);
        assertThat(resolve("?", "bot")).isEqualTo(new Resolution.Notice(BotReplies.QUOTED_BOT_REPLY));
        assertThat(resolve("為什麼這樣說", "bot")).isInstanceOf(Resolution.FactCheck.class);
        assertThat(resolve("?", "voice")).isEqualTo(new Resolution.Notice(BotReplies.MEDIA_UNSUPPORTED));
        assertThat(resolve("?", "gone")).isEqualTo(new Resolution.Notice(BotReplies.IMAGE_GONE));
        assertThat(resolve("?", "big")).isEqualTo(new Resolution.Notice(BotReplies.IMAGE_TOO_LARGE));
        assertThat(resolve("?", "ok")).isInstanceOf(Resolution.FactCheck.class);
    }

    @Test
    void mentionTextWithoutAQuoteIsSentAsAQuestionOnly() {
        put("recent", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 60_000);
        assertThat(resolve("聽說六十五歲以上每個人都可以領五千元是真的嗎", null)).isEqualTo(new Resolution.QuestionOnly());
        assertThat(resolve("真的嗎", null)).isEqualTo(new Resolution.QuestionOnly());
    }

    @Test
    void aBareMentionIsLeftForTheJobToLookBack() {
        put("recent", MaterialKind.TEXT, MediaStatus.NONE, false, NOW - 60_000);
        assertThat(resolve("", null)).isEqualTo(new Resolution.QuestionOnly());
    }

    @Test
    void imagineCommandWinsOverLongText() {
        assertThat(resolve("/imagine 一隻在台北街頭散步的柴犬穿著雨衣", null))
                .isEqualTo(new Resolution.Imagine("一隻在台北街頭散步的柴犬穿著雨衣"));
    }

    @Test
    void bareImagineAsksForAPrompt() {
        assertThat(resolve("/imagine", null)).isEqualTo(new Resolution.Notice(BotReplies.IMAGINE_NEEDS_PROMPT));
        assertThat(resolve("/imagine    ", null)).isEqualTo(new Resolution.Notice(BotReplies.IMAGINE_NEEDS_PROMPT));
    }
}
