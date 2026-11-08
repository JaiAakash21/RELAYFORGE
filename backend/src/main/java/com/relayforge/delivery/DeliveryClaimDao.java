package com.relayforge.delivery;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The concurrency-critical delivery SQL.
 *
 * <p>These statements are written as SQL rather than expressed through JPA
 * deliberately. Each one depends on a guarantee the database gives and an ORM
 * does not: a conditional {@code UPDATE} that is atomic against concurrent
 * workers, {@code FOR UPDATE SKIP LOCKED} to hand disjoint work to competing
 * pollers, and {@code RETURNING} to claim and read in a single round trip. Those
 * are the mechanisms that make the pipeline correct under concurrency, so they
 * are kept visible instead of hidden behind generated SQL.
 */
@Repository
public class DeliveryClaimDao {

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryClaimDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A delivery handed to Kafka by the dispatcher. */
    public record DispatchableDelivery(
            UUID id, UUID tenantId, UUID eventId, UUID endpointId, int attemptCount) {}

    /**
     * Attempts to take exclusive ownership of one delivery.
     *
     * <p>This single statement is the whole mutual-exclusion story for delivery
     * workers. It succeeds when the delivery is genuinely ready to be attempted:
     *
     * <ul>
     *   <li>it is {@code PENDING} or {@code RETRY_SCHEDULED} and its backoff has
     *       elapsed, or
     *   <li>it is {@code IN_PROGRESS} but the previous owner's lease has expired,
     *       which means that worker died mid-flight.
     * </ul>
     *
     * <p>Anything else — already succeeded, already exhausted, cancelled, or
     * currently held by a live worker — updates zero rows. A duplicate Kafka
     * message therefore costs one cheap no-op UPDATE and nothing else: this is
     * precisely where at-least-once message delivery is converted into
     * effectively-once delivery <em>processing</em>.
     *
     * @return {@code true} if this caller now owns the delivery
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID deliveryId, String owner, Instant now, Duration leaseDuration) {
        String sql =
                """
                UPDATE deliveries
                   SET status           = 'IN_PROGRESS',
                       lease_owner      = :owner,
                       lease_until      = CAST(:leaseUntil AS timestamptz),
                       first_attempt_at = COALESCE(first_attempt_at, CAST(:now AS timestamptz)),
                       updated_at       = CAST(:now AS timestamptz),
                       version          = version + 1
                 WHERE id = :id
                   AND (
                         (status IN ('PENDING', 'RETRY_SCHEDULED')
                           AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:now AS timestamptz)))
                      OR (status = 'IN_PROGRESS' AND lease_until IS NOT NULL AND lease_until <= CAST(:now AS timestamptz))
                   )
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("id", deliveryId)
                        .addValue("owner", owner)
                        .addValue("now", Timestamp.from(now))
                        .addValue("leaseUntil", Timestamp.from(now.plus(leaseDuration)));
        return jdbc.update(sql, params) == 1;
    }

    /**
     * Extends the lease of a delivery this worker already owns.
     *
     * <p>Needed when a receiver is slow but still within its timeout budget: the
     * lease must outlive the request, or the reconciler would reclaim work that
     * is making perfectly good progress.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean extendLease(UUID deliveryId, String owner, Instant newLeaseUntil) {
        String sql =
                """
                UPDATE deliveries
                   SET lease_until = CAST(:leaseUntil AS timestamptz)
                 WHERE id = :id
                   AND lease_owner = :owner
                   AND status = 'IN_PROGRESS'
                """;
        return jdbc.update(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("id", deliveryId)
                                .addValue("owner", owner)
                                .addValue("leaseUntil", Timestamp.from(newLeaseUntil)))
                == 1;
    }

    /**
     * Claims a batch of due deliveries and marks them as queued, atomically.
     *
     * <p>The {@code FOR UPDATE SKIP LOCKED} subquery is what lets several
     * dispatcher instances poll the same table concurrently: each one locks a
     * disjoint set of rows and skips whatever a sibling already holds, so there
     * is no coordination, no leader election and no contention beyond row locks.
     *
     * <p>{@code queued_at} is advanced in the same statement. A delivery is
     * therefore not re-enqueued while its Kafka message is plausibly still in
     * flight, but <em>is</em> re-enqueued once {@code staleBefore} has passed —
     * which is exactly the recovery path for a message that Kafka lost, a
     * publisher that died, or a consumer that crashed without acknowledging.
     *
     * @param now         logical clock for this poll
     * @param staleBefore only consider rows last queued at or before this instant
     * @param limit       batch size
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<DispatchableDelivery> claimDueForDispatch(
            Instant now, Instant staleBefore, int limit) {
        String sql =
                """
                WITH due AS (
                    SELECT id
                      FROM deliveries
                     WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
                       AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:now AS timestamptz))
                       AND (queued_at IS NULL OR queued_at <= CAST(:staleBefore AS timestamptz))
                     ORDER BY next_attempt_at NULLS FIRST, created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE deliveries d
                   SET queued_at = CAST(:now AS timestamptz)
                  FROM due
                 WHERE d.id = due.id
             RETURNING d.id, d.tenant_id, d.event_id, d.endpoint_id, d.attempt_count
                """;
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("staleBefore", Timestamp.from(staleBefore))
                        .addValue("limit", limit);
        return jdbc.query(
                sql,
                params,
                (rs, i) ->
                        new DispatchableDelivery(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("event_id", UUID.class),
                                rs.getObject("endpoint_id", UUID.class),
                                rs.getInt("attempt_count")));
    }

    /**
     * Recovers deliveries stranded in {@code IN_PROGRESS} by a dead worker.
     *
     * <p>The lease is the liveness signal: a worker that is alive keeps extending
     * it, so an expired lease means the process holding it is gone. Such a
     * delivery is moved back to {@code RETRY_SCHEDULED} with a short delay and
     * its {@code queued_at} cleared so the dispatcher re-enqueues it promptly.
     *
     * <p><strong>Honest caveat.</strong> If the worker died <em>after</em> the
     * receiver processed the request but before the result was written, this
     * recovery produces a second delivery of the same event. That is inherent to
     * at-least-once delivery over a network that can lose the response, not a
     * defect in this implementation — which is why every outbound request carries
     * a stable {@code X-RelayForge-Event-Id} for receivers to deduplicate on.
     * The {@code attempt_count} is still incremented so the budget cannot be
     * escaped by crash-looping.
     *
     * @return number of deliveries recovered
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reclaimExpiredLeases(Instant now, Duration retryDelay, int limit) {
        String sql =
                """
                WITH expired AS (
                    SELECT id
                      FROM deliveries
                     WHERE status = 'IN_PROGRESS'
                       AND lease_until IS NOT NULL
                       AND lease_until <= CAST(:now AS timestamptz)
                     ORDER BY lease_until
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE deliveries d
                   SET status          = CASE
                                            WHEN d.attempt_count >= d.max_attempts THEN 'FAILED'
                                            ELSE 'RETRY_SCHEDULED'
                                         END,
                       next_attempt_at = CASE
                                            WHEN d.attempt_count >= d.max_attempts THEN NULL
                                            ELSE CAST(:nextAttemptAt AS timestamptz)
                                         END,
                       completed_at    = CASE
                                            WHEN d.attempt_count >= d.max_attempts THEN CAST(:now AS timestamptz)
                                            ELSE NULL
                                         END,
                       queued_at       = NULL,
                       lease_owner     = NULL,
                       lease_until     = NULL,
                       last_error      = 'Worker lease expired before the attempt was recorded; '
                                      || 'the request may or may not have reached the receiver.',
                       last_error_class = 'UNKNOWN',
                       updated_at      = CAST(:now AS timestamptz),
                       version         = d.version + 1
                  FROM expired
                 WHERE d.id = expired.id
                """;
        return jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("nextAttemptAt", Timestamp.from(now.plus(retryDelay)))
                        .addValue("limit", limit));
    }

    /**
     * Records the result of a successful attempt and releases the lease.
     *
     * <p>Guarded on {@code lease_owner} so a worker whose lease was reclaimed
     * while it was blocked on the network cannot overwrite the decision the
     * reconciler already made.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeSuccess(
            UUID deliveryId, String owner, int attemptCount, Integer responseCode, Instant now) {
        String sql =
                """
                UPDATE deliveries
                   SET status             = 'SUCCESS',
                       attempt_count      = :attemptCount,
                       last_response_code = :responseCode,
                       last_error         = NULL,
                       last_error_class   = 'NONE',
                       next_attempt_at    = NULL,
                       queued_at          = NULL,
                       lease_owner        = NULL,
                       lease_until        = NULL,
                       completed_at       = CAST(:now AS timestamptz),
                       updated_at         = CAST(:now AS timestamptz),
                       version            = version + 1
                 WHERE id = :id
                   AND lease_owner = :owner
                   AND status = 'IN_PROGRESS'
                """;
        return jdbc.update(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("id", deliveryId)
                                .addValue("owner", owner)
                                .addValue("attemptCount", attemptCount)
                                .addValue("responseCode", responseCode)
                                .addValue("now", Timestamp.from(now)))
                == 1;
    }

    /** Schedules the next attempt after a retryable failure, releasing the lease. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleRetry(
            UUID deliveryId,
            String owner,
            int attemptCount,
            Integer responseCode,
            String error,
            ErrorClass errorClass,
            Instant nextAttemptAt,
            Instant now) {
        String sql =
                """
                UPDATE deliveries
                   SET status             = 'RETRY_SCHEDULED',
                       attempt_count      = :attemptCount,
                       last_response_code = :responseCode,
                       last_error         = :error,
                       last_error_class   = :errorClass,
                       next_attempt_at    = CAST(:nextAttemptAt AS timestamptz),
                       queued_at          = NULL,
                       lease_owner        = NULL,
                       lease_until        = NULL,
                       completed_at       = NULL,
                       updated_at         = CAST(:now AS timestamptz),
                       version            = version + 1
                 WHERE id = :id
                   AND lease_owner = :owner
                   AND status = 'IN_PROGRESS'
                """;
        return jdbc.update(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("id", deliveryId)
                                .addValue("owner", owner)
                                .addValue("attemptCount", attemptCount)
                                .addValue("responseCode", responseCode)
                                .addValue("error", error)
                                .addValue("errorClass", errorClass.name())
                                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                                .addValue("now", Timestamp.from(now)))
                == 1;
    }

    /** Moves a delivery to the dead-letter state, releasing the lease. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failTerminally(
            UUID deliveryId,
            String owner,
            int attemptCount,
            Integer responseCode,
            String error,
            ErrorClass errorClass,
            Instant now) {
        String sql =
                """
                UPDATE deliveries
                   SET status             = 'FAILED',
                       attempt_count      = :attemptCount,
                       last_response_code = :responseCode,
                       last_error         = :error,
                       last_error_class   = :errorClass,
                       next_attempt_at    = NULL,
                       queued_at          = NULL,
                       lease_owner        = NULL,
                       lease_until        = NULL,
                       completed_at       = CAST(:now AS timestamptz),
                       updated_at         = CAST(:now AS timestamptz),
                       version            = version + 1
                 WHERE id = :id
                   AND lease_owner = :owner
                   AND status = 'IN_PROGRESS'
                """;
        return jdbc.update(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("id", deliveryId)
                                .addValue("owner", owner)
                                .addValue("attemptCount", attemptCount)
                                .addValue("responseCode", responseCode)
                                .addValue("error", error)
                                .addValue("errorClass", errorClass.name())
                                .addValue("now", Timestamp.from(now)))
                == 1;
    }

    /**
     * Reopens a dead-lettered delivery for replay.
     *
     * <p>Guarded on the current status being replayable so that two operators
     * clicking "replay" at the same moment produce exactly one replay. The
     * attempt budget is extended rather than reset, and {@code attempt_count} is
     * <em>not</em> zeroed — the historical attempts remain counted and remain
     * visible, because erasing them would destroy the evidence of why the
     * delivery dead-lettered in the first place.
     *
     * @param extraAttempts additional attempts granted for this replay
     * @return {@code true} when this caller performed the replay
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reopenForReplay(UUID deliveryId, UUID tenantId, int extraAttempts, Instant now) {
        String sql =
                """
                UPDATE deliveries
                   SET status          = 'PENDING',
                       max_attempts    = attempt_count + :extraAttempts,
                       replay_count    = replay_count + 1,
                       next_attempt_at = CAST(:now AS timestamptz),
                       queued_at       = NULL,
                       lease_owner     = NULL,
                       lease_until     = NULL,
                       completed_at    = NULL,
                       updated_at      = CAST(:now AS timestamptz),
                       version         = version + 1
                 WHERE id = :id
                   AND tenant_id = :tenantId
                   AND status IN ('FAILED', 'CANCELLED')
                """;
        return jdbc.update(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("id", deliveryId)
                                .addValue("tenantId", tenantId)
                                .addValue("extraAttempts", extraAttempts)
                                .addValue("now", Timestamp.from(now)))
                == 1;
    }

    /** Count of deliveries that are due now but not yet picked up — the queue backlog. */
    public long countDueBacklog(Instant now) {
        String sql =
                """
                SELECT COUNT(*) FROM deliveries
                 WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
                   AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:now AS timestamptz))
                """;
        Long value =
                jdbc.queryForObject(
                        sql, Map.of("now", Timestamp.from(now)), Long.class);
        return value == null ? 0L : value;
    }

    /** Count of deliveries currently leased by a worker. */
    public long countInProgress() {
        Long value =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM deliveries WHERE status = 'IN_PROGRESS'",
                        Map.of(),
                        Long.class);
        return value == null ? 0L : value;
    }

    /**
     * Age of the oldest piece of due-but-unstarted work, in seconds.
     *
     * <p>This is the platform's real lag signal. Kafka consumer lag says how many
     * messages are unread; this says how long a tenant's oldest ready delivery has
     * been waiting, which is what a user actually experiences.
     */
    public double oldestDueAgeSeconds(Instant now) {
        String sql =
                """
                SELECT COALESCE(
                         MAX(EXTRACT(EPOCH FROM (CAST(:now AS timestamptz) - COALESCE(next_attempt_at, created_at)))),
                         0)
                  FROM deliveries
                 WHERE status IN ('PENDING', 'RETRY_SCHEDULED')
                   AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:now AS timestamptz))
                """;
        Double value =
                jdbc.queryForObject(sql, Map.of("now", Timestamp.from(now)), Double.class);
        return value == null ? 0d : value;
    }
}
