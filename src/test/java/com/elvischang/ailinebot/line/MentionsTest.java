package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.Mention;
import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserMentionee;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MentionsTest {

    private static TextMessageContent text(String text, UserMentionee... mentionees) {
        Mention mention = mentionees.length == 0 ? null : new Mention(List.of(mentionees));
        return new TextMessageContent("1", text, null, mention, "qt", null);
    }

    @Test
    void detectsAndStripsSelfMention() {
        var msg = text("@bot 這是真的嗎", new UserMentionee(0, 4, "Ubot", true));
        assertThat(Mentions.mentionsBot(msg)).isTrue();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("這是真的嗎");
    }

    @Test
    void ignoresMentionOfOtherUsers() {
        var msg = text("@媽媽 早安", new UserMentionee(0, 3, "Umom", false));
        assertThat(Mentions.mentionsBot(msg)).isFalse();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("@媽媽 早安");
    }

    @Test
    void stripsMentionAfterEmoji() {
        var msg = text("😀@bot 你好", new UserMentionee(2, 4, "Ubot", true));
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("😀 你好");
    }

    @Test
    void keepsTextWhenMentionIndexIsOutOfRange() {
        var msg = text("短", new UserMentionee(5, 4, "Ubot", true));
        assertThat(Mentions.mentionsBot(msg)).isTrue();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("短");
    }

    @Test
    void handlesMissingMention() {
        var msg = text("沒有 mention");
        assertThat(Mentions.mentionsBot(msg)).isFalse();
        assertThat(Mentions.stripBotMentions(msg)).isEqualTo("沒有 mention");
    }
}
