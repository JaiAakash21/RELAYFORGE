# Engineering decisions

Each entry states the decision, what it costs, and what the alternative would
have cost. A decision with no stated cost has not been thought through.

---

## 1. PostgreSQL is the source of truth; Kafka only distributes work

**Decision.** Delivery state lives in PostgreSQL. Kafka carries pointers —
`{deliveryId, tenantId, eventId, endpointId}` — not payloads, and never decides
whether a delivery still needs doing.

**Why.** Kafka is a log, not a database. It cannot answer "which deliveries are
due now?", "has this one already succeeded?" or "show me the attempt history",
and all three are routine. Keeping state in Kafka would mean rebuilding it by
replaying a topic.

Carrying only identifiers has three further consequences:
- The worker reads current state. If an endpoint was paused or its secret
  rotated between enqueue and processing, the worker acts on what is true now.
  A self-contained message would sign with a secret the user had already
  rotated away from.
- Duplicates are harmless, because processing is gated on the live row.
- No secrets on the broker. Kafka retains messages; the signing secret and the
  payload stay behind database access control.

**Cost.** One indexed read per attempt. Far cheaper than the correctness and
security problems the alternative creates.

---

## 2. Transactional outbox rather than dual writes

**Decision.** Ingestion writes the event, its deliveries and one outbox row per
delivery in a single transaction. Nothing is published to Kafka during the
request.

**Why.** The two orderings of a dual write are both broken — see
[ARCHITECTURE.md](ARCHITECTURE.md#the-transactional-outbox). The outbox makes
"accepted" and "queued for relay" the same atomic fact.

**Cost.** Relay latency of up to one publisher poll interval (1s by default),
and the possibility of a duplicate Kafka message. Both are cheap. Losing an
event is not.

**Alternative rejected.** A distributed transaction across PostgreSQL and
Kafka. Expensive, fragile, poorly supported, and *still* not exactly-once end
to end once an external HTTP call is involved — so it would buy complexity
without buying the guarantee.

---

## 3. At-least-once with idempotent consumption, not exactly-once

**Decision.** Accept duplicate messages; make processing idempotent.

**Why.** Exactly-once delivery of an external side effect is not achievable.
The receiver's acknowledgement travels over a network that can lose it, so
"did the receiver process this?" is sometimes unanswerable. Every system that
claims exactly-once either controls both sides of the boundary or is quietly
redefining the term.

What *is* achievable is making duplicates cheap. The conditional claim means a
duplicate costs one no-op `UPDATE`.

**Cost.** Receivers must deduplicate. RelayForge makes that possible by sending
a stable `X-RelayForge-Event-Id` that does not change across retries or
replays, and says so in the API documentation rather than burying it.

---

## 4. Retries scheduled in the database, not in Kafka

**Decision.** `next_attempt_at` on the delivery row; a dispatcher turns due
work into Kafka messages.

**Why.** Kafka has no per-message delay, and both workarounds are bad: delay
topics quantise the schedule so jitter stops working, and sleeping in a
consumer blocks a partition. Neither lets a user see or correct a scheduled
retry.

**Cost.** A polling dispatcher — one indexed query per second against a partial
index. Measured in microseconds, and the same query doubles as the recovery
path for lost messages.

---

## 5. Symmetric jitter, not full jitter

**Decision.** Delay drawn from `[base × (1−f), base × (1+f)]`, default f = 0.2.

**Why.** Pure exponential backoff preserves the synchronisation that caused the
outage: 500 deliveries that failed together retry together, forever, on a
doubling schedule. Jitter is not optional.

Among jitter schemes:
- **Full jitter** (`random(0, base)`) spreads best but can retry almost
  immediately after a long wait, wasting an attempt from a small budget on an
  endpoint that is clearly still down.
- **Equal jitter** never exceeds the base delay, so it cannot spread a herd
  across the boundary ahead.
- **Symmetric** keeps the expected delay equal to the intended schedule — which
  matters when the budget is six attempts and each wait has to mean something —
  while smearing in both directions.

**Cost.** Slightly less spreading than full jitter. Configurable per endpoint,
so it is a default rather than a dogma.

---

## 6. `FAILED` is the dead-letter state; the DLQ is a view

**Decision.** No separate status, no separate table. The dead-letter queue is
`SELECT ... WHERE status = 'FAILED'`.

**Why.** Two representations of one delivery can disagree. A replay would have
to update both, and the attempt history would have to live in one of them. One
row cannot desynchronise from itself.

**Cost.** None identified. The partial index on `(tenant_id, updated_at DESC)
WHERE status = 'FAILED'` makes the view as fast as a dedicated table.

---

## 7. Replay appends; it never resets

**Decision.** Replay extends the attempt budget (`max_attempts = attempt_count
+ extra`) and leaves `attempt_count` alone. Attempt numbering continues upward.

**Why.** The previous attempts are the evidence of why the delivery
dead-lettered. Erasing them would make a delivery that succeeded on its third
replay indistinguishable from one that worked first time, hiding a persistently
broken receiver.

**Cost.** Attempt numbers are not "attempts since the last replay", which can
briefly surprise. The UI labels each attempt's `kind` (`AUTOMATIC`, `RETRY`,
`REPLAY`) to make it unambiguous.

---

## 8. Modular monolith, split by runtime role

**Decision.** One codebase, one artefact, three roles chosen by
`relayforge.role`.

**Why.** Microservices would buy independent deployment at the cost of network
hops, distributed transactions and operational overhead that a project this
size cannot justify. But the API and the workers genuinely do scale on
different signals, so they run as separate processes.

Splitting at configuration time rather than build time means one thing to
build, test and version, with no shared-library drift between services.

**Cost.** API and workers are versioned together. For this scale that is a
benefit — the delivery contract between them is a Java record, checked by the
compiler, rather than a schema that can drift.

---

## 9. BCrypt for passwords, SHA-256 for machine credentials

**Decision.** Passwords use BCrypt at cost 12. API keys and refresh tokens use
unsalted SHA-256.

**Why.** They are different problems. BCrypt's slowness defends low-entropy
human-chosen secrets against offline guessing. An API key is 256 bits from a
CSPRNG: there is no dictionary, so slowness buys nothing — and it costs a great
deal, because the key is verified on *every* ingestion request. A 250ms KDF
there would cap throughput at a handful of requests per second per core and
make the platform trivially self-DoS-able.

No salt, deliberately: a salt would require fetching the row before hashing,
and the lookup *is* by hash. With full-entropy inputs a salt adds nothing.

**Cost.** None. Using one algorithm for both would be worse in whichever
direction it was chosen.

---

## 10. AES-256-GCM for webhook secrets — encrypted, not hashed

**Decision.** Endpoint signing secrets are encrypted at rest, not hashed.

**Why.** We have to *use* the secret to sign with it, so a one-way hash is not
an option. GCM rather than CBC because it is authenticated: tampering with a
stored ciphertext fails decryption rather than silently yielding a different
key an attacker influenced.

**Cost.** Key management. The encryption key must be supplied from the
environment and backed up — losing it makes every stored secret unreadable. The
bootstrap script says so.

---

## 11. The SSRF policy pins DNS rather than merely validating it

**Decision.** `DestinationPolicy` resolves and validates; `PinnedDnsResolver`
hands the HTTP client exactly those addresses.

**Why.** Validating a hostname and then handing it to an HTTP client means two
resolutions. Between them, a record with a short TTL can return a public
address to the check and a private one to the connection — DNS rebinding, which
defeats hostname validation completely. Replacing the client's resolver removes
the second lookup, so the window is closed rather than narrowed.

**Cost.** The delivery client cannot resolve anything that has not been
validated — it fails closed. A future code path that forgets to validate breaks
loudly in tests rather than quietly reaching the internal network.

**Honest limitation.** This cannot defend against a destination that is public
at connect time and proxies internally. Production needs network-level egress
filtering; see [SECURITY.md](SECURITY.md).

---

## 12. Rate limiting fails open

**Decision.** When Redis is unreachable, ingestion is allowed and a counter is
incremented.

**Why.** Rate limiting is a protective optimisation. The correctness guarantees
are the database constraints, payload caps and attempt budgets. Rejecting every
event because the limiter is down converts a Redis outage into a total
ingestion outage, which is strictly worse.

**Cost.** A Redis outage removes throttling. Every fail-open decision is
counted and alerted on, so the gap is visible rather than silent.
Configurable — `relayforge.rate-limits.fail-open=false` reverses it.

---

## 13. A per-minute rollup for dashboard metrics

**Decision.** `delivery_minute_stats`, maintained by one UPSERT in the same
transaction that records the attempt.

**Why.** The dashboard asks for success rate and latency percentiles over a
window. Computing those from `delivery_attempts` scans every attempt in the
window, so the query gets slower exactly as the platform gets busier — the
wrong direction for a view that is most needed when things are busy.

Same transaction, so the aggregate can never describe an attempt that was
rolled back, and no background job can fall behind.

**Cost.** Latency percentiles are interpolated from fixed buckets, not exact.
Surfaced as `approximateLatency: true` in the API and labelled in the UI,
rather than presented as precise.

---

## 14. Two metrics systems

**Decision.** Per-tenant figures from PostgreSQL; operational figures to
Prometheus.

**Why.** Per-tenant metrics in Prometheus need a `tenant_id` label, and the
series count would grow with the customer base — the one number a platform
wants to grow. Operator metrics in PostgreSQL would make the dashboard
aggregate across tenants on every page load.

**Cost.** Two places to look. Mitigated by scoping each clearly: the product
dashboard answers "how is *my* integration doing", Grafana answers "how is the
*platform* doing".

---

## 15. Tokens in httpOnly cookies, not localStorage

**Decision.** The browser never holds a token. A Next.js route handler reads
the cookie server-side and proxies to the API.

**Why.** Any script on the page can read `localStorage`. One compromised
dependency, one XSS in a payload viewer, and an attacker has a credential
usable from anywhere. An httpOnly cookie cannot be read by script at all.

**Cost.** An extra network hop, and the browser cannot call the API directly.
Paid deliberately. The proxy allowlists the path prefixes the dashboard
actually uses, so it cannot become a general-purpose confused deputy.

---

## 16. Access tokens are not revocable; refresh tokens are

**Decision.** Short-lived self-contained JWTs for access; stateful, rotating,
revocable refresh tokens.

**Why.** Verifying a JWT needs no database round trip, which is what makes it
cheap enough to check on every request. The consequence — it cannot be revoked
before it expires — is bounded by a 15-minute TTL. Making access tokens
stateful would mean a lookup per request and turn the token into a session id.

API keys are the opposite trade: long-lived, so they *are* checked against the
database on every request and revocation is immediate.

**Cost.** Up to 15 minutes between "sign out everywhere" and full effect. The
settings page says so explicitly rather than implying instant revocation.

---

## 17. Jackson serialises nulls

**Decision.** `default-property-inclusion: always`.

**Why.** In this API a null is information. `responseStatus: null` on an
attempt means no HTTP response was ever received — a timeout or a dropped
connection — which is a different fact from the field being absent. Omitting
nulls makes the two indistinguishable and forces clients to guess.

**Cost.** Slightly larger payloads. Worth it for a contract where every
documented field is always present.
