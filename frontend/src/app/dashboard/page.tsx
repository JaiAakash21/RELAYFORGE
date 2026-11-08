import type { Metadata } from "next";
import Link from "next/link";
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  CheckCircle2,
  Clock,
  Inbox,
  RefreshCw,
  Send,
  Webhook,
} from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Stat } from "@/components/ui/Stat";
import { Card, CardHeader } from "@/components/ui/Card";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { DeliveryStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip } from "@/components/ui/Code";
import { Tbody, Td, TableWrapper, Th, Thead, Tr } from "@/components/ui/Table";
import { ThroughputChart } from "@/components/charts/ThroughputChart";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import {
  formatCount,
  formatDuration,
  formatPercent,
  formatRelative,
} from "@/lib/format";
import type {
  Delivery,
  MetricsSummary,
  PageResponse,
  Timeseries,
} from "@/types/api";

export const metadata: Metadata = { title: "Overview" };

/**
 * The overview dashboard.
 *
 * <h2>Every number here is read from the backend</h2>
 *
 * There are no placeholder figures and no client-side arithmetic dressed up as a
 * measurement. A brand-new tenant sees zeros, which is correct and is the point:
 * a dashboard that shows impressive numbers before any event has been published
 * is showing a decoration, not a measurement.
 *
 * <h2>Three requests, in parallel</h2>
 *
 * The summary, the timeseries and the recent deliveries are independent, so they
 * are fetched with `Promise.all`. Awaiting them in sequence would make the page's
 * latency the sum of three round trips instead of the slowest one.
 */
export default async function OverviewPage() {
  let summary: MetricsSummary;
  let timeseries: Timeseries;
  let recent: PageResponse<Delivery>;

  try {
    [summary, timeseries, recent] = await Promise.all([
      apiGet<MetricsSummary>("/api/v1/metrics/summary?window=24h"),
      apiGet<Timeseries>("/api/v1/metrics/deliveries?window=24h"),
      apiGet<PageResponse<Delivery>>("/api/v1/deliveries?size=8"),
    ]);
  } catch (error) {
    const message =
      error instanceof ApiRequestError
        ? error.message
        : "The API could not be reached.";
    const traceId = error instanceof ApiRequestError ? error.payload.traceId : null;
    return (
      <>
        <PageHeader title="Overview" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load the dashboard"
            message={message}
            traceId={traceId}
          />
        </Card>
      </>
    );
  }

  const hasTraffic = summary.totalEvents > 0;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Overview"
        description={`Delivery health across your organization. Figures cover the last ${summary.window} and are computed from stored events and attempts.`}
        actions={
          <Link
            href="/dashboard/playground"
            className="inline-flex h-9 items-center gap-2 rounded border border-border bg-surface-2 px-3 text-sm text-content transition-colors hover:border-border-strong hover:bg-surface-3"
          >
            <Activity className="h-3.5 w-3.5 text-accent-hover" aria-hidden />
            Open the Playground
          </Link>
        }
      />

      {!hasTraffic ? <GettingStarted /> : null}

      {/* Primary row: the four figures someone checks first. */}
      <section aria-label="Key metrics" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Events published"
          value={formatCount(summary.totalEvents)}
          icon={<Send className="h-3.5 w-3.5" aria-hidden />}
          hint={`${formatCount(summary.eventsInWindow)} in the last ${summary.window}`}
        />
        <Stat
          label="Success rate"
          value={formatPercent(summary.successRate)}
          tone={
            summary.totalDeliveries === 0
              ? "default"
              : summary.successRate >= 0.99
                ? "success"
                : summary.successRate >= 0.9
                  ? "warning"
                  : "danger"
          }
          icon={<CheckCircle2 className="h-3.5 w-3.5" aria-hidden />}
          hint={
            summary.totalDeliveries === 0
              ? "No deliveries yet"
              : `${formatCount(summary.successfulDeliveries)} of ${formatCount(
                  summary.successfulDeliveries + summary.failedDeliveries,
                )} resolved deliveries succeeded`
          }
        />
        <Stat
          label="p95 latency"
          value={formatDuration(summary.p95LatencyMs)}
          icon={<Clock className="h-3.5 w-3.5" aria-hidden />}
          // Stated, not implied. The percentile comes from histogram buckets.
          hint={
            summary.approximateLatency
              ? `Approximate — interpolated from latency buckets. Mean ${formatDuration(summary.avgLatencyMs)}, p99 ${formatDuration(summary.p99LatencyMs)}.`
              : undefined
          }
        />
        <Stat
          label="Dead letters"
          value={formatCount(summary.deadLetterCount)}
          tone={summary.deadLetterCount > 0 ? "danger" : "success"}
          icon={<Inbox className="h-3.5 w-3.5" aria-hidden />}
          hint={
            summary.deadLetterCount > 0
              ? "Deliveries that exhausted their attempts. Replayable with their history intact."
              : "Nothing has exhausted its attempt budget."
          }
        />
      </section>

      {/* Secondary row: pipeline internals. */}
      <section aria-label="Pipeline state" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="In flight"
          value={formatCount(summary.pendingDeliveries + summary.inProgressDeliveries)}
          icon={<Activity className="h-3.5 w-3.5" aria-hidden />}
          hint={`${formatCount(summary.inProgressDeliveries)} leased by a worker right now`}
        />
        <Stat
          label="Retries"
          value={formatCount(summary.retriesInWindow)}
          tone={summary.retriesInWindow > 0 ? "warning" : "default"}
          icon={<RefreshCw className="h-3.5 w-3.5" aria-hidden />}
          hint={`Attempts beyond the first in the last ${summary.window}. ${formatCount(summary.replaysInWindow)} manual replays.`}
        />
        <Stat
          label="Queue lag"
          value={
            summary.queue.oldestDueAgeSeconds < 1
              ? "0"
              : summary.queue.oldestDueAgeSeconds.toFixed(1)
          }
          unit="s"
          tone={summary.queue.oldestDueAgeSeconds > 30 ? "warning" : "default"}
          icon={<Clock className="h-3.5 w-3.5" aria-hidden />}
          hint="How long the oldest ready delivery has been waiting. This, not Kafka consumer lag, is what a user experiences."
        />
        <Stat
          label="Active endpoints"
          value={formatCount(summary.activeEndpoints)}
          icon={<Webhook className="h-3.5 w-3.5" aria-hidden />}
          hint={
            summary.queue.unpublishedOutboxRecords > 0
              ? `${formatCount(summary.queue.unpublishedOutboxRecords)} outbox records awaiting relay`
              : "Outbox is fully drained"
          }
        />
      </section>

      {summary.queue.oldestUnpublishedOutboxAgeSeconds > 30 ? (
        <OutboxWarning seconds={summary.queue.oldestUnpublishedOutboxAgeSeconds} />
      ) : null}

      <Card>
        <CardHeader
          title="Delivery throughput"
          description={`Attempts and outcomes over the last ${timeseries.window}, in ${timeseries.bucketMinutes}-minute buckets.`}
        />
        <div className="p-4">
          {timeseries.points.length === 0 ? (
            <EmptyState
              title="No attempts recorded yet"
              description="Publish an event, or run a Reliability Playground scenario, and this chart will fill from the recorded attempts."
            />
          ) : (
            <ThroughputChart points={timeseries.points} />
          )}
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Recent deliveries"
          actions={
            <Link
              href="/dashboard/deliveries"
              className="inline-flex items-center gap-1 text-xs text-accent-hover hover:underline"
            >
              View all
              <ArrowUpRight className="h-3 w-3" aria-hidden />
            </Link>
          }
        />
        {recent.items.length === 0 ? (
          <EmptyState
            title="No deliveries yet"
            description="A delivery is created for each endpoint that subscribes to an event you publish."
          />
        ) : (
          <TableWrapper>
            <Thead>
              <Tr>
                <Th>Event</Th>
                <Th>Endpoint</Th>
                <Th>Status</Th>
                <Th align="right">Attempts</Th>
                <Th align="right">Created</Th>
                <Th />
              </Tr>
            </Thead>
            <Tbody>
              {recent.items.map((delivery) => (
                <Tr key={delivery.id}>
                  <Td>
                    <span className="rf-mono text-content">
                      {delivery.eventType ?? "—"}
                    </span>
                  </Td>
                  <Td className="max-w-[18rem]">
                    <span className="block truncate text-content-secondary">
                      {delivery.endpointName ?? <IdChip value={delivery.endpointId} />}
                    </span>
                  </Td>
                  <Td>
                    <DeliveryStatusBadge status={delivery.status} live />
                  </Td>
                  <Td align="right" className="tabular">
                    {delivery.attemptCount}/{delivery.maxAttempts}
                  </Td>
                  <Td align="right" className="whitespace-nowrap">
                    {formatRelative(delivery.createdAt)}
                  </Td>
                  <Td align="right">
                    <Link
                      href={`/dashboard/deliveries/${delivery.id}`}
                      className="text-xs text-accent-hover hover:underline"
                    >
                      Inspect
                    </Link>
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </TableWrapper>
        )}
      </Card>
    </div>
  );
}

/** Shown only while the tenant has published nothing. */
function GettingStarted() {
  const steps = [
    {
      title: "Create an API key",
      body: "The credential your servers use to publish events. Shown once, stored only as a hash.",
      href: "/dashboard/api-keys",
      cta: "Create a key",
    },
    {
      title: "Register an endpoint",
      body: "A destination URL and the event types it subscribes to. You get a signing secret to verify requests with.",
      href: "/dashboard/endpoints",
      cta: "Add an endpoint",
    },
    {
      title: "Break it on purpose",
      body: "The Reliability Playground injects real failures into the real pipeline so you can watch retries, backoff and dead-lettering happen.",
      href: "/dashboard/playground",
      cta: "Open the Playground",
    },
  ];

  return (
    <Card className="border-accent-muted bg-accent-subtle/25">
      <CardHeader
        title="Get started"
        description="Your organization has no events yet. Three steps to a working pipeline."
      />
      <ol className="grid gap-px bg-border sm:grid-cols-3">
        {steps.map((step, index) => (
          <li key={step.title} className="bg-surface p-4">
            <span className="flex h-6 w-6 items-center justify-center rounded-full bg-accent-subtle text-2xs font-semibold text-accent-hover">
              {index + 1}
            </span>
            <h3 className="mt-2.5 text-base font-medium text-content">{step.title}</h3>
            <p className="mt-1 text-sm leading-relaxed text-content-secondary">
              {step.body}
            </p>
            <Link
              href={step.href}
              className="mt-3 inline-flex items-center gap-1 text-xs text-accent-hover hover:underline"
            >
              {step.cta}
              <ArrowUpRight className="h-3 w-3" aria-hidden />
            </Link>
          </li>
        ))}
      </ol>
    </Card>
  );
}

/**
 * Surfaces a stalled outbox.
 *
 * Worth its own banner because it is the one failure the rest of the dashboard
 * would not reveal: ingestion keeps returning 202 and the event counts keep
 * rising, while nothing is actually being relayed to Kafka. Without this, the
 * dashboard would look healthy during a total delivery outage.
 */
function OutboxWarning({ seconds }: { seconds: number }) {
  return (
    <div
      role="alert"
      className="rf-card flex items-start gap-3 border-status-retry-border bg-status-retry-bg/40 p-4"
    >
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-status-retry" aria-hidden />
      <div>
        <p className="text-base font-medium text-content">The outbox is not draining</p>
        <p className="mt-1 text-sm leading-relaxed text-content-secondary">
          The oldest unpublished outbox record is {Math.round(seconds)}s old. Events are
          still being accepted and stored — nothing is lost — but they are not reaching
          the delivery workers. The usual cause is an unreachable Kafka broker or a
          stopped worker process. Relay resumes automatically once it recovers.
        </p>
      </div>
    </div>
  );
}
