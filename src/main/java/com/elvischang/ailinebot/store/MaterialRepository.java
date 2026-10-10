package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

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
        insert(m, null);
    }

    public void insert(Material m, String senderId) {
        insert(m, senderId, null);
    }

    public void insert(Material m, String senderId, Long mediaDurationMs) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO material(group_id, message_id, kind, text, is_bot_trigger, media_status,
                                                       media_sha256, media_attempts, created_at, expires_at, sender_id,
                                                       media_duration_ms)
                        VALUES (:groupId, :messageId, :kind, :text, :botTrigger, :mediaStatus,
                                :sha, :attempts, :createdAt, :expiresAt, :sender, :duration)
                        """)
                .param("sender", senderId)
                .param("duration", mediaDurationMs)
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

    public void delete(String groupId, String messageId) {
        jdbc.sql("DELETE FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId)
                .param("m", messageId)
                .update();
    }

    public List<String> deleteGroup(String groupId) {
        return jdbc.sql("DELETE FROM material WHERE group_id = :g RETURNING message_id")
                .param("g", groupId)
                .query(String.class)
                .list();
    }

    public List<RecentMessage> recentCandidates(String groupId, long since, long until, String excludeMessageId,
                                                boolean includeBotReplies) {
        return jdbc.sql("""
                        SELECT * FROM material
                        WHERE group_id = :g AND created_at >= :since AND created_at <= :until AND message_id <> :exclude
                          AND is_bot_trigger = 0
                          AND (kind <> 'BOT_REPLY' OR (:bot = 1 AND text IS NOT NULL))
                        ORDER BY created_at DESC
                        LIMIT 5
                        """)
                .param("g", groupId)
                .param("since", since)
                .param("until", until)
                .param("exclude", excludeMessageId)
                .param("bot", includeBotReplies ? 1 : 0)
                .query((rs, n) -> new RecentMessage(MAPPER.mapRow(rs, n), rs.getString("sender_id")))
                .list();
    }

    public OptionalLong mediaDurationMs(String groupId, String messageId) {
        return jdbc.sql("SELECT media_duration_ms FROM material WHERE group_id = :g AND message_id = :m")
                .param("g", groupId)
                .param("m", messageId)
                .query((rs, n) -> {
                    long value = rs.getLong(1);
                    return rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
                })
                .optional()
                .orElse(OptionalLong.empty());
    }

    public int saveTranscript(String groupId, String messageId, String text, String previewSha256) {
        return jdbc.sql("""
                        UPDATE material SET text = :t, media_status = 'STORED', media_sha256 = :sha
                        WHERE group_id = :g AND message_id = :m
                        """)
                .param("t", text)
                .param("sha", previewSha256)
                .param("g", groupId)
                .param("m", messageId)
                .update();
    }

    public int markMedia(String groupId, String messageId, MediaStatus status, String sha256) {
        return jdbc.sql("UPDATE material SET media_status = :s, media_sha256 = :sha WHERE group_id = :g AND message_id = :m")
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
