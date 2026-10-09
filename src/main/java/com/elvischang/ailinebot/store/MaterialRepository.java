package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

@Repository
public class MaterialRepository {

    private static final RowMapper<Material> MAPPER = (rs, n) -> new Material(
            rs.getString("group_id"),
            rs.getString("message_id"),
            MaterialKind.valueOf(rs.getString("kind")),
            rs.getString("text"),
            rs.getInt("is_bot_trigger") == 1,
            MediaStatus.valueOf(rs.getString("media_status")),
            rs.getString("media_sha256"),
            rs.getInt("media_attempts"),
            rs.getLong("created_at"),
            rs.getLong("expires_at"));

    private final JdbcClient jdbc;

    public MaterialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Material m) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO material(group_id, message_id, kind, text, is_bot_trigger, media_status,
                                                       media_sha256, media_attempts, created_at, expires_at)
                        VALUES (:groupId, :messageId, :kind, :text, :botTrigger, :mediaStatus,
                                :sha, :attempts, :createdAt, :expiresAt)
                        """)
                .param("groupId", m.groupId())
                .param("messageId", m.messageId())
                .param("kind", m.kind().name())
                .param("text", m.text())
                .param("botTrigger", m.botTrigger() ? 1 : 0)
                .param("mediaStatus", m.mediaStatus().name())
                .param("sha", m.mediaSha256())
                .param("attempts", m.mediaAttempts())
                .param("createdAt", m.createdAt())
                .param("expiresAt", m.expiresAt())
                .update();
    }

    public Optional<Material> find(String groupId, String messageId) {
        return jdbc.sql("SELECT * FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId)
                .param("m", messageId)
                .query(MAPPER)
                .optional();
    }

    public List<Material> recentCandidates(String groupId, long since, String excludeMessageId) {
        return jdbc.sql("""
                        SELECT * FROM material
                        WHERE group_id = :g AND created_at >= :since AND message_id <> :exclude
                          AND is_bot_trigger = 0 AND kind <> 'BOT_REPLY'
                        ORDER BY created_at DESC
                        LIMIT 5
                        """)
                .param("g", groupId)
                .param("since", since)
                .param("exclude", excludeMessageId)
                .query(MAPPER)
                .list();
    }

    public void markMedia(String groupId, String messageId, MediaStatus status, String sha256) {
        jdbc.sql("UPDATE material SET media_status = :s, media_sha256 = :sha WHERE group_id = :g AND message_id = :m")
                .param("s", status.name())
                .param("sha", sha256)
                .param("g", groupId)
                .param("m", messageId)
                .update();
    }

    public OptionalInt recordMediaFailure(String groupId, String messageId, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE material
                        SET media_attempts = media_attempts + 1,
                            media_status = CASE WHEN media_attempts + 1 >= :max THEN 'FAILED' ELSE media_status END
                        WHERE group_id = :g AND message_id = :m AND media_status = 'PENDING'
                        RETURNING media_attempts
                        """)
                .param("max", maxAttempts)
                .param("g", groupId)
                .param("m", messageId)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    public List<Material> pendingMedia(long createdBefore) {
        return jdbc.sql("SELECT * FROM material WHERE media_status = 'PENDING' AND created_at < :before")
                .param("before", createdBefore)
                .query(MAPPER)
                .list();
    }

    public List<Material> expired(long now) {
        return jdbc.sql("SELECT * FROM material WHERE expires_at < :now")
                .param("now", now)
                .query(MAPPER)
                .list();
    }

    public int deleteExpired(long now) {
        return jdbc.sql("DELETE FROM material WHERE expires_at < :now")
                .param("now", now)
                .update();
    }
}
