import { cn } from "@/lib/cn";

/**
 * A single metric tile.
 *
 * <h2>What is deliberately absent</h2>
 *
 * No sparkline, no percentage-change arrow. Both require a comparison baseline
 * the API does not provide, and inventing one — comparing against the previous
 * window, say, without saying so — would present a derived number as a measured
 * one. When a trend is genuinely available it goes in a chart next to the tile,
 * where its axis can be labelled.
 *
 * @param hint shown as a tooltip; used to flag approximate values
 */
export function Stat({
  label,
  value,
  unit,
  hint,
  tone = "default",
  icon,
  className,
}: {
  label: string;
  value: React.ReactNode;
  unit?: string;
  hint?: string;
  tone?: "default" | "success" | "warning" | "danger" | "accent";
  icon?: React.ReactNode;
  className?: string;
}) {
  const tones: Record<string, string> = {
    default: "text-content",
    success: "text-status-success",
    warning: "text-status-retry",
    danger: "text-status-failed",
    accent: "text-accent-hover",
  };
  return (
    <div className={cn("rf-card p-4", className)} title={hint}>
      <div className="flex items-start justify-between gap-2">
        <span className="rf-label">{label}</span>
        {icon ? <span className="text-content-tertiary">{icon}</span> : null}
      </div>
      <div className="mt-2 flex items-baseline gap-1">
        <span className={cn("tabular text-2xl font-semibold leading-none", tones[tone])}>
          {value}
        </span>
        {unit ? (
          <span className="text-sm font-normal text-content-tertiary">{unit}</span>
        ) : null}
      </div>
      {hint ? (
        <p className="mt-2 text-2xs leading-relaxed text-content-tertiary">{hint}</p>
      ) : null}
    </div>
  );
}
