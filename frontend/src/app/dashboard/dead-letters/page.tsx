import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { DeadLetterTable } from "./DeadLetterTable";
import type { DeadLetter, PageResponse, WebhookEndpoint } from "@/types/api";

export const metadata: Metadata = { title: "Dead letters" };

/**
 * The dead-letter queue.
 *
 * <h2>Not a separate store</h2>
 *
 * This is a view over `deliveries WHERE status = 'FAILED'`, not a second copy of
 * the data. One row means one delivery, so a replay cannot leave a "dead letter"
 * and a "live delivery" describing the same thing and disagreeing about it — and
 * the attempt history stays attached to the delivery it explains.
 */
export default async function DeadLettersPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; endpointId?: string }>;
}) {
  const params = await searchParams;
  const page = Number.parseInt(params.page ?? "0", 10) || 0;
  const endpointId = params.endpointId?.trim() ?? "";

  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (endpointId) query.set("endpointId", endpointId);

  let deadLetters: PageResponse<DeadLetter>;
  let endpoints: PageResponse<WebhookEndpoint>;
  try {
    [deadLetters, endpoints] = await Promise.all([
      apiGet<PageResponse<DeadLetter>>(`/api/v1/dead-letters?${query}`),
      apiGet<PageResponse<WebhookEndpoint>>("/api/v1/endpoints?size=100"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Dead letters" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load the dead-letter queue"
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

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Dead-letter queue"
        description="Deliveries that exhausted their attempt budget, or failed in a way that retrying cannot fix. Their full attempt history is preserved, and replaying one appends new attempts rather than erasing the old ones."
      />
      <DeadLetterTable
        initial={deadLetters}
        endpoints={endpoints.items}
        activeEndpointId={endpointId}
      />
    </div>
  );
}
