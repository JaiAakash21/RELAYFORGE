import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { EndpointsView } from "./EndpointsView";
import type { PageResponse, WebhookEndpoint } from "@/types/api";

export const metadata: Metadata = { title: "Endpoints" };

export default async function EndpointsPage() {
  let endpoints: PageResponse<WebhookEndpoint>;
  let eventTypes: string[];
  try {
    [endpoints, eventTypes] = await Promise.all([
      apiGet<PageResponse<WebhookEndpoint>>("/api/v1/endpoints?size=100"),
      apiGet<string[]>("/api/v1/events/types"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Endpoints" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load endpoints"
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
    <EndpointsView initial={endpoints.items} knownEventTypes={eventTypes} />
  );
}
