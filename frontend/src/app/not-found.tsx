import Link from "next/link";
import { FileQuestion } from "lucide-react";

/**
 * The 404 page.
 *
 * Also what a cross-tenant identifier lands on: the API returns 404 rather than
 * 403 for a resource belonging to another organization, so it cannot be used to
 * confirm that someone else's id exists. The copy mentions that explicitly,
 * because "not found" on an id you just copied from a colleague is otherwise
 * confusing.
 */
export default function NotFound() {
  return (
    <main
      id="main"
      className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 text-center"
    >
      <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-lg border border-border bg-surface-2 text-content-tertiary">
        <FileQuestion className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="text-xl font-semibold text-content">Not found</h1>
      <p className="mt-2 max-w-md text-sm leading-relaxed text-content-secondary">
        This page, or the resource it refers to, does not exist. If you arrived from a
        link to a specific event or delivery, it may belong to a different organization —
        RelayForge answers 404 rather than 403 for those, so an identifier cannot be used
        to confirm that another tenant&apos;s data exists.
      </p>
      <div className="mt-6 flex items-center gap-3">
        <Link
          href="/dashboard"
          className="inline-flex h-9 items-center rounded bg-accent px-4 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover"
        >
          Back to the dashboard
        </Link>
        <Link
          href="/"
          className="inline-flex h-9 items-center rounded border border-border px-4 text-sm text-content-secondary transition-colors hover:bg-surface-2 hover:text-content"
        >
          Overview
        </Link>
      </div>
    </main>
  );
}
