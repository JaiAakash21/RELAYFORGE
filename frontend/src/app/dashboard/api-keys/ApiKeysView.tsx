"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, KeyRound, Plus, Shield, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { EmptyState } from "@/components/ui/States";
import { Badge } from "@/components/ui/StatusBadge";
import { CodeBlock, CopyButton } from "@/components/ui/Code";
import { TextInput } from "@/components/ui/Input";
import { PageHeader } from "@/components/ui/PageHeader";
import { Tbody, TableWrapper, Td, Th, Thead, Tr } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import { ClientApiError, api } from "@/lib/api/client";
import { formatAbsolute, formatRelative } from "@/lib/format";
import type { ApiKey, CreatedApiKey } from "@/types/api";

/**
 * API key management.
 *
 * <h2>What is never shown here</h2>
 *
 * The stored hash. It is not a usable credential, but displaying one invites
 * someone to treat it as an identifier, paste it into a config file, or compare
 * it by hand — so it never crosses the API boundary at all. The table shows the
 * non-secret prefix and a masked form, which is enough to tell two keys apart.
 */
export function ApiKeysView({ initial }: { initial: ApiKey[] }) {
  const router = useRouter();
  const toast = useToast();
  const [createOpen, setCreateOpen] = useState(false);
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function createKey(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const result = await api.post<CreatedApiKey>("/api-keys", {
        name: name.trim(),
        expiresAt: null,
      });
      setCreateOpen(false);
      setCreated(result);
      setName("");
      router.refresh();
    } catch (caught) {
      setError(
        caught instanceof ClientApiError
          ? caught.message
          : "The key could not be created.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function revokeKey() {
    if (!revoking) return;
    setBusy(true);
    try {
      await api.delete(`/api-keys/${revoking.id}`);
      toast.success(
        "Key revoked",
        "It stops working immediately — every API-key request is validated against the database.",
      );
      setRevoking(null);
      router.refresh();
    } catch (error) {
      toast.error(
        "Could not revoke the key",
        error instanceof ClientApiError ? error.message : "The request failed.",
      );
    } finally {
      setBusy(false);
    }
  }

  const activeKeys = initial.filter((key) => key.status === "ACTIVE");

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="API keys"
        description="Credentials your servers use to publish events. Stored only as a SHA-256 hash — the full key is shown exactly once, when it is created."
        actions={
          <Button
            variant="primary"
            onClick={() => setCreateOpen(true)}
            leadingIcon={<Plus className="h-3.5 w-3.5" aria-hidden />}
          >
            Create key
          </Button>
        }
      />

      <Card>
        {initial.length === 0 ? (
          <EmptyState
            icon={<KeyRound className="h-4 w-4" aria-hidden />}
            title="No API keys yet"
            description="Create a key to start publishing events from your application."
            action={
              <Button variant="primary" onClick={() => setCreateOpen(true)}>
                Create your first key
              </Button>
            }
          />
        ) : (
          <TableWrapper>
            <Thead>
              <Tr>
                <Th>Name</Th>
                <Th>Key</Th>
                <Th>Status</Th>
                <Th align="right">Last used</Th>
                <Th align="right">Created</Th>
                <Th align="right" />
              </Tr>
            </Thead>
            <Tbody>
              {initial.map((key) => (
                <Tr key={key.id}>
                  <Td>
                    <span className="font-medium text-content">{key.name}</span>
                  </Td>
                  <Td>
                    <code className="rf-mono rounded bg-surface-2 px-1.5 py-0.5">
                      {key.maskedKey}
                    </code>
                  </Td>
                  <Td>
                    <Badge
                      tone={
                        key.status === "ACTIVE"
                          ? "success"
                          : key.status === "EXPIRED"
                            ? "warning"
                            : "neutral"
                      }
                    >
                      {key.status.toLowerCase()}
                    </Badge>
                  </Td>
                  <Td align="right" className="whitespace-nowrap">
                    {key.lastUsedAt ? (
                      <span title={formatAbsolute(key.lastUsedAt)}>
                        {formatRelative(key.lastUsedAt)}
                      </span>
                    ) : (
                      <span className="text-content-tertiary">never</span>
                    )}
                  </Td>
                  <Td align="right" className="whitespace-nowrap">
                    {formatRelative(key.createdAt)}
                  </Td>
                  <Td align="right">
                    {key.status === "ACTIVE" ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => setRevoking(key)}
                        leadingIcon={<Trash2 className="h-3 w-3" aria-hidden />}
                      >
                        Revoke
                      </Button>
                    ) : (
                      <span className="text-2xs text-content-tertiary">
                        {key.revokedAt
                          ? `revoked ${formatRelative(key.revokedAt)}`
                          : "expired"}
                      </span>
                    )}
                  </Td>
                </Tr>
              ))}
            </Tbody>
          </TableWrapper>
        )}
      </Card>

      <SecurityGuidance hasActiveKey={activeKeys.length > 0} />

      <Dialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="Create an API key"
        description="The full key is returned once, in the next dialog. It is stored only as a hash and cannot be retrieved afterwards."
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreateOpen(false)} disabled={busy}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={createKey}
              loading={busy}
              type="submit"
              form="api-key-form"
            >
              Create key
            </Button>
          </>
        }
      >
        <form id="api-key-form" onSubmit={createKey} className="flex flex-col gap-4">
          {error ? (
            <p role="alert" className="text-xs text-status-failed">
              {error}
            </p>
          ) : null}
          <TextInput
            label="Name"
            required
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={120}
            placeholder="Production order service"
            help="Describes where the key is used, so you know which one to revoke if it leaks."
          />
        </form>
      </Dialog>

      <CreatedKeyDialog created={created} onClose={() => setCreated(null)} />

      <ConfirmDialog
        open={revoking !== null}
        onClose={() => setRevoking(null)}
        onConfirm={revokeKey}
        loading={busy}
        title={`Revoke "${revoking?.name ?? ""}"?`}
        description="Any service still using this key will start receiving 401 responses on its very next request. This cannot be undone — issue a new key instead."
        confirmLabel="Revoke key"
      />
    </div>
  );
}

/** Reveals a newly created key, with a usable example. */
function CreatedKeyDialog({
  created,
  onClose,
}: {
  created: CreatedApiKey | null;
  onClose: () => void;
}) {
  return (
    <Dialog
      open={created !== null}
      onClose={onClose}
      title="API key created"
      description="Copy it now. RelayForge stores only a hash and cannot show it again."
      size="lg"
      footer={
        <Button variant="primary" onClick={onClose}>
          I have stored it
        </Button>
      }
    >
      {created ? (
        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-2.5 rounded border border-status-retry-border bg-status-retry-bg/50 p-3">
            <AlertTriangle
              className="mt-0.5 h-4 w-4 shrink-0 text-status-retry"
              aria-hidden
            />
            <p className="text-xs leading-relaxed text-content-secondary">
              {created.warning}
            </p>
          </div>

          <div>
            <p className="rf-label mb-1.5">{created.apiKey.name}</p>
            <div className="flex items-start gap-2">
              <code className="min-w-0 flex-1 break-all rounded border border-border bg-canvas px-3 py-2 font-mono text-xs leading-relaxed text-content">
                {created.key}
              </code>
              <CopyButton value={created.key} size="md" />
            </div>
          </div>

          <CodeBlock
            language="bash"
            code={`curl -X POST http://localhost:8080/api/v1/events \\
  -H "X-API-Key: ${created.key}" \\
  -H "Idempotency-Key: order-1001-created" \\
  -H "Content-Type: application/json" \\
  -d '{
    "eventType": "order.created",
    "payload": { "orderId": "ORD-1001", "amount": 2499 }
  }'`}
          />

          <p className="text-xs leading-relaxed text-content-tertiary">
            Send an <code className="rf-mono">Idempotency-Key</code> whenever the call
            might be retried. A repeated key returns the original event instead of
            creating a second one — which is what makes your own retry logic safe.
          </p>
        </div>
      ) : null}
    </Dialog>
  );
}

function SecurityGuidance({ hasActiveKey }: { hasActiveKey: boolean }) {
  const points = [
    {
      title: "Keys are hashed, not encrypted",
      body: "RelayForge stores a SHA-256 digest. A database compromise does not yield usable keys, and nobody — including us — can recover one you have lost.",
    },
    {
      title: "Why SHA-256 and not BCrypt",
      body: "A key is 256 bits from a CSPRNG, so there is no dictionary to attack and a slow hash buys nothing. It would cost a great deal: the key is verified on every ingestion request. Passwords are a different problem and do use BCrypt.",
    },
    {
      title: "Revocation is immediate",
      body: "Unlike the dashboard's short-lived access token, every API-key request is validated against the database. Revoking takes effect on the next call, not when something expires.",
    },
    {
      title: "An API key cannot manage API keys",
      body: "Keys can publish events and read your organization's data. They deliberately cannot create or revoke credentials or change settings — otherwise one leaked ingestion key would become permanent access.",
    },
    {
      title: "One key per service",
      body: "Separate keys mean a leak can be revoked without taking every integration down with it, and `last used` tells you which key a given service is actually on.",
    },
  ];

  return (
    <Card>
      <CardHeader
        title={
          <span className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-accent-hover" aria-hidden />
            How RelayForge handles your keys
          </span>
        }
        description={
          hasActiveKey
            ? undefined
            : "Worth reading before you create your first key."
        }
      />
      <ul className="grid gap-px bg-border sm:grid-cols-2">
        {points.map((point) => (
          <li key={point.title} className="bg-surface p-4">
            <h3 className="text-base font-medium text-content">{point.title}</h3>
            <p className="mt-1 text-sm leading-relaxed text-content-secondary">
              {point.body}
            </p>
          </li>
        ))}
      </ul>
    </Card>
  );
}
