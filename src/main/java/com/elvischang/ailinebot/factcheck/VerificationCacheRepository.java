package com.elvischang.ailinebot.factcheck;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class VerificationCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(VerificationCacheRepository.class);

    private final JdbcClient jdbc;
    private final FactCheckProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public VerificationCacheRepository(JdbcClient jdbc, FactCheckProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Optional<FactCheckResult> get(String key, long now) {
        Optional<String> json = jdbc.sql("SELECT result_json FROM verification_cache WHERE content_hash = :k AND expires_at > :now")
                .param("k", key)
                .param("now", now)
                .query(String.class)
                .optional();
        return json.flatMap(this::read);
    }

    public void put(String key, FactCheckResult result, long now) {
        String json;
        try {
            json = mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fact-check result is not serializable", e);
        }
        jdbc.sql("""
                        INSERT INTO verification_cache(content_hash, result_json, created_at, expires_at)
                        VALUES (:k, :json, :now, :expires)
                        ON CONFLICT(content_hash) DO UPDATE SET result_json = excluded.result_json,
                            created_at = excluded.created_at, expires_at = excluded.expires_at
                        """)
                .param("k", key)
                .param("json", json)
                .param("now", now)
                .param("expires", now + props.cacheTtl().toMillis())
                .update();
    }

    public int deleteExpired(long now) {
        return jdbc.sql("DELETE FROM verification_cache WHERE expires_at <= :now").param("now", now).update();
    }

    private Optional<FactCheckResult> read(String json) {
        try {
            FactCheckResult result = mapper.readValue(json, FactCheckResult.class);
            if (result == null || result.overall() == null || result.claims() == null || result.sources() == null
                    || result.mode() == null || result.answerPoints() == null) {
                log.warn("ignoring unreadable cache entry cause=incomplete");
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (JsonProcessingException e) {
            log.warn("ignoring unreadable cache entry cause={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
