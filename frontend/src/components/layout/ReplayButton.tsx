"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { TextArea } from "@/components/ui/Input";
import { useToast } from "@/components/ui/Toast";
import { ClientApiError, api } from "@/lib/api/client";
import type { ReplayResult } from "@/types/api";

/**
 * Replays one dead-lettered delivery.
 *
 * <h2>Why there is a confirmation step</h2>
 *
 * Replay re-sends a webhook, and the receiver may act on it — charge a card,
 * ship an order. That is not an action to take on a stray click, so it asks
 * first, and asks for a reason which is written to the replay audit record.
 *
 * <p>The explanatory copy states exactly what replay does and does not do,
 * because "replay" could reasonably be read as "start over" — and it does not:
 * the previous attempts are kept and attempt numbering continues upward.
 */
export function ReplayButton({
  deliveryId,
  onReplayed,
}: {
  deliveryId: string;
  onReplayed?: () => void;
}) {
  const router = useRouter();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function replay() {
    setSubmitting(true);
    try {
      const result = await api.post<ReplayResult>(
        `/deliveries/${deliveryId}/replay`,
        { reason: reason.trim() || null },
      );
      if (result.accepted) {
        toast.success("Replay accepted", result.message);
      } else {
        // Not an error: a concurrent replay already queued it, which is what the
        // caller wanted. Reported as information rather than as a failure.
        toast.info("Already queued", result.message);
      }
      setOpen(false);
      setReason("");
      onReplayed?.();
      router.refresh();
    } catch (error) {
      const message =
        error instanceof ClientApiError
          ? error.message
          : "The replay request could not be completed.";
      toast.error(
        "Replay failed",
        message,
        error instanceof ClientApiError ? error.payload.traceId : undefined,
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <Button
        variant="primary"
        size="md"
        onClick={() => setOpen(true)}
        leadingIcon={<RotateCcw className="h-3.5 w-3.5" aria-hidden />}
      >
        Replay
      </Button>

      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Replay this delivery"
        description="The webhook will be sent again. If your receiver is not idempotent, it may act on the event a second time."
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)} disabled={submitting}>
              Cancel
            </Button>
            <Button variant="primary" onClick={replay} loading={submitting}>
              Replay delivery
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="rounded border border-border bg-surface-3 p-3">
            <p className="text-xs font-medium text-content">What replay does</p>
            <ul className="mt-1.5 space-y-1 text-xs leading-relaxed text-content-secondary">
              <li>• Grants a fresh attempt budget and queues the delivery immediately.</li>
              <li>
                • Keeps every previous attempt. Numbering continues upward, so attempt 4
                follows the three failures rather than replacing them.
              </li>
              <li>
                • Reuses the same event and delivery ids, so a receiver that
                deduplicates on <code className="rf-mono">X-RelayForge-Event-Id</code>{" "}
                will recognise it.
              </li>
            </ul>
          </div>

          <TextArea
            label="Reason"
            help="Recorded in the replay audit trail. Useful when someone asks why a delivery was replayed three times."
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={500}
            placeholder="e.g. Receiver was redeployed with the correct signing secret"
          />
        </div>
      </Dialog>
    </>
  );
}
