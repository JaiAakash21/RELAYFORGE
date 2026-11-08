"use client";

import Link from "next/link";
import { ArrowUpRight, Clock, Target } from "lucide-react";
import { Card, CardHeader } from "@/components/ui/Card";
import { DeliveryStatusBadge } from "@/components/ui/StatusBadge";
import { AttemptTimeline } from "@/components/layout/AttemptTimeline";
import { ReplayButton } from "@/components/layout/ReplayButton";
import { IdChip } from "@/components/ui/Code";
import { RunStatusChip } from "./PlaygroundView";
import { cn } from "@/lib/cn";
import { formatCountdown } from "@/lib/format";
import type { PlaygroundRun, Scenario } from "@/types/api";

/**
 * Live narration of a playground run.
 *
 * <h2>Two independent views of the same run</h2>
 *
 * The attempt timeline is RelayForge's record of what it did. The simulator
 * counters are the receiver's record of what it saw. Showing both is the point:
 * when they agree, the demonstration is self-corroborating, and when they
 * disagree (a request sent but no response received) that disagreement is itself
 * the interesting fact — it is exactly the at-least-once ambiguity the platform
 * documents rather than hides.
 */
export function RunTimeline({
  run,
  scenario,
}: {
  run: PlaygroundRun;
  scenario: Scenario | undefined;
}) {
  const delivery = run.delivery;
  const running = run.status === "RUNNING";

  return (
    <div className="flex flex-col gap-4">
      {/* Progress strip. The attempt counter and countdown are what a viewer
          watches, so they are the largest things on the panel. */}
      <Card
        className={cn(
          running ? "border-status-progress-border" : undefined,
          run.status === "SUCCEEDED" ? "border-status-success-border" : undefined,
          run.status === "DEAD_LETTERED" ? "border-status-failed-border" : undefined,
        )}
      >
        <CardHeader
          title={run.scenarioName}
          description={scenario?.description}
          actions={<RunStatusChip status={run.status} />}
        />
        <div className="grid gap-px bg-border sm:grid-cols-4">
          <Metric
            label="Attempts"
            value={
              delivery
                ? `${delivery.attemptCount}/${delivery.maxAttempts}`
                : "0/—"
            }
          />
          <Metric
            label="Delivery state"
            value={
              delivery ? (
                <DeliveryStatusBadge status={delivery.status} live />
              ) : (
                <span className="text-sm text-content-tertiary">queued</span>
              )
            }
          />
          <Metric
            label="Next attempt"
            value={
              run.secondsUntilNextAttempt !== null ? (
                <span className="tabular inline-flex items-center gap-1.5 text-status-retry">
                  <Clock className="h-3.5 w-3.5" aria-hidden />
                  {formatCountdown(run.secondsUntilNextAttempt)}
                </span>
              ) : (
                <span className="text-sm text-content-tertiary">—</span>
              )
            }
          />
          <Metric
            label="Simulator saw"
            value={
              run.simulator.requestsReceived !== null ? (
                <span className="tabular text-sm">
                  {run.simulator.requestsReceived} req ·{" "}
                  <span className="text-status-failed">
                    {run.simulator.failuresReturned ?? 0} failed
                  </span>{" "}
                  ·{" "}
                  <span className="text-status-success">
                    {run.simulator.successesReturned ?? 0} ok
                  </span>
                </span>
              ) : (
                <span className="text-sm text-content-tertiary">—</span>
              )
            }
          />
        </div>
      </Card>

      {/* The verdict. States the expectation and then the result, so the run can
          be judged rather than just observed. */}
      <div
        className={cn(
          "rf-card p-4",
          run.status === "SUCCEEDED" &&
            "border-status-success-border bg-status-success-bg/30",
          run.status === "DEAD_LETTERED" &&
            "border-status-failed-border bg-status-failed-bg/30",
          running && "border-status-progress-border bg-status-progress-bg/25",
        )}
      >
        <h3 className="flex items-center gap-2 text-base font-medium text-content">
          <Target className="h-4 w-4" aria-hidden />
          {running ? "Expected outcome" : "Outcome"}
        </h3>
        <p className="mt-1.5 text-sm leading-relaxed text-content-secondary">
          {running
            ? (scenario?.expectedOutcome ?? "Watching the delivery.")
            : (run.outcomeNote ?? "The run finished.")}
        </p>
        {!running && scenario ? (
          <p className="mt-2 border-t border-border-subtle pt-2 text-xs leading-relaxed text-content-tertiary">
            <span className="font-medium text-content-secondary">Expected:</span>{" "}
            {scenario.expectedOutcome}
          </p>
        ) : null}
        {run.status === "DEAD_LETTERED" && delivery?.replayable ? (
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <ReplayButton deliveryId={delivery.id} />
            <Link
              href="/dashboard/dead-letters"
              className="inline-flex items-center gap-1 text-xs text-accent-hover hover:underline"
            >
              See it in the dead-letter queue
              <ArrowUpRight className="h-3 w-3" aria-hidden />
            </Link>
          </div>
        ) : null}
      </div>

      <Card>
        <CardHeader
          title="Attempt timeline"
          description="Read directly from the delivery_attempts table. The gap between attempts is the retry engine's actual schedule, with jitter applied."
          actions={
            delivery ? (
              <Link
                href={`/dashboard/deliveries/${delivery.id}`}
                className="inline-flex items-center gap-1 text-xs text-accent-hover hover:underline"
              >
                Full delivery
                <ArrowUpRight className="h-3 w-3" aria-hidden />
              </Link>
            ) : null
          }
        />
        {run.attempts.length === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-content-tertiary">
            {running
              ? "Waiting for the first attempt. The event has been committed and queued; a worker will pick it up within a second or two."
              : "No attempts were recorded."}
          </p>
        ) : (
          <AttemptTimeline attempts={run.attempts} />
        )}
      </Card>

      <Card>
        <CardHeader title="Run details" />
        <dl className="grid gap-px bg-border sm:grid-cols-2">
          <DetailCell label="Run ID" value={<IdChip value={run.runId} length={18} />} />
          <DetailCell
            label="Simulator target"
            value={
              <code className="rf-mono break-all">{run.simulatorReceivePath}</code>
            }
          />
          <DetailCell
            label="Event"
            value={
              run.eventId ? (
                <Link
                  href={`/dashboard/events/${run.eventId}`}
                  className="text-accent-hover hover:underline"
                >
                  <IdChip value={run.eventId} length={18} />
                </Link>
              ) : (
                "—"
              )
            }
          />
          <DetailCell
            label="Endpoint"
            value={
              run.endpointId ? (
                <Link
                  href={`/dashboard/endpoints/${run.endpointId}`}
                  className="text-accent-hover hover:underline"
                >
                  <IdChip value={run.endpointId} length={18} />
                </Link>
              ) : (
                "—"
              )
            }
          />
          <DetailCell
            label="Injected configuration"
            value={
              <code className="rf-mono break-all text-content-tertiary">
                {JSON.stringify(run.config)}
              </code>
            }
            wide
          />
        </dl>
      </Card>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="bg-surface p-4">
      <p className="rf-label">{label}</p>
      <div className="mt-1.5 text-lg font-semibold text-content">{value}</div>
    </div>
  );
}

function DetailCell({
  label,
  value,
  wide = false,
}: {
  label: string;
  value: React.ReactNode;
  wide?: boolean;
}) {
  return (
    <div className={cn("bg-surface px-4 py-3", wide && "sm:col-span-2")}>
      <dt className="rf-label">{label}</dt>
      <dd className="mt-1 min-w-0 text-sm text-content">{value}</dd>
    </div>
  );
}
