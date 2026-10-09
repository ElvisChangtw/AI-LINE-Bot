package com.elvischang.ailinebot.store;

import com.elvischang.ailinebot.config.BotProperties;
import com.elvischang.ailinebot.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends IntegrationTestSupport {

    @Autowired
    BotProperties props;

    @Test
    void createsTablesInWalMode() {
        var tables = jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table'").query(String.class).list();
        assertThat(tables).contains("inbound_event", "material", "job", "flyway_schema_history");
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
    }

    @Test
    void bindsBotProperties() {
        assertThat(props.replyDeadline()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.materialTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.maxMessagesPerReply()).isEqualTo(5);
        assertThat(props.mediaMaxBytes()).isEqualTo(1024);
    }
}
