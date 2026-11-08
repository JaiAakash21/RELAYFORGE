-- =============================================================================
-- V4 — Per-minute delivery rollup.
--
-- Why a rollup at all: the overview and observability screens ask questions
-- like "success rate and p95 latency over the last 24 hours". Answering that
-- from `delivery_attempts` means scanning every attempt row in the window,
-- which gets slower exactly as the system gets more interesting.
--
-- The rollup is maintained incrementally by the worker that records an attempt
-- (one UPSERT per attempt, same transaction), so the aggregate can never drift
-- from the attempt it describes. Latency percentiles are approximated from
-- fixed buckets rather than stored exactly — see ENGINEERING_DECISIONS.md for
-- why an approximate percentile is the right trade here.
-- =============================================================================

CREATE TABLE delivery_minute_stats (
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    bucket_start       TIMESTAMPTZ NOT NULL,
    attempts           BIGINT      NOT NULL DEFAULT 0,
    successes          BIGINT      NOT NULL DEFAULT 0,
    retryable_failures BIGINT      NOT NULL DEFAULT 0,
    terminal_failures  BIGINT      NOT NULL DEFAULT 0,
    duration_ms_sum    BIGINT      NOT NULL DEFAULT 0,
    duration_ms_max    INTEGER     NOT NULL DEFAULT 0,
    -- Latency histogram buckets (upper bound, milliseconds), cumulative-free
    -- counts: <=50, <=100, <=250, <=500, <=1000, <=2500, <=5000, >5000
    b_50   BIGINT NOT NULL DEFAULT 0,
    b_100  BIGINT NOT NULL DEFAULT 0,
    b_250  BIGINT NOT NULL DEFAULT 0,
    b_500  BIGINT NOT NULL DEFAULT 0,
    b_1000 BIGINT NOT NULL DEFAULT 0,
    b_2500 BIGINT NOT NULL DEFAULT 0,
    b_5000 BIGINT NOT NULL DEFAULT 0,
    b_inf  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, bucket_start)
);

CREATE INDEX delivery_minute_stats_bucket_idx ON delivery_minute_stats (bucket_start DESC);
