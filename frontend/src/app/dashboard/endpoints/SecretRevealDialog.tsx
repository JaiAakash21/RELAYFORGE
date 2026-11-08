"use client";

import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { CopyButton } from "@/components/ui/Code";
import type { EndpointSecret } from "@/types/api";

/**
 * Reveals a signing secret, once.
 *
 * <h2>Why this is a blocking dialog with a deliberate dismiss</h2>
 *
 * The secret in this dialog cannot be retrieved again — it is sealed with
 * AES-256-GCM before storage and the plaintext exists nowhere else. A toast that
 * fades after three seconds would lose it. So the dialog blocks, states plainly
 * that this is the only time the value is shown, and makes the user acknowledge
 * that before closing.
 *
 * <p>The value is rendered with `break-all` so the full secret is visible rather
 * than truncated. A truncated credential that looks complete is worse than no
 * credential at all.
 */
export function SecretRevealDialog({
  secret,
  onClose,
  title,
}: {
  secret: EndpointSecret | null;
  onClose: () => void;
  title: string;
}) {
  return (
    <Dialog
      open={secret !== null}
      onClose={onClose}
      title={title}
      description="Copy this signing secret now. It is encrypted at rest and cannot be shown again."
      footer={
        <Button variant="primary" onClick={onClose}>
          I have stored it
        </Button>
      }
    >
      {secret ? (
        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-2.5 rounded border border-status-retry-border bg-status-retry-bg/50 p-3">
            <AlertTriangle
              className="mt-0.5 h-4 w-4 shrink-0 text-status-retry"
              aria-hidden
            />
            <p className="text-xs leading-relaxed text-content-secondary">
              {secret.warning}
            </p>
          </div>

          <div>
            <p className="rf-label mb-1.5">Signing secret</p>
            <div className="flex items-start gap-2">
              <code className="min-w-0 flex-1 break-all rounded border border-border bg-canvas px-3 py-2 font-mono text-xs leading-relaxed text-content">
                {secret.secret}
              </code>
              <CopyButton value={secret.secret} size="md" label="Copy" />
            </div>
          </div>

          <div className="rounded border border-border bg-surface-3 p-3">
            <p className="text-xs font-medium text-content">Verifying requests</p>
            <p className="mt-1.5 text-xs leading-relaxed text-content-secondary">
              Each delivery carries{" "}
              <code className="rf-mono">X-RelayForge-Signature: t=&lt;unix&gt;,v1=&lt;hex&gt;</code>
              , where the signature is{" "}
              <code className="rf-mono">HMAC-SHA256(secret, &quot;&lt;t&gt;.&lt;raw body&gt;&quot;)</code>
              . Compare it in constant time, and reject timestamps outside a tolerance
              window — otherwise a captured request stays replayable forever. During a
              secret rotation the header carries one <code className="rf-mono">v1=</code>{" "}
              value per valid secret; accept the request if any of them matches.
            </p>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}
