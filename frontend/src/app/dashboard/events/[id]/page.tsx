import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { PageHeader, DetailRow } from "@/components/ui/PageHeader";
import { Card, CardHeader } from "@/components/ui/Card";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { DeliveryStatusBadge, Badge, HttpStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip, JsonViewer } from "@/components/ui/Code";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { formatAbsolute, formatBytes, formatRelative } from "@/lib/format";
import type { Delivery, EventDetail } from "@/types/api";

export const metadata: Metadata = { title: "Event" };

/** One event, its payload and the deliveries it produced. */
export default async function EventDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let event: EventDetail;
  let deliveries: Delivery[];
  try {
    [event, deliveries] = await Promise.all([
      apiGet<EventDetail>(`/api/v1/events/${id}`),
      apiGet<Delivery[]>(`/api/v1/events/${id}/deliveries`),
    ]);
  } catch (error) {
    // A cross-tenant id is a 404 by design: the API does not confirm that an
    // identifier belonging to someone else exists.
    if (error instanceof ApiRequestError && error.isNotFound) {
      notFound();
    }
    return (
      <Card>
        <ErrorState
          title="Could not load the event"
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

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/dashboard/events"
          className="inline-flex items-center gap-1.5 text-xs text-content-tertiary transition-colors hover:text-content"
        >
          <ArrowLeft className="h-3 w-3" aria-hidden />
          All events
        </Link>
        <PageHeader
          className="mt-3"
          title={event.eventType}
          description={`Published ${formatRelative(event.createdAt)} · ${formatAbsolute(event.createdAt)}`}
          actions={<Badge tone="accent">{event.source.toLowerCase().replace("_", " ")}</Badge>}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex flex-col gap-6">
          <Card>
            <CardHeader
              title="Payload"
              description={`${formatBytes(event.payloadBytes)} of JSON, stored exactly as published.`}
            />
            <div className="p-4">
              <JsonViewer value={event.payload} maxHeight="26rem" />
            </div>
          </Card>

          <Card>
            <CardHeader
              title="Deliveries"
              description="One delivery per endpoint that subscribed to this event type at publish time."
            />
            {deliveries.length === 0 ? (
              <EmptyState
                title="No endpoint subscribed to this event type"
                description="The event is stored and is not lost. Add a subscription and replay it, or publish again."
              />
            ) : (
              <TableWrapper>
                <Thead>
                  <Tr>
                    <Th>Endpoint</Th>
                    <Th>Status</Th>
                    <Th align="right">Attempts</Th>
                    <Th align="right">Last code</Th>
                    <Th align="right">Updated</Th>
                    <Th />
                  </Tr>
                </Thead>
                <Tbody>
                  {deliveries.map((delivery) => (
                    <Tr key={delivery.id}>
                      <Td className="max-w-[16rem]">
                        <span className="block truncate text-content">
                          {delivery.endpointName ?? "—"}
                        </span>
                        <span className="rf-mono block truncate text-content-tertiary">
                          {delivery.endpointUrl ?? ""}
                        </span>
                      </Td>
                      <Td>
                        <DeliveryStatusBadge status={delivery.status} live />
                      </Td>
                      <Td align="right" className="tabular">
                        {delivery.attemptCount}/{delivery.maxAttempts}
                      </Td>
                      <Td align="right">
                        <HttpStatusBadge status={delivery.lastResponseCode} />
                      </Td>
                      <Td align="right" className="whitespace-nowrap">
                        {formatRelative(delivery.updatedAt)}
                      </Td>
                      <Td align="right">
                        <Link
                          href={`/dashboard/deliveries/${delivery.id}`}
                          className="text-xs text-accent-hover hover:underline"
                        >
                          Timeline
                        </Link>
                      </Td>
                    </Tr>
                  ))}
                </Tbody>
              </TableWrapper>
            )}
          </Card>
        </div>

        <Card className="h-fit">
          <CardHeader title="Details" />
          <dl className="divide-y divide-border-subtle px-4 py-2">
            <DetailRow label="Event ID">
              <IdChip value={event.id} length={36} />
            </DetailRow>
            <DetailRow label="Event type">
              <code className="rf-mono">{event.eventType}</code>
            </DetailRow>
            <DetailRow label="Idempotency key">
              {event.idempotencyKey ? (
                <code className="rf-mono break-all">{event.idempotencyKey}</code>
              ) : (
                <span
                  className="text-content-tertiary"
                  title="No Idempotency-Key header was sent. A retry of that publish call would have created a second event."
                >
                  none supplied
                </span>
              )}
            </DetailRow>
            <DetailRow label="Source">
              {event.source.toLowerCase().replace("_", " ")}
            </DetailRow>
            <DetailRow label="Payload size">{formatBytes(event.payloadBytes)}</DetailRow>
            <DetailRow label="Published">{formatAbsolute(event.createdAt)}</DetailRow>
            <DetailRow label="Fan-out">
              <span className="tabular">
                {event.deliverySummary.total} deliveries ·{" "}
                {event.deliverySummary.succeeded} delivered ·{" "}
                {event.deliverySummary.failed} failed
              </span>
            </DetailRow>
          </dl>
        </Card>
      </div>
    </div>
  );
}
