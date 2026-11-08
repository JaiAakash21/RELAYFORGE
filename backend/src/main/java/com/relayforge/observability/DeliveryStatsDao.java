package com.relayforge.observability;

import com.relayforge.delivery.AttemptOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Maintains and reads the per-minute delivery rollup.
 *
 * <h2>Why a rollup rather than querying attempts directly</h2>
 *
 * The overview and observability screens ask for success rate, throughput and
 * latency percentiles over a window. Computing those from {@code
 * delivery_attempts} means scanning every attempt in the window — the query gets
 * slower in exact proportion to how much traffic the platform has handled, which
 * is the wrong direction for a dashboard that is meant to be most useful when
 * things are busy.
 *
 * <p>The rollup is written by the same transaction that records the attempt, as a
 * single UPSERT. That ordering matters: the aggregate can never describe an
 * attempt that was rolled back, and no background job can fall behind.
 *
 * <h2>Approximate percentiles, stated plainly</h2>
 *
 * Latency is kept as fixed histogram buckets, so p50/p95/p99 computed from them
 * are interpolated estimates, not exact order statistics. That is a deliberate
 * trade: exact percentiles need every raw observation in the window, which is
 * the scan we are avoiding. The dashboard labels these values as approximate.
 */
@Repository
public class DeliveryStatsDao {

    private final NamedParameterJdbcTemplate jdbc;

    public DeliveryStatsDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upper bounds of the latency histogram buckets, in milliseconds. */
    private static final int[] BUCKET_BOUNDS = {50, 100, 250, 500, 1000, 2500, 5000};

    private static final String[] BUCKET_COLUMNS = {
        "b_50", "b_100", "b_250", "b_500", "b_1000", "b_2500", "b_5000", "b_inf"
    };

    /**
     * Folds one attempt into the minute bucket it belongs to.
     *
     * <p>Runs inside the caller's transaction on purpose — see the class comment.
     */
    public void recordAttempt(
            UUID tenantId, Instant startedAt, AttemptOutcome outcome, int durationMs) {
        Instant bucket = startedAt.truncatedTo(ChronoUnit.MINUTES);
        String bucketColumn = BUCKET_COLUMNS[bucketIndex(durationMs)];

        String sql =
                ("""
                 INSERT INTO delivery_minute_stats (
                     tenant_id, bucket_start, attempts, successes, retryable_failures,
                     terminal_failures, duration_ms_sum, duration_ms_max, %s)
                 VALUES (
                     :tenantId, CAST(:bucket AS timestamptz), 1, :success, :retryable,
                     :terminal, :duration, :duration, 1)
                 ON CONFLICT (tenant_id, bucket_start) DO UPDATE SET
                     attempts           = delivery_minute_stats.attempts + 1,
                     successes          = delivery_minute_stats.successes + :success,
                     retryable_failures = delivery_minute_stats.retryable_failures + :retryable,
                     terminal_failures  = delivery_minute_stats.terminal_failures + :terminal,
                     duration_ms_sum    = delivery_minute_stats.duration_ms_sum + :duration,
                     duration_ms_max    = GREATEST(delivery_minute_stats.duration_ms_max, :duration),
                     %s                 = delivery_minute_stats.%s + 1
                 """)
                        .formatted(bucketColumn, bucketColumn, bucketColumn);

        jdbc.update(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("bucket", Timestamp.from(bucket))
                        .addValue("success", outcome == AttemptOutcome.SUCCESS ? 1 : 0)
                        .addValue("retryable", outcome == AttemptOutcome.RETRYABLE_FAILURE ? 1 : 0)
                        .addValue("terminal", outcome == AttemptOutcome.TERMINAL_FAILURE ? 1 : 0)
                        .addValue("duration", durationMs));
    }

    private static int bucketIndex(int durationMs) {
        for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
            if (durationMs <= BUCKET_BOUNDS[i]) {
                return i;
            }
        }
        return BUCKET_COLUMNS.length - 1;
    }

    /** One time bucket of the delivery timeseries. */
    public record TimeBucket(
            Instant start,
            long attempts,
            long successes,
            long retryableFailures,
            long terminalFailures,
            double avgDurationMs,
            int maxDurationMs) {}

    /**
     * Downsamples the per-minute rollup into buckets of {@code bucketMinutes}.
     *
     * <p>The chart asks for a fixed number of points regardless of window length,
     * so the server picks the bucket width and aggregates. Sending 1 440
     * one-minute points for a 24-hour chart would be wasted bytes and wasted
     * pixels.
     */
    public List<TimeBucket> timeseries(
            UUID tenantId, Instant from, Instant to, int bucketMinutes) {
        String sql =
                """
                SELECT to_timestamp(
                           floor(EXTRACT(EPOCH FROM bucket_start) / (:bucketSeconds))
                           * :bucketSeconds) AS bucket,
                       SUM(attempts)           AS attempts,
                       SUM(successes)          AS successes,
                       SUM(retryable_failures) AS retryable_failures,
                       SUM(terminal_failures)  AS terminal_failures,
                       SUM(duration_ms_sum)    AS duration_sum,
                       MAX(duration_ms_max)    AS duration_max
                  FROM delivery_minute_stats
                 WHERE tenant_id = :tenantId
                   AND bucket_start >= CAST(:from AS timestamptz)
                   AND bucket_start <  CAST(:to AS timestamptz)
                 GROUP BY bucket
                 ORDER BY bucket
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("from", Timestamp.from(from))
                        .addValue("to", Timestamp.from(to))
                        .addValue("bucketSeconds", bucketMinutes * 60),
                (rs, i) -> {
                    long attempts = rs.getLong("attempts");
                    long sum = rs.getLong("duration_sum");
                    return new TimeBucket(
                            rs.getTimestamp("bucket").toInstant(),
                            attempts,
                            rs.getLong("successes"),
                            rs.getLong("retryable_failures"),
                            rs.getLong("terminal_failures"),
                            attempts == 0 ? 0d : (double) sum / attempts,
                            rs.getInt("duration_max"));
                });
    }

    /** Aggregated totals plus approximate latency percentiles for a window. */
    public record WindowSummary(
            long attempts,
            long successes,
            long retryableFailures,
            long terminalFailures,
            double avgDurationMs,
            int maxDurationMs,
            double p50Ms,
            double p95Ms,
            double p99Ms) {

        public double successRate() {
            return attempts == 0 ? 0d : (double) successes / attempts;
        }
    }

    public WindowSummary windowSummary(UUID tenantId, Instant from, Instant to) {
        String sql =
                """
                SELECT COALESCE(SUM(attempts), 0)           AS attempts,
                       COALESCE(SUM(successes), 0)          AS successes,
                       COALESCE(SUM(retryable_failures), 0) AS retryable_failures,
                       COALESCE(SUM(terminal_failures), 0)  AS terminal_failures,
                       COALESCE(SUM(duration_ms_sum), 0)    AS duration_sum,
                       COALESCE(MAX(duration_ms_max), 0)    AS duration_max,
                       COALESCE(SUM(b_50), 0)    AS b_50,
                       COALESCE(SUM(b_100), 0)   AS b_100,
                       COALESCE(SUM(b_250), 0)   AS b_250,
                       COALESCE(SUM(b_500), 0)   AS b_500,
                       COALESCE(SUM(b_1000), 0)  AS b_1000,
                       COALESCE(SUM(b_2500), 0)  AS b_2500,
                       COALESCE(SUM(b_5000), 0)  AS b_5000,
                       COALESCE(SUM(b_inf), 0)   AS b_inf
                  FROM delivery_minute_stats
                 WHERE tenant_id = :tenantId
                   AND bucket_start >= CAST(:from AS timestamptz)
                   AND bucket_start <  CAST(:to AS timestamptz)
                """;
        Map<String, Object> row =
                jdbc.queryForMap(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("tenantId", tenantId)
                                .addValue("from", Timestamp.from(from))
                                .addValue("to", Timestamp.from(to)));

        long attempts = ((Number) row.get("attempts")).longValue();
        long sum = ((Number) row.get("duration_sum")).longValue();
        int max = ((Number) row.get("duration_max")).intValue();

        long[] counts = new long[BUCKET_COLUMNS.length];
        for (int i = 0; i < BUCKET_COLUMNS.length; i++) {
            counts[i] = ((Number) row.get(BUCKET_COLUMNS[i])).longValue();
        }

        return new WindowSummary(
                attempts,
                ((Number) row.get("successes")).longValue(),
                ((Number) row.get("retryable_failures")).longValue(),
                ((Number) row.get("terminal_failures")).longValue(),
                attempts == 0 ? 0d : (double) sum / attempts,
                max,
                percentile(counts, max, 0.50),
                percentile(counts, max, 0.95),
                percentile(counts, max, 0.99));
    }

    /**
     * Estimates a percentile from the histogram by linear interpolation inside
     * the bucket the target rank falls into.
     *
     * <p>The open-ended top bucket has no upper bound, so when the target lands
     * there the observed maximum is used instead of extrapolating — an estimate
     * that is at least bounded by something real.
     */
    static double percentile(long[] bucketCounts, int observedMax, double quantile) {
        long total = 0;
        for (long c : bucketCounts) {
            total += c;
        }
        if (total == 0) {
            return 0d;
        }
        double target = quantile * total;
        long cumulative = 0;
        double lowerBound = 0d;
        for (int i = 0; i < bucketCounts.length; i++) {
            long count = bucketCounts[i];
            double upperBound =
                    i < BUCKET_BOUNDS.length ? BUCKET_BOUNDS[i] : Math.max(observedMax, 5000);
            if (cumulative + count >= target && count > 0) {
                double withinBucket = (target - cumulative) / count;
                return lowerBound + withinBucket * (upperBound - lowerBound);
            }
            cumulative += count;
            lowerBound = upperBound;
        }
        return Math.max(observedMax, lowerBound);
    }
}
