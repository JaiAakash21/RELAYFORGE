import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  RefreshCw,
  RotateCcw,
  XCircle,
} from "lucide-react";
import { AttemptOutcomeBadge, HttpStatusBadge } from "@/components/ui/StatusBadge";
import { IdChip } from "@/components/ui/Code";
import { cn } from "@/lib/cn";
import {
  formatAbsolute,
  formatDuration,
  formatRelative,
  humanizeEnum,
} from "@/lib/format";
import type { DeliveryAttempt } from "@/types/api";

/**
 * The vertical attempt timeline.
 *
 * <h2>What it is built to answer</h2>
 *
 * "What actually happened to my webhook?" — which means the gap between attempts
 * matters as much as the attempts themselves. The gap is computed and labelled
 * on each node, because that is where the retry policy becomes visible: a reader
 * can see the delay roughly doubling, see jitter moving it off the exact power of
 * two, and see a `Retry-After` value overriding the curve entirely.
 *
 * <p>Each node also states the error class explicitly, including the case where
 * no HTTP status was received at all. A timeline that showed a blank status for a
 * timeout would make the most interesting failures look like missing data.
 */
export function AttemptTimeline({ attempts }: { attempts: DeliveryAttempt[] }) {
  if (attempts.length === 0) {
    return (
      <p className="px-4 py-8 text-center text-sm text-content-tertiary">
        No attempt has been made yet. The delivery is queued and a worker will pick it
        up shortly.
      </p>
    );
  }

  return (
    <ol className="relative px-4 py-2">
      {attempts.map((attempt, index) => {
        const previous = index > 0 ? attempts[index - 1] : undefined;
        const gapSeconds = previous
          ? (new Date(attempt.startedAt).getTime() -
              new Date(previous.completedAt).getTime()) /
            1000
          : null;
        const isLast = index === attempts.length - 1;

        return (
          <li key={attempt.id} className="relative flex gap-4 pb-6 last:pb-2">
            {/* The connecting rail. Stops before the last node so the line does
                not dangle below the final attempt. */}
            {!isLast ? (
              <span
                aria-hidden
                className="absolute left-[15px] top-8 h-[calc(100%-1rem)] w-px bg-border"
              />
            ) : null}

            <AttemptMarker attempt={attempt} />

            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-base font-medium text-content">
                  Attempt {attempt.attemptNumber}
                </span>
                <AttemptOutcomeBadge outcome={attempt.outcome} />
                <HttpStatusBadge status={attempt.responseStatus} />
                {attempt.kind !== "AUTOMATIC" ? (
                  <span className="rounded-full border border-border bg-surface-2 px-2 py-0.5 text-2xs text-content-tertiary">
                    {humanizeEnum(attempt.kind)}
                  </span>
                ) : null}
              </div>

              <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-2xs text-content-tertiary">
                <span title={formatAbsolute(attempt.startedAt)}>
                  {formatRelative(attempt.startedAt)}
                </span>
                <span className="tabular">took {formatDuration(attempt.durationMs)}</span>
                {gapSeconds !== null ? (
                  <span className="tabular text-status-retry">
                    +{formatDuration(gapSeconds * 1000)} after the previous attempt
                  </span>
                ) : null}
                {attempt.retryAfterSeconds !== null ? (
                  <span
                    className="tabular text-status-retry"
                    title="The receiver sent a Retry-After header. It takes precedence over the computed exponential backoff."
                  >
                    Retry-After: {attempt.retryAfterSeconds}s
                  </span>
                ) : null}
              </div>

              {attempt.errorMessage ? (
                <p
                  className={cn(
                    "mt-2 rounded border px-2.5 py-1.5 text-xs leading-relaxed",
                    attempt.outcome === "TERMINAL_FAILURE"
                      ? "border-status-failed-border bg-status-failed-bg/60 text-status-failed"
                      : "border-status-retry-border bg-status-retry-bg/60 text-status-retry",
                  )}
                >
                  {attempt.errorMessage}
                </p>
              ) : null}

              {attempt.responseBodyExcerpt ? (
                <details className="mt-2 group">
                  <summary className="cursor-pointer text-2xs text-content-tertiary transition-colors hover:text-content-secondary">
                    Response body excerpt
                  </summary>
                  {/* Rendered as text inside <pre>. The body is third-party
                      content, so it is never turned into markup. */}
                  <pre className="mt-1.5 max-h-48 overflow-auto rounded border border-border bg-canvas p-2.5 font-mono text-2xs leading-relaxed text-content-secondary">
                    {attempt.responseBodyExcerpt}
                  </pre>
                </details>
              ) : null}

              {attempt.responseHeadersExcerpt ? (
                <details className="mt-1.5">
                  <summary className="cursor-pointer text-2xs text-content-tertiary transition-colors hover:text-content-secondary">
                    Response headers
                  </summary>
                  <pre className="mt-1.5 overflow-auto rounded border border-border bg-canvas p-2.5 font-mono text-2xs leading-relaxed text-content-secondary">
                    {attempt.responseHeadersExcerpt}
                  </pre>
                </details>
              ) : null}

              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-2xs text-content-tertiary">
                {attempt.errorClass && attempt.errorClass !== "NONE" ? (
                  <span>
                    class <code className="rf-mono">{attempt.errorClass}</code>
                  </span>
                ) : null}
                {attempt.workerId ? (
                  <span title="The worker process that made this attempt">
                    worker <code className="rf-mono">{attempt.workerId}</code>
                  </span>
                ) : null}
                {attempt.traceId ? (
                  <span className="inline-flex items-center gap-1">
                    trace <IdChip value={attempt.traceId} length={12} />
                  </span>
                ) : null}
              </div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

function AttemptMarker({ attempt }: { attempt: DeliveryAttempt }) {
  const config =
    attempt.outcome === "SUCCESS"
      ? {
          icon: <CheckCircle2 className="h-3.5 w-3.5" aria-hidden />,
          className: "border-status-success-border bg-status-success-bg text-status-success",
        }
      : attempt.outcome === "TERMINAL_FAILURE"
        ? {
            icon: <XCircle className="h-3.5 w-3.5" aria-hidden />,
            className: "border-status-failed-border bg-status-failed-bg text-status-failed",
          }
        : attempt.errorClass === "READ_TIMEOUT" ||
            attempt.errorClass === "CONNECT_TIMEOUT"
          ? {
              icon: <Clock className="h-3.5 w-3.5" aria-hidden />,
              className: "border-status-retry-border bg-status-retry-bg text-status-retry",
            }
          : {
              icon: <AlertTriangle className="h-3.5 w-3.5" aria-hidden />,
              className: "border-status-retry-border bg-status-retry-bg text-status-retry",
            };

  const Icon =
    attempt.kind === "REPLAY" ? (
      <RotateCcw className="h-3.5 w-3.5" aria-hidden />
    ) : attempt.kind === "RETRY" ? (
      <RefreshCw className="h-3.5 w-3.5" aria-hidden />
    ) : null;

  return (
    <span
      className={cn(
        "relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full border",
        config.className,
      )}
    >
      {attempt.outcome === "SUCCESS" || attempt.outcome === "TERMINAL_FAILURE"
        ? config.icon
        : (Icon ?? config.icon)}
    </span>
  );
}
