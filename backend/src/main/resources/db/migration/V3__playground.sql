-- =============================================================================
-- V3 — Reliability Playground.
--
-- A "run" is one supervised experiment: a scenario, the parameters it was given,
-- the endpoint that was pointed at the simulator, the event that was published
-- and the delivery that resulted. The frontend polls a run to narrate what the
-- backend is actually doing — nothing in the timeline is synthesised client-side.
-- =============================================================================

CREATE TABLE playground_runs (
    id            UUID         PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    scenario_id   VARCHAR(60)  NOT NULL,
    scenario_name VARCHAR(120) NOT NULL,
    config        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Opaque handle the simulator uses to keep per-run fault-injection state.
    simulator_token VARCHAR(80) NOT NULL,
    endpoint_id   UUID         REFERENCES webhook_endpoints (id) ON DELETE SET NULL,
    event_id      UUID         REFERENCES events (id) ON DELETE SET NULL,
    delivery_id   UUID         REFERENCES deliveries (id) ON DELETE SET NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    outcome_note  VARCHAR(500),
    created_by    UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    CONSTRAINT playground_runs_status_valid
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'DEAD_LETTERED', 'CANCELLED')),
    CONSTRAINT playground_runs_token_key UNIQUE (simulator_token)
);

CREATE INDEX playground_runs_tenant_idx   ON playground_runs (tenant_id, created_at DESC);
CREATE INDEX playground_runs_delivery_idx ON playground_runs (delivery_id);
CREATE INDEX playground_runs_active_idx
    ON playground_runs (created_at)
    WHERE status = 'RUNNING';
