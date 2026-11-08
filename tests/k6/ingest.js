/**
 * Event ingestion load test.
 *
 * Measures the synchronous half of the pipeline: how fast RelayForge can accept
 * events and commit them, with their deliveries and outbox rows, in one
 * transaction. It deliberately does <em>not</em> measure delivery — that is
 * asynchronous by design and is covered by delivery-lag.js.
 *
 * Usage:
 *   k6 run -e API_KEY=rf_live_... tests/k6/ingest.js
 *   k6 run -e API_KEY=... -e BASE_URL=http://localhost:8080 -e RATE=200 tests/k6/ingest.js
 *
 * Record the environment with any result you quote. A number without the
 * hardware, the dataset size and the configuration it was measured on is not a
 * benchmark; it is a rumour.
 */
import http from "k6/http";
import { check, fail } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { randomString } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API_KEY = __ENV.API_KEY;
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || "60s";

const accepted = new Counter("events_accepted");
const deduplicated = new Counter("events_deduplicated");
const throttled = new Counter("events_throttled");
const fanOut = new Trend("deliveries_per_event");
const acceptRate = new Rate("accept_rate");

export const options = {
  scenarios: {
    // Arrival rate, not VU count. A fixed number of virtual users measures the
    // system's own pace back to itself: if it slows down, the load slows with
    // it and the throughput number flatters the server. A constant arrival rate
    // holds the offered load steady and lets latency reveal the saturation
    // point.
    steady: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(RATE / 4)),
      maxVUs: Math.max(100, RATE * 2),
    },
  },
  thresholds: {
    // Targets to measure against, not claims. A failing threshold here is a
    // finding to report, not something to relax until it passes.
    http_req_failed: ["rate<0.01"],
    "http_req_duration{expected_response:true}": ["p(95)<250", "p(99)<500"],
    accept_rate: ["rate>0.99"],
  },
  // Discard per-request detail; the summary is what gets reported.
  discardResponseBodies: false,
};

export function setup() {
  if (!API_KEY) {
    fail(
      "API_KEY is required. Create one in the dashboard under API keys, then:\n" +
        "  k6 run -e API_KEY=rf_live_... tests/k6/ingest.js",
    );
  }
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    fail(`RelayForge is not reachable at ${BASE_URL} (status ${health.status})`);
  }
  return { startedAt: new Date().toISOString() };
}

export default function () {
  const orderId = `ORD-${randomString(10)}`;
  const payload = JSON.stringify({
    eventType: "order.created",
    payload: {
      orderId,
      amount: Math.floor(Math.random() * 100000),
      currency: "INR",
      items: [{ sku: randomString(8), qty: 1 }],
    },
  });

  const response = http.post(`${BASE_URL}/api/v1/events`, payload, {
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": API_KEY,
      // A unique key per request: this test measures the ingest path, not the
      // deduplication short-circuit, which is a different and much cheaper
      // code path.
      "Idempotency-Key": `load-${orderId}`,
    },
    tags: { name: "POST /api/v1/events" },
  });

  const ok = check(response, {
    "accepted (202) or deduplicated (200)": (r) => r.status === 202 || r.status === 200,
    "returns an event id": (r) => {
      if (r.status !== 202 && r.status !== 200) return false;
      try {
        return typeof r.json("eventId") === "string";
      } catch {
        return false;
      }
    },
  });

  acceptRate.add(ok);

  if (response.status === 202) {
    accepted.add(1);
    try {
      fanOut.add(response.json("deliveriesCreated"));
    } catch {
      // Body not JSON; the check above already recorded the failure.
    }
  } else if (response.status === 200) {
    deduplicated.add(1);
  } else if (response.status === 429) {
    // Not a failure: the limiter doing its job. Counted separately so a run
    // that was throttled is not mistaken for one that was slow.
    throttled.add(1);
  }
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const get = (name, stat) => {
    const m = metrics[name];
    if (!m) return "n/a";
    const v = stat ? m.values[stat] : m.values.count;
    return typeof v === "number" ? v.toFixed(stat ? 2 : 0) : "n/a";
  };

  const report = `
RelayForge — event ingestion load test
======================================
Offered load      ${RATE} req/s for ${DURATION}
Target            ${BASE_URL}

Throughput
  requests        ${get("http_reqs")}
  achieved rate   ${get("http_reqs", "rate")} req/s
  accepted        ${get("events_accepted")}
  deduplicated    ${get("events_deduplicated")}
  throttled (429) ${get("events_throttled")}

Latency (ms)
  p50             ${get("http_req_duration", "med")}
  p95             ${get("http_req_duration", "p(95)")}
  p99             ${get("http_req_duration", "p(99)")}
  max             ${get("http_req_duration", "max")}

Reliability
  failed          ${get("http_req_failed", "rate")} (rate)
  fan-out/event   ${get("deliveries_per_event", "avg")} avg

NOTE: these figures describe this machine, this dataset and this configuration.
Quote them only together with that context.
`;

  return {
    stdout: report,
    "tests/k6/results/ingest-summary.json": JSON.stringify(data, null, 2),
  };
}
