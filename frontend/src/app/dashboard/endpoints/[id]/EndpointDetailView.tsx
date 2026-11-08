"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  KeyRound,
  Pause,
  Pencil,
  Play,
  Send,
  ShieldOff,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { EmptyState } from "@/components/ui/States";
import { DeliveryStatusBadge, HealthBadge, HttpStatusBadge, Badge } from "@/components/ui/StatusBadge";
import { IdChip } from "@/components/ui/Code";
import { DetailRow } from "@/components/ui/PageHeader";
import { Stat } from "@/components/ui/Stat";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import { EndpointFormDialog } from "../EndpointFormDialog";
import { SecretRevealDialog } from "../SecretRevealDialog";
import { ClientApiError, api } from "@/lib/api/client";
import {
  formatAbsolute,
  formatDuration,
  formatPercent,
  formatRelative,
} from "@/lib/format";
import type {
  Delivery,
  EndpointSecret,
  EndpointTestResult,
  WebhookEndpoint,
} from "@/types/api";

/** Endpoint detail: configuration, secret management, health and delivery history. */
export function EndpointDetailView({
  endpoint,
  deliveries,
  totalDeliveries,
  knownEventTypes,
}: {
  endpoint: WebhookEndpoint;
  deliveries: Delivery[];
  totalDeliveries: number;
  knownEventTypes: string[];
}) {
  const router = useRouter();
  const toast = useToast();
  const [editOpen, setEditOpen] = useState(false);
  const [revealed, setRevealed] = useState<EndpointSecret | null>(null);
  const [confirm, setConfirm] = useState<
    "delete" | "rotate" | "revoke-previous" | null
  >(null);
  const [busy, setBusy] = useState(false);
  const [lastTest, setLastTest] = useState<EndpointTestResult | null>(null);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
    } finally {
      setBusy(false);
    }
  }

  async function sendTest() {
    await run(async () => {
      try {
        const result = await api.post<EndpointTestResult>(
          `/endpoints/${endpoint.id}/test`,
        );
        setLastTest(result);
        if (result.delivered) {
          toast.success(
            "Test delivered",
            `Responded ${result.responseStatus} in ${result.durationMs}ms.`,
          );
        } else {
          toast.error("Test failed", result.errorMessage ?? result.errorClass);
        }
        router.refresh();
      } catch (error) {
        toast.error(
          "Could not send the test",
          error instanceof ClientApiError ? error.message : "The request failed.",
        );
      }
    });
  }

  async function togglePause() {
    await run(async () => {
      const action = endpoint.paused ? "resume" : "pause";
      try {
        await api.post(`/endpoints/${endpoint.id}/${action}`);
        toast.success(endpoint.paused ? "Delivery resumed" : "Delivery paused");
        router.refresh();
      } catch (error) {
        toast.error(
          `Could not ${action}`,
          error instanceof ClientApiError ? error.message : "The request failed.",
        );
      }
    });
  }

  async function rotateSecret() {
    await run(async () => {
      try {
        const secret = await api.post<EndpointSecret>(
          `/endpoints/${endpoint.id}/rotate-secret`,
        );
        setConfirm(null);
        setRevealed(secret);
        router.refresh();
      } catch (error) {
        toast.error(
          "Rotation failed",
          error instanceof ClientApiError ? error.message : "The request failed.",
        );
      }
    });
  }

  async function revokePreviousSecret() {
    await run(async () => {
      try {
        await api.post(`/endpoints/${endpoint.id}/revoke-previous-secret`);
        toast.success(
          "Previous secret revoked",
          "Only the current secret is used for signing from now on.",
        );
        setConfirm(null);
        router.refresh();
      } catch (error) {
        toast.error(
          "Could not revoke",
          error instanceof ClientApiError ? error.message : "The request failed.",
        );
      }
    });
  }

  async function deleteEndpoint() {
    await run(async () => {
      try {
        await api.delete(`/endpoints/${endpoint.id}`);
        toast.success(
          "Endpoint deleted",
          "Queued deliveries for it were cancelled; the delivery history is preserved.",
        );
        router.push("/dashboard/endpoints");
      } catch (error) {
        toast.error(
          "Could not delete",
          error instanceof ClientApiError ? error.message : "The request failed.",
        );
      }
    });
  }

  const resolved = endpoint.health.succeeded + endpoint.health.failed;

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="secondary"
          onClick={sendTest}
          loading={busy}
          leadingIcon={<Send className="h-3.5 w-3.5" aria-hidden />}
        >
          Send test
        </Button>
        <Button
          variant="secondary"
          onClick={() => setEditOpen(true)}
          leadingIcon={<Pencil className="h-3.5 w-3.5" aria-hidden />}
        >
          Edit
        </Button>
        <Button
          variant="secondary"
          onClick={togglePause}
          loading={busy}
          leadingIcon={
            endpoint.paused ? (
              <Play className="h-3.5 w-3.5" aria-hidden />
            ) : (
              <Pause className="h-3.5 w-3.5" aria-hidden />
            )
          }
        >
          {endpoint.paused ? "Resume delivery" : "Pause delivery"}
        </Button>
        <Button
          variant="secondary"
          onClick={() => setConfirm("rotate")}
          leadingIcon={<KeyRound className="h-3.5 w-3.5" aria-hidden />}
        >
          Rotate secret
        </Button>
        <Button
          variant="danger"
          onClick={() => setConfirm("delete")}
          leadingIcon={<Trash2 className="h-3.5 w-3.5" aria-hidden />}
          className="ml-auto"
        >
          Delete
        </Button>
      </div>

      {lastTest ? (
        <div
          className={`rf-card p-4 ${
            lastTest.delivered
              ? "border-status-success-border bg-status-success-bg/40"
              : "border-status-failed-border bg-status-failed-bg/40"
          }`}
        >
          <p className="text-base font-medium text-content">
            {lastTest.delivered ? "Test delivered" : "Test failed"}
          </p>
          <p className="mt-1 text-sm leading-relaxed text-content-secondary">
            {lastTest.delivered
              ? `The endpoint responded ${lastTest.responseStatus} in ${formatDuration(lastTest.durationMs)}.`
              : (lastTest.errorMessage ?? `Error class: ${lastTest.errorClass}`)}
          </p>
          {lastTest.deliveryId ? (
            <Link
              href={`/dashboard/deliveries/${lastTest.deliveryId}`}
              className="mt-2 inline-block text-xs text-accent-hover hover:underline"
            >
              Inspect the test delivery
            </Link>
          ) : null}
          <p className="mt-2 text-2xs leading-relaxed text-content-tertiary">
            This was a real signed event through the full pipeline, with an attempt
            budget of one — not a simulated check.
          </p>
        </div>
      ) : null}

      <section aria-label="Endpoint health" className="grid gap-3 sm:grid-cols-4">
        <Stat
          label="Delivered (24h)"
          value={endpoint.health.succeeded}
          tone="success"
        />
        <Stat
          label="Failed (24h)"
          value={endpoint.health.failed}
          tone={endpoint.health.failed > 0 ? "danger" : "default"}
        />
        <Stat label="In flight" value={endpoint.health.pending} />
        <Stat
          label="Success rate"
          value={resolved === 0 ? "—" : formatPercent(endpoint.health.successRate)}
          hint={
            resolved === 0
              ? "No resolved deliveries in the window."
              : `Over ${resolved} resolved deliveries.`
          }
        />
      </section>

      {endpoint.hasPreviousSecret ? (
        <div className="rf-card flex flex-wrap items-center justify-between gap-3 border-status-retry-border bg-status-retry-bg/40 p-4">
          <div className="min-w-0">
            <p className="text-base font-medium text-content">
              Secret rotation in progress
            </p>
            <p className="mt-1 text-sm leading-relaxed text-content-secondary">
              Requests are signed with both the current and the previous secret, so your
              receiver can roll over without dropping events. Rotated{" "}
              {formatRelative(endpoint.secretRotatedAt)}. Revoke the previous secret once
              your receiver is updated — a superseded secret that stayed valid forever
              would mean rotation never actually revoked anything.
            </p>
          </div>
          <Button
            variant="outline"
            onClick={() => setConfirm("revoke-previous")}
            leadingIcon={<ShieldOff className="h-3.5 w-3.5" aria-hidden />}
          >
            Revoke previous secret
          </Button>
        </div>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <Card>
          <CardHeader
            title="Delivery history"
            description={`${totalDeliveries} deliveries in total. Showing the ${Math.min(deliveries.length, 20)} most recent.`}
            actions={
              <Link
                href={`/dashboard/deliveries?endpointId=${endpoint.id}`}
                className="text-xs text-accent-hover hover:underline"
              >
                View all
              </Link>
            }
          />
          {deliveries.length === 0 ? (
            <EmptyState
              title="No deliveries to this endpoint yet"
              description="Publish an event matching one of its subscriptions, or press Send test."
            />
          ) : (
            <TableWrapper>
              <Thead>
                <Tr>
                  <Th>Event type</Th>
                  <Th>Status</Th>
                  <Th align="right">Attempts</Th>
                  <Th align="right">Code</Th>
                  <Th align="right">Updated</Th>
                  <Th />
                </Tr>
              </Thead>
              <Tbody>
                {deliveries.map((delivery) => (
                  <Tr key={delivery.id}>
                    <Td>
                      <span className="rf-mono text-content">
                        {delivery.eventType ?? "—"}
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

        <Card className="h-fit">
          <CardHeader title="Configuration" />
          <dl className="divide-y divide-border-subtle px-4 py-2">
            <DetailRow label="Endpoint ID">
              <IdChip value={endpoint.id} length={36} />
            </DetailRow>
            <DetailRow label="Status">
              <div className="flex flex-wrap items-center gap-2">
                <HealthBadge state={endpoint.health.state} />
                {endpoint.paused ? <Badge tone="warning">Paused</Badge> : null}
                {!endpoint.active ? <Badge tone="neutral">Inactive</Badge> : null}
              </div>
            </DetailRow>
            <DetailRow label="URL">
              <code className="rf-mono break-all">{endpoint.url}</code>
            </DetailRow>
            {endpoint.description ? (
              <DetailRow label="Description">{endpoint.description}</DetailRow>
            ) : null}
            <DetailRow label="Subscriptions">
              {endpoint.subscribedEventTypes.length === 0 ? (
                <Badge tone="accent">all event types</Badge>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {endpoint.subscribedEventTypes.map((type) => (
                    <code
                      key={type}
                      className="rf-mono rounded bg-surface-2 px-1.5 py-0.5"
                    >
                      {type}
                    </code>
                  ))}
                </div>
              )}
            </DetailRow>
            <DetailRow label="Max attempts">
              {endpoint.retryMaxAttempts ?? (
                <span className="text-content-tertiary">system default</span>
              )}
            </DetailRow>
            <DetailRow label="Initial delay">
              {endpoint.retryInitialDelayMs ? (
                formatDuration(endpoint.retryInitialDelayMs)
              ) : (
                <span className="text-content-tertiary">system default</span>
              )}
            </DetailRow>
            <DetailRow label="Max delay">
              {endpoint.retryMaxDelayMs ? (
                formatDuration(endpoint.retryMaxDelayMs)
              ) : (
                <span className="text-content-tertiary">system default</span>
              )}
            </DetailRow>
            <DetailRow label="Per-attempt timeout">
              {endpoint.retryTimeoutMs ? (
                formatDuration(endpoint.retryTimeoutMs)
              ) : (
                <span className="text-content-tertiary">system default</span>
              )}
            </DetailRow>
            <DetailRow label="Delivery rate limit">
              {endpoint.rateLimitPerSecond ? (
                `${endpoint.rateLimitPerSecond}/s`
              ) : (
                <span className="text-content-tertiary">system default</span>
              )}
            </DetailRow>
            <DetailRow label="Signing secret">
              <span className="text-content-tertiary">
                encrypted at rest · shown only when created or rotated
              </span>
            </DetailRow>
            <DetailRow label="Created">{formatAbsolute(endpoint.createdAt)}</DetailRow>
          </dl>
        </Card>
      </div>

      <EndpointFormDialog
        open={editOpen}
        onClose={() => setEditOpen(false)}
        knownEventTypes={knownEventTypes}
        existing={endpoint}
        onUpdated={() => router.refresh()}
      />

      <SecretRevealDialog
        secret={revealed}
        onClose={() => setRevealed(null)}
        title="New signing secret"
      />

      <ConfirmDialog
        open={confirm === "delete"}
        onClose={() => setConfirm(null)}
        onConfirm={deleteEndpoint}
        loading={busy}
        title="Delete this endpoint?"
        description="Queued deliveries for it will be cancelled rather than left to burn their retry budget against a destination that no longer exists. The delivery history is preserved so past deliveries stay interpretable. This cannot be undone."
        confirmLabel="Delete endpoint"
      />

      <ConfirmDialog
        open={confirm === "rotate"}
        onClose={() => setConfirm(null)}
        onConfirm={rotateSecret}
        loading={busy}
        tone="primary"
        title="Rotate the signing secret?"
        description="A new secret is generated and shown once. The current secret stays valid for the rotation grace window and both signatures are sent during it, so your receiver can be updated without dropping events."
        confirmLabel="Rotate secret"
      />

      <ConfirmDialog
        open={confirm === "revoke-previous"}
        onClose={() => setConfirm(null)}
        onConfirm={revokePreviousSecret}
        loading={busy}
        title="Revoke the previous secret now?"
        description="Requests will be signed with the current secret only. Any receiver still configured with the old secret will start rejecting deliveries immediately."
        confirmLabel="Revoke previous secret"
      />
    </>
  );
}
