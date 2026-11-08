-- =============================================================================
-- V2 — The delivery domain: endpoints, events, deliveries, attempts, outbox.
-- =============================================================================

-- ----------------------------------------------------------------------------
-- webhook_endpoints
--
--  * `encrypted_secret` holds the signing secret sealed with AES-256-GCM. The
--    plaintext never touches the database or the logs.
--  * `previous_encrypted_secret` supports zero-downtime secret rotation: for a
--    grace window both secrets are considered valid by the receiver, so the
--    receiving application can roll its configuration without dropping events.
--  * Retry configuration is stored per endpoint so that a slow partner can be
--    given a gentler schedule than a fast one. NULL means "use the system
--    default", which keeps the row small and the default changeable centrally.
-- ----------------------------------------------------------------------------
CREATE TABLE webhook_endpoints (
    id                        UUID         PRIMARY KEY,
    tenant_id                 UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name                      VARCHAR(120) NOT NULL,
    url                       VARCHAR(2048) NOT NULL,
    description               VARCHAR(500),
    subscribed_event_types    TEXT[]       NOT NULL DEFAULT '{}',
    encrypted_secret          TEXT         NOT NULL,
    previous_encrypted_secret TEXT,
    secret_rotated_at         TIMESTAMPTZ,
    retry_max_attempts        INTEGER,
    retry_initial_delay_ms    BIGINT,
    retry_max_delay_ms        BIGINT,
    retry_timeout_ms          INTEGER,
    retry_jitter_factor       DOUBLE PRECISION,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    paused_at                 TIMESTAMPTZ,
    rate_limit_per_second     INTEGER,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at                TIMESTAMPTZ,
    CONSTRAINT webhook_endpoints_url_scheme CHECK (url ~* '^https?://'),
    CONSTRAINT webhook_endpoints_retry_attempts_sane
        CHECK (retry_max_attempts IS NULL OR (retry_max_attempts BETWEEN 1 AND 25)),
    CONSTRAINT webhook_endpoints_retry_delay_sane
        CHECK (retry_initial_delay_ms IS NULL OR retry_initial_delay_ms BETWEEN 100 AND 3600000),
    CONSTRAINT webhook_endpoints_jitter_sane
        CHECK (retry_jitter_factor IS NULL OR retry_jitter_factor BETWEEN 0 AND 1)
);

-- Endpoint list screens are always tenant-scoped and ordered by recency.
CREATE INDEX webhook_endpoints_tenant_idx
    ON webhook_endpoints (tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Subscription matching on ingestion: "which active endpoints of this tenant
-- subscribe to this event type?". A GIN index over the array makes the
-- containment test (`subscribed_event_types && ARRAY[...]`) index-assisted.
CREATE INDEX webhook_endpoints_event_types_gin
    ON webhook_endpoints USING GIN (subscribed_event_types);

-- ----------------------------------------------------------------------------
-- events — the immutable record of something a tenant published.
-- ----------------------------------------------------------------------------
CREATE TABLE events (
    id              UUID         PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_type      VARCHAR(120) NOT NULL,
    payload         JSONB        NOT NULL,
    idempotency_key VARCHAR(255),
    source          VARCHAR(40)  NOT NULL DEFAULT 'API',
    api_key_id      UUID         REFERENCES api_keys (id) ON DELETE SET NULL,
    payload_bytes   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT events_type_not_blank CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT events_source_valid   CHECK (source IN ('API', 'PLAYGROUND', 'ENDPOINT_TEST', 'SEED'))
);

-- Idempotency is scoped to the tenant: two tenants may legitimately reuse the
-- same key. A partial unique index lets most events omit the key entirely
-- without burning index space on NULLs.
CREATE UNIQUE INDEX events_tenant_idempotency_key
    ON events (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Event history screen: tenant-scoped, newest first, optionally typed.
CREATE INDEX events_tenant_created_idx ON events (tenant_id, created_at DESC);
CREATE INDEX events_tenant_type_idx    ON events (tenant_id, event_type, created_at DESC);

-- ----------------------------------------------------------------------------
-- deliveries — one row per (event, endpoint) pair. This is the unit of work
-- the delivery workers claim, and the authoritative delivery state.
--
-- Concurrency contract:
--  * `lease_owner` / `lease_until` form an optimistic lease. A worker may only
--    move a row into IN_PROGRESS with a conditional UPDATE, so two workers can
--    never hold the same delivery at once.
--  * `queued_at` records when the delivery was last handed to Kafka. It lets
--    the dispatcher re-enqueue work whose Kafka message was lost without
--    double-enqueueing work that is simply still in flight.
-- ----------------------------------------------------------------------------
CREATE TABLE deliveries (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    event_id           UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    endpoint_id        UUID        NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    status             VARCHAR(20) NOT NULL,
    attempt_count      INTEGER     NOT NULL DEFAULT 0,
    replay_count       INTEGER     NOT NULL DEFAULT 0,
    max_attempts       INTEGER     NOT NULL,
    next_attempt_at    TIMESTAMPTZ,
    queued_at          TIMESTAMPTZ,
    lease_until        TIMESTAMPTZ,
    lease_owner        VARCHAR(80),
    last_response_code INTEGER,
    last_error         VARCHAR(500),
    last_error_class   VARCHAR(40),
    first_attempt_at   TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT deliveries_status_valid CHECK (
        status IN ('PENDING', 'IN_PROGRESS', 'RETRY_SCHEDULED', 'SUCCESS', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT deliveries_event_endpoint_unique UNIQUE (event_id, endpoint_id),
    CONSTRAINT deliveries_attempt_count_sane CHECK (attempt_count >= 0),
    -- A terminal row must not keep a lease or a future attempt pencilled in.
    CONSTRAINT deliveries_terminal_has_no_schedule CHECK (
        status NOT IN ('SUCCESS', 'CANCELLED') OR next_attempt_at IS NULL
    )
);

-- The hot path for the dispatcher: due work, oldest first. Partial index keeps
-- it tiny — terminal rows (the overwhelming majority over time) are excluded.
CREATE INDEX deliveries_due_idx
    ON deliveries (next_attempt_at NULLS FIRST, queued_at NULLS FIRST)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- Lease reconciliation: find IN_PROGRESS rows whose owner went away.
CREATE INDEX deliveries_expired_lease_idx
    ON deliveries (lease_until)
    WHERE status = 'IN_PROGRESS';

-- Dashboard / list screens.
CREATE INDEX deliveries_tenant_status_idx   ON deliveries (tenant_id, status, created_at DESC);
CREATE INDEX deliveries_tenant_created_idx  ON deliveries (tenant_id, created_at DESC);
CREATE INDEX deliveries_endpoint_idx        ON deliveries (endpoint_id, created_at DESC);
CREATE INDEX deliveries_event_idx           ON deliveries (event_id);

-- Dead-letter queue screen: exhausted deliveries only.
CREATE INDEX deliveries_dead_letter_idx
    ON deliveries (tenant_id, updated_at DESC)
    WHERE status = 'FAILED';

-- ----------------------------------------------------------------------------
-- delivery_attempts — append-only forensic record of every HTTP attempt.
--
-- Response bodies are truncated by the application to a bounded excerpt. We
-- never store an unbounded third-party response: it is unnecessary for
-- debugging and it turns the database into an uncontrolled sink for whatever
-- the receiver chose to return.
-- ----------------------------------------------------------------------------
CREATE TABLE delivery_attempts (
    id                     UUID        PRIMARY KEY,
    delivery_id            UUID        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    tenant_id              UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    attempt_number         INTEGER     NOT NULL,
    kind                   VARCHAR(20) NOT NULL DEFAULT 'AUTOMATIC',
    outcome                VARCHAR(20) NOT NULL,
    response_status        INTEGER,
    response_body_excerpt  VARCHAR(2048),
    response_headers_excerpt VARCHAR(2048),
    error_message          VARCHAR(500),
    error_class            VARCHAR(40),
    retry_after_seconds    INTEGER,
    request_bytes          INTEGER,
    duration_ms            INTEGER     NOT NULL DEFAULT 0,
    worker_id              VARCHAR(80),
    trace_id               VARCHAR(64),
    started_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT delivery_attempts_unique UNIQUE (delivery_id, attempt_number),
    CONSTRAINT delivery_attempts_kind_valid
        CHECK (kind IN ('AUTOMATIC', 'RETRY', 'REPLAY', 'MANUAL_TEST')),
    CONSTRAINT delivery_attempts_outcome_valid
        CHECK (outcome IN ('SUCCESS', 'RETRYABLE_FAILURE', 'TERMINAL_FAILURE'))
);
CREATE INDEX delivery_attempts_delivery_idx ON delivery_attempts (delivery_id, attempt_number);
CREATE INDEX delivery_attempts_tenant_idx   ON delivery_attempts (tenant_id, started_at DESC);

-- ----------------------------------------------------------------------------
-- outbox_events — the transactional outbox.
--
-- Written in the SAME transaction as the aggregate it describes, then relayed
-- to Kafka by a separate publisher. Status lifecycle:
--     PENDING -> CLAIMED -> PUBLISHED
--                   `-> (publisher crash) -> re-claimed after claim_expires_at
--
-- The CLAIMED state is what makes crash recovery safe: a publisher that dies
-- between "published to Kafka" and "marked PUBLISHED" leaves a stale claim,
-- which another publisher picks up and republishes. Consumers must therefore
-- be idempotent — they are, via the conditional delivery claim.
-- ----------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id                UUID        PRIMARY KEY,
    aggregate_type    VARCHAR(40) NOT NULL,
    aggregate_id      UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    event_type        VARCHAR(80) NOT NULL,
    topic             VARCHAR(120) NOT NULL,
    partition_key     VARCHAR(120) NOT NULL,
    payload           JSONB       NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER     NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    claimed_by        VARCHAR(80),
    claimed_at        TIMESTAMPTZ,
    claim_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    trace_parent      VARCHAR(64),
    CONSTRAINT outbox_events_status_valid
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD'))
);

-- The publisher's only query: unpublished rows in insertion order. Partial
-- index so the index shrinks back to near-zero once the backlog is drained.
CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events (created_at)
    WHERE status IN ('PENDING', 'CLAIMED');

CREATE INDEX outbox_events_aggregate_idx ON outbox_events (aggregate_type, aggregate_id);

-- ----------------------------------------------------------------------------
-- replay_records — audit trail for manual replays.
--
-- Replay never rewrites history: the previous attempts stay in
-- delivery_attempts and the delivery's prior terminal state is captured here.
-- ----------------------------------------------------------------------------
CREATE TABLE replay_records (
    id               UUID        PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    delivery_id      UUID        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    requested_by     UUID        REFERENCES users (id) ON DELETE SET NULL,
    requested_via    VARCHAR(20) NOT NULL DEFAULT 'API',
    reason           VARCHAR(500),
    previous_status  VARCHAR(20) NOT NULL,
    attempts_before  INTEGER     NOT NULL,
    batch_id         UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX replay_records_delivery_idx ON replay_records (delivery_id, created_at DESC);
CREATE INDEX replay_records_tenant_idx   ON replay_records (tenant_id, created_at DESC);
CREATE INDEX replay_records_batch_idx    ON replay_records (batch_id) WHERE batch_id IS NOT NULL;
