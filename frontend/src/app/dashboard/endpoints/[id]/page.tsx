import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { EndpointDetailView } from "./EndpointDetailView";
import type { Delivery, PageResponse, WebhookEndpoint } from "@/types/api";

export const metadata: Metadata = { title: "Endpoint" };

export default async function EndpointDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  let endpoint: WebhookEndpoint;
  let deliveries: PageResponse<Delivery>;
  let eventTypes: string[];
  try {
    [endpoint, deliveries, eventTypes] = await Promise.all([
      apiGet<WebhookEndpoint>(`/api/v1/endpoints/${id}`),
      apiGet<PageResponse<Delivery>>(`/api/v1/deliveries?endpointId=${id}&size=20`),
      apiGet<string[]>("/api/v1/events/types"),
    ]);
  } catch (error) {
    if (error instanceof ApiRequestError && error.isNotFound) {
      notFound();
    }
    return (
      <Card>
        <ErrorState
          title="Could not load the endpoint"
          message={
            error instanceof ApiRequestError
              ? error.message
              : "The API could not be reached."
          }
          traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
        />
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/dashboard/endpoints"
          className="inline-flex items-center gap-1.5 text-xs text-content-tertiary transition-colors hover:text-content"
        >
          <ArrowLeft className="h-3 w-3" aria-hidden />
          All endpoints
        </Link>
        <PageHeader className="mt-3" title={endpoint.name} description={endpoint.url} />
      </div>
      <EndpointDetailView
        endpoint={endpoint}
        deliveries={deliveries.items}
        totalDeliveries={deliveries.totalItems}
        knownEventTypes={eventTypes}
      />
    </div>
  );
}
