# Failure handling

Eighteen scenarios, the expected behaviour of each, and where it is verified.
Where the outcome is genuinely uncertain, that is stated rather than papered
over.

| # | Scenario | Expected behaviour | Verified |
|---|---|---|---|
| 1 | **Kafka unavailable at ingestion** | Ingestion succeeds. Nothing is published during the request — the outbox row commits with the event, and the publisher relays it when the broker returns. The caller sees 202 and is not lied to. | By design; `spring.kafka.admin.fail-fast=false` lets a worker start without the broker |
| 2 | **PostgreSQL unavailable** | Ingestion fails with 503. This is the one dependency whose loss must fail loudly: accepting an event we cannot store would be the lie the whole design exists to avoid. | Hikari `initialization-fail-timeout` |
| 3 | **Outbox publisher crashes mid-batch** | The claim expires; another publisher re-claims and republishes. Rows already acknowledged by the broker are republished too — a duplicate, absorbed downstream. | `OutboxClaimDao` claim TTL |
| 4 | **Kafka delivers a message twice** | The second message's conditional claim updates zero rows. One cheap no-op; no second webhook. | `DeliveryClaimConcurrencyIT.duplicateMessageIsNoOp` |
| 5 | **Worker crashes before sending** | The lease expires, the reconciler returns the delivery to `RETRY_SCHEDULED`, the dispatcher re-enqueues it. No duplicate, because nothing was sent. | `DeliveryClaimConcurrencyIT.reconcilerRecoversExpiredLeases` |
| 6 | **Worker crashes after sending, before recording** | **The outcome is unknown and RelayForge says so.** The lease expires and the delivery is retried, which may deliver the event a second time. The attempt is counted so a crash loop cannot escape the budget, and `last_error` records that the request "may or may not have reached the receiver" rather than inventing a failure. This is inherent to at-least-once delivery over a network that can lose a response; it is why every request carries a stable `X-RelayForge-Event-Id`. | Same test; the error text is asserted |
| 7 | **Receiver returns 429** | Retryable. `Retry-After` takes precedence over the computed backoff, clamped to a maximum so a receiver cannot park our work indefinitely. | `DeliveryPipelineIT.honoursRetryAfter` |
| 8 | **Receiver returns 503** | Retryable with exponential backoff and jitter. | `DeliveryPipelineIT.retriesThenSucceeds` |
| 9 | **Receiver times out** | The attempt is bounded by the per-attempt timeout, recorded with `READ_TIMEOUT` and **no HTTP status** — which the UI renders as "No response", not as a blank cell. A slow receiver cannot pin a worker. | `DeliveryPipelineIT.slowReceiverTimesOut` |
| 10 | **Receiver never recovers** | Attempts are exhausted, the delivery becomes `FAILED`, and the full attempt history is preserved for the dead-letter queue. | `DeliveryPipelineIT.exhaustsAndDeadLetters` |
| 11 | **Duplicate event ingestion** | With an `Idempotency-Key`, the original event is returned and no second event or delivery is created. Two concurrent requests race on the unique index; the loser returns the winner's event. | `DeliveryPipelineIT.idempotencyPreventsDuplicates` |
| 12 | **Two workers claim one delivery** | Exactly one wins. The database decides in a single atomic statement; the loser observes zero updated rows and stops. | `DeliveryClaimConcurrencyIT.onlyOneWorkerClaims` (32 threads) |
| 13 | **Replay while another operation is active** | The reopen is guarded on the status still being replayable, so sixteen simultaneous replays produce exactly one. A concurrent delivery in progress cannot be replayed at all — `IN_PROGRESS` is not a replayable state. | `DeliveryClaimConcurrencyIT.concurrentReplayIsSingular` |
| 14 | **API key revoked** | Fails on the very next request. Every API-key request is validated against the database — no cache, no grace period. | `TenantIsolationIT.revokedKeyFailsImmediately` |
| 15 | **One tenant reads another's data** | 404, not 403, so the API cannot be used as an existence oracle. Enforced by tenant-scoped queries, not by a later check. | `TenantIsolationIT`, 11 cases |
| 16 | **Redis unavailable** | Rate limiting **fails open**: traffic is allowed and a counter increments. Rejecting every event because the limiter is down would turn a Redis outage into a total ingestion outage. Configurable; alerted on. | `RateLimiter.failOpen`; `relayforge_ratelimit_unavailable_total` |
| 17 | **Endpoint URL resolves to a prohibited address** | Refused at registration where possible, and re-validated at delivery time — a DNS record may have changed in between. Recorded as an attempt with `BLOCKED_DESTINATION` so the user can see why nothing was delivered. | `DestinationPolicyTest`, 48 cases |
| 18 | **Application restarts with deliveries `IN_PROGRESS`** | Their leases expire and the reconciler recovers them. A delivery cannot become permanently stuck. If the budget is already spent it is dead-lettered rather than granted a free retry. | `DeliveryClaimConcurrencyIT.exhaustedStrandedDeliveryFails` |

## Two failures the dashboard would otherwise hide

**A stalled outbox.** Ingestion keeps returning 202, event counts keep rising,
and every dependency reports healthy — while nothing is being relayed. This is
the most dangerous failure in the system because every other signal looks
normal. It is surfaced three ways: a banner on the overview, the
`relayforge_outbox_oldest_age_seconds` gauge, and an `OutboxNotDraining` alert.

**A delivery backlog.** Reported as `DEGRADED`, never `DOWN`. Reporting DOWN
would make an orchestrator restart or drain the instance, which is exactly the
wrong response to a backlog — fewer workers makes it worse. The distinction is
deliberate: DOWN means "do not send me traffic", a backlog means "I need more
capacity".

## Poison messages

A Kafka message that cannot be processed at all — malformed, or referencing a
delivery that does not exist — is acknowledged and forwarded to
`relayforge.delivery.requests.dlt`. Not acknowledging would redeliver it
forever and block its partition, taking every other endpoint mapped to that
partition down with it. The delivery row remains the authoritative schedule, so
nothing is lost by letting the message go.

This is distinct from the delivery dead-letter queue: that is for receiver
failures, this is for broken messages. Conflating them would hide
infrastructure bugs among ordinary failures.
