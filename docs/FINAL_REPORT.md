# RelayForge — final delivery report

## 1. Summary

RelayForge is a multi-tenant webhook delivery platform: customers register
endpoint URLs, publish events, and RelayForge signs and delivers them —
retrying failures with exponential backoff, dead-lettering what it cannot
deliver, and keeping every attempt as evidence.

The design rests on one sentence: **PostgreSQL is the source of truth for what
must be delivered; Kafka distributes the work of delivering it, and never
decides whether a delivery still needs doing.**

Built, tested and verified end to end. **213 automated tests pass from a clean
build.** Thirteen real defects were found and fixed by running the system
rather than reading it.

## 2. Implemented features

**Pipeline** — transactional outbox with `FOR UPDATE SKIP LOCKED` claiming and
broker-acknowledged publishing · database-scheduled retries · exponential
backoff with symmetric jitter · `Retry-After` precedence · worker leases with
reconciliation · dead-letter queue · replay that appends rather than resets ·
tenant-scoped idempotency.

**Security** — HMAC-SHA256 with the timestamp inside the MAC · constant-time
verification · dual signatures during rotation · AES-256-GCM secrets at rest ·
SSRF policy with DNS pinning · BCrypt passwords, SHA-256 machine credentials ·
httpOnly cookies · refresh rotation with reuse detection · Redis token-bucket
rate limiting.

**Product** — 11 dashboard screens · the Reliability Playground with seven
fault-injection scenarios · OpenAPI documentation · Prometheus metrics ·
19-panel Grafana dashboard · OpenTelemetry tracing.

## 3. Architecture

```
Client ──POST──▶ API
                  │
         ┌────────┴────────┐
         │ ONE TRANSACTION │   events → deliveries → outbox
         └────────┬────────┘
                  │ commit → 202 Accepted
   Outbox publisher ──▶ Kafka ──▶ Worker ──▶ Receiver
   (SKIP LOCKED,        (part. =   (lease,      │
    awaits ack)         endpoint)   sign)   ┌───┴───┐
                                          2xx   429/5xx/timeout
                            Dispatcher ◀───┘  RETRY_SCHEDULED
                            (next_attempt_at)      │ exhausted
                                                 FAILED ──replay──▶ PENDING
```

One artefact, three runtime roles (`api`, `worker`, `all`) chosen by
configuration — so the API scales on request volume and workers on delivery
volume, without a second codebase. Full diagrams in
[ARCHITECTURE.md](ARCHITECTURE.md).

## 4. Technologies

Java 21 · Spring Boot 3.5.16 · Spring Security · Spring Data JPA · Spring Kafka
· Flyway · PostgreSQL 16 · Apache Kafka (KRaft) · Redis 7 · Apache HttpClient 5
Next.js 15 · React 19 · TypeScript · Tailwind CSS · Recharts
Micrometer · Prometheus · Grafana · OpenTelemetry · Tempo
JUnit 5 · Testcontainers · WireMock · Playwright · k6 · Docker · GitHub Actions

## 5. Repository

```
backend/              133 files — API + workers (one artefact, three roles)
webhook-simulator/      9 files — controlled fault injection
frontend/              67 files — Next.js dashboard
infra/                 12 files — Dockerfiles, Prometheus, Grafana, Tempo, scripts
docs/                  12 files — architecture, decisions, security, interview prep
tests/                 10 files — Playwright E2E + k6 load scripts
.github/workflows/      4 files — backend, frontend, e2e, security
```
262 tracked files, 9 commits.

## 6. Database

13 tables across 4 Flyway migrations. `hibernate.ddl-auto=validate` everywhere
including tests, so the migrations are always what is exercised — this caught
two silent data bugs during development.

The delivery row carries three columns that do the concurrency work:
`lease_owner`/`lease_until` (mutual exclusion), `next_attempt_at` (the
queryable retry schedule), `queued_at` (lost-message recovery). `max_attempts`
is snapshotted at creation so changing an endpoint's policy cannot
retroactively dead-letter work already in flight.

Partial indexes on every hot path. Details in
[DATABASE_DESIGN.md](DATABASE_DESIGN.md).

## 7. Key engineering decisions

Seventeen, each with its cost, in
[ENGINEERING_DECISIONS.md](ENGINEERING_DECISIONS.md). The load-bearing ones:

| Decision | Why | Cost |
|---|---|---|
| Transactional outbox over dual writes | Both orderings of a dual write lose events or create orphans | ≤1s relay latency; duplicate messages |
| At-least-once + idempotent consumption | Exactly-once across an external HTTP call is not achievable | Receivers must deduplicate |
| Retries scheduled in PostgreSQL, not Kafka | Delay topics quantise the schedule and break jitter; sleeping blocks a partition | A 1/s polling query |
| Symmetric jitter | Keeps the expected delay equal to the intended schedule with a small budget | Spreads slightly less than full jitter |
| `FAILED` *is* the dead-letter state | Two representations of one delivery can disagree | None identified |
| SHA-256 for API keys, BCrypt for passwords | Different threats; a slow KDF on every ingestion caps throughput | None |
| DNS pinning, not just validation | Closes the rebinding window rather than narrowing it | Fails closed by design |
| Rate limiting fails open | A Redis outage must not become an ingestion outage | Throttling lapses; counted and alerted |

## 8. Security

Full threat model in [SECURITY.md](SECURITY.md). Verified: 48 SSRF tests, 11
tenant-isolation tests, immediate API-key revocation, signature verification
against the published algorithm, and a CI secret scan proven to catch a
real-shaped key while ignoring test fixtures.

Stated honestly: application-level SSRF protection is defence in depth, not a
boundary — production needs network-level egress filtering.

## 9. Test results

```
mvn clean verify
  Unit:        180 passed, 0 failed
  Integration:  33 passed, 0 failed   (Testcontainers: PostgreSQL, Redis, Kafka)

npx playwright test
  End-to-end:   21 passed, 1 skipped  (desktop + mobile)

Total: 213 passing
```

Behaviour observed against the running stack:

| Observation | Measured |
|---|---|
| Retry gaps (jitter off) | +5.0s then +7.0s — doubling visible |
| Playground run (jitter on) | +6.7s then +11.0s |
| Read timeout | 5007ms against a 5s budget, **no HTTP status** |
| 32 concurrent claims | exactly 1 winner |
| 16 concurrent replays | exactly 1 replay |
| Replay | attempt #4 appended, 3 failures intact |
| Tenant isolation | 404 on all cross-tenant reads |
| SSRF | 6/6 malicious URLs refused |
| Auth rate limit | 20×401 then 5×429 |
| Horizontal overflow | 0px — 12 pages × 3 viewports |

## 10. Load test results

**None measured.** The k6 scripts (`tests/k6/`) are written and runnable, but
no figures were produced on hardware worth quoting, and the load generator
would have shared a host with the stack. A throughput number without its
environment is not a benchmark, so none is published.

## 11. Build status

| | |
|---|---|
| `mvn clean verify` | **PASS** |
| `next build` | **PASS** — lint and type errors fail the build |
| `npx playwright test` | **PASS** |
| `docker compose config` | **PASS** |
| Docker image build | **BLOCKED** — see §17 |

## 12. Docker

Full stack: PostgreSQL, Redis, Kafka (KRaft), API, workers, simulator,
frontend, Prometheus, Grafana, Tempo. Multi-stage non-root Dockerfiles using
Spring Boot layertools and Next.js standalone output. Health checks probe
*readiness*, not just liveness. Required-variable guards fail with an
actionable message.

## 13. Running it

```bash
./infra/scripts/bootstrap.sh    # generates real secrets into a git-ignored .env
docker compose up --build
```

Dashboard <http://localhost:3000> · API docs
<http://localhost:8080/swagger-ui.html> · Grafana <http://localhost:3001>

```bash
./infra/scripts/demo.sh         # full lifecycle: fail → dead-letter → fix → replay
```

Native (no Docker for the apps) is documented in
[DEPLOYMENT.md](DEPLOYMENT.md) and is how this project was verified.

## 14. API documentation

Interactive at `/swagger-ui.html`; machine-readable at `/v3/api-docs`. Written
reference with the signature-verification algorithm in
[API_DOCUMENTATION.md](API_DOCUMENTATION.md).

## 15. Demo credentials

**None are shipped.** An account with a known password is a backdoor, and
`bootstrap.sh` generates fresh secrets per installation.

To create one: register through the dashboard, or run `./infra/scripts/demo.sh`
(which prints the account it creates), or set
`RELAYFORGE_DEMO_SEED_ENABLED=true` and read the key from the API logs.

## 16. Screenshots

Sixteen in [docs/screenshots/](screenshots/), captured with Playwright against
the running stack. `06-playground-run-complete.jpg` is the one to look at.

## 17. Limitations

**Environment:**
- **Docker images were never built.** The sandbox's TLS-intercepting proxy
  blocks Maven Central from inside build containers. The Dockerfiles are
  written and `docker compose config` validates; every service was verified
  running natively instead.
- **Tempo tracing is configured but not exercised** in this environment.
- **No measured load-test figures** (§10).

**Design, stated rather than discovered:**
- At-least-once, not exactly-once. Receivers must deduplicate on
  `X-RelayForge-Event-Id`.
- Access tokens cannot be revoked before expiry (15-minute window). Refresh
  tokens and API keys are immediately revocable.
- Product-dashboard percentiles are interpolated from histogram buckets, and
  labelled approximate everywhere they appear.
- Application-level SSRF protection is defence in depth, not a boundary.
- Offset pagination drifts as rows are inserted.
- Settings is read-mostly; writes are absent rather than stubbed.
- One encryption key for all tenants.

## 18. Suggested production improvements

In priority order:

1. **Network-level egress filtering** — the single most important addition.
2. A secret manager instead of environment variables, with AES key rotation.
3. Time-partition `delivery_attempts`, the fastest-growing table.
4. An endpoint-level circuit breaker, so a dead endpoint stops consuming
   capacity one delivery at a time.
5. Cursor pagination.
6. Per-tenant encryption keys.
7. Multi-broker Kafka (RF ≥ 3) and a soak test.
8. Audit logging of administrative actions to an append-only store.

## 19. Interview preparation

[INTERVIEW_PREPARATION.md](INTERVIEW_PREPARATION.md) — 24 questions with
defensible answers, including why exactly-once is not achievable here, why
SHA-256 and BCrypt are both correct in different places, and where the
bottlenecks are.

## 20. Resume description

**Short:**

> **RelayForge — Distributed Webhook Delivery & Reliability Platform**
> *Java · Spring Boot · Apache Kafka · PostgreSQL · Redis · Next.js · TypeScript · Docker · OpenTelemetry*
>
> Multi-tenant event delivery infrastructure built around the transactional
> outbox pattern, with Kafka-backed delivery workers, exponential backoff with
> jitter, worker-lease crash recovery, and dead-letter handling with replay.
> 213 automated tests including concurrency tests proving exactly-one claim
> under 32 racing workers.

**Bullets:**

- Designed a multi-tenant webhook delivery platform where PostgreSQL is the
  source of truth and Kafka distributes work, so a lost message, a dead worker
  or a duplicate cannot lose or double-count an event
- Implemented the transactional outbox pattern, making event acceptance and
  relay-queueing a single atomic commit
- Built delivery workers using optimistic leases and conditional updates,
  converting at-least-once messaging into effectively-once processing;
  demonstrated with 32 concurrent threads against real PostgreSQL
- Engineered retry scheduling with exponential backoff and symmetric jitter,
  honouring receiver `Retry-After` headers
- Built dead-letter handling and replay that appends attempts rather than
  resetting them, preserving the evidence of why a delivery failed
- Implemented HMAC-SHA256 request signing with timestamp binding, constant-time
  verification and zero-downtime secret rotation
- Closed the DNS-rebinding SSRF window by pinning the HTTP client's resolver to
  pre-validated addresses
- Added a Reliability Playground injecting real faults into the real pipeline,
  so retry and recovery behaviour can be observed rather than described
- Instrumented with Prometheus and OpenTelemetry using deliberately bounded
  label cardinality
- 213 tests: 180 unit, 33 Testcontainers integration, 21 Playwright end-to-end

## 21. Status by category

**Fully implemented and verified:** backend pipeline, auth and tenancy, retry
engine, dead-letter and replay, SSRF protection, rate limiting, all 11
frontend screens, the Reliability Playground, Prometheus metrics, the Grafana
dashboard, the test suite, CI workflows, documentation.

**Implemented but not verified in this environment:** Docker image builds,
Tempo trace collection.

**Not implemented, deliberately:** settings writes, tenant switching UI, cursor
pagination, per-tenant keys, published throughput figures.

**Blocked:** Docker image builds — sandbox TLS proxy. Nothing was blocked by
missing authorisation; no external deployment was attempted, as none was
authorised.
