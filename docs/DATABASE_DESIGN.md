# Database design

PostgreSQL 16. Schema managed by Flyway; `hibernate.ddl-auto=validate` in every
environment including tests, so the migrations are always the thing being
exercised.

## Principles

**Every tenant-owned table carries `tenant_id` directly.** Denormalised on
purpose: every query can filter on a leading index column without a join, and
a missing tenant predicate becomes obvious in code review.

**Append-only where the data is evidence.** `events` and `delivery_attempts`
are never updated or deleted. When a user asks "what actually happened to my
webhook?", the answer is a table, not a reconstruction.

**Partial indexes on the hot paths.** The dispatcher's index covers only
non-terminal deliveries, so it shrinks back to near-zero as work completes
rather than growing with history.

## Tables

| Table | Purpose | Notable |
|---|---|---|
| `tenants` | Isolation boundary | |
| `users` | Human identities | Email lower-cased by a check constraint, not by convention |
| `tenant_memberships` | User ↔ tenant with a role | |
| `api_keys` | Programmatic credentials | SHA-256 hash + non-secret `prefix` for O(1) lookup |
| `refresh_tokens` | Rotating sessions | `family_id` + `replaced_by` make reuse detectable |
| `webhook_endpoints` | Destinations | AES-GCM sealed secret; `previous_encrypted_secret` for rotation |
| `events` | Immutable published events | `jsonb` payload; partial unique index on `(tenant_id, idempotency_key)` |
| `deliveries` | One per (event, endpoint) | The unit of work. Lease, schedule and attempt budget |
| `delivery_attempts` | Append-only attempt record | Bounded excerpts, never unbounded third-party content |
| `outbox_events` | Transactional outbox | `PENDING → CLAIMED → PUBLISHED`, with claim expiry |
| `replay_records` | Replay audit trail | `batch_id` groups a bulk replay |
| `playground_runs` | Reliability Playground experiments | |
| `delivery_minute_stats` | Per-minute rollup | Maintained by the attempt's own transaction |

## The delivery row

The concurrency-critical table. Three columns do the work:

| Column | Role |
|---|---|
| `lease_owner` / `lease_until` | Mutual exclusion. A worker takes the row with a conditional `UPDATE`; an expired lease is positive evidence the holder died. |
| `next_attempt_at` | The retry schedule, queryable and displayable — which is why retries live here rather than in Kafka delay topics. |
| `queued_at` | When the delivery was last handed to Kafka. Lets the dispatcher re-enqueue work whose message was lost without double-enqueueing work still in flight. |

`max_attempts` is **snapshotted at creation** rather than read live from the
endpoint. Changing an endpoint's retry policy must not retroactively
dead-letter — or resurrect — deliveries already in flight. A delivery is judged
against the rules that applied when it was created.

## Indexes

Each one exists for a named query:

```sql
-- The dispatcher's hot path. Partial: terminal rows are the overwhelming
-- majority over time and are excluded entirely.
CREATE INDEX deliveries_due_idx
    ON deliveries (next_attempt_at NULLS FIRST, queued_at NULLS FIRST)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- Lease reconciliation: find rows whose owner went away.
CREATE INDEX deliveries_expired_lease_idx
    ON deliveries (lease_until) WHERE status = 'IN_PROGRESS';

-- The dead-letter queue, as fast as a dedicated table would be.
CREATE INDEX deliveries_dead_letter_idx
    ON deliveries (tenant_id, updated_at DESC) WHERE status = 'FAILED';

-- The outbox publisher's only query. Shrinks to near-zero once drained.
CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events (created_at) WHERE status IN ('PENDING', 'CLAIMED');

-- Idempotency, scoped to the tenant. Partial, so events without a key cost
-- no index space.
CREATE UNIQUE INDEX events_tenant_idempotency_key
    ON events (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
```

## Constraints that encode rules

Not decoration — each one makes an invalid state unrepresentable:

```sql
-- A terminal delivery must not keep a future attempt pencilled in.
CONSTRAINT deliveries_terminal_has_no_schedule CHECK (
    status NOT IN ('SUCCESS', 'CANCELLED') OR next_attempt_at IS NULL
)

-- One delivery per (event, endpoint). Fan-out cannot duplicate.
CONSTRAINT deliveries_event_endpoint_unique UNIQUE (event_id, endpoint_id)

-- Attempt numbering is dense and unique within a delivery.
CONSTRAINT delivery_attempts_unique UNIQUE (delivery_id, attempt_number)
```

## The rollup

`delivery_minute_stats` holds counts and a fixed latency histogram per
`(tenant_id, minute)`. Maintained by a single UPSERT **in the same transaction
that records the attempt**, so the aggregate can never describe an attempt that
was rolled back and no background job can fall behind.

Percentiles are interpolated from the buckets and are therefore approximate.
That is the trade: exact percentiles need every raw observation in the window,
which is the scan the rollup exists to avoid — and the query would get slower
exactly as the platform got busier. The API flags this as
`approximateLatency: true` and the UI says so wherever a percentile appears.

## Migrations

| | |
|---|---|
| `V1__core_identity_and_tenancy.sql` | Tenants, users, memberships, API keys, refresh tokens |
| `V2__endpoints_events_deliveries.sql` | The delivery domain and the outbox |
| `V3__playground.sql` | Reliability Playground runs |
| `V4__delivery_metrics_rollup.sql` | Per-minute rollup |

`ddl-auto=validate` caught two real defects during development: `CHAR(64)` for
credential hashes (which pads with spaces, so a stored hash could differ from a
computed one by trailing whitespace) and an array-type mismatch. Both would
have been silent data bugs.
