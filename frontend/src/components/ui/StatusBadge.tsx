import { cn } from "@/lib/cn";
import type { AttemptOutcome, DeliveryStatus } from "@/types/api";

/**
 * Status pills for delivery and attempt states.
 *
 * <h2>Why colour is never the only signal</h2>
 *
 * Each badge carries its own text, and the shade of the leading dot differs
 * from the pill's background. Colour alone would be unreadable for the ~8% of
 * men with a red-green deficiency — and on this product the distinction that
 * matters most is precisely success (green) versus failed (red).
 */

const DELIVERY_STYLES: Record<DeliveryStatus, { label: string; className: string }> = {
  SUCCESS: {
    label: "Success",
    className:
      "bg-status-success-bg text-status-success border-status-success-border",
  },
  FAILED: {
    // "Failed" rather than "Dead-lettered": the status is the fact, and the
    // dead-letter queue is where it is acted on. Two names for one state would
    // make the UI and the API disagree.
    label: "Failed",
    className: "bg-status-failed-bg text-status-failed border-status-failed-border",
  },
  RETRY_SCHEDULED: {
    label: "Retry scheduled",
    className: "bg-status-retry-bg text-status-retry border-status-retry-border",
  },
  PENDING: {
    label: "Pending",
    className: "bg-status-pending-bg text-status-pending border-status-pending-border",
  },
  IN_PROGRESS: {
    label: "In progress",
    className:
      "bg-status-progress-bg text-status-progress border-status-progress-border",
  },
  CANCELLED: {
    label: "Cancelled",
    className: "bg-status-neutral-bg text-status-neutral border-status-neutral-border",
  },
};

const OUTCOME_STYLES: Record<AttemptOutcome, { label: string; className: string }> = {
  SUCCESS: {
    label: "Delivered",
    className: "bg-status-success-bg text-status-success border-status-success-border",
  },
  RETRYABLE_FAILURE: {
    label: "Retryable failure",
    className: "bg-status-retry-bg text-status-retry border-status-retry-border",
  },
  TERMINAL_FAILURE: {
    label: "Terminal failure",
    className: "bg-status-failed-bg text-status-failed border-status-failed-border",
  },
};

const BASE =
  "inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-2xs font-medium whitespace-nowrap";

export function DeliveryStatusBadge({
  status,
  live = false,
  className,
}: {
  status: DeliveryStatus;
  /** Animates the dot for states a worker is actively driving. */
  live?: boolean;
  className?: string;
}) {
  const style = DELIVERY_STYLES[status];
  const isActive = live && (status === "IN_PROGRESS" || status === "PENDING");
  return (
    <span className={cn(BASE, style.className, className)}>
      <span
        aria-hidden
        className={cn(
          "h-1.5 w-1.5 shrink-0 rounded-full bg-current",
          isActive && "animate-pulse-dot",
        )}
      />
      {style.label}
    </span>
  );
}

export function AttemptOutcomeBadge({
  outcome,
  className,
}: {
  outcome: AttemptOutcome;
  className?: string;
}) {
  const style = OUTCOME_STYLES[outcome];
  return (
    <span className={cn(BASE, style.className, className)}>
      <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {style.label}
    </span>
  );
}

/**
 * An HTTP status pill.
 *
 * Renders "No response" when the status is null. That is a real and important
 * case — a timeout or a dropped connection never produced a status — and
 * showing a blank cell would imply missing data rather than a meaningful one.
 */
export function HttpStatusBadge({
  status,
  className,
}: {
  status: number | null;
  className?: string;
}) {
  if (status === null) {
    return (
      <span
        className={cn(
          BASE,
          "bg-status-neutral-bg text-status-neutral border-status-neutral-border",
          className,
        )}
        title="No HTTP response was received — the request timed out or the connection failed"
      >
        No response
      </span>
    );
  }
  const tone =
    status < 300
      ? "bg-status-success-bg text-status-success border-status-success-border"
      : status === 429
        ? "bg-status-retry-bg text-status-retry border-status-retry-border"
        : status >= 500
          ? "bg-status-retry-bg text-status-retry border-status-retry-border"
          : "bg-status-failed-bg text-status-failed border-status-failed-border";
  return (
    <span className={cn(BASE, "font-mono tabular", tone, className)}>{status}</span>
  );
}

const HEALTH_STYLES: Record<string, { label: string; className: string }> = {
  HEALTHY: {
    label: "Healthy",
    className: "bg-status-success-bg text-status-success border-status-success-border",
  },
  DEGRADED: {
    label: "Degraded",
    className: "bg-status-retry-bg text-status-retry border-status-retry-border",
  },
  FAILING: {
    label: "Failing",
    className: "bg-status-failed-bg text-status-failed border-status-failed-border",
  },
  NO_DATA: {
    label: "No data",
    className: "bg-status-neutral-bg text-status-neutral border-status-neutral-border",
  },
  INSUFFICIENT_DATA: {
    // Named honestly. Calling an endpoint "healthy" on the strength of two
    // deliveries would be a claim the data does not support.
    label: "Too few deliveries",
    className: "bg-status-neutral-bg text-status-neutral border-status-neutral-border",
  },
};

export function HealthBadge({
  state,
  className,
}: {
  state: string;
  className?: string;
}) {
  const style = HEALTH_STYLES[state] ?? HEALTH_STYLES.NO_DATA!;
  return (
    <span className={cn(BASE, style.className, className)}>
      <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {style.label}
    </span>
  );
}

export function Badge({
  children,
  tone = "neutral",
  className,
}: {
  children: React.ReactNode;
  tone?: "neutral" | "accent" | "success" | "warning" | "danger";
  className?: string;
}) {
  const tones: Record<string, string> = {
    neutral: "bg-surface-3 text-content-secondary border-border",
    accent: "bg-accent-subtle text-accent-hover border-accent-muted",
    success: "bg-status-success-bg text-status-success border-status-success-border",
    warning: "bg-status-retry-bg text-status-retry border-status-retry-border",
    danger: "bg-status-failed-bg text-status-failed border-status-failed-border",
  };
  return <span className={cn(BASE, tones[tone], className)}>{children}</span>;
}
