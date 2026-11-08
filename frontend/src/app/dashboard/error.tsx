"use client";

import { useEffect } from "react";
import { AlertTriangle, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/Button";

/**
 * The dashboard error boundary.
 *
 * Catches a render or data-fetch failure in any dashboard page so the whole app
 * does not go blank. `digest` is Next's server-side error identifier: the
 * message itself is withheld from the browser in production (deliberately — it
 * could contain internal detail), and the digest is what correlates the user's
 * report with the server log.
 */
export default function DashboardError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Surfaced in the browser console for local debugging; the server has the
    // full stack.
    console.error("Dashboard render failed", error);
  }, [error]);

  return (
    <div
      role="alert"
      className="flex flex-col items-center justify-center rounded-lg border border-border bg-surface px-6 py-16 text-center"
    >
      <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-lg border border-status-failed-border bg-status-failed-bg text-status-failed">
        <AlertTriangle className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="text-lg font-semibold text-content">This page failed to load</h1>
      <p className="mt-2 max-w-md text-sm leading-relaxed text-content-secondary">
        Something went wrong rendering this view. The usual cause in local development is
        that the RelayForge API is not running — check{" "}
        <code className="rf-mono">docker compose ps</code>.
      </p>
      {error.digest ? (
        <p className="rf-mono mt-4 text-content-tertiary">
          digest <span className="text-content-secondary">{error.digest}</span>
        </p>
      ) : null}
      <Button
        variant="primary"
        className="mt-6"
        onClick={reset}
        leadingIcon={<RotateCcw className="h-3.5 w-3.5" aria-hidden />}
      >
        Try again
      </Button>
    </div>
  );
}
