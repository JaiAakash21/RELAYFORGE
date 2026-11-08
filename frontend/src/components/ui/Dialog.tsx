"use client";

import { useCallback, useEffect, useRef } from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./Button";

/**
 * A modal dialog.
 *
 * Built on the native `<dialog>` element so the browser provides the parts that
 * are easy to get wrong: focus is trapped inside while open, the rest of the page
 * is inert, Escape closes it, and the dialog is announced correctly. A
 * hand-rolled div would need all of that reimplemented, and the usual result is a
 * modal a keyboard user can tab out of behind.
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = "md",
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children?: React.ReactNode;
  footer?: React.ReactNode;
  size?: "sm" | "md" | "lg";
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (open && !element.open) {
      element.showModal();
    } else if (!open && element.open) {
      element.close();
    }
  }, [open]);

  // The browser fires `cancel` for Escape. Intercepting it keeps React state
  // and the DOM in agreement — otherwise the dialog closes while `open` is
  // still true and will not reopen.
  const handleCancel = useCallback(
    (event: React.SyntheticEvent<HTMLDialogElement>) => {
      event.preventDefault();
      onClose();
    },
    [onClose],
  );

  const sizes = { sm: "max-w-sm", md: "max-w-lg", lg: "max-w-2xl" };

  return (
    <dialog
      ref={ref}
      onCancel={handleCancel}
      onClose={() => {
        if (open) onClose();
      }}
      // Clicking the backdrop closes. The check compares the target to the
      // dialog itself: the padded ::backdrop area reports the dialog as target,
      // while a click on the content reports the inner div.
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
      aria-labelledby="dialog-title"
      aria-describedby={description ? "dialog-description" : undefined}
      className={cn(
        "m-auto w-[calc(100vw-2rem)] rounded-xl border border-border bg-surface-2 p-0 text-content shadow-overlay",
        "backdrop:bg-canvas/80 backdrop:backdrop-blur-sm",
        "open:animate-slide-up",
        sizes[size],
      )}
    >
      <div className="flex items-start justify-between gap-4 border-b border-border-subtle px-4 py-3">
        <div className="min-w-0">
          <h2 id="dialog-title" className="text-md font-semibold text-content">
            {title}
          </h2>
          {description ? (
            <p
              id="dialog-description"
              className="mt-1 text-sm leading-relaxed text-content-secondary"
            >
              {description}
            </p>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close dialog"
          className="-m-1 shrink-0 rounded p-1 text-content-tertiary transition-colors hover:bg-surface-4 hover:text-content"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      {children ? <div className="px-4 py-4">{children}</div> : null}

      {footer ? (
        <div className="flex items-center justify-end gap-2 border-t border-border-subtle px-4 py-3">
          {footer}
        </div>
      ) : null}
    </dialog>
  );
}

/**
 * Confirmation for a destructive action.
 *
 * Required before anything irreversible — deleting an endpoint, revoking a key.
 * The confirm button restates the verb ("Delete endpoint", not "OK") so the
 * dialog cannot be dismissed on autopilot without reading what it does.
 */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel,
  loading = false,
  tone = "danger",
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  description: string;
  confirmLabel: string;
  loading?: boolean;
  tone?: "danger" | "primary";
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      description={description}
      size="sm"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={loading}>
            Cancel
          </Button>
          <Button variant={tone} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    />
  );
}
