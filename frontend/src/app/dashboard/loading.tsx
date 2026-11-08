import { CardSkeleton, TableSkeleton } from "@/components/ui/States";

/**
 * The dashboard loading skeleton.
 *
 * Matches the shape of the pages it stands in for — a stat row above a table —
 * so the layout does not jump when the real content arrives. A centred spinner
 * would be less work and would cause a visible reflow on every navigation.
 */
export default function DashboardLoading() {
  return (
    <div className="flex flex-col gap-6" aria-busy aria-live="polite">
      <span className="sr-only">Loading</span>
      <div>
        <div className="rf-skeleton h-6 w-40" />
        <div className="rf-skeleton mt-2 h-3.5 w-80" />
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <CardSkeleton key={index} />
        ))}
      </div>
      <div className="rf-card">
        <div className="border-b border-border-subtle p-4">
          <div className="rf-skeleton h-4 w-32" />
        </div>
        <TableSkeleton rows={6} columns={6} />
      </div>
    </div>
  );
}
