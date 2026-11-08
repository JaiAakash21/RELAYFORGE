"use client";

import { useCallback, useMemo, useState, useTransition } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { CheckCircle2, RotateCcw, X } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card, CardFooter } from "@/components/ui/Card";
import { Dialog } from "@/components/ui/Dialog";
import { EmptyState, TableSkeleton } from "@/components/ui/States";
import { HttpStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip } from "@/components/ui/Code";
import { InlineSelect, TextArea } from "@/components/ui/Input";
import { Pagination } from "@/components/ui/Pagination";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import { ClientApiError, api } from "@/lib/api/client";
import { formatRelative } from "@/lib/format";
import type {
  BulkReplayResult,
  DeadLetter,
  PageResponse,
  WebhookEndpoint,
} from "@/types/api";

/**
 * The dead-letter table, with selection and bulk replay.
 *
 * <h2>Why selection is per-page</h2>
 *
 * There is no "select all matching the filter" that reaches beyond the current
 * page. A user clicking one checkbox should never dispatch a replay of ten
 * thousand deliveries they have not seen — a replay re-sends real webhooks that
 * receivers will act on. Bulk replay for a whole endpoint exists as a separate,
 * explicitly-labelled action with its own confirmation.
 */
export function DeadLetterTable({
  initial,
  endpoints,
  activeEndpointId,
}: {
  initial: PageResponse<DeadLetter>;
  endpoints: WebhookEndpoint[];
  activeEndpointId: string;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const toast = useToast();
  const [isPending, startTransition] = useTransition();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkOpen, setBulkOpen] = useState(false);
  const [allForEndpointOpen, setAllForEndpointOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const pushParams = useCallback(
    (updates: Record<string, string | null>) => {
      const next = new URLSearchParams(searchParams.toString());
      for (const [key, value] of Object.entries(updates)) {
        if (value === null || value === "") next.delete(key);
        else next.set(key, value);
      }
      if (!("page" in updates)) next.delete("page");
      setSelected(new Set());
      startTransition(() => {
        router.push(`/dashboard/dead-letters?${next.toString()}`);
      });
    },
    [router, searchParams],
  );

  const allOnPageSelected = useMemo(
    () =>
      initial.items.length > 0 &&
      initial.items.every((item) => selected.has(item.id)),
    [initial.items, selected],
  );

  function toggle(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAllOnPage() {
    setSelected(
      allOnPageSelected ? new Set() : new Set(initial.items.map((item) => item.id)),
    );
  }

  function report(result: BulkReplayResult) {
    if (result.accepted > 0 && result.skipped === 0) {
      toast.success(
        `${result.accepted} replay${result.accepted === 1 ? "" : "s"} accepted`,
        "The deliveries are queued. Their previous attempts remain in their history.",
      );
    } else if (result.accepted > 0) {
      toast.info(
        `${result.accepted} accepted, ${result.skipped} skipped`,
        "Skipped deliveries were already replayed or are no longer in a replayable state.",
      );
    } else {
      toast.info("Nothing to replay", "None of the selected deliveries were eligible.");
    }
  }

  async function replaySelected() {
    setSubmitting(true);
    try {
      const result = await api.post<BulkReplayResult>("/deliveries/replay", {
        deliveryIds: Array.from(selected),
        reason: reason.trim() || null,
      });
      report(result);
      setBulkOpen(false);
      setSelected(new Set());
      setReason("");
      router.refresh();
    } catch (error) {
      toast.error(
        "Bulk replay failed",
        error instanceof ClientApiError ? error.message : "The request failed.",
        error instanceof ClientApiError ? error.payload.traceId : undefined,
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function replayAllForEndpoint() {
    setSubmitting(true);
    try {
      const result = await api.post<BulkReplayResult>("/dead-letters/replay-all", {
        endpointId: activeEndpointId,
        reason: reason.trim() || null,
      });
      report(result);
      setAllForEndpointOpen(false);
      setReason("");
      router.refresh();
    } catch (error) {
      toast.error(
        "Bulk replay failed",
        error instanceof ClientApiError ? error.message : "The request failed.",
        error instanceof ClientApiError ? error.payload.traceId : undefined,
      );
    } finally {
      setSubmitting(false);
    }
  }

  const selectedEndpointName =
    endpoints.find((endpoint) => endpoint.id === activeEndpointId)?.name ?? "";

  return (
    <>
      <Card>
        <div className="flex flex-col gap-2 border-b border-border-subtle p-3 sm:flex-row sm:items-center">
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
          {activeEndpointId ? (
            <button
              type="button"
              onClick={() => pushParams({ endpointId: null })}
              className="inline-flex h-9 items-center gap-1.5 rounded border border-border px-2.5 text-xs text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
            >
              <X className="h-3 w-3" aria-hidden />
              Clear
            </button>
          ) : null}

          <div className="flex flex-1 items-center justify-end gap-2">
            {selected.size > 0 ? (
              <Button
                variant="primary"
                size="sm"
                onClick={() => setBulkOpen(true)}
                leadingIcon={<RotateCcw className="h-3.5 w-3.5" aria-hidden />}
              >
                Replay {selected.size} selected
              </Button>
            ) : null}
            {activeEndpointId && initial.totalItems > 0 ? (
              <Button
                variant="outline"
                size="sm"
                onClick={() => setAllForEndpointOpen(true)}
              >
                Replay all for this endpoint
              </Button>
            ) : null}
          </div>
        </div>

        {isPending ? (
          <TableSkeleton rows={8} columns={7} />
        ) : initial.items.length === 0 ? (
          <EmptyState
            icon={<CheckCircle2 className="h-4 w-4 text-status-success" aria-hidden />}
            title="No dead letters"
            description={
              activeEndpointId
                ? "This endpoint has no exhausted deliveries."
                : "Nothing has exhausted its attempt budget. Every delivery either succeeded or is still being retried."
            }
            action={
              <Link
                href="/dashboard/playground"
                className="text-xs text-accent-hover hover:underline"
              >
                Create one deliberately in the Reliability Playground
              </Link>
            }
          />
        ) : (
          <TableWrapper>
            <Thead>
              <Tr>
                <Th className="w-9">
                  <input
                    type="checkbox"
                    checked={allOnPageSelected}
                    onChange={toggleAllOnPage}
                    aria-label="Select all dead letters on this page"
                    className="h-3.5 w-3.5 rounded border-border bg-surface-2 text-accent focus:ring-1 focus:ring-accent"
                  />
                </Th>
                <Th>Event type</Th>
                <Th>Endpoint</Th>
                <Th>Failure</Th>
                <Th align="right">Attempts</Th>
                <Th align="right">Failed</Th>
                <Th />
              </Tr>
            </Thead>
            <Tbody>
              {initial.items.map((item) => (
                <Tr key={item.id}>
                  <Td>
                    <input
                      type="checkbox"
                      checked={selected.has(item.id)}
                      onChange={() => toggle(item.id)}
                      aria-label={`Select delivery ${item.id}`}
                      className="h-3.5 w-3.5 rounded border-border bg-surface-2 text-accent focus:ring-1 focus:ring-accent"
                    />
                  </Td>
                  <Td>
                    <span className="rf-mono text-content">
                      {item.eventType ?? "—"}
                    </span>
                  </Td>
                  <Td className="max-w-[13rem]">
                    <span className="block truncate text-content-secondary">
                      {item.endpointName ?? <IdChip value={item.endpointId} />}
                    </span>
                  </Td>
                  <Td className="max-w-[20rem]">
                    <div className="flex items-center gap-2">
                      <HttpStatusBadge status={item.lastResponseCode} />
                      {item.lastErrorClass && item.lastErrorClass !== "NONE" ? (
                        <code className="rf-mono text-content-tertiary">
                          {item.lastErrorClass}
                        </code>
                      ) : null}
                    </div>
                    {item.lastError ? (
                      <p
                        className="mt-0.5 truncate text-2xs text-content-tertiary"
                        title={item.lastError}
                      >
                        {item.lastError}
                      </p>
                    ) : null}
                  </Td>
                  <Td align="right" className="tabular">
                    {item.attemptCount}
                    {item.replayCount > 0 ? (
                      <span
                        className="ml-1 text-2xs text-accent-hover"
                        title={`Already replayed ${item.replayCount} time(s)`}
                      >
                        ↻{item.replayCount}
                      </span>
                    ) : null}
                  </Td>
                  <Td align="right" className="whitespace-nowrap">
                    <span title={item.failedAt}>{formatRelative(item.failedAt)}</span>
                  </Td>
                  <Td align="right">
                    <Link
                      href={`/dashboard/deliveries/${item.id}`}
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

      <Dialog
        open={bulkOpen}
        onClose={() => setBulkOpen(false)}
        title={`Replay ${selected.size} deliver${selected.size === 1 ? "y" : "ies"}`}
        description="Each webhook will be sent again. Receivers that are not idempotent may act on these events a second time."
        footer={
          <>
            <Button variant="ghost" onClick={() => setBulkOpen(false)} disabled={submitting}>
              Cancel
            </Button>
            <Button variant="primary" onClick={replaySelected} loading={submitting}>
              Replay {selected.size}
            </Button>
          </>
        }
      >
        <TextArea
          label="Reason"
          help="Recorded against every delivery in this batch, under one batch id."
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={500}
          placeholder="e.g. Partner confirmed their endpoint is back online"
        />
      </Dialog>

      <Dialog
        open={allForEndpointOpen}
        onClose={() => setAllForEndpointOpen(false)}
        title="Replay every dead letter for this endpoint"
        description={`All ${initial.totalItems} exhausted deliver${initial.totalItems === 1 ? "y" : "ies"} for ${selectedEndpointName || "this endpoint"} will be queued, up to a limit of 500 per request.`}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => setAllForEndpointOpen(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button variant="danger" onClick={replayAllForEndpoint} loading={submitting}>
              Replay all
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="rounded border border-status-retry-border bg-status-retry-bg/40 p-3">
            <p className="text-xs leading-relaxed text-content-secondary">
              This re-sends every failed webhook for the endpoint. If the underlying
              problem has not been fixed, they will fail again and consume their new
              attempt budgets. Confirm the receiver is healthy first — the endpoint&apos;s
              <strong className="text-content"> Send test</strong> button is the quickest
              way to check.
            </p>
          </div>
          <TextArea
            label="Reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={500}
            placeholder="e.g. Receiver deployed a fix for the signature check"
          />
        </div>
      </Dialog>
    </>
  );
}
