package com.relayforge.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim/ack SQL for the transactional outbox.
 *
 * <h2>The three-state claim, and why it matters</h2>
 *
 * A naive publisher reads {@code PENDING} rows, publishes them and marks them
 * {@code PUBLISHED}. That has two failure modes it cannot survive:
 *
 * <ol>
 *   <li>Two publisher instances read the same rows and publish them twice, every
 *       time, not just occasionally.
 *   <li>A publisher dies after the broker acknowledged but before the row was
 *       updated. The row stays {@code PENDING} forever or is republished forever,
 *       depending on how the retry is written.
 * </ol>
 *
 * The intermediate {@code CLAIMED} state with an expiry fixes both. Claiming is
 * a single atomic statement using {@code FOR UPDATE SKIP LOCKED}, so instances
 * never claim the same row concurrently. And a claim that is never acknowledged
 * expires, which turns case (2) from "lost or looping" into "republished once,
 * shortly afterwards" — a duplicate, which the consumer is built to absorb.
 *
 * <p>This is the deliberate trade: the outbox guarantees <em>at-least-once</em>
 * relay to Kafka and relies on idempotent consumption, rather than pretending to
 * guarantee exactly-once across two systems that share no transaction.
 */
@Repository
public class OutboxClaimDao {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxClaimDao(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** A claimed outbox row, ready to be relayed. */
    public record ClaimedRecord(
            UUID id,
            String topic,
            String partitionKey,
            String eventType,
            UUID tenantId,
            JsonNode payload,
            String traceParent,
            int attemptCount) {}

    /**
     * Atomically claims up to {@code limit} relayable rows.
     *
     * <p>Picks up both never-claimed rows and rows whose previous claim expired,
     * which is the crash-recovery path. The claim is written and the rows are
     * returned by one statement, so there is no window in which a row is selected
     * but not yet marked.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedRecord> claimBatch(
            String owner, Instant now, Duration claimTtl, int limit) {
        String sql =
                """
                WITH claimable AS (
                    SELECT id
                      FROM outbox_events
                     WHERE (status = 'PENDING')
                        OR (status = 'CLAIMED'
                            AND claim_expires_at IS NOT NULL
                            AND claim_expires_at <= :now)
                     ORDER BY created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE outbox_events o
                   SET status           = 'CLAIMED',
                       claimed_by       = :owner,
                       claimed_at       = :now,
                       claim_expires_at = :claimExpiresAt,
                       attempt_count    = o.attempt_count + 1
                  FROM claimable
                 WHERE o.id = claimable.id
             RETURNING o.id, o.topic, o.partition_key, o.event_type, o.tenant_id,
                       o.payload, o.trace_parent, o.attempt_count
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("owner", owner)
                        .addValue("now", Timestamp.from(now))
                        .addValue("claimExpiresAt", Timestamp.from(now.plus(claimTtl)))
                        .addValue("limit", limit);
        return jdbc.query(
                sql,
                params,
                (rs, i) -> {
                    JsonNode payload;
                    try {
                        payload = objectMapper.readTree(rs.getString("payload"));
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Outbox row " + rs.getString("id") + " has unreadable payload", e);
                    }
                    return new ClaimedRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("topic"),
                            rs.getString("partition_key"),
                            rs.getString("event_type"),
                            rs.getObject("tenant_id", UUID.class),
                            payload,
                            rs.getString("trace_parent"),
                            rs.getInt("attempt_count"));
                });
    }

    /**
     * Marks rows as published after the broker acknowledged them.
     *
     * <p>Called only on acknowledgement, never optimistically. If this update
     * itself fails — the exact "Kafka succeeded but the database did not" case —
     * the rows stay {@code CLAIMED}, their claim expires, and they are
     * republished. One duplicate Kafka message is the cost of never losing an
     * event, and the consumer is idempotent precisely so that cost is affordable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markPublished(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql =
                """
                UPDATE outbox_events
                   SET status           = 'PUBLISHED',
                       published_at     = :now,
                       claim_expires_at = NULL,
                       last_error       = NULL
                 WHERE id IN (:ids)
                """;
        return jdbc.update(
                sql,
                new MapSqlParameterSource().addValue("ids", ids).addValue("now", Timestamp.from(now)));
    }

    /**
     * Releases a claim after a publish failure so the row is retried promptly,
     * or parks it as {@code DEAD} once it has failed too many times.
     *
     * <p>Parking rather than retrying forever is intentional: a row that cannot
     * be published after many tries is almost always malformed or pointing at a
     * topic that does not exist, and a tight infinite retry loop on it would
     * starve the healthy backlog behind it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseWithError(UUID id, String error, int maxAttempts) {
        String sql =
                """
                UPDATE outbox_events
                   SET status           = CASE WHEN attempt_count >= :maxAttempts
                                               THEN 'DEAD' ELSE 'PENDING' END,
                       claimed_by       = NULL,
                       claim_expires_at = NULL,
                       last_error       = :error
                 WHERE id = :id
                   AND status = 'CLAIMED'
                """;
        return jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("error", error)
                        .addValue("maxAttempts", maxAttempts));
    }

    /** Age in seconds of the oldest unpublished outbox row. */
    public double oldestUnpublishedAgeSeconds(Instant now) {
        String sql =
                """
                SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (:now - created_at))), 0)
                  FROM outbox_events
                 WHERE status IN ('PENDING', 'CLAIMED')
                """;
        Double value = jdbc.queryForObject(sql, Map.of("now", Timestamp.from(now)), Double.class);
        return value == null ? 0d : value;
    }

    public long countByStatus(OutboxStatus status) {
        Long value =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE status = :status",
                        Map.of("status", status.name()),
                        Long.class);
        return value == null ? 0L : value;
    }
}
