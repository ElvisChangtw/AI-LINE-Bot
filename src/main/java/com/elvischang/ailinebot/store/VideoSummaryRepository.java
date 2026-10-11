package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class VideoSummaryRepository {

    // An empty summary records that the video could not be watched.
    public record Saved(String summary, long createdAt) {

        public boolean failed() {
            return summary.isEmpty();
        }
    }

    private final JdbcClient jdbc;

    public VideoSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Saved> find(String videoId) {
        return jdbc.sql("SELECT summary, created_at FROM video_summary WHERE video_id = :id")
                .param("id", videoId)
                .query((rs, n) -> new Saved(rs.getString("summary"), rs.getLong("created_at")))
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
