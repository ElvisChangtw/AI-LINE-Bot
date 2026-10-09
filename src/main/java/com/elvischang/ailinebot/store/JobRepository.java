package com.elvischang.ailinebot.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private static final RowMapper<Job> MAPPER = (rs, n) -> new Job(
            rs.getString("id"),
            JobKind.valueOf(rs.getString("kind")),
            rs.getString("group_id"),
            rs.getString("trigger_message_id"),
            rs.getString("material_message_id"),
            rs.getString("user_question"),
            rs.getString("reply_token"),
            rs.getString("quote_token"),
            rs.getLong("received_at"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            nullableLong(rs, "lease_until"),
            rs.getString("result_text"),
            SendState.valueOf(rs.getString("send_state")),
            rs.getString("carrier_job_id"),
            nullableLong(rs, "waiting_since"),
            rs.getString("error_code"),
            rs.getLong("created_at"),
            nullableLong(rs, "finished_at"));

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public String insert(NewJob job, long now) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO job(id, kind, group_id, trigger_message_id, material_message_id, user_question,
                                        reply_token, quote_token, received_at, status, result_text, created_at)
                        VALUES (:id, :kind, :groupId, :trigger, :material, :question,
                                :replyToken, :quoteToken, :now, 'PENDING', :result, :now)
                        """)
                .param("id", id)
                .param("kind", job.kind().name())
                .param("groupId", job.groupId())
                .param("trigger", job.triggerMessageId())
                .param("material", job.materialMessageId())
                .param("question", job.userQuestion())
                .param("replyToken", job.replyToken())
                .param("quoteToken", job.quoteToken())
                .param("result", job.resultText())
                .param("now", now)
                .update();
        return id;
    }

    public Optional<Job> find(String id) {
        return jdbc.sql("SELECT * FROM job WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public boolean claim(String id, long now, long leaseMs) {
        return jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', attempts = attempts + 1, lease_until = :lease
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("lease", now + leaseMs)
                .param("id", id)
                .update() == 1;
    }

    public void complete(String id, String resultText, long now, int attempt) {
        jdbc.sql("""
                        UPDATE job SET status = 'DONE', result_text = :result, lease_until = NULL,
                                       error_code = NULL, finished_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("result", resultText)
                .param("now", now)
                .param("id", id)
                .param("attempt", attempt)
                .update();
    }

    public void fail(String id, String resultText, String errorCode, long now, int attempt) {
        jdbc.sql("""
                        UPDATE job SET status = 'FAILED', result_text = :result, lease_until = NULL,
                                       error_code = :code, finished_at = :now
                        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("result", resultText)
                .param("code", errorCode)
                .param("now", now)
                .param("id", id)
                .param("attempt", attempt)
                .update();
    }

    public void release(String id, String errorCode, int attempt) {
        jdbc.sql("""
                        UPDATE job SET status = 'PENDING', lease_until = NULL, error_code = :code
                        WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                        """)
                .param("code", errorCode)
                .param("id", id)
                .param("attempt", attempt)
                .update();
    }

    public List<String> stalePending(long createdBefore) {
        return jdbc.sql("SELECT id FROM job WHERE status = 'PENDING' AND created_at < :before ORDER BY created_at")
                .param("before", createdBefore)
                .query(String.class)
                .list();
    }

    public List<Job> expiredLeases(long now) {
        return jdbc.sql("SELECT * FROM job WHERE status = 'RUNNING' AND lease_until < :now")
                .param("now", now)
                .query(MAPPER)
                .list();
    }

    public List<String> unsentFinished(long finishedBefore) {
        return jdbc.sql("""
                        SELECT id FROM job
                        WHERE status IN ('DONE', 'FAILED') AND send_state = 'NOT_SENT' AND finished_at < :before
                        ORDER BY finished_at
                        """)
                .param("before", finishedBefore)
                .query(String.class)
                .list();
    }

    public boolean hasWaiting(String groupId, long notBefore) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM job
                        WHERE group_id = :g AND send_state = 'WAITING_NEXT' AND waiting_since >= :notBefore
                        """)
                .param("g", groupId)
                .param("notBefore", notBefore)
                .query(Integer.class)
                .single() > 0;
    }

    public int attachWaiting(String groupId, String carrierJobId, int limit, long notBefore) {
        return jdbc.sql("""
                        UPDATE job SET send_state = 'CARRIED', carrier_job_id = :carrier
                        WHERE id IN (
                            SELECT id FROM job
                            WHERE group_id = :g AND send_state = 'WAITING_NEXT' AND waiting_since >= :notBefore
                            ORDER BY waiting_since, created_at
                            LIMIT :limit)
                        """)
                .param("carrier", carrierJobId)
                .param("g", groupId)
                .param("notBefore", notBefore)
                .param("limit", limit)
                .update();
    }

    public List<Job> carried(String carrierJobId) {
        return jdbc.sql("""
                        SELECT * FROM job WHERE carrier_job_id = :carrier AND send_state = 'CARRIED'
                        ORDER BY waiting_since, created_at
                        """)
                .param("carrier", carrierJobId)
                .query(MAPPER)
                .list();
    }

    public boolean startSending(String id) {
        return jdbc.sql("UPDATE job SET send_state = 'SENDING_REPLY' WHERE id = :id AND send_state = 'NOT_SENT'")
                .param("id", id)
                .update() == 1;
    }

    public void setSendState(Collection<String> ids, SendState state) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE job SET send_state = :state WHERE id IN (:ids)")
                .param("state", state.name())
                .param("ids", ids)
                .update();
    }

    public void markWaiting(Collection<String> ids, long now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        UPDATE job SET send_state = 'WAITING_NEXT', carrier_job_id = NULL,
                                       waiting_since = COALESCE(waiting_since, :now)
                        WHERE id IN (:ids) AND send_state IN ('NOT_SENT', 'SENDING_REPLY', 'CARRIED')
                        """)
                .param("now", now)
                .param("ids", ids)
                .update();
    }

    public int expireWaiting(long waitingBefore) {
        return jdbc.sql("UPDATE job SET send_state = 'EXPIRED' WHERE send_state = 'WAITING_NEXT' AND waiting_since < :before")
                .param("before", waitingBefore)
                .update();
    }

    public int expireGroup(String groupId) {
        return jdbc.sql("UPDATE job SET send_state = 'EXPIRED' WHERE group_id = :g AND send_state IN ('WAITING_NEXT', 'CARRIED')")
                .param("g", groupId)
                .update();
    }

    public int resetSendingToUnknown() {
        return jdbc.sql("UPDATE job SET send_state = 'UNKNOWN' WHERE send_state = 'SENDING_REPLY'").update();
    }

    public int clearContent(long createdBefore) {
        return jdbc.sql("""
                        UPDATE job SET result_text = NULL, user_question = NULL
                        WHERE created_at < :before AND send_state NOT IN ('NOT_SENT', 'CARRIED', 'WAITING_NEXT', 'SENDING_REPLY')
                          AND (result_text IS NOT NULL OR user_question IS NOT NULL)
                        """)
                .param("before", createdBefore)
                .update();
    }

    public int deleteOlderThan(long createdBefore) {
        return jdbc.sql("DELETE FROM job WHERE created_at < :before")
                .param("before", createdBefore)
                .update();
    }
}
