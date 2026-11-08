"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { FlaskConical, Play, Target } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { Card, CardHeader } from "@/components/ui/Card";
import { PageHeader } from "@/components/ui/PageHeader";
import { TextInput } from "@/components/ui/Input";
import { InlineError } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import { cn } from "@/lib/cn";
import { ClientApiError, api } from "@/lib/api/client";
import { formatRelative } from "@/lib/format";
import { RunTimeline } from "./RunTimeline";
import type { PlaygroundRun, PlaygroundRunSummary, Scenario } from "@/types/api";

/**
 * Scenario selection, configuration and live run narration.
 *
 * <h2>Polling, not server-sent events</h2>
 *
 * A run is a handful of rows behind an indexed query, and polling it once a
 * second is cheap. It was chosen over SSE deliberately: polling survives a page
 * reload, a worker restart and a proxy that buffers responses, none of which an
 * SSE stream handles without reconnection logic and heartbeats. The simpler
 * mechanism that cannot silently stop working is the better one here.
 *
 * <p>Polling stops the moment the run reaches a terminal state, so a finished
 * run is never polled.
 */
export function PlaygroundView({
  scenarios,
  recentRuns,
}: {
  scenarios: Scenario[];
  recentRuns: PlaygroundRunSummary[];
}) {
  const toast = useToast();
  const [selectedId, setSelectedId] = useState(scenarios[0]?.id ?? "");
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [run, setRun] = useState<PlaygroundRun | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selected = scenarios.find((scenario) => scenario.id === selectedId);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  // Poll the active run. Cleared on unmount and whenever the run finishes.
  useEffect(() => {
    if (!run || run.status !== "RUNNING") {
      stopPolling();
      return;
    }
    if (pollRef.current) {
      return;
    }
    pollRef.current = setInterval(async () => {
      try {
        const next = await api.get<PlaygroundRun>(`/playground/runs/${run.runId}`);
        setRun(next);
        if (next.status !== "RUNNING") {
          stopPolling();
          if (next.status === "SUCCEEDED") {
            toast.success("Run succeeded", next.outcomeNote ?? undefined);
          } else if (next.status === "DEAD_LETTERED") {
            toast.info("Run dead-lettered", next.outcomeNote ?? undefined);
          }
        }
      } catch {
        // A single failed poll is not worth interrupting the run for; the next
        // tick will pick the state up.
      }
    }, 1000);
    return stopPolling;
  }, [run, stopPolling, toast]);

  useEffect(() => stopPolling, [stopPolling]);

  // Reset overrides when the scenario changes, so a value typed for one
  // scenario is not silently carried into another where it means something else.
  useEffect(() => {
    setOverrides({});
  }, [selectedId]);

  async function startRun() {
    if (!selected) return;
    setError(null);
    setStarting(true);
    stopPolling();

    const body: Record<string, unknown> = { scenarioId: selected.id };
    for (const parameter of selected.parameters) {
      const raw = overrides[parameter.key];
      if (raw !== undefined && raw.trim() !== "") {
        const parsed = Number(raw);
        if (Number.isFinite(parsed)) {
          body[parameter.key] = parsed;
        }
      }
    }

    try {
      const started = await api.post<PlaygroundRun>("/playground/test-events", body);
      setRun(started);
    } catch (caught) {
      const message =
        caught instanceof ClientApiError
          ? caught.message
          : "The run could not be started.";
      setError(message);
      toast.error("Could not start the run", message);
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Reliability Playground"
        description="Break a receiver on purpose and watch the pipeline handle it. Every run creates a real endpoint, publishes a real event and is processed by the ordinary delivery workers — the timeline below is the backend's own attempt history, not an animation."
      />

      <div className="grid gap-6 lg:grid-cols-[22rem_minmax(0,1fr)]">
        <div className="flex flex-col gap-4">
          <Card>
            <CardHeader title="1 · Choose a failure" />
            <ul className="divide-y divide-border-subtle" role="radiogroup" aria-label="Scenario">
              {scenarios.map((scenario) => {
                const active = scenario.id === selectedId;
                return (
                  <li key={scenario.id}>
                    <button
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => setSelectedId(scenario.id)}
                      className={cn(
                        "flex w-full flex-col items-start gap-1 px-4 py-3 text-left transition-colors",
                        active
                          ? "bg-accent-subtle/60"
                          : "hover:bg-surface-2 focus-visible:bg-surface-2",
                      )}
                    >
                      <span className="flex items-center gap-2">
                        <span
                          aria-hidden
                          className={cn(
                            "h-2 w-2 shrink-0 rounded-full",
                            active ? "bg-accent" : "bg-border-strong",
                          )}
                        />
                        <span
                          className={cn(
                            "text-base font-medium",
                            active ? "text-accent-hover" : "text-content",
                          )}
                        >
                          {scenario.name}
                        </span>
                      </span>
                      <span className="pl-4 text-xs leading-relaxed text-content-secondary">
                        {scenario.description}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </Card>

          {selected ? (
            <Card>
              <CardHeader title="2 · Configure and run" />
              <div className="flex flex-col gap-4 p-4">
                {selected.parameters.map((parameter) => {
                  const fallback = defaultFor(selected, parameter.key);
                  return (
                    <TextInput
                      key={parameter.key}
                      label={parameter.label}
                      type="number"
                      min={parameter.min ?? undefined}
                      max={parameter.max ?? undefined}
                      value={overrides[parameter.key] ?? ""}
                      onChange={(event) =>
                        setOverrides((current) => ({
                          ...current,
                          [parameter.key]: event.target.value,
                        }))
                      }
                      placeholder={fallback !== null ? String(fallback) : "default"}
                      help={parameter.help}
                    />
                  );
                })}

                {error ? <InlineError message={error} /> : null}

                <Button
                  variant="primary"
                  size="lg"
                  onClick={startRun}
                  loading={starting}
                  leadingIcon={<Play className="h-4 w-4" aria-hidden />}
                >
                  Publish a test event
                </Button>

                <p className="text-2xs leading-relaxed text-content-tertiary">
                  Creates an endpoint pointing at the controlled simulator, publishes an
                  event, and polls the resulting delivery once a second. Playground
                  endpoints use short retry delays so a run finishes while you watch; the
                  algorithm itself is unchanged.
                </p>
              </div>
            </Card>
          ) : null}

          {recentRuns.length > 0 ? (
            <Card>
              <CardHeader title="Recent runs" />
              <ul className="divide-y divide-border-subtle">
                {recentRuns.slice(0, 8).map((summary) => (
                  <li
                    key={summary.runId}
                    className="flex items-center justify-between gap-3 px-4 py-2.5"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm text-content">
                        {summary.scenarioName}
                      </p>
                      <p className="text-2xs text-content-tertiary">
                        {formatRelative(summary.createdAt)}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      <RunStatusChip status={summary.status} />
                      {summary.deliveryId ? (
                        <Link
                          href={`/dashboard/deliveries/${summary.deliveryId}`}
                          className="text-2xs text-accent-hover hover:underline"
                        >
                          open
                        </Link>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}
        </div>

        <div className="min-w-0">
          {run ? (
            <RunTimeline run={run} scenario={selected} />
          ) : (
            <ExplainerPanel scenario={selected} />
          )}
        </div>
      </div>
    </div>
  );
}

function defaultFor(scenario: Scenario, key: string): number | null {
  const defaults = scenario.defaults as unknown as Record<string, number>;
  const value = defaults[key];
  return typeof value === "number" ? value : null;
}

export function RunStatusChip({ status }: { status: string }) {
  const styles: Record<string, string> = {
    RUNNING: "border-status-progress-border bg-status-progress-bg text-status-progress",
    SUCCEEDED: "border-status-success-border bg-status-success-bg text-status-success",
    DEAD_LETTERED: "border-status-failed-border bg-status-failed-bg text-status-failed",
    CANCELLED: "border-status-neutral-border bg-status-neutral-bg text-status-neutral",
  };
  const labels: Record<string, string> = {
    RUNNING: "Running",
    SUCCEEDED: "Succeeded",
    DEAD_LETTERED: "Dead-lettered",
    CANCELLED: "Cancelled",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 text-2xs font-medium",
        styles[status] ?? styles.CANCELLED,
      )}
    >
      <span
        aria-hidden
        className={cn(
          "h-1.5 w-1.5 rounded-full bg-current",
          status === "RUNNING" && "animate-pulse-dot",
        )}
      />
      {labels[status] ?? status}
    </span>
  );
}

/**
 * Shown before a run starts.
 *
 * States what the scenario teaches and what the expected outcome is, up front.
 * That is what lets a viewer judge whether the system behaved correctly rather
 * than merely watch something happen — and it is the difference between a demo
 * that impresses and one that can be followed.
 */
function ExplainerPanel({ scenario }: { scenario: Scenario | undefined }) {
  if (!scenario) {
    return null;
  }
  return (
    <Card className="h-full">
      <CardHeader
        title={
          <span className="flex items-center gap-2">
            <FlaskConical className="h-4 w-4 text-accent-hover" aria-hidden />
            {scenario.name}
          </span>
        }
        description={scenario.description}
      />
      <div className="flex flex-col gap-5 p-5">
        <section>
          <h3 className="rf-label">What this demonstrates</h3>
          <p className="mt-1.5 text-sm leading-relaxed text-content-secondary">
            {scenario.teaches}
          </p>
        </section>

        <section className="rounded-lg border border-accent-muted bg-accent-subtle/30 p-4">
          <h3 className="flex items-center gap-2 text-base font-medium text-content">
            <Target className="h-4 w-4 text-accent-hover" aria-hidden />
            Expected outcome
          </h3>
          <p className="mt-1.5 text-sm leading-relaxed text-content-secondary">
            {scenario.expectedOutcome}
          </p>
        </section>

        <section>
          <h3 className="rf-label">What happens when you press run</h3>
          <ol className="mt-2 space-y-2">
            {[
              "The simulator is configured to fail in exactly this way.",
              "A real webhook endpoint is created, pointing at that simulator target, with its own AES-GCM-sealed signing secret.",
              "A real event is published through POST /api/v1/events — same transaction, same outbox rows, same Kafka relay.",
              "The ordinary delivery workers pick it up. Nothing about this path is special-cased for the Playground.",
              "This panel polls the resulting delivery and shows its recorded attempts.",
            ].map((step, index) => (
              <li key={step} className="flex gap-2.5 text-sm text-content-secondary">
                <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-border bg-surface-2 text-2xs text-content-tertiary">
                  {index + 1}
                </span>
                <span className="leading-relaxed">{step}</span>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </Card>
  );
}
