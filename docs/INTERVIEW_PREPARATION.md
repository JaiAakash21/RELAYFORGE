# Interview preparation

Twenty-four questions with answers you can defend, not memorise. Each states
the trade-off as well as the decision — an answer that cannot name what it cost
is not an answer.

---

### 1. What is RelayForge?

Multi-tenant webhook delivery infrastructure. A customer registers endpoint
URLs and publishes events; RelayForge signs each event and delivers it,
retrying failures with exponential backoff, dead-lettering what it cannot
deliver, and keeping every attempt as evidence.

The thing worth building was not the happy path — that is one HTTP POST. It was
everything that happens when the receiver is down, the worker dies, or the
broker loses a message.

### 2. What problem does it actually solve?

Four ways a naive pipeline loses events:

1. Your database commits, then the broker is unreachable — the event is
   accepted and silently never delivered.
2. A receiver returns 500 for ninety seconds, and retrying at a fixed interval
   adds exactly the load causing the failure.
3. A worker is OOM-killed mid-delivery and the row is stuck forever.
4. Kafka delivers the same message twice and a card is charged twice.

Each has a named mechanism answering it: the transactional outbox, backoff with
jitter, worker leases with reconciliation, and the conditional claim.

### 3. Walk me through the architecture.

PostgreSQL is the source of truth for what must be delivered; Kafka distributes
the work of delivering it, and never decides whether a delivery still needs
doing.

Ingestion writes the event, one delivery per subscribed endpoint, and one
outbox row per delivery — in a single transaction. The API returns 202 on
commit and does not wait for receivers. A publisher relays the outbox to Kafka.
Workers consume, take an exclusive lease on the delivery row, sign and send.
2xx succeeds; a retryable failure schedules the next attempt on the row itself;
an exhausted budget dead-letters.

### 4. Why the transactional outbox?

Ingestion must persist the event and tell the workers. Those live in two
systems with no shared transaction, and both orderings are broken: commit-then-
publish loses the event if the process dies between them, and publish-then-
commit has workers chasing a row that does not exist.

The outbox removes the choice. The only write ingestion performs is to
PostgreSQL, so "accepted" and "queued for relay" become one atomic fact.

**Cost:** up to one poll interval of relay latency, and the possibility of a
duplicate message. **Alternative rejected:** a distributed transaction — which
is expensive, fragile, and *still* not exactly-once once an external HTTP call
is involved.

### 5. At-most-once, at-least-once, exactly-once?

- **At-most-once:** acknowledge before processing. Fast, loses messages on
  crash.
- **At-least-once:** acknowledge after processing. Never loses, may duplicate.
- **Exactly-once:** every message has exactly one effect.

RelayForge is at-least-once: the Kafka offset is committed only after the
attempt's outcome is durably written.

### 6. Why can't you guarantee exactly-once?

Because the receiver's acknowledgement travels over a network that can lose it.
If a worker sends a request and dies before recording the result, the
information needed to decide — whether the receiver acted — was in the response
that was lost. No amount of engineering on our side recovers it.

Kafka's "exactly-once semantics" is exactly-once *within Kafka*: reading,
processing and writing back to Kafka, in one transaction. The moment the effect
is an external HTTP call, that boundary is crossed and the guarantee is gone.

RelayForge is explicit about this: it counts the attempt, records that the
outcome is unknown rather than inventing a failure, and sends a stable
`X-RelayForge-Event-Id` so receivers can deduplicate — which is the only place
the ambiguity can actually be resolved.

### 7. How do you make duplicates harmless?

A conditional UPDATE:

```sql
UPDATE deliveries SET status='IN_PROGRESS', lease_owner=?, lease_until=?
 WHERE id = ?
   AND ((status IN ('PENDING','RETRY_SCHEDULED') AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
     OR (status = 'IN_PROGRESS' AND lease_until <= ?))
```

One statement, atomic. If it updates one row the caller owns the delivery; if
zero, something else already handled it. A duplicate message costs one cheap
no-op — which is how at-least-once *messaging* becomes effectively-once
*processing*.

Demonstrated with 32 real threads against real PostgreSQL in
`DeliveryClaimConcurrencyIT`.

### 8. Why not just use `SELECT ... FOR UPDATE`?

That needs a read, a decision and a write inside one transaction, holding a row
lock across all three. The conditional UPDATE does it in one statement with no
application-side decision. It is also the only form that is correct when the
application has already made its decision from stale data — the `WHERE` clause
re-checks against the row as it is *now*.

`FOR UPDATE SKIP LOCKED` is still used, but for a different job: handing
*disjoint batches* to competing pollers in the outbox publisher and the
dispatcher.

### 9. Explain the backoff.

`delay = min(initialDelay × 2^(attempt−1), maxDelay)`, then jitter.

Doubling is the standard response to an overloaded dependency: retrying at a
constant interval adds the load causing the failure. The ceiling stops a
long-dead endpoint being scheduled days out, which would make the dead-letter
queue useless as a signal.

### 10. Why is jitter not optional?

Without it, 500 deliveries that failed together retry together — and again at
the next boundary. Pure exponential backoff *preserves* the synchronisation
that caused the outage. It is the difference between a receiver recovering and
being re-killed by its own backlog.

### 11. Why symmetric jitter rather than full jitter?

Full jitter (`random(0, base)`) spreads best but can retry almost immediately
after a long wait, wasting an attempt from a six-attempt budget on an endpoint
that is clearly still down. Equal jitter never exceeds the base, so it cannot
spread across the boundary ahead.

Symmetric keeps the expected delay equal to the intended schedule while
smearing in both directions. It is configurable per endpoint, so it is a
default rather than a dogma.

### 12. Why are retries scheduled in the database, not Kafka?

Kafka has no per-message delay. Delay topics quantise the schedule — a computed
7.3s becomes "the 10s topic", and jitter stops working. Sleeping in a consumer
blocks the partition, stalling every other endpoint mapped to it. Neither lets
a user see or correct a pending retry, and the UI must show "next attempt in
2m 14s" from somewhere.

So `next_attempt_at` lives on the row and a dispatcher turns due into queued.
The same query is the recovery path for any lost message.

### 13. What happens when a worker dies mid-delivery?

The lease expires. A reconciler finds `IN_PROGRESS` rows whose `lease_until`
has passed and returns them to `RETRY_SCHEDULED`.

A lease is better than a "stuck for N minutes" heuristic because a live worker
keeps extending it — so an expired lease is *positive evidence* the holder is
gone. A timeout heuristic cannot distinguish a dead worker from a live one
talking to a slow receiver, so it either duplicates needlessly or waits so long
that recovery is useless.

If the worker died after sending, this produces a duplicate. The attempt is
still counted so a crash loop cannot escape the budget.

### 14. How is the dead-letter queue implemented?

As a view over `deliveries WHERE status = 'FAILED'` — no separate status, no
separate table. Two representations of one delivery can disagree, and a replay
would have to update both. One row cannot desynchronise from itself.

A partial index on `(tenant_id, updated_at DESC) WHERE status = 'FAILED'` makes
it as fast as a dedicated table would be.

### 15. What does replay do, exactly?

Extends the budget (`max_attempts = attempt_count + extra`), sets the status
back to `PENDING`, increments `replay_count`, and writes an audit record.

It does **not** reset `attempt_count` or delete attempts. The previous attempts
are the evidence of why it dead-lettered; erasing them would make a delivery
that succeeded on its third replay look identical to one that worked first
time, hiding a persistently broken receiver.

Concurrency: the reopen is guarded on the status still being replayable, so
sixteen simultaneous replays produce exactly one.

### 16. How does rate limiting work?

A Redis token bucket in an atomic Lua script.

A fixed window allows twice the rate across a boundary — 100 requests at 0.999s
and 100 more at 1.001s. A token bucket has no boundary to straddle and its
burst is an explicit parameter.

Lua because refill-and-consume must be atomic. As `GET`, compute, `SET` across
several API instances it is a lost-update race under exactly the concurrency it
exists to control.

Two kinds of limit: ingestion limits protect *us* and return 429; per-endpoint
delivery limits protect *the receiver* and **reschedule rather than drop** —
the event was already accepted.

### 17. What happens when Redis is down?

Fail open: traffic is allowed and a counter increments. Rate limiting is a
protective optimisation; the correctness guarantees are the database
constraints and attempt budgets. Rejecting everything because the limiter is
down turns a Redis outage into a total ingestion outage, which is strictly
worse. Configurable, and every fail-open decision is alerted on.

### 18. Explain the signature scheme.

`HMAC-SHA256(secret, "<timestamp>.<raw body>")`, sent as
`t=<unix>,v1=<hex>`.

The timestamp is **inside** the MAC. A signature over the body alone is
replayable forever; binding the timestamp lets a receiver reject stale
requests, and because it is covered it cannot be edited to make an old capture
look fresh.

Verification is constant-time — a byte-by-byte early return leaks the signature.

During rotation the header carries one `v1=` per valid secret, so a receiver
can roll over without dropping events. The old secret expires after a grace
window; one that stayed valid forever would mean rotating a leaked secret never
revoked it.

### 19. Why encrypt webhook secrets but hash API keys?

Different requirements. We must *use* the webhook secret to sign, so it must be
reversible — AES-256-GCM, authenticated so tampering fails decryption rather
than yielding a key an attacker influenced. We only need to *compare* an API
key, so a one-way hash is strictly better: a database dump yields nothing.

### 20. Why SHA-256 for API keys but BCrypt for passwords?

BCrypt's cost defends *low-entropy human-chosen* secrets against offline
guessing. An API key is 256 bits from a CSPRNG — no dictionary, so slowness
buys nothing and costs a great deal: the key is verified on every ingestion
request, and a 250ms KDF there would cap throughput at a handful of requests
per second per core.

### 21. How do you prevent SSRF?

The hard case, because the product's purpose is to fetch user-supplied URLs.

Validate scheme, port, userinfo, and **every resolved address** against
loopback, RFC1918, link-local (where every cloud metadata service lives),
CGNAT, IPv6 unique-local and the IPv4-mapped forms of all of them. Redirects
are not followed. DNS resolution is time-bounded.

The subtle part is **DNS rebinding**: validating a hostname then handing it to
an HTTP client means two resolutions, and a short-TTL record can return a
public address to the check and a private one to the connection. RelayForge
replaces the HTTP client's DNS resolver with one that returns only the
validated addresses, so there is no second lookup to poison. It fails closed —
an unpinned hostname is refused, not resolved.

**Honest limit:** this cannot stop a public host that proxies internally.
Production needs network-level egress filtering; application validation is
defence in depth.

### 22. How is multi-tenancy enforced?

Every tenant-owned table carries `tenant_id`, and every read path uses a
tenant-scoped query rather than `findById` followed by a check — isolation is a
property of the query, not of a later `if`.

Cross-tenant references return **404, not 403**, so the API cannot be used as
an existence oracle.

Verified by `TenantIsolationIT`, which calls the services directly with a
foreign tenant id — the way a real bug would.

### 23. Where are the bottlenecks, and how would you scale past them?

In order:

1. **Outbound HTTP.** Each delivery waits on a third party. Bounded by
   `partitions × worker instances × concurrency`. Scale workers; raise
   partitions past six.
2. **Database writes on ingestion.** One transaction with 1 + N + N inserts.
   Batch the fan-out, then partition `deliveries` and `delivery_attempts` by
   time.
3. **`delivery_attempts` growth.** The fastest-growing table. Needs time
   partitioning with old partitions detached to cold storage.
4. **Dispatcher polling.** One query per second. A single leader at very high
   volume, or `LISTEN/NOTIFY` to cut the latency.

What is *not* a bottleneck: Kafka, which carries pointers, and the API read
paths, which are indexed and tenant-scoped.

### 24. What would you do differently, and what is missing?

**Differently:**
- Cursor pagination from the start. Offset drifts as rows are inserted.
- Time-partition `delivery_attempts` before it is needed, not after.
- An endpoint-level circuit breaker. Currently each delivery to a dead endpoint
  burns its own budget independently; a breaker would fail fast and stop
  wasting capacity.

**Missing:**
- Per-tenant encryption keys, so one key compromise is not total.
- A real soak test. Nothing has run for hours, so slow leaks would not have
  been caught.
- Multi-broker Kafka testing — partition leadership changes are untested.
- Chaos testing: worker death is simulated by expiring a lease, not by killing
  a container.

---

## Diagram to draw on a whiteboard

```
Client ──POST──▶ API
                  │
         ┌────────┴────────┐
         │ ONE TRANSACTION │   events → deliveries → outbox
         └────────┬────────┘
                  │ commit → 202
                  │
   Outbox publisher ──▶ Kafka ──▶ Worker ──▶ Receiver
   (SKIP LOCKED,        (part. =   (lease,      │
    awaits ack)         endpoint)   sign)   ┌───┴───┐
                                          2xx   429/5xx
                            Dispatcher ◀───┘  RETRY_SCHEDULED
                            (next_attempt_at)      │
                                                exhausted
                                                   ▼
                                                 FAILED ──replay──▶ PENDING
```

Say while drawing: **"PostgreSQL decides what needs doing. Kafka decides who
does it."** Everything else follows.
