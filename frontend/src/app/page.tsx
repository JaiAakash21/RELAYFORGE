import Link from "next/link";
import {
  ArrowRight,
  BookOpen,
  Database,
  FlaskConical,
  Github,
  Lock,
  RefreshCw,
  Shield,
  Timer,
  Workflow,
} from "lucide-react";
import { LogoMark } from "@/components/layout/AppShell";
import { CodeBlock } from "@/components/ui/Code";
import { DashboardPreview } from "@/components/layout/DashboardPreview";
import { ArchitectureDiagram } from "@/components/layout/ArchitectureDiagram";

const githubUrl =
  process.env.NEXT_PUBLIC_GITHUB_URL ?? "https://github.com/JaiAakash21/RELAYFORGE";

/**
 * The landing page.
 *
 * Statically rendered: it has no per-request data, so it is prerendered at build
 * time and served as HTML. That is what makes the first paint fast enough to
 * matter, and it is the reason this page does not import the API client.
 */
export default function LandingPage() {
  return (
    <div className="min-h-screen bg-canvas">
      <SiteHeader />
      <main id="main">
        <Hero />
        <ReliabilitySection />
        <FeatureGrid />
        <ArchitectureSection />
        <PlaygroundSection />
        <StackSection />
        <ClosingCta />
      </main>
      <SiteFooter />
    </div>
  );
}

function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-canvas/85 backdrop-blur">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
        <Link href="/" className="flex items-center gap-2.5">
          <LogoMark />
          <span className="text-md font-semibold tracking-tight text-content">
            RelayForge
          </span>
        </Link>
        <nav aria-label="Site" className="flex items-center gap-1 sm:gap-2">
          <a
            href="/swagger-ui.html"
            className="hidden rounded px-2.5 py-1.5 text-sm text-content-secondary transition-colors hover:bg-surface-2 hover:text-content sm:block"
          >
            API docs
          </a>
          <a
            href={githubUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1.5 rounded px-2.5 py-1.5 text-sm text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
          >
            <Github className="h-3.5 w-3.5" aria-hidden />
            <span className="hidden sm:inline">GitHub</span>
          </a>
          <Link
            href="/login"
            className="rounded px-2.5 py-1.5 text-sm text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
          >
            Sign in
          </Link>
          <Link
            href="/register"
            className="inline-flex h-8 items-center rounded bg-accent px-3 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover"
          >
            Get started
          </Link>
        </nav>
      </div>
    </header>
  );
}

function Hero() {
  return (
    <section className="relative overflow-hidden border-b border-border">
      <div aria-hidden className="rf-grid-bg absolute inset-0 opacity-50" />
      {/* A single soft radial behind the headline. One light source, low opacity —
          enough to lift the text off the canvas without turning into a gradient
          wash. */}
      <div
        aria-hidden
        className="absolute left-1/2 top-0 h-[32rem] w-[52rem] -translate-x-1/2 -translate-y-1/3 rounded-full bg-accent/[0.07] blur-3xl"
      />
      <div className="relative mx-auto w-full max-w-6xl px-4 py-20 sm:px-6 sm:py-28">
        <div className="mx-auto max-w-3xl text-center">
          <span className="inline-flex items-center gap-2 rounded-full border border-border bg-surface px-3 py-1 text-2xs text-content-secondary">
            <span
              aria-hidden
              className="h-1.5 w-1.5 animate-pulse-dot rounded-full bg-status-success"
            />
            Transactional outbox · Kafka workers · at-least-once delivery
          </span>

          <h1 className="mt-6 text-balance text-3xl font-semibold tracking-tight text-content sm:text-4xl lg:text-5xl">
            Reliable event delivery for applications that{" "}
            <span className="text-accent-hover">cannot afford to lose events</span>
          </h1>

          <p className="mx-auto mt-5 max-w-2xl text-pretty text-md leading-relaxed text-content-secondary">
            RelayForge accepts your events in a single database transaction, delivers
            them to signed webhook endpoints through Kafka-backed workers, retries
            failures with exponential backoff and jitter, and keeps every attempt as
            evidence — so a delivery that fails is something you can inspect and replay,
            not something you discover from a customer.
          </p>

          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Link
              href="/register"
              className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-accent px-6 text-md font-medium text-accent-fg transition-colors hover:bg-accent-hover sm:w-auto"
            >
              Open the dashboard
              <ArrowRight className="h-4 w-4" aria-hidden />
            </Link>
            <Link
              href="/register?next=/dashboard/playground"
              className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-md border border-border bg-surface px-6 text-md font-medium text-content transition-colors hover:border-border-strong hover:bg-surface-2 sm:w-auto"
            >
              <FlaskConical className="h-4 w-4 text-accent-hover" aria-hidden />
              Try the Reliability Playground
            </Link>
          </div>

          <p className="mt-4 text-xs text-content-tertiary">
            Runs entirely on your machine with <code className="rf-mono">docker compose up</code>.
          </p>
        </div>

        <div className="mt-14">
          <DashboardPreview />
        </div>
      </div>
    </section>
  );
}

/**
 * The reliability explanation.
 *
 * Deliberately leads with the failure modes rather than the features. A webhook
 * platform is only interesting because of what it does when things go wrong, and
 * stating the problems first is what makes the mechanisms below read as answers
 * instead of as a list of technologies.
 */
function ReliabilitySection() {
  const problems = [
    {
      problem: "Your database commits, then the broker is unreachable.",
      consequence: "The event is accepted and silently never delivered.",
      answer:
        "Events, deliveries and outbox rows are written in one transaction. Nothing is published to Kafka during the request, so “accepted” and “queued for relay” are the same atomic fact.",
      icon: <Database className="h-4 w-4" aria-hidden />,
    },
    {
      problem: "A receiver returns 500 for ninety seconds.",
      consequence:
        "Retrying at a fixed interval adds exactly the load that is causing the failure.",
      answer:
        "Exponential backoff with symmetric jitter. The delay doubles, and randomisation stops a batch of failed deliveries from retrying in lockstep and re-killing a recovering receiver.",
      icon: <Timer className="h-4 w-4" aria-hidden />,
    },
    {
      problem: "A worker is OOM-killed mid-delivery.",
      consequence: "The delivery is stuck IN_PROGRESS forever, and nobody notices.",
      answer:
        "Workers hold a lease they must keep renewing. An expired lease is positive evidence the holder is gone, and a reconciler returns the delivery to the schedule.",
      icon: <RefreshCw className="h-4 w-4" aria-hidden />,
    },
    {
      problem: "Kafka delivers the same message twice.",
      consequence: "The webhook is sent twice, and the receiver charges a card twice.",
      answer:
        "Processing is gated on a conditional UPDATE against the live delivery row. A duplicate message costs one no-op statement — which is how at-least-once messaging becomes effectively-once processing.",
      icon: <Workflow className="h-4 w-4" aria-hidden />,
    },
  ];

  return (
    <section className="border-b border-border">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 sm:py-20">
        <div className="max-w-2xl">
          <p className="rf-label">Reliability</p>
          <h2 className="mt-2 text-2xl font-semibold tracking-tight text-content">
            Four ways a webhook pipeline loses events, and what RelayForge does
            about each
          </h2>
        </div>

        <div className="mt-10 grid gap-4 sm:grid-cols-2">
          {problems.map((item) => (
            <article key={item.problem} className="rf-card p-5">
              <div className="flex items-center gap-2.5">
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded border border-status-failed-border bg-status-failed-bg text-status-failed">
                  {item.icon}
                </span>
                <h3 className="text-base font-medium text-content">{item.problem}</h3>
              </div>
              <p className="mt-3 text-sm leading-relaxed text-status-failed/90">
                {item.consequence}
              </p>
              <p className="mt-3 text-sm leading-relaxed text-content-secondary">
                {item.answer}
              </p>
            </article>
          ))}
        </div>

        <div className="rf-card mt-6 border-status-retry-border bg-status-retry-bg/30 p-5">
          <h3 className="flex items-center gap-2 text-base font-medium text-content">
            <Shield className="h-4 w-4 text-status-retry" aria-hidden />
            What RelayForge does not claim
          </h3>
          <p className="mt-2 max-w-3xl text-sm leading-relaxed text-content-secondary">
            Delivery is <strong className="text-content">at-least-once</strong>, not
            exactly-once. If a worker sends a request and dies before the response is
            recorded, there is no way to know whether the receiver acted on it — that
            information was in the response that was lost. RelayForge records the
            uncertainty honestly, counts the attempt, and sends a stable{" "}
            <code className="rf-mono">X-RelayForge-Event-Id</code> on every request so
            receivers can deduplicate. Any platform promising exactly-once delivery of
            an external HTTP side effect is promising something the network cannot
            provide.
          </p>
        </div>
      </div>
    </section>
  );
}

function FeatureGrid() {
  const features = [
    {
      title: "Transactional outbox",
      body: "Event, deliveries and outbox rows commit together. A separate publisher relays to Kafka and marks rows published only after the broker acknowledges them.",
    },
    {
      title: "Database-scheduled retries",
      body: "next_attempt_at lives on the delivery row, so a retry is queryable, displayable and correctable. Kafka distributes work; PostgreSQL remembers what is owed.",
    },
    {
      title: "HMAC-signed requests",
      body: "HMAC-SHA256 over the timestamp and the exact body sent, so a captured request cannot be replayed. Both secrets are signed during a rotation window.",
    },
    {
      title: "Dead-letter queue and replay",
      body: "An exhausted delivery keeps its whole attempt history. Replay appends attempt #4 after the three failures rather than erasing the evidence.",
    },
    {
      title: "SSRF-guarded egress",
      body: "Every resolved address is checked against loopback, private, link-local and cloud-metadata ranges, and the HTTP client is pinned to the addresses that passed.",
    },
    {
      title: "Observability that means something",
      body: "Prometheus metrics with deliberately bounded label cardinality, OpenTelemetry traces from ingestion to outbound request, and queue lag measured as what a user actually waits.",
    },
  ];

  return (
    <section className="border-b border-border bg-surface/30">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 sm:py-20">
        <div className="max-w-2xl">
          <p className="rf-label">Platform</p>
          <h2 className="mt-2 text-2xl font-semibold tracking-tight text-content">
            What is actually implemented
          </h2>
        </div>
        <div className="mt-10 grid gap-px overflow-hidden rounded-lg border border-border bg-border sm:grid-cols-2 lg:grid-cols-3">
          {features.map((feature) => (
            <div key={feature.title} className="bg-surface p-5">
              <h3 className="text-base font-medium text-content">{feature.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-content-secondary">
                {feature.body}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function ArchitectureSection() {
  return (
    <section className="border-b border-border">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 sm:py-20">
        <div className="max-w-2xl">
          <p className="rf-label">Architecture</p>
          <h2 className="mt-2 text-2xl font-semibold tracking-tight text-content">
            PostgreSQL is the source of truth. Kafka distributes the work.
          </h2>
          <p className="mt-3 text-sm leading-relaxed text-content-secondary">
            The distinction is the whole design. Kafka decides which worker picks up a
            delivery; it never decides whether that delivery still needs doing. That is
            what lets the pipeline survive a lost message, a dead worker or a duplicate
            without losing or double-counting an event.
          </p>
        </div>

        <div className="mt-10">
          <ArchitectureDiagram />
        </div>

        <div className="mt-8 grid gap-4 lg:grid-cols-2">
          <div>
            <h3 className="text-base font-medium text-content">Publish an event</h3>
            <p className="mt-1.5 text-sm leading-relaxed text-content-secondary">
              One call, authenticated with an API key. It returns as soon as the
              transaction commits — it does not wait for your receivers.
            </p>
            <CodeBlock
              className="mt-3"
              language="bash"
              code={`curl -X POST https://api.example.com/api/v1/events \\
  -H "X-API-Key: rf_live_xxxxxxxx_..." \\
  -H "Idempotency-Key: order-1001-created" \\
  -H "Content-Type: application/json" \\
  -d '{
    "eventType": "order.created",
    "payload": { "orderId": "ORD-1001", "amount": 2499 }
  }'`}
            />
          </div>
          <div>
            <h3 className="text-base font-medium text-content">Verify the signature</h3>
            <p className="mt-1.5 text-sm leading-relaxed text-content-secondary">
              Compare in constant time, and reject timestamps outside a tolerance window
              — otherwise a captured request stays replayable forever.
            </p>
            <CodeBlock
              className="mt-3"
              language="javascript"
              code={`import { createHmac, timingSafeEqual } from "node:crypto";

export function verify(secret, header, rawBody, toleranceSec = 300) {
  const parts = Object.fromEntries(
    header.split(",").map((p) => p.trim().split("=")),
  );
  const timestamp = Number(parts.t);

  // Reject anything outside the window: without this check a
  // captured request can be replayed indefinitely.
  if (Math.abs(Date.now() / 1000 - timestamp) > toleranceSec) {
    return false;
  }

  const expected = createHmac("sha256", secret)
    .update(\`\${timestamp}.\`)
    .update(rawBody)   // the exact bytes received, not re-serialised JSON
    .digest();

  // Constant-time: a byte-by-byte early return leaks the signature.
  return timingSafeEqual(expected, Buffer.from(parts.v1, "hex"));
}`}
            />
          </div>
        </div>
      </div>
    </section>
  );
}

function PlaygroundSection() {
  const scenarios = [
    "Transient 500s, then recovery",
    "HTTP 429 with Retry-After",
    "Slow receiver past the timeout",
    "Permanently broken endpoint",
    "Dropped connection, no status",
    "Outage, then recovery",
  ];

  return (
    <section className="relative overflow-hidden border-b border-border bg-surface/30">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 sm:py-20">
        <div className="grid items-center gap-10 lg:grid-cols-2">
          <div>
            <p className="rf-label">Signature feature</p>
            <h2 className="mt-2 text-2xl font-semibold tracking-tight text-content">
              The Reliability Playground
            </h2>
            <p className="mt-3 text-sm leading-relaxed text-content-secondary">
              Pick a failure, configure it, publish an event, and watch the pipeline
              handle it. The playground points a real endpoint at a controlled simulator
              and publishes a real event through the ordinary workers.
            </p>
            <p className="mt-3 text-sm leading-relaxed text-content-secondary">
              Nothing about it is simulated in the browser. The timeline you watch is a
              read of the <code className="rf-mono">delivery_attempts</code> table — so
              if the retry engine were broken, the playground would show you that rather
              than hide it.
            </p>
            <ul className="mt-5 grid gap-2 sm:grid-cols-2">
              {scenarios.map((scenario) => (
                <li
                  key={scenario}
                  className="flex items-start gap-2 text-sm text-content-secondary"
                >
                  <span
                    aria-hidden
                    className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-accent"
                  />
                  {scenario}
                </li>
              ))}
            </ul>
            <Link
              href="/register?next=/dashboard/playground"
              className="mt-7 inline-flex h-10 items-center gap-2 rounded-md bg-accent px-5 text-base font-medium text-accent-fg transition-colors hover:bg-accent-hover"
            >
              <FlaskConical className="h-4 w-4" aria-hidden />
              Run a scenario
            </Link>
          </div>

          <PlaygroundTeaser />
        </div>
      </div>
    </section>
  );
}

/**
 * A static illustration of a playground run.
 *
 * Clearly labelled as an illustration. It shows the shape of the timeline using
 * a representative run, and the real thing is one click away — presenting
 * invented numbers as live data would be exactly the dishonesty this project is
 * trying to avoid.
 */
function PlaygroundTeaser() {
  const attempts = [
    { n: 1, label: "HTTP 500", tone: "retry", detail: "Retryable · 42 ms" },
    { n: 2, label: "HTTP 500", tone: "retry", detail: "Retryable · 38 ms · +5.2 s" },
    { n: 3, label: "HTTP 200", tone: "success", detail: "Delivered · 51 ms · +10.4 s" },
  ];
  return (
    <div className="rf-card overflow-hidden">
      <div className="flex items-center justify-between border-b border-border-subtle bg-surface-2 px-4 py-2.5">
        <span className="text-xs font-medium text-content">
          Transient 500s, then recovery
        </span>
        <span className="rounded-full border border-border bg-surface-3 px-2 py-0.5 text-2xs text-content-tertiary">
          Illustration
        </span>
      </div>
      <ol className="divide-y divide-border-subtle">
        {attempts.map((attempt) => (
          <li key={attempt.n} className="flex items-center gap-3 px-4 py-3">
            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-border bg-surface-2 text-2xs font-medium text-content-secondary">
              {attempt.n}
            </span>
            <span
              className={
                attempt.tone === "success"
                  ? "rounded-full border border-status-success-border bg-status-success-bg px-2 py-0.5 font-mono text-2xs text-status-success"
                  : "rounded-full border border-status-retry-border bg-status-retry-bg px-2 py-0.5 font-mono text-2xs text-status-retry"
              }
            >
              {attempt.label}
            </span>
            <span className="text-xs text-content-tertiary">{attempt.detail}</span>
          </li>
        ))}
      </ol>
      <div className="border-t border-border-subtle bg-surface-2 px-4 py-2.5 text-xs text-content-secondary">
        Delivered on attempt 3. The gap roughly doubles each time — that is the
        exponential backoff, with jitter applied.
      </div>
    </div>
  );
}

function StackSection() {
  const groups = [
    {
      label: "Backend",
      items: [
        "Java 21",
        "Spring Boot 3.5",
        "Spring Security",
        "Spring Data JPA",
        "Spring Kafka",
        "Flyway",
      ],
    },
    {
      label: "Data & messaging",
      items: ["PostgreSQL 16", "Apache Kafka (KRaft)", "Redis 7"],
    },
    {
      label: "Frontend",
      items: ["Next.js 15", "React 19", "TypeScript", "Tailwind CSS", "Recharts"],
    },
    {
      label: "Observability",
      items: ["Micrometer", "Prometheus", "Grafana", "OpenTelemetry", "Tempo"],
    },
    {
      label: "Testing",
      items: ["JUnit 5", "Testcontainers", "WireMock", "Playwright", "k6"],
    },
    { label: "Infrastructure", items: ["Docker", "Docker Compose", "GitHub Actions"] },
  ];

  return (
    <section className="border-b border-border">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 sm:px-6 sm:py-20">
        <p className="rf-label">Technology</p>
        <h2 className="mt-2 text-2xl font-semibold tracking-tight text-content">
          Built with
        </h2>
        <div className="mt-8 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {groups.map((group) => (
            <div key={group.label}>
              <h3 className="rf-label">{group.label}</h3>
              <ul className="mt-2.5 flex flex-wrap gap-1.5">
                {group.items.map((item) => (
                  <li
                    key={item}
                    className="rounded border border-border bg-surface px-2 py-1 text-xs text-content-secondary"
                  >
                    {item}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function ClosingCta() {
  return (
    <section className="border-b border-border">
      <div className="mx-auto w-full max-w-6xl px-4 py-16 text-center sm:px-6">
        <h2 className="text-2xl font-semibold tracking-tight text-content">
          See it handle a failure
        </h2>
        <p className="mx-auto mt-3 max-w-xl text-sm leading-relaxed text-content-secondary">
          Create an account, open the Reliability Playground, and break a receiver on
          purpose. Everything you see afterwards is the backend&apos;s own record of
          what it did.
        </p>
        <div className="mt-7 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <Link
            href="/register"
            className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-md bg-accent px-6 text-md font-medium text-accent-fg transition-colors hover:bg-accent-hover sm:w-auto"
          >
            Create an account
            <ArrowRight className="h-4 w-4" aria-hidden />
          </Link>
          <a
            href={githubUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-md border border-border bg-surface px-6 text-md font-medium text-content transition-colors hover:border-border-strong hover:bg-surface-2 sm:w-auto"
          >
            <Github className="h-4 w-4" aria-hidden />
            Read the source
          </a>
        </div>
      </div>
    </section>
  );
}

function SiteFooter() {
  return (
    <footer className="mx-auto w-full max-w-6xl px-4 py-10 sm:px-6">
      <div className="flex flex-col items-start justify-between gap-5 sm:flex-row sm:items-center">
        <div className="flex items-center gap-2.5">
          <LogoMark className="h-5 w-5" />
          <span className="text-sm text-content-secondary">
            RelayForge — reliable webhook delivery infrastructure
          </span>
        </div>
        <nav aria-label="Footer" className="flex flex-wrap items-center gap-4">
          <a
            href="/swagger-ui.html"
            className="inline-flex items-center gap-1.5 text-sm text-content-tertiary transition-colors hover:text-content"
          >
            <BookOpen className="h-3.5 w-3.5" aria-hidden />
            API reference
          </a>
          <a
            href={`${githubUrl}/tree/main/docs`}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1.5 text-sm text-content-tertiary transition-colors hover:text-content"
          >
            <Lock className="h-3.5 w-3.5" aria-hidden />
            Engineering docs
          </a>
          <a
            href={githubUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1.5 text-sm text-content-tertiary transition-colors hover:text-content"
          >
            <Github className="h-3.5 w-3.5" aria-hidden />
            GitHub
          </a>
        </nav>
      </div>
      <p className="mt-6 text-xs leading-relaxed text-content-tertiary">
        An independently designed and implemented demonstration of webhook delivery
        infrastructure, built to be explained and defended rather than merely shown.
        Not affiliated with any commercial webhook provider.
      </p>
    </footer>
  );
}
