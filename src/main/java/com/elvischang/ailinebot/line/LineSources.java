package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.Source;

import java.util.Optional;

public final class LineSources {

    private LineSources() {
    }

    public static Optional<String> chatId(Source source) {
        if (source instanceof GroupSource group) {
            return Optional.ofNullable(group.groupId());
        }
        if (source instanceof RoomSource room) {
            return Optional.ofNullable(room.roomId());
        }
        return Optional.empty();
    }
}
