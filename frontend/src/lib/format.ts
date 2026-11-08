/**
 * Display formatting.
 *
 * Centralised so that a duration or a timestamp is rendered the same way on
 * every screen. Inconsistent formatting across a dashboard makes two identical
 * numbers look like different measurements.
 */

/** Formats a millisecond duration at a sensible precision for its magnitude. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "—";
  if (ms < 1) return "<1ms";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) {
    // One decimal below a minute: the difference between 1.2s and 1.8s matters
    // when reading latency, and "1s" hides it.
    return `${(ms / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return seconds === 0 ? `${minutes}m` : `${minutes}m ${seconds}s`;
}

/** Formats a second count as a compact countdown, e.g. `2m 14s`. */
export function formatCountdown(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return "—";
  if (seconds <= 0) return "now";
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  if (minutes < 60) return rest === 0 ? `${minutes}m` : `${minutes}m ${rest}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

/**
 * Relative time, e.g. `3m ago`.
 *
 * Deliberately coarse beyond a day: "4d ago" is as much as anyone needs, and a
 * precise figure invites the reader to do arithmetic the absolute timestamp
 * already answers.
 */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return "—";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "—";
  const deltaSeconds = (Date.now() - then) / 1000;
  const future = deltaSeconds < 0;
  const abs = Math.abs(deltaSeconds);

  let value: string;
  if (abs < 10) value = "just now";
  else if (abs < 60) value = `${Math.round(abs)}s`;
  else if (abs < 3600) value = `${Math.round(abs / 60)}m`;
  else if (abs < 86_400) value = `${Math.round(abs / 3600)}h`;
  else value = `${Math.round(abs / 86_400)}d`;

  if (value === "just now") return value;
  return future ? `in ${value}` : `${value} ago`;
}

/** Absolute timestamp, for tooltips and detail panes. */
export function formatAbsolute(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

/** Clock time only, for dense chart axes. */
export function formatClock(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/** Thousands separators. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString();
}

/**
 * A ratio as a percentage.
 *
 * Shows one decimal below 99.95% so a 99.9% success rate is distinguishable
 * from 100% — on a reliability dashboard that difference is the entire point.
 */
export function formatPercent(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined) return "—";
  const percent = ratio * 100;
  if (percent >= 99.95) return "100%";
  if (percent === 0) return "0%";
  if (percent < 10) return `${percent.toFixed(1)}%`;
  return `${percent.toFixed(1)}%`;
}

/** Byte counts in binary units. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

/** Shortens a UUID for dense tables, keeping enough to be recognisable. */
export function shortId(id: string | null | undefined, length = 8): string {
  if (!id) return "—";
  return id.length <= length ? id : id.slice(0, length);
}

/** Pretty-prints a JSON value for the payload viewer. */
export function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/** Turns an ENUM_LIKE_NAME into readable text. */
export function humanizeEnum(value: string | null | undefined): string {
  if (!value) return "—";
  return value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}
