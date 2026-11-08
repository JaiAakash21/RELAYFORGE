import { AlertTriangle, Inbox, Loader2 } from "lucide-react";
import { cn } from "@/lib/cn";

/**
 * Loading, empty and error states.
 *
 * All three exist as first-class components because a dashboard spends a
 * surprising amount of its life in one of them, and leaving them to be improvised
 * per screen is how a product ends up with four different spinners and a blank
 * white box where a table should be.
 */

/** Skeleton rows that match the table they replace, so the layout does not jump. */
export function TableSkeleton({
  rows = 6,
  columns = 5,
}: {
  rows?: number;
  columns?: number;
}) {
  return (
    <div className="divide-y divide-border-subtle" aria-busy aria-live="polite">
      <span className="sr-only">Loading</span>
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <div key={rowIndex} className="flex items-center gap-4 px-3 py-3">
          {Array.from({ length: columns }).map((__, columnIndex) => (
            <div
              key={columnIndex}
              className="rf-skeleton h-3.5"
              style={{
                // Varied widths so the skeleton reads as content rather than as
                // a progress bar.
                width: columnIndex === 0 ? "22%" : `${12 + ((columnIndex * 7) % 18)}%`,
              }}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

export function CardSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn("rf-card p-4", className)} aria-busy>
      <div className="rf-skeleton h-2.5 w-24" />
      <div className="rf-skeleton mt-3 h-7 w-20" />
    </div>
  );
}

export function Spinner({ className }: { className?: string }) {
  return (
    <Loader2
      className={cn("h-4 w-4 animate-spin text-content-tertiary", className)}
      aria-hidden
    />
  );
}

/**
 * An empty state.
 *
 * Always takes an action where one exists. "No endpoints yet" is a dead end;
 * "No endpoints yet — create one" is the next step.
 */
export function EmptyState({
  title,
  description,
  action,
  icon,
  className,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  icon?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center px-6 py-14 text-center",
        className,
      )}
    >
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg border border-border bg-surface-2 text-content-tertiary">
        {icon ?? <Inbox className="h-4 w-4" aria-hidden />}
      </div>
      <h3 className="text-md font-medium text-content">{title}</h3>
      {description ? (
        <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-content-secondary">
          {description}
        </p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

/**
 * An error state.
 *
 * Shows the trace id when the API supplied one. That single string is what turns
 * a user's "it broke" into something searchable in the server logs, so it is
 * surfaced rather than swallowed.
 */
export function ErrorState({
  title = "Something went wrong",
  message,
  traceId,
  action,
  className,
}: {
  title?: string;
  message?: string;
  traceId?: string | null;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center justify-center px-6 py-12 text-center",
        className,
      )}
    >
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-lg border border-status-failed-border bg-status-failed-bg text-status-failed">
        <AlertTriangle className="h-4 w-4" aria-hidden />
      </div>
      <h3 className="text-md font-medium text-content">{title}</h3>
      {message ? (
        <p className="mt-1.5 max-w-md text-sm leading-relaxed text-content-secondary">
          {message}
        </p>
      ) : null}
      {traceId ? (
        <p className="rf-mono mt-3 text-content-tertiary">
          trace <span className="text-content-secondary">{traceId}</span>
        </p>
      ) : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  );
}

/** A compact inline error, for use above a form or beside a control. */
export function InlineError({ message }: { message: string }) {
  return (
    <p
      role="alert"
      className="flex items-start gap-1.5 text-xs leading-relaxed text-status-failed"
    >
      <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
      {message}
    </p>
  );
}
