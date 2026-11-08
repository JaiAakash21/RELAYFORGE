# Load tests

Two k6 scripts, measuring the two halves of the pipeline separately because
they fail in different ways and have different fixes.

| Script | Measures | Fix when it is slow |
|---|---|---|
| `ingest.js` | The synchronous path: accept an event and commit it with its deliveries and outbox rows in one transaction. | Database write capacity, connection pool, API instances. |
| `delivery-lag.js` | The asynchronous path: outbox drain, dispatch and outbound HTTP, under sustained ingestion. | Worker count, per-endpoint rate limits, receiver speed. |

## Running

k6 is not bundled. Install it from <https://k6.io/docs/get-started/installation/>.

Start the stack and create an API key in the dashboard, then:

```bash
k6 run -e API_KEY=rf_live_xxxxxxxx_... tests/k6/ingest.js
```

```bash
k6 run -e API_KEY=rf_live_xxxxxxxx_... -e ADMIN_TOKEN=eyJ... tests/k6/delivery-lag.js
```

For the delivery test, point at least one endpoint at the simulator's
always-succeeding target first, or there is nothing to deliver:

```
http://webhook-simulator:8081/simulator/receive/loadtest
```

## Tuning the load

```bash
k6 run -e API_KEY=... -e RATE=500 -e DURATION=5m tests/k6/ingest.js
```

Both scripts use a **constant arrival rate**, not a fixed virtual-user count.
That distinction matters: a fixed number of VUs measures the system's pace back
to itself, so when it slows down the offered load slows with it and the
throughput figure flatters the server. A constant arrival rate holds the offered
load steady, which is what makes the saturation point visible.

## Reporting results honestly

Every result must be quoted with the environment that produced it:

- CPU, memory and whether the stack shared a host with the load generator
- Row counts in `events`, `deliveries` and `delivery_attempts` at the start
- `RELAYFORGE_DELIVERY_CONCURRENCY` and the worker replica count
- The receiver used, and whether it was the simulator or a real endpoint

A throughput number without that context is not a benchmark. In particular,
running k6 on the same machine as the stack means the two compete for CPU, so
the figures are a lower bound on what the software can do and an upper bound on
nothing at all.

Results are written to `tests/k6/results/`, which is git-ignored.
