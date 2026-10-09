package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InboundEventRepository {

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(String webhookEventId, long receivedAt, boolean redelivery) {
        return jdbc.sql("""
                        INSERT OR IGNORE INTO inbound_event(webhook_event_id, received_at, is_redelivery)
                        VALUES (:id, :at, :redelivery)
                        """)
                .param("id", webhookEventId)
                .param("at", receivedAt)
                .param("redelivery", redelivery ? 1 : 0)
                .update() == 1;
    }

    public int deleteOlderThan(long receivedBefore) {
        return jdbc.sql("DELETE FROM inbound_event WHERE received_at < :before")
                .param("before", receivedBefore)
                .update();
    }
}
