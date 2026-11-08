import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { PlaygroundView } from "./PlaygroundView";
import type { PlaygroundRunSummary, Scenario } from "@/types/api";

export const metadata: Metadata = { title: "Reliability Playground" };

/**
 * The Reliability Playground.
 *
 * <h2>Why this is a real demonstration</h2>
 *
 * Starting a run configures a controlled simulator to fail in a chosen way,
 * creates a genuine webhook endpoint pointing at it, and publishes a genuine
 * event through the ordinary ingestion path. The ordinary workers, retry engine
 * and dead-letter logic then handle it with no special-casing anywhere.
 *
 * <p>The timeline the page draws is a read of the platform's own
 * `delivery_attempts` records. Nothing is animated client-side — so if the retry
 * engine were broken, this page would show that rather than conceal it.
 */
export default async function PlaygroundPage() {
  let scenarios: Scenario[];
  let recentRuns: PlaygroundRunSummary[];
  try {
    [scenarios, recentRuns] = await Promise.all([
      apiGet<Scenario[]>("/api/v1/playground/scenarios"),
      apiGet<PlaygroundRunSummary[]>("/api/v1/playground/runs"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Reliability Playground" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load the Playground"
            message={
              error instanceof ApiRequestError
                ? error.message
                : "The API could not be reached."
            }
            traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
          />
        </Card>
      </>
    );
  }

  return <PlaygroundView scenarios={scenarios} recentRuns={recentRuns} />;
}
