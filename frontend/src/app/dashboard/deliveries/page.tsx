import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { DeliveriesTable } from "./DeliveriesTable";
import type { Delivery, PageResponse, WebhookEndpoint } from "@/types/api";

export const metadata: Metadata = { title: "Deliveries" };

/** Every delivery, filterable by status and endpoint. */
export default async function DeliveriesPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; status?: string; endpointId?: string }>;
}) {
  const params = await searchParams;
  const page = Number.parseInt(params.page ?? "0", 10) || 0;
  const status = params.status?.trim() ?? "";
  const endpointId = params.endpointId?.trim() ?? "";

  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (status) query.set("status", status);
  if (endpointId) query.set("endpointId", endpointId);

  let deliveries: PageResponse<Delivery>;
  let endpoints: PageResponse<WebhookEndpoint>;
  try {
    [deliveries, endpoints] = await Promise.all([
      apiGet<PageResponse<Delivery>>(`/api/v1/deliveries?${query}`),
      apiGet<PageResponse<WebhookEndpoint>>("/api/v1/endpoints?size=100"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Deliveries" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load deliveries"
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
        title="Deliveries"
        description="One delivery per (event, endpoint) pair. This is the unit of work the delivery workers claim, and the authoritative record of whether a webhook arrived."
      />
      <DeliveriesTable
        initial={deliveries}
        endpoints={endpoints.items}
        activeStatus={status}
        activeEndpointId={endpointId}
      />
    </div>
  );
}
