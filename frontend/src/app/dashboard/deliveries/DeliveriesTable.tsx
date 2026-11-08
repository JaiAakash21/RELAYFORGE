"use client";

import { useCallback, useTransition } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Radio, X } from "lucide-react";
import { Card, CardFooter } from "@/components/ui/Card";
import { EmptyState, TableSkeleton } from "@/components/ui/States";
import { DeliveryStatusBadge, HttpStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip } from "@/components/ui/Code";
import { InlineSelect } from "@/components/ui/Input";
import { Pagination } from "@/components/ui/Pagination";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { formatCountdown, formatRelative } from "@/lib/format";
import type { Delivery, PageResponse, WebhookEndpoint } from "@/types/api";

const STATUS_OPTIONS = [
  { value: "", label: "All statuses" },
  { value: "PENDING", label: "Pending" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "RETRY_SCHEDULED", label: "Retry scheduled" },
  { value: "SUCCESS", label: "Success" },
  { value: "FAILED", label: "Failed" },
  { value: "CANCELLED", label: "Cancelled" },
];

export function DeliveriesTable({
  initial,
  endpoints,
  activeStatus,
  activeEndpointId,
}: {
  initial: PageResponse<Delivery>;
  endpoints: WebhookEndpoint[];
  activeStatus: string;
  activeEndpointId: string;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [isPending, startTransition] = useTransition();

  const pushParams = useCallback(
    (updates: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [key, value] of Object.entries(updates)) {
        if (value === null || value === "") next.delete(key);
        else next.set(key, value);
      }
      if (!("page" in updates)) next.delete("page");
      startTransition(() => {
        router.push(`/dashboard/deliveries?${next.toString()}`);
      });
    },
    [router, searchParams],
  );

  const hasFilters = activeStatus !== "" || activeEndpointId !== "";

  return (
    <Card>
      <div className="flex flex-col gap-2 border-b border-border-subtle p-3 sm:flex-row sm:items-center">
        <InlineSelect
          ariaLabel="Filter by delivery status"
          value={activeStatus}
          onChange={(event) => pushParams({ status: event.target.value || null })}
          options={STATUS_OPTIONS}
        />
        <InlineSelect
          ariaLabel="Filter by endpoint"
          value={activeEndpointId}
          onChange={(event) => pushParams({ endpointId: event.target.value || null })}
          className="min-w-0 flex-1 sm:max-w-xs"
          options={[
            { value: "", label: "All endpoints" },
            ...endpoints.map((endpoint) => ({
              value: endpoint.id,
              label: endpoint.name,
            })),
          ]}
        />
        {hasFilters ? (
          <button
            type="button"
            onClick={() => pushParams({ status: null, endpointId: null })}
            className="inline-flex h-9 items-center gap-1.5 rounded border border-border px-2.5 text-xs text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
          >
            <X className="h-3 w-3" aria-hidden />
            Clear
          </button>
        ) : null}
      </div>

      {isPending ? (
        <TableSkeleton rows={8} columns={7} />
      ) : initial.items.length === 0 ? (
        <EmptyState
          icon={<Radio className="h-4 w-4" aria-hidden />}
          title={hasFilters ? "No deliveries match those filters" : "No deliveries yet"}
          description={
            hasFilters
              ? "Try a different status or endpoint."
              : "A delivery is created for each endpoint subscribed to an event you publish."
          }
        />
      ) : (
        <TableWrapper>
          <Thead>
            <Tr>
              <Th>Event type</Th>
              <Th>Endpoint</Th>
              <Th>Status</Th>
              <Th align="right">Attempts</Th>
              <Th align="right">Last code</Th>
              <Th align="right">Next / updated</Th>
              <Th />
            </Tr>
          </Thead>
          <Tbody>
            {initial.items.map((delivery) => (
              <Tr key={delivery.id}>
                <Td>
                  <span className="rf-mono text-content">
                    {delivery.eventType ?? "—"}
                  </span>
                </Td>
                <Td className="max-w-[15rem]">
                  <span className="block truncate text-content-secondary">
                    {delivery.endpointName ?? <IdChip value={delivery.endpointId} />}
                  </span>
                </Td>
                <Td>
                  <DeliveryStatusBadge status={delivery.status} live />
                </Td>
                <Td align="right" className="tabular">
                  {delivery.attemptCount}/{delivery.maxAttempts}
                  {delivery.replayCount > 0 ? (
                    <span
                      className="ml-1 text-2xs text-accent-hover"
                      title={`Replayed ${delivery.replayCount} time(s)`}
                    >
                      ↻{delivery.replayCount}
                    </span>
                  ) : null}
                </Td>
                <Td align="right">
                  <HttpStatusBadge status={delivery.lastResponseCode} />
                </Td>
                <Td align="right" className="whitespace-nowrap">
                  {/* For a scheduled retry, the useful number is when it will
                      next try — not when the row was last touched. */}
                  {delivery.status === "RETRY_SCHEDULED" && delivery.nextAttemptAt ? (
                    <span className="tabular text-status-retry">
                      in{" "}
                      {formatCountdown(
                        (new Date(delivery.nextAttemptAt).getTime() - Date.now()) / 1000,
                      )}
                    </span>
                  ) : (
                    <span title={delivery.updatedAt}>
                      {formatRelative(delivery.updatedAt)}
                    </span>
                  )}
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

      {initial.totalItems > 0 ? (
        <CardFooter>
          <Pagination
            page={initial.page}
            size={initial.size}
            totalItems={initial.totalItems}
            totalPages={initial.totalPages}
            loading={isPending}
            onPageChange={(nextPage) => pushParams({ page: String(nextPage) })}
          />
        </CardFooter>
      ) : null}
    </Card>
  );
}
