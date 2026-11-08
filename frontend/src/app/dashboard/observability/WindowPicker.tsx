"use client";

import { useRouter } from "next/navigation";
import { InlineSelect } from "@/components/ui/Input";

const WINDOWS = [
  { value: "1h", label: "Last hour" },
  { value: "6h", label: "Last 6 hours" },
  { value: "24h", label: "Last 24 hours" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
];

/**
 * Window selector.
 *
 * Writes to the URL rather than to component state, so a selected window is
 * linkable and survives a reload — the same reasoning as the list-page filters.
 */
export function WindowPicker({ current }: { current: string }) {
  const router = useRouter();
  return (
    <InlineSelect
      ariaLabel="Metrics window"
      value={current}
      onChange={(event) =>
        router.push(`/dashboard/observability?window=${event.target.value}`)
      }
      options={WINDOWS}
    />
  );
}
