# Implementation progress

Status as built and verified. Marked from evidence, not intention.

Legend: **Done** — implemented and verified · **Partial** — implemented, limits
noted · **Blocked** — environment limitation · **Not done** — deliberately out
of scope

## Backend

| Item | Status | Evidence |
|---|---|---|
| Builds (Java 21, Spring Boot 3.5.16) | Done | `mvn clean verify` |
| PostgreSQL persistence, Flyway migrations | Done | 4 migrations; `ddl-auto=validate` |
| Registration, login, JWT, refresh rotation | Done | Verified via API and UI |
| API keys: create, authenticate, revoke | Done | `TenantIsolationIT` |
| Multi-tenant isolation | Done | `TenantIsolationIT`, 11 cases |
| Endpoint CRUD, pause/resume, secret rotation | Done | `DeliveryPipelineIT`, E2E |
| Event ingestion with idempotency | Done | `DeliveryPipelineIT` |
| Transactional outbox | Done | Verified end to end |
| Kafka integration | Done | Testcontainers |
| Delivery workers with leases | Done | `DeliveryClaimConcurrencyIT` |
| HMAC signing + rotation | Done | Verified against the documented algorithm |
| Persistent attempt history | Done | Append-only |
| Retry engine (backoff, jitter, Retry-After) | Done | Gaps of +5.0s then +7.0s observed |
| Dead-letter and replay | Done | Replay appends attempt #4 |
| SSRF protection | Done | 48 tests |
| Rate limiting | Done | Auth limiter: 20×401 then 5×429 |
| Lease reconciliation | Done | `DeliveryClaimConcurrencyIT` |
| OpenAPI documentation | Done | `/swagger-ui.html` |

## Frontend

| Item | Status | Evidence |
|---|---|---|
| Production build | Done | `next build`, lint and types enforced |
| All 11 screens | Done | Verified in a browser |
| Auth via httpOnly cookies | Done | E2E |
| Real API data throughout | Done | No hardcoded metrics |
| Reliability Playground | Done | 4 scenarios verified E2E |
| Replay controls | Done | E2E |
| Mobile layout | Done | 0px overflow at 390/768/1440 across 12 pages |
| Accessibility | Done | Skip link, focus-visible, aria-live, native dialog |

## Infrastructure

| Item | Status | Notes |
|---|---|---|
| docker-compose.yml | Done | Validates; required-variable guards work |
| PostgreSQL, Redis, Kafka | Done | Verified running |
| Prometheus config + alerts | Done | 8 alert rules |
| Grafana dashboard | Done | 19 panels, provisioned |
| Health checks | Done | Readiness, not just liveness |
| **Docker image builds** | **Blocked** | The sandbox's TLS-intercepting proxy blocks Maven Central from inside build containers. Dockerfiles are written and compose validates; images were not built here. Services were verified running natively instead. |
| Tempo tracing | Partial | Configured and wired; not exercised in this environment |

## Quality

| Item | Status | Evidence |
|---|---|---|
| Unit tests | Done | 180 passing |
| Integration tests | Done | 33 passing (Testcontainers) |
| End-to-end tests | Done | 21 passing |
| Backend build | Done | `mvn clean verify` green |
| Frontend build | Done | `next build` green |
| Security scenarios tested | Done | SSRF, tenant isolation, revocation |
| No secrets committed | Done | Scanner verified clean, and verified to catch a real key |
| Load tests | Partial | Scripts written; **no figures measured** — see below |
| Documentation | Done | 11 documents |

## Not done, deliberately

- **Published throughput figures.** The k6 scripts exist, but nothing has been
  measured on hardware worth quoting. A number without its environment is not a
  benchmark.
- **Settings write operations.** Renaming an organization, editing a profile. A
  form that silently discards input is worse than one honest about its scope.
- **Tenant switching UI.** The backend supports multiple memberships; the UI
  shows the active tenant only.
- **Cursor pagination.** Offset works and is ordered stably; the drift is
  documented.
- **Per-tenant encryption keys.** One key for all tenants.

## Defects found and fixed during development

Thirteen, all found by running the thing rather than by reading it:

**Running the pipeline end to end (6):** `CHAR(64)` padding on credential
hashes; Hibernate array-type mismatch; PostgreSQL parameter type inference
inside `CASE` arms; the `(:param IS NULL OR …)` filter pattern, which
PostgreSQL rejects outright; an API-key prefix that could contain the
delimiter; the connection-drop scenario returning 200 instead of dropping.

**Driving the UI in a browser (4):** 185px horizontal overflow from grid items
refusing to shrink; two buttons labelled "Create key"; a 24h window labelled
"1d"; clipped chart axis labels.

**Writing the tests (3):** pause silently dropping events; `isDevAllowlisted`
not normalising trailing dots; Spring context leakage making the suite pass
class-by-class but fail together.
