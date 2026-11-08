import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { DetailRow, PageHeader } from "@/components/ui/PageHeader";
import { Card, CardHeader } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { DeliveryStatusBadge, HttpStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip, JsonViewer } from "@/components/ui/Code";
import { AttemptTimeline } from "@/components/layout/AttemptTimeline";
import { ReplayButton } from "@/components/layout/ReplayButton";
import { DeliveryAutoRefresh } from "@/components/layout/DeliveryAutoRefresh";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { formatAbsolute, formatCountdown, formatRelative } from "@/lib/format";
import type { DeliveryDetail } from "@/types/api";

export const metadata: Metadata = { title: "Delivery" };

/** One delivery: its state, its full attempt history and its replay audit trail. */
export default async function DeliveryDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let detail: DeliveryDetail;
  try {
    detail = await apiGet<DeliveryDetail>(`/api/v1/deliveries/${id}`);
  } catch (error) {
    if (error instanceof ApiRequestError && error.isNotFound) {
      notFound();
    }
    return (
      <Card>
        <ErrorState
          title="Could not load the delivery"
          message={
            error instanceof ApiRequestError
              ? error.message
              : "The API could not be reached."
          }
          traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
        />
      </Card>
    );
  }

  const { delivery, attempts, replays, eventPayload } = detail;
  const inFlight =
    delivery.status === "PENDING" ||
    delivery.status === "IN_PROGRESS" ||
    delivery.status === "RETRY_SCHEDULED";

  const secondsUntilNext =
    delivery.nextAttemptAt !== null
      ? Math.max(
          0,
          (new Date(delivery.nextAttemptAt).getTime() - Date.now()) / 1000,
        )
      : null;

  return (
    <div className="flex flex-col gap-6">
      {/* Refreshes the server component while the delivery is still moving, and
          stops once it reaches a terminal state — no polling a finished row. */}
      {inFlight ? <DeliveryAutoRefresh /> : null}

      <div>
        <Link
          href="/dashboard/deliveries"
          className="inline-flex items-center gap-1.5 text-xs text-content-tertiary transition-colors hover:text-content"
        >
          <ArrowLeft className="h-3 w-3" aria-hidden />
          All deliveries
        </Link>
        <PageHeader
          className="mt-3"
          title={delivery.eventType ?? "Delivery"}
          description={
            <span className="flex flex-wrap items-center gap-2">
              <span>to</span>
              <span className="text-content">{delivery.endpointName ?? "unknown endpoint"}</span>
              {delivery.endpointUrl ? (
                <code className="rf-mono break-all text-content-tertiary">
                  {delivery.endpointUrl}
                </code>
              ) : null}
            </span>
          }
          actions={
            <>
              <DeliveryStatusBadge status={delivery.status} live />
              {delivery.replayable ? (
                <ReplayButton deliveryId={delivery.id} />
              ) : null}
            </>
          }
        />
      </div>

      {/* The next-attempt banner. The single most-asked question about a
          delivery that has not finished is "when will it try again?". */}
      {delivery.status === "RETRY_SCHEDULED" && delivery.nextAttemptAt ? (
        <div className="rf-card flex flex-wrap items-center justify-between gap-3 border-status-retry-border bg-status-retry-bg/40 p-4">
          <div>
            <p className="text-base font-medium text-content">
              Next attempt in {formatCountdown(secondsUntilNext)}
            </p>
            <p className="mt-1 text-sm text-content-secondary">
              Scheduled for {formatAbsolute(delivery.nextAttemptAt)}. This is attempt{" "}
              {delivery.attemptCount + 1} of {delivery.maxAttempts}; the delay grows
              exponentially with jitter applied.
            </p>
          </div>
        </div>
      ) : null}

      {delivery.status === "FAILED" ? (
        <div className="rf-card border-status-failed-border bg-status-failed-bg/40 p-4">
          <p className="text-base font-medium text-content">
            Dead-lettered after {delivery.attemptCount} attempts
          </p>
          <p className="mt-1 text-sm leading-relaxed text-content-secondary">
            {delivery.lastError ??
              "The attempt budget was exhausted and the delivery was moved to the dead-letter queue."}
          </p>
          <p className="mt-2 text-xs leading-relaxed text-content-tertiary">
            Replaying grants a fresh attempt budget. The attempts below are kept —
            numbering continues upward rather than restarting, so the record of why this
            failed is never erased.
          </p>
        </div>
      ) : null}

      {delivery.status === "IN_PROGRESS" && delivery.leaseUntil ? (
        <div className="rf-card border-status-progress-border bg-status-progress-bg/40 p-4">
          <p className="text-base font-medium text-content">
            Currently held by a worker
          </p>
          <p className="mt-1 text-sm leading-relaxed text-content-secondary">
            Worker <code className="rf-mono">{delivery.leaseOwner}</code> holds a lease
            until {formatAbsolute(delivery.leaseUntil)}. If that worker dies, the lease
            expires and a reconciler returns this delivery to the schedule — it cannot
            become permanently stuck.
          </p>
        </div>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex flex-col gap-6">
          <Card>
            <CardHeader
              title="Attempt history"
              description={`${attempts.length} recorded attempt${attempts.length === 1 ? "" : "s"}. Every outbound request is kept, including the ones that failed.`}
            />
            <AttemptTimeline attempts={attempts} />
          </Card>

          {replays.length > 0 ? (
            <Card>
              <CardHeader
                title="Replay history"
                description="Every manual replay is recorded with the state the delivery was in beforehand."
              />
              <ul className="divide-y divide-border-subtle">
                {replays.map((replay) => (
                  <li key={replay.id} className="px-4 py-3">
                    <p className="text-sm text-content">
                      Replayed {formatRelative(replay.createdAt)} from{" "}
                      <span className="text-status-failed">{replay.previousStatus}</span>{" "}
                      after {replay.attemptsBefore} attempts
                    </p>
                    {replay.reason ? (
                      <p className="mt-1 text-xs leading-relaxed text-content-secondary">
                        “{replay.reason}”
                      </p>
                    ) : null}
                    <p className="mt-1 text-2xs text-content-tertiary">
                      {formatAbsolute(replay.createdAt)}
                      {replay.batchId ? " · part of a bulk replay" : ""}
                    </p>
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}

          <Card>
            <CardHeader title="Event payload" description="The body that was signed and sent." />
            <div className="p-4">
              <JsonViewer value={eventPayload} maxHeight="22rem" />
            </div>
          </Card>
        </div>

        <Card className="h-fit">
          <CardHeader title="Delivery" />
          <dl className="divide-y divide-border-subtle px-4 py-2">
            <DetailRow label="Delivery ID">
              <IdChip value={delivery.id} length={36} />
            </DetailRow>
            <DetailRow label="Event">
              <Link
                href={`/dashboard/events/${delivery.eventId}`}
                className="inline-flex items-center gap-1 text-accent-hover hover:underline"
              >
                <IdChip value={delivery.eventId} />
                <ExternalLink className="h-3 w-3" aria-hidden />
              </Link>
            </DetailRow>
            <DetailRow label="Endpoint">
              <Link
                href={`/dashboard/endpoints/${delivery.endpointId}`}
                className="text-accent-hover hover:underline"
              >
                {delivery.endpointName ?? delivery.endpointId}
              </Link>
            </DetailRow>
            <DetailRow label="Attempts">
              <span className="tabular">
                {delivery.attemptCount} of {delivery.maxAttempts}
                {delivery.attemptsRemaining > 0 && !delivery.status.startsWith("SUCCESS")
                  ? ` · ${delivery.attemptsRemaining} remaining`
                  : ""}
              </span>
            </DetailRow>
            <DetailRow label="Replays">
              <span className="tabular">{delivery.replayCount}</span>
            </DetailRow>
            <DetailRow label="Last response">
              <HttpStatusBadge status={delivery.lastResponseCode} />
            </DetailRow>
            {delivery.lastErrorClass && delivery.lastErrorClass !== "NONE" ? (
              <DetailRow label="Error class">
                <code className="rf-mono">{delivery.lastErrorClass}</code>
              </DetailRow>
            ) : null}
            <DetailRow label="First attempt">
              {delivery.firstAttemptAt
                ? formatAbsolute(delivery.firstAttemptAt)
                : "not yet attempted"}
            </DetailRow>
            <DetailRow label="Completed">
              {delivery.completedAt ? formatAbsolute(delivery.completedAt) : "—"}
            </DetailRow>
            <DetailRow label="Created">{formatAbsolute(delivery.createdAt)}</DetailRow>
          </dl>
        </Card>
      </div>
    </div>
  );
}
