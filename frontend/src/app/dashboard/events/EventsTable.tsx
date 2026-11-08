"use client";

import { useCallback, useEffect, useRef, useState, useTransition } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Search, Send, X } from "lucide-react";
import { Card, CardFooter } from "@/components/ui/Card";
import { Badge } from "@/components/ui/StatusBadge";
import { EmptyState, TableSkeleton } from "@/components/ui/States";
import { IdChip } from "@/components/ui/Code";
import { InlineSelect } from "@/components/ui/Input";
import { Pagination } from "@/components/ui/Pagination";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { formatBytes, formatRelative } from "@/lib/format";
import type { EventListItem, PageResponse } from "@/types/api";

/**
 * The event table with its filter toolbar.
 *
 * <h2>Why the search box is debounced</h2>
 *
 * Each keystroke would otherwise push a new URL and trigger a server round trip.
 * At typing speed that is a dozen queries to produce one answer, and the results
 * can arrive out of order so the list briefly shows matches for a prefix the user
 * has already moved past. A 350 ms debounce means one request per pause in
 * typing.
 */
export function EventsTable({
  initial,
  eventTypes,
  activeEventType,
  activeSearch,
}: {
  initial: PageResponse<EventListItem>;
  eventTypes: string[];
  activeEventType: string;
  activeSearch: string;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [isPending, startTransition] = useTransition();
  const [searchText, setSearchText] = useState(activeSearch);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const pushParams = useCallback(
    (updates: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [key, value] of Object.entries(updates)) {
        if (value === null || value === "") {
          next.delete(key);
        } else {
          next.set(key, value);
        }
      }
      // Any filter change invalidates the current page offset.
      if (!("page" in updates)) {
        next.delete("page");
      }
      startTransition(() => {
        router.push(`/dashboard/events?${next.toString()}`);
      });
    },
    [router, searchParams],
  );

  // Debounce the search field.
  useEffect(() => {
    if (searchText === activeSearch) {
      return;
    }
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }
    debounceRef.current = setTimeout(() => {
      pushParams({ search: searchText || null });
    }, 350);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [searchText, activeSearch, pushParams]);

  const hasFilters = activeEventType !== "" || activeSearch !== "";

  return (
    <Card>
      <div className="flex flex-col gap-3 border-b border-border-subtle p-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search
            className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-content-tertiary"
            aria-hidden
          />
          <input
            type="search"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            placeholder="Search event types…"
            aria-label="Search event types"
            className="h-9 w-full rounded border border-border bg-surface-2 pl-8 pr-3 text-sm text-content placeholder:text-content-tertiary focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>

        <InlineSelect
          ariaLabel="Filter by event type"
          value={activeEventType}
          onChange={(event) => pushParams({ eventType: event.target.value || null })}
          options={[
            { value: "", label: "All event types" },
            ...eventTypes.map((type) => ({ value: type, label: type })),
          ]}
        />

        {hasFilters ? (
          <button
            type="button"
            onClick={() => {
              setSearchText("");
              pushParams({ search: null, eventType: null });
            }}
            className="inline-flex h-9 items-center gap-1.5 rounded border border-border px-2.5 text-xs text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
          >
            <X className="h-3 w-3" aria-hidden />
            Clear filters
          </button>
        ) : null}
      </div>

      {isPending ? (
        <TableSkeleton rows={6} columns={6} />
      ) : initial.items.length === 0 ? (
        <EmptyState
          icon={<Send className="h-4 w-4" aria-hidden />}
          title={hasFilters ? "No events match those filters" : "No events yet"}
          description={
            hasFilters
              ? "Try a different event type, or clear the filters."
              : "Publish an event with POST /api/v1/events using an API key, or run a Reliability Playground scenario."
          }
          action={
            hasFilters ? null : (
              <Link
                href="/dashboard/playground"
                className="inline-flex h-9 items-center rounded bg-accent px-3 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover"
              >
                Open the Playground
              </Link>
            )
          }
        />
      ) : (
        <TableWrapper>
          <Thead>
            <Tr>
              <Th>Event type</Th>
              <Th>Event ID</Th>
              <Th>Source</Th>
              <Th align="center">Deliveries</Th>
              <Th align="right">Payload</Th>
              <Th align="right">Published</Th>
              <Th />
            </Tr>
          </Thead>
          <Tbody>
            {initial.items.map((event) => (
              <Tr key={event.id}>
                <Td>
                  <span className="rf-mono text-content">{event.eventType}</span>
                  {event.idempotencyKey ? (
                    <span
                      className="ml-2 text-2xs text-content-tertiary"
                      title={`Idempotency-Key: ${event.idempotencyKey}`}
                    >
                      idem
                    </span>
                  ) : null}
                </Td>
                <Td>
                  <IdChip value={event.id} />
                </Td>
                <Td>
                  <Badge tone={event.source === "API" ? "neutral" : "accent"}>
                    {event.source.toLowerCase().replace("_", " ")}
                  </Badge>
                </Td>
                <Td align="center">
                  <FanoutCell summary={event.deliverySummary} />
                </Td>
                <Td align="right" className="tabular whitespace-nowrap">
                  {formatBytes(event.payloadBytes)}
                </Td>
                <Td align="right" className="whitespace-nowrap">
                  <span title={event.createdAt}>{formatRelative(event.createdAt)}</span>
                </Td>
                <Td align="right">
                  <Link
                    href={`/dashboard/events/${event.id}`}
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

/**
 * The fan-out cell.
 *
 * Shows counts per outcome rather than a single total. "3 deliveries" hides the
 * fact that one of them failed, which is the thing a reader of this table most
 * needs to notice.
 */
function FanoutCell({
  summary,
}: {
  summary: EventListItem["deliverySummary"];
}) {
  if (summary.total === 0) {
    return (
      <span
        className="text-2xs text-content-tertiary"
        title="No endpoint subscribed to this event type when it was published. The event is still stored and can be delivered by replaying it after adding a subscription."
      >
        no subscribers
      </span>
    );
  }
  return (
    <span className="tabular inline-flex items-center gap-1.5 text-2xs">
      {summary.succeeded > 0 ? (
        <span className="text-status-success" title={`${summary.succeeded} delivered`}>
          {summary.succeeded} ok
        </span>
      ) : null}
      {summary.pending > 0 ? (
        <span className="text-status-pending" title={`${summary.pending} in flight`}>
          {summary.pending} live
        </span>
      ) : null}
      {summary.failed > 0 ? (
        <span className="text-status-failed" title={`${summary.failed} failed`}>
          {summary.failed} failed
        </span>
      ) : null}
      {summary.cancelled > 0 ? (
        <span className="text-content-tertiary" title={`${summary.cancelled} cancelled`}>
          {summary.cancelled} cancelled
        </span>
      ) : null}
    </span>
  );
}
