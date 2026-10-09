package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.TextMessageContent;
import com.linecorp.bot.webhook.model.UserMentionee;

import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

public final class Mentions {

    private Mentions() {
    }

    public static boolean mentionsBot(TextMessageContent message) {
        return selfMentionees(message).findAny().isPresent();
    }

    public static String stripBotMentions(TextMessageContent message) {
        String text = Objects.requireNonNullElse(message.text(), "");
        StringBuilder sb = new StringBuilder(text);
        selfMentionees(message)
                .filter(m -> m.index() != null && m.length() != null
                        && m.index() >= 0 && m.index() + m.length() <= text.length())
                .sorted(Comparator.comparingInt(UserMentionee::index).reversed())
                .forEach(m -> sb.delete(m.index(), m.index() + m.length()));
        return sb.toString().trim();
    }

    private static Stream<UserMentionee> selfMentionees(TextMessageContent message) {
        if (message.mention() == null || message.mention().mentionees() == null) {
            return Stream.empty();
        }
        return message.mention().mentionees().stream()
                .filter(UserMentionee.class::isInstance)
                .map(UserMentionee.class::cast)
                .filter(m -> Boolean.TRUE.equals(m.isSelf()));
    }
}
