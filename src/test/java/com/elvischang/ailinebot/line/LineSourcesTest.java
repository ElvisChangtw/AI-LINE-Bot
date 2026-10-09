package com.elvischang.ailinebot.line;

import com.linecorp.bot.webhook.model.GroupSource;
import com.linecorp.bot.webhook.model.RoomSource;
import com.linecorp.bot.webhook.model.UserSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineSourcesTest {

    @Test
    void usesGroupOrRoomIdAndIgnoresOneOnOne() {
        assertThat(LineSources.chatId(new GroupSource("Cg1", "U1"))).contains("Cg1");
        assertThat(LineSources.chatId(new RoomSource("U1", "Rr1"))).contains("Rr1");
        assertThat(LineSources.chatId(new UserSource("U1"))).isEmpty();
    }
}
