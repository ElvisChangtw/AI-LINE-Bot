package com.elvischang.ailinebot.factcheck;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SpendRepository {

    private final JdbcClient jdbc;

    public SpendRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long at, long microUsd) {
        jdbc.sql("INSERT INTO spend_log(spent_at, micro_usd) VALUES (:at, :cost)")
                .param("at", at)
                .param("cost", microUsd)
                .update();
    }

    public long sumSince(long since) {
        return jdbc.sql("SELECT COALESCE(SUM(micro_usd), 0) FROM spend_log WHERE spent_at >= :since")
                .param("since", since)
                .query(Long.class)
                .single();
    }

    public int deleteOlderThan(long before) {
        return jdbc.sql("DELETE FROM spend_log WHERE spent_at < :before").param("before", before).update();
    }
}
