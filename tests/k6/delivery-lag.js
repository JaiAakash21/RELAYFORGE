/**
 * Delivery pipeline load test.
 *
 * Measures the asynchronous half: how quickly the outbox drains, the dispatcher
 * enqueues and the workers deliver, under sustained ingestion. Ingest latency is
 * incidental here — the number that matters is how far behind the pipeline
 * falls and whether it catches up afterwards.
 *
 * Usage:
 *   k6 run -e API_KEY=rf_live_... -e ADMIN_TOKEN=eyJ... tests/k6/delivery-lag.js
 *
 * ADMIN_TOKEN is a dashboard access token, used to poll /metrics/summary. Get
 * one from the browser's session or by calling POST /api/v1/auth/login.
 *
 * Point at least one endpoint at the webhook simulator's always-succeeding
 * target before running, or there will be nothing to deliver:
 *   http://webhook-simulator:8081/simulator/receive/loadtest
 */
import http from "k6/http";
import { check, fail, sleep } from "k6";
import { Gauge, Trend } from "k6/metrics";
import { randomString } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API_KEY = __ENV.API_KEY;
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN;
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || "90s";

// Pipeline depth, sampled rather than derived from request timings.
const dueBacklog = new Gauge("pipeline_due_backlog");
const oldestDueAge = new Gauge("pipeline_oldest_due_age_seconds");
const outboxBacklog = new Gauge("pipeline_outbox_backlog");
const outboxAge = new Gauge("pipeline_outbox_age_seconds");
const inFlight = new Gauge("pipeline_in_flight");
const successRate = new Gauge("pipeline_success_rate");
const deliveryLatency = new Trend("pipeline_delivery_latency_ms");

export const options = {
  scenarios: {
    ingest: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(10, Math.ceil(RATE / 4)),
      maxVUs: Math.max(50, RATE * 2),
      exec: "publish",
    },
    // A single observer VU sampling the pipeline's own metrics. Kept separate
    // so its polling never competes with the load for VUs, and so it keeps
    // running through the drain phase after ingestion stops.
    observe: {
      executor: "constant-vus",
      vus: 1,
      duration: `${parseDuration(DURATION) + 60}s`,
      exec: "observe",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    // The pipeline must not fall more than 30 seconds behind under this load.
    // A failure here is a capacity finding, not a flaky test.
    pipeline_oldest_due_age_seconds: ["value<30"],
  },
};

function parseDuration(value) {
  const match = /^(\d+)([smh])$/.exec(value);
  if (!match) return 90;
  const n = Number(match[1]);
  return match[2] === "h" ? n * 3600 : match[2] === "m" ? n * 60 : n;
}

export function setup() {
  if (!API_KEY) {
    fail("API_KEY is required. See the comment at the top of this file.");
  }
  if (!ADMIN_TOKEN) {
    fail("ADMIN_TOKEN is required to read /metrics/summary. See the comment above.");
  }
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    fail(`RelayForge is not reachable at ${BASE_URL}`);
  }
  return {};
}

export function publish() {
  const response = http.post(
    `${BASE_URL}/api/v1/events`,
    JSON.stringify({
      eventType: "order.created",
      payload: { orderId: `LOAD-${randomString(10)}`, amount: 1000 },
    }),
    {
      headers: { "Content-Type": "application/json", "X-API-Key": API_KEY },
      tags: { name: "POST /api/v1/events" },
    },
  );
  check(response, { accepted: (r) => r.status === 202 || r.status === 429 });
}

export function observe() {
  const response = http.get(`${BASE_URL}/api/v1/metrics/summary?window=1h`, {
    headers: { Authorization: `Bearer ${ADMIN_TOKEN}` },
    tags: { name: "GET /api/v1/metrics/summary" },
  });

  if (response.status === 200) {
    try {
      const body = response.json();
      dueBacklog.add(body.queue.dueBacklog);
      oldestDueAge.add(body.queue.oldestDueAgeSeconds);
      outboxBacklog.add(body.queue.unpublishedOutboxRecords);
      outboxAge.add(body.queue.oldestUnpublishedOutboxAgeSeconds);
      inFlight.add(body.inProgressDeliveries);
      successRate.add(body.successRate);
      deliveryLatency.add(body.p95LatencyMs);
    } catch {
      // A malformed sample is not worth failing the run over.
    }
  }
  sleep(2);
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat = "value") =>
    m[name] && typeof m[name].values[stat] === "number"
      ? m[name].values[stat].toFixed(2)
      : "n/a";

  const report = `
RelayForge — delivery pipeline load test
=========================================
Offered load      ${RATE} events/s for ${DURATION}
Target            ${BASE_URL}

Ingestion
  requests        ${m.http_reqs ? m.http_reqs.values.count : "n/a"}
  p95 latency     ${v("http_req_duration", "p(95)")} ms
  failed          ${v("http_req_failed", "rate")} (rate)

Pipeline depth (sampled every 2s, max observed)
  due backlog     ${v("pipeline_due_backlog", "max")}
  oldest due age  ${v("pipeline_oldest_due_age_seconds", "max")} s
  outbox backlog  ${v("pipeline_outbox_backlog", "max")}
  outbox age      ${v("pipeline_outbox_age_seconds", "max")} s
  in flight       ${v("pipeline_in_flight", "max")}

Delivery
  success rate    ${v("pipeline_success_rate", "value")}
  p95 attempt     ${v("pipeline_delivery_latency_ms", "avg")} ms (approximate)

Interpretation
  A backlog that grows and then drains after ingestion stops means the pipeline
  is keeping up but is behind. A backlog that keeps growing means it is not:
  add workers (docker compose up --scale worker=N) rather than restarting them.

NOTE: these figures describe this machine and this configuration. Quote them
only with that context.
`;

  return {
    stdout: report,
    "tests/k6/results/delivery-lag-summary.json": JSON.stringify(data, null, 2),
  };
}
