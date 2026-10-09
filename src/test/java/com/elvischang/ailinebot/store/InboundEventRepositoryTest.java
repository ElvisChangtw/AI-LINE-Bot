package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class InboundEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    InboundEventRepository events;

    @Test
    void acceptsEachWebhookEventOnce() {
        assertThat(events.insertIfAbsent("e1", 100, false)).isTrue();
        assertThat(events.insertIfAbsent("e1", 200, true)).isFalse();
        assertThat(events.deleteOlderThan(150)).isEqualTo(1);
        assertThat(events.insertIfAbsent("e1", 300, false)).isTrue();
    }
}
