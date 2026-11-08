"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Pause, Play, Plus, Send, Webhook } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/States";
import { HealthBadge, Badge } from "@/components/ui/StatusBadge";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { PageHeader } from "@/components/ui/PageHeader";
import { useToast } from "@/components/ui/Toast";
import { EndpointFormDialog } from "./EndpointFormDialog";
import { SecretRevealDialog } from "./SecretRevealDialog";
import { ClientApiError, api } from "@/lib/api/client";
import { formatPercent, formatRelative } from "@/lib/format";
import type { EndpointSecret, EndpointTestResult, WebhookEndpoint } from "@/types/api";

/** The endpoint list with create, pause/resume and test actions. */
export function EndpointsView({
  initial,
  knownEventTypes,
}: {
  initial: WebhookEndpoint[];
  knownEventTypes: string[];
}) {
  const router = useRouter();
  const toast = useToast();
  const [createOpen, setCreateOpen] = useState(false);
  const [revealed, setRevealed] = useState<EndpointSecret | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  async function togglePause(endpoint: WebhookEndpoint) {
    setBusyId(endpoint.id);
    const action = endpoint.paused ? "resume" : "pause";
    try {
      await api.post(`/endpoints/${endpoint.id}/${action}`);
      toast.success(
        endpoint.paused ? "Delivery resumed" : "Delivery paused",
        endpoint.paused
          ? "Held deliveries will be attempted again shortly."
          : "Queued deliveries are held, not discarded. They resume when you unpause.",
      );
      router.refresh();
    } catch (error) {
      toast.error(
        `Could not ${action} the endpoint`,
        error instanceof ClientApiError ? error.message : "The request failed.",
      );
    } finally {
      setBusyId(null);
    }
  }

  async function sendTest(endpoint: WebhookEndpoint) {
    setBusyId(endpoint.id);
    try {
      const result = await api.post<EndpointTestResult>(
        `/endpoints/${endpoint.id}/test`,
      );
      if (result.delivered) {
        toast.success(
          "Test delivered",
          `The endpoint responded ${result.responseStatus} in ${result.durationMs}ms.`,
        );
      } else {
        toast.error(
          "Test delivery failed",
          result.errorMessage ??
            `The endpoint did not accept the request (${result.errorClass}).`,
        );
      }
      router.refresh();
    } catch (error) {
      toast.error(
        "Could not send the test",
        error instanceof ClientApiError ? error.message : "The request failed.",
      );
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Endpoints"
        description="Destinations RelayForge delivers to. Each gets its own signing secret, subscription list and retry policy."
        actions={
          <Button
            variant="primary"
            onClick={() => setCreateOpen(true)}
            leadingIcon={<Plus className="h-3.5 w-3.5" aria-hidden />}
          >
            Add endpoint
          </Button>
        }
      />

      <Card>
        {initial.length === 0 ? (
          <EmptyState
            icon={<Webhook className="h-4 w-4" aria-hidden />}
            title="No endpoints yet"
            description="Register a destination URL and the event types it should receive. You get a signing secret to verify requests with."
            action={
              <Button variant="primary" onClick={() => setCreateOpen(true)}>
                Add your first endpoint
              </Button>
            }
          />
        ) : (
          <TableWrapper>
            <Thead>
              <Tr>
                <Th>Endpoint</Th>
                <Th>Subscriptions</Th>
                <Th>Health (24h)</Th>
                <Th align="right">Success rate</Th>
                <Th align="right">Created</Th>
                <Th align="right">Actions</Th>
              </Tr>
            </Thead>
            <Tbody>
              {initial.map((endpoint) => (
                <Tr key={endpoint.id}>
                  <Td className="max-w-[20rem]">
                    <Link
                      href={`/dashboard/endpoints/${endpoint.id}`}
                      className="block truncate font-medium text-content hover:text-accent-hover hover:underline"
                    >
                      {endpoint.name}
                    </Link>
                    <span className="rf-mono mt-0.5 block truncate text-content-tertiary">
                      {endpoint.url}
                    </span>
                    {endpoint.paused ? (
                      <Badge tone="warning" className="mt-1">
                        Paused
                      </Badge>
                    ) : !endpoint.active ? (
                      <Badge tone="neutral" className="mt-1">
                        Inactive
                      </Badge>
                    ) : null}
                  </Td>
                  <Td className="max-w-[14rem]">
                    {endpoint.subscribedEventTypes.length === 0 ? (
                      <Badge tone="accent">all event types</Badge>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {endpoint.subscribedEventTypes.slice(0, 3).map((type) => (
                          <code
                            key={type}
                            className="rf-mono rounded bg-surface-2 px-1.5 py-0.5"
                          >
                            {type}
                          </code>
                        ))}
                        {endpoint.subscribedEventTypes.length > 3 ? (
                          <span className="text-2xs text-content-tertiary">
                            +{endpoint.subscribedEventTypes.length - 3}
                          </span>
                        ) : null}
                      </div>
                    )}
                  </Td>
                  <Td>
                    <HealthBadge state={endpoint.health.state} />
                    {endpoint.health.succeeded + endpoint.health.failed > 0 ? (
                      <p className="tabular mt-1 text-2xs text-content-tertiary">
                        {endpoint.health.succeeded} ok · {endpoint.health.failed} failed
                        {endpoint.health.pending > 0
                          ? ` · ${endpoint.health.pending} live`
                          : ""}
                      </p>
                    ) : null}
                  </Td>
                  <Td align="right" className="tabular">
                    {endpoint.health.succeeded + endpoint.health.failed === 0 ? (
                      <span className="text-content-tertiary">—</span>
                    ) : (
                      formatPercent(endpoint.health.successRate)
                    )}
                  </Td>
                  <Td align="right" className="whitespace-nowrap">
                    {formatRelative(endpoint.createdAt)}
                  </Td>
                  <Td align="right">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => sendTest(endpoint)}
                        loading={busyId === endpoint.id}
                        title="Publishes a real signed test event through the full pipeline"
                        leadingIcon={<Send className="h-3 w-3" aria-hidden />}
                      >
                        Test
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => togglePause(endpoint)}
                        loading={busyId === endpoint.id}
                        title={
                          endpoint.paused
                            ? "Resume delivery"
                            : "Hold deliveries without discarding them"
                        }
                        leadingIcon={
                          endpoint.paused ? (
                            <Play className="h-3 w-3" aria-hidden />
                          ) : (
                            <Pause className="h-3 w-3" aria-hidden />
                          )
                        }
                      >
                        {endpoint.paused ? "Resume" : "Pause"}
                      </Button>
                    </div>
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </TableWrapper>
        )}
      </Card>

      <EndpointFormDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        knownEventTypes={knownEventTypes}
        onCreated={(secret) => {
          setCreateOpen(false);
          setRevealed(secret);
          router.refresh();
        }}
      />

      <SecretRevealDialog
        secret={revealed}
        onClose={() => setRevealed(null)}
        title="Endpoint created"
      />
    </div>
  );
}
