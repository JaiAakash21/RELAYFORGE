"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { LiveIndicator } from "./AppShell";

/**
 * Refreshes a server-rendered page while the data it shows is still changing.
 *
 * <h2>Why polling, and why it stops</h2>
 *
 * `router.refresh()` re-runs the server component and streams the new markup in,
 * so the page stays server-rendered and there is no duplicate client-side
 * fetching layer to keep in step with it.
 *
 * <p>Polling stops on its own in two situations, both of which matter:
 *
 * <ul>
 *   <li><b>The tab is hidden.</b> A background tab left open overnight would
 *       otherwise issue thousands of pointless requests. The Page Visibility API
 *       pauses the timer and resumes with an immediate refresh when the tab comes
 *       back, so returning to it shows current data at once.
 *   <li><b>A cap is reached.</b> After the configured number of polls the page
 *       stops and offers a manual refresh. A delivery still moving after several
 *       minutes is on a long backoff, and a page quietly polling for an hour is
 *       a leak, not a feature.
 * </ul>
 *
 * The parent only mounts this component while the delivery is non-terminal, so a
 * finished delivery is never polled at all.
 */
export function DeliveryAutoRefresh({
  intervalMs = 3000,
  maxPolls = 120,
}: {
  intervalMs?: number;
  maxPolls?: number;
}) {
  const router = useRouter();
  const [polls, setPolls] = useState(0);
  const [paused, setPaused] = useState(
    typeof document !== "undefined" && document.visibilityState === "hidden",
  );

  const exhausted = polls >= maxPolls;

  useEffect(() => {
    const onVisibilityChange = () => {
      const hidden = document.visibilityState === "hidden";
      setPaused(hidden);
      if (!hidden) {
        // Catch up immediately rather than waiting a full interval.
        router.refresh();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, [router]);

  useEffect(() => {
    if (paused || exhausted) {
      return;
    }
    const timer = setInterval(() => {
      router.refresh();
      setPolls((count) => count + 1);
    }, intervalMs);
    return () => clearInterval(timer);
  }, [paused, exhausted, intervalMs, router]);

  return (
    <div className="flex items-center justify-between gap-3 rounded border border-border bg-surface-2 px-3 py-2">
      <LiveIndicator
        active={!paused && !exhausted}
        label={
          exhausted
            ? "Live updates paused"
            : paused
              ? "Paused while the tab is hidden"
              : "Live — refreshing every 3s"
        }
      />
      {exhausted ? (
        <button
          type="button"
          onClick={() => {
            setPolls(0);
            router.refresh();
          }}
          className="text-xs text-accent-hover hover:underline"
        >
          Resume updates
        </button>
      ) : null}
    </div>
  );
}
