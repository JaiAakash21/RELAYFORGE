# Testing

213 automated tests, all passing from a clean build.

| Layer | Count | What it is for |
|---|---|---|
| Unit (JUnit 5) | 180 | Pure logic with no I/O: backoff, classification, signatures, crypto, state predicates |
| Integration (Testcontainers) | 33 | Behaviour that only the real database provides |
| End-to-end (Playwright) | 21 | User flows through the real UI against the running stack |
| Load (k6) | 2 scripts | Throughput and pipeline lag, measured not claimed |

```bash
mvn test              # unit only — a logic error surfaces in ~2 minutes
mvn verify            # + integration (needs Docker)
npx playwright test   # end-to-end (needs the stack running)
```

## Why integration tests use real containers

Every mechanism worth testing here depends on behaviour an in-memory substitute
does not faithfully reproduce: `FOR UPDATE SKIP LOCKED`, partial indexes,
`jsonb`, `RETURNING`, and the exact semantics of a conditional `UPDATE` under
concurrency. H2 in PostgreSQL-compatibility mode implements none of them
properly, so a suite built on it would pass while the production query was
wrong — which is worse than having no test at all.

### `DeliveryClaimConcurrencyIT` — 12 tests

The most important class in the suite. Everything about correctness under
concurrency rests on one claim: a conditional `UPDATE` lets exactly one worker
take a delivery however many try at once. These run **real threads against real
PostgreSQL**, because the property being tested *is* the database's behaviour
and a mock cannot demonstrate it.

- 32 workers released simultaneously produce exactly one claim
- Concurrent dispatchers get disjoint batches (`SKIP LOCKED`)
- A worker whose lease was reclaimed cannot overwrite the result
- 16 simultaneous replays produce exactly one replay
- An expired lease is recoverable; an exhausted one dead-letters rather than
  looping

### `DeliveryPipelineIT` — 10 tests

Real events through the outbox, Kafka, the dispatcher and the workers to a
WireMock receiver. Nothing stubbed between ingestion and the HTTP request.

Notably, the signature test verifies using the **documented algorithm** over
the raw bytes received — so if the signer and the published documentation ever
diverge, a test fails rather than an integration silently breaking.

### `TenantIsolationIT` — 11 tests

Calls the services directly with a foreign tenant id, bypassing HTTP. A test
that went through the API would mostly prove the controller passes the right
tenant; it would not catch a service method that ignores the tenant it was
given. These call the services the way a future bug would.

## Defects these tests found

Worth listing, because a test suite's value is what it catches:

1. **Pause silently dropped events.** Pausing an endpoint before publishing
   created no delivery row at all, so there was nothing left to replay —
   directly contradicting the promise that pause holds events.
   (`DeliveryPipelineIT.pauseHoldsDeliveries`)
2. **`isDevAllowlisted` did not normalise trailing dots** while the validation
   path did, so it was correct only when its caller had already normalised.
   (`DestinationPolicyTest.normalisesTrailingDot`)
3. **Suite-level context leakage.** The suite passed class by class and failed
   when run together: Spring caches contexts for the JVM lifetime and a cached
   context keeps its `@Scheduled` methods firing, so one class's workers
   claimed another's fixture rows. Only the pipeline class runs workers now,
   and its context is dirtied after use — 5× faster as a side effect.

Four more defects came from running the UI in a browser (horizontal overflow
from grid items refusing to shrink, ambiguous button labels) and six from
running the pipeline end to end before any test existed (`CHAR` padding on
credential hashes, PostgreSQL parameter type inference, an API-key prefix that
could contain the delimiter).

## End-to-end

21 Playwright tests across desktop and mobile projects, against the real stack.
They register their own tenants rather than sharing a fixture account: tenant
isolation means a fresh tenant sees an empty dashboard, so a test can assert on
exact counts and a failure is attributable to the test that caused it.

Covered: registration and sign-in, the generic error on bad credentials,
endpoint creation with one-time secret reveal, SSRF rejection surfacing a
usable reason, four Playground scenarios end to end, dead-letter replay with
history preserved, URL-driven filtering, and zero horizontal overflow on twelve
pages at three viewports.

## Load testing

Two k6 scripts, measuring the two halves of the pipeline separately because
they fail differently and have different fixes. See
[tests/k6/README.md](../tests/k6/README.md).

Both use a **constant arrival rate**, not a fixed virtual-user count. A fixed
VU count measures the system's pace back to itself: when it slows down the
offered load slows with it, and the throughput figure flatters the server
exactly when it starts to struggle.

**No throughput figures are published in this repository**, because none have
been measured on hardware worth quoting. The scripts exist so a figure can be
produced with its environment attached; a number without the hardware, dataset
size and configuration it came from is not a benchmark.

## What is not covered

Stated rather than left to be discovered:

- **No mutation testing.** Coverage is not measured as a percentage, which can
  be gamed; the tests target the mechanisms that would be expensive to get
  wrong.
- **No chaos testing.** Worker death is simulated by expiring a lease, not by
  killing a container. The reconciler's behaviour is verified; the orchestrator
  interaction is not.
- **No multi-broker Kafka tests.** Single-node KRaft only, so partition
  leadership changes and ISR shrinkage are untested.
- **No sustained soak test.** Nothing has run for hours, so slow resource leaks
  would not have been caught.
