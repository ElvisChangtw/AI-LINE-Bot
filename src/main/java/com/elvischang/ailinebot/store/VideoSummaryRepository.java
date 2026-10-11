package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class VideoSummaryRepository {

    private final JdbcClient jdbc;

    public VideoSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String videoId, long notBefore) {
        return jdbc.sql("SELECT summary FROM video_summary WHERE video_id = :id AND created_at >= :since")
                .param("id", videoId)
                .param("since", notBefore)
                .query(String.class)
                .optional();
    }

    // Old rows are dropped on the way, so the table only ever holds the last TTL's worth of videos.
    public void save(String videoId, String summary, long now, long notBefore) {
        jdbc.sql("DELETE FROM video_summary WHERE created_at < :since").param("since", notBefore).update();
        jdbc.sql("INSERT OR REPLACE INTO video_summary(video_id, summary, created_at) VALUES (:id, :summary, :now)")
                .param("id", videoId)
                .param("summary", summary)
                .param("now", now)
                .update();
    }
}
