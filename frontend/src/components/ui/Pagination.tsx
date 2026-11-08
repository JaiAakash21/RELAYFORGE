"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./Button";
import { formatCount } from "@/lib/format";

/**
 * Page controls.
 *
 * Offset-based, matching the API. Honest about its limitation: with a
 * continuously growing table, a row inserted while the user is on page 2 shifts
 * everything by one. The backend mitigates it by ordering on
 * `(createdAt DESC, id DESC)`, which at least makes the order total and stable
 * for existing rows. Cursor pagination would remove the drift entirely and is
 * listed in the suggested improvements.
 */
export function Pagination({
  page,
  size,
  totalItems,
  totalPages,
  onPageChange,
  loading = false,
}: {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  loading?: boolean;
}) {
  if (totalItems === 0) {
    return null;
  }
  const first = page * size + 1;
  const last = Math.min((page + 1) * size, totalItems);

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="tabular text-xs text-content-tertiary">
        Showing <span className="text-content-secondary">{formatCount(first)}</span>–
        <span className="text-content-secondary">{formatCount(last)}</span> of{" "}
        <span className="text-content-secondary">{formatCount(totalItems)}</span>
      </p>
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() => onPageChange(page - 1)}
          disabled={page === 0 || loading}
          leadingIcon={<ChevronLeft className="h-3.5 w-3.5" aria-hidden />}
        >
          Previous
        </Button>
        <span className="tabular px-1 text-xs text-content-tertiary">
          {page + 1} / {Math.max(1, totalPages)}
        </span>
        <Button
          size="sm"
          variant="outline"
          onClick={() => onPageChange(page + 1)}
          disabled={page + 1 >= totalPages || loading}
          trailingIcon={<ChevronRight className="h-3.5 w-3.5" aria-hidden />}
        >
          Next
        </Button>
      </div>
    </div>
  );
}
