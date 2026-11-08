import type { Metadata } from "next";
import Link from "next/link";
import { Send } from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { EventsTable } from "./EventsTable";
import type { EventListItem, PageResponse } from "@/types/api";

export const metadata: Metadata = { title: "Events" };

/**
 * Event history.
 *
 * <h2>Filters in the URL, not in component state</h2>
 *
 * The event type, search text and page are read from the query string and
 * rendered on the server. That makes a filtered view linkable and
 * back-button-correct — someone investigating an incident can paste the URL into
 * a ticket and the recipient sees the same rows. Holding the filters in
 * `useState` would make the view unshareable and would reset it on every reload.
 */
export default async function EventsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; eventType?: string; search?: string }>;
}) {
  const params = await searchParams;
  const page = Number.parseInt(params.page ?? "0", 10) || 0;
  const eventType = params.eventType?.trim() ?? "";
  const search = params.search?.trim() ?? "";

  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (eventType) query.set("eventType", eventType);
  if (search) query.set("search", search);

  let events: PageResponse<EventListItem>;
  let eventTypes: string[];
  try {
    [events, eventTypes] = await Promise.all([
      apiGet<PageResponse<EventListItem>>(`/api/v1/events?${query}`),
      apiGet<string[]>("/api/v1/events/types"),
    ]);
  } catch (error) {
    return (
      <>
        <PageHeader title="Events" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load events"
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
        title="Events"
        description="Everything your organization has published, and how far each event got. Events are immutable — delivery state lives on the deliveries they created."
        actions={
          <Link
            href="/dashboard/api-keys"
            className="inline-flex h-9 items-center gap-2 rounded border border-border bg-surface-2 px-3 text-sm text-content transition-colors hover:border-border-strong hover:bg-surface-3"
          >
            <Send className="h-3.5 w-3.5" aria-hidden />
            How to publish
          </Link>
        }
      />
      <EventsTable
        initial={events}
        eventTypes={eventTypes}
        activeEventType={eventType}
        activeSearch={search}
      />
    </div>
  );
}
