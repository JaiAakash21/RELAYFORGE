import type { Metadata } from "next";
import { AlertTriangle, Database, Gauge, Layers } from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card, CardHeader } from "@/components/ui/Card";
import { Stat } from "@/components/ui/Stat";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { HealthBadge } from "@/components/ui/StatusBadge";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { ThroughputChart } from "@/components/charts/ThroughputChart";
import {
  EndpointPerformanceChart,
  LatencyChart,
} from "@/components/charts/LatencyChart";
import { WindowPicker } from "./WindowPicker";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { formatCount, formatDuration, formatPercent } from "@/lib/format";
import type { EndpointPerformance, MetricsSummary, Timeseries } from "@/types/api";

export const metadata: Metadata = { title: "Observability" };

const ALLOWED_WINDOWS = new Set(["1h", "6h", "24h", "7d", "30d"]);

/**
 * Tenant-facing observability.
 *
 * <h2>Two metrics systems, and why this is one of them</h2>
 *
 * This page answers per-tenant questions and reads from PostgreSQL. The
 * operational metrics — queue depth, worker health, label-bounded counters — go
 * to Prometheus and are viewed in Grafana. They are not duplicates: putting
 * per-tenant figures into Prometheus would need a `tenant_id` label, and the
 * series count would then grow with the customer base, which is the one number a
 * platform wants to grow.
 */
export default async function ObservabilityPage({
  searchParams,
}: {
  searchParams: Promise<{ window?: string }>;
}) {
  const params = await searchParams;
  // Validated against an allowlist: the API enumerates its windows, and passing
  // an arbitrary duration through would let the page request a decade-wide
  // aggregate.
  const window = ALLOWED_WINDOWS.has(params.window ?? "") ? params.window! : "24h";

  let summary: MetricsSummary;
  let timeseries: Timeseries;
  let endpoints: EndpointPerformance[];
  try {
    [summary, timeseries, endpoints] = await Promise.all([
      apiGet<MetricsSummary>(`/api/v1/metrics/summary?window=${window}`),
      apiGet<Timeseries>(`/api/v1/metrics/deliveries?window=${window}`),
      apiGet<EndpointPerformance[]>("/api/v1/metrics/endpoints"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Observability" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load metrics"
            message={
              error instanceof ApiRequestError
                ? error.message
                : "The API could not be reached."
            }
            traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
          />
        </Card>
      </>
    );
  }

  const hasData = timeseries.points.length > 0;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Observability"
        description="Delivery performance for your organization, computed from stored attempts. Infrastructure-level metrics are exported to Prometheus and visualised in Grafana."
        actions={<WindowPicker current={window} />}
      />

      <section aria-label="Latency" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Attempts"
          value={formatCount(summary.attemptsInWindow)}
          icon={<Layers className="h-3.5 w-3.5" aria-hidden />}
          hint={`Outbound HTTP requests in the last ${summary.window}, including retries.`}
        />
        <Stat
          label="p50 latency"
          value={formatDuration(summary.p50LatencyMs)}
          icon={<Gauge className="h-3.5 w-3.5" aria-hidden />}
          hint="Approximate — interpolated from fixed latency buckets."
        />
        <Stat
          label="p95 latency"
          value={formatDuration(summary.p95LatencyMs)}
          icon={<Gauge className="h-3.5 w-3.5" aria-hidden />}
          hint="Approximate. Exact percentiles would need every raw observation in the window, which is the scan the rollup exists to avoid."
        />
        <Stat
          label="p99 latency"
          value={formatDuration(summary.p99LatencyMs)}
          icon={<Gauge className="h-3.5 w-3.5" aria-hidden />}
          hint={`Approximate. Mean ${formatDuration(summary.avgLatencyMs)}.`}
        />
      </section>

      <Card>
        <CardHeader
          title="Throughput and outcomes"
          description={`Attempts per ${timeseries.bucketMinutes}-minute bucket, split by outcome. Height is volume; the amber and red bands are failures.`}
        />
        <div className="p-4">
          {hasData ? (
            <ThroughputChart points={timeseries.points} />
          ) : (
            <EmptyState
              title="No attempts in this window"
              description="Publish an event or run a Playground scenario, then widen the window."
            />
          )}
        </div>
      </Card>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader
            title="Attempt duration"
            description="Mean and peak wall-clock time per outbound request."
          />
          <div className="p-4">
            {hasData ? (
              <LatencyChart points={timeseries.points} />
            ) : (
              <EmptyState title="No data in this window" />
            )}
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Retry volume"
            description="How much work the retry engine is doing. A rising retry count with a flat success rate means receivers are struggling but recovering."
          />
          <div className="grid gap-3 p-4 sm:grid-cols-2">
            <Stat
              label="Retries"
              value={formatCount(summary.retriesInWindow)}
              tone={summary.retriesInWindow > 0 ? "warning" : "default"}
              hint="Attempts beyond the first."
            />
            <Stat
              label="Manual replays"
              value={formatCount(summary.replaysInWindow)}
              tone={summary.replaysInWindow > 0 ? "accent" : "default"}
              hint="Operator-initiated replays of dead letters."
            />
            <Stat
              label="Success rate"
              value={formatPercent(summary.successRate)}
              tone={
                summary.totalDeliveries === 0
                  ? "default"
                  : summary.successRate >= 0.99
                    ? "success"
                    : "warning"
              }
            />
            <Stat
              label="Dead letters"
              value={formatCount(summary.deadLetterCount)}
              tone={summary.deadLetterCount > 0 ? "danger" : "success"}
            />
          </div>
        </Card>
      </div>

      <Card>
        <CardHeader
          title="Pipeline health"
          description="The internals: how much work is waiting, and whether the outbox is draining."
        />
        <div className="grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-5">
          <Stat
            label="Due backlog"
            value={formatCount(summary.queue.dueBacklog)}
            tone={summary.queue.dueBacklog > 100 ? "warning" : "default"}
            hint="Deliveries that are due now and not yet being attempted."
          />
          <Stat
            label="Oldest due"
            value={
              summary.queue.oldestDueAgeSeconds < 1
                ? "0"
                : summary.queue.oldestDueAgeSeconds.toFixed(1)
            }
            unit="s"
            tone={summary.queue.oldestDueAgeSeconds > 30 ? "warning" : "default"}
            hint="The real lag signal: how long a user's oldest ready delivery has been waiting."
          />
          <Stat
            label="Leased by workers"
            value={formatCount(summary.inProgressDeliveries)}
            hint="Deliveries a worker currently holds a lease on."
          />
          <Stat
            label="Outbox backlog"
            value={formatCount(summary.queue.unpublishedOutboxRecords)}
            tone={summary.queue.unpublishedOutboxRecords > 0 ? "warning" : "success"}
            icon={<Database className="h-3.5 w-3.5" aria-hidden />}
            hint="Rows committed but not yet relayed to Kafka."
          />
          <Stat
            label="Oldest outbox row"
            value={
              summary.queue.oldestUnpublishedOutboxAgeSeconds < 1
                ? "0"
                : summary.queue.oldestUnpublishedOutboxAgeSeconds.toFixed(1)
            }
            unit="s"
            tone={
              summary.queue.oldestUnpublishedOutboxAgeSeconds > 30 ? "danger" : "default"
            }
            hint="Sustained growth here means Kafka is unreachable. Events are still stored and nothing is lost, but they are not reaching the workers."
          />
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Endpoint performance"
          description="Least healthy first, over the last 24 hours. Endpoints with no resolved deliveries are omitted — a success rate over zero deliveries is not a measurement."
        />
        <div className="p-4">
          <EndpointPerformanceChart endpoints={endpoints} />
        </div>
        {endpoints.length > 0 ? (
          <TableWrapper>
            <Thead>
              <Tr>
                <Th>Endpoint</Th>
                <Th>Health</Th>
                <Th align="right">Delivered</Th>
                <Th align="right">Failed</Th>
                <Th align="right">In flight</Th>
                <Th align="right">Success rate</Th>
              </Tr>
            </Thead>
            <Tbody>
              {endpoints.map((endpoint) => (
                <Tr key={endpoint.endpointId}>
                  <Td className="max-w-[20rem]">
                    <span className="block truncate text-content">{endpoint.name}</span>
                    <span className="rf-mono block truncate text-content-tertiary">
                      {endpoint.url}
                    </span>
                  </Td>
                  <Td>
                    <HealthBadge state={endpoint.health} />
                  </Td>
                  <Td align="right" className="tabular text-status-success">
                    {formatCount(endpoint.succeeded)}
                  </Td>
                  <Td
                    align="right"
                    className={`tabular ${endpoint.failed > 0 ? "text-status-failed" : ""}`}
                  >
                    {formatCount(endpoint.failed)}
                  </Td>
                  <Td align="right" className="tabular">
                    {formatCount(endpoint.pending)}
                  </Td>
                  <Td align="right" className="tabular">
                    {endpoint.succeeded + endpoint.failed === 0
                      ? "—"
                      : formatPercent(endpoint.successRate)}
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </TableWrapper>
        ) : null}
      </Card>

      <div className="rf-card flex items-start gap-3 p-4">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-content-tertiary" aria-hidden />
        <div>
          <p className="text-base font-medium text-content">
            On the approximate percentiles
          </p>
          <p className="mt-1 max-w-3xl text-sm leading-relaxed text-content-secondary">
            Latency is stored as fixed histogram buckets and aggregated per minute, so
            p50/p95/p99 here are interpolated estimates rather than exact order
            statistics. Exact percentiles would require every raw observation in the
            window, which is precisely the scan the rollup exists to avoid — and the
            query would get slower exactly as the platform got busier. For
            infrastructure-grade percentiles, the Prometheus histogram exported at{" "}
            <code className="rf-mono">/actuator/prometheus</code> can be aggregated
            across instances in Grafana.
          </p>
        </div>
      </div>
    </div>
  );
}
