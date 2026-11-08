"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatClock, formatCount } from "@/lib/format";
import type { TimeseriesPoint } from "@/types/api";

/**
 * Stacked delivery outcomes over time.
 *
 * <h2>Why stacked, and why this colour order</h2>
 *
 * Stacking makes the total height the attempt volume while each band shows its
 * composition — so "we got busier" and "we started failing" are distinguishable
 * at a glance, which two separate charts would not achieve.
 *
 * Successes sit at the bottom in green, failures above them in amber and red.
 * Failures therefore appear as a band growing <em>upward from a stable base</em>,
 * which is the shape people already read as "something is wrong". Putting
 * failures underneath would make the success band appear to move during an
 * incident, which is misleading.
 *
 * <h2>Accessibility</h2>
 *
 * Colour is not the only signal: the legend names each series, the tooltip gives
 * exact figures, and the three colours differ in lightness as well as hue so they
 * remain distinguishable with a red-green deficiency.
 */
export function ThroughputChart({ points }: { points: TimeseriesPoint[] }) {
  const data = points.map((point) => ({
    time: formatClock(point.timestamp),
    timestamp: point.timestamp,
    successes: point.successes,
    retryable: point.retryableFailures,
    terminal: point.terminalFailures,
    attempts: point.attempts,
  }));

  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
          <defs>
            <linearGradient id="g-success" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#2dd47f" stopOpacity={0.35} />
              <stop offset="100%" stopColor="#2dd47f" stopOpacity={0.05} />
            </linearGradient>
            <linearGradient id="g-retry" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#f0a126" stopOpacity={0.35} />
              <stop offset="100%" stopColor="#f0a126" stopOpacity={0.05} />
            </linearGradient>
            <linearGradient id="g-failed" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#f2556f" stopOpacity={0.35} />
              <stop offset="100%" stopColor="#f2556f" stopOpacity={0.05} />
            </linearGradient>
          </defs>

          {/* Horizontal gridlines only. Vertical ones add ink without helping
              anyone read a value off the y-axis. */}
          <CartesianGrid stroke="#1a1a21" vertical={false} />
          <XAxis
            dataKey="time"
            tick={{ fill: "#6c6c7c", fontSize: 10 }}
            stroke="#23232c"
            tickLine={false}
            // Thins the labels automatically rather than overlapping them.
            minTickGap={28}
          />
          <YAxis
            tick={{ fill: "#6c6c7c", fontSize: 10 }}
            stroke="#23232c"
            tickLine={false}
            allowDecimals={false}
            width={46}
          />
          <Tooltip content={<ChartTooltip />} />
          <Legend
            verticalAlign="top"
            align="right"
            height={28}
            iconType="circle"
            iconSize={7}
            formatter={(value) => (
              <span className="text-xs text-content-secondary">{value}</span>
            )}
          />
          <Area
            type="monotone"
            dataKey="successes"
            name="Delivered"
            stackId="1"
            stroke="#2dd47f"
            strokeWidth={1.5}
            fill="url(#g-success)"
          />
          <Area
            type="monotone"
            dataKey="retryable"
            name="Retryable failures"
            stackId="1"
            stroke="#f0a126"
            strokeWidth={1.5}
            fill="url(#g-retry)"
          />
          <Area
            type="monotone"
            dataKey="terminal"
            name="Terminal failures"
            stackId="1"
            stroke="#f2556f"
            strokeWidth={1.5}
            fill="url(#g-failed)"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

interface TooltipPayloadEntry {
  name?: string;
  value?: number;
  color?: string;
  payload?: { attempts?: number };
}

function ChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: TooltipPayloadEntry[];
  label?: string;
}) {
  if (!active || !payload || payload.length === 0) {
    return null;
  }
  const total = payload[0]?.payload?.attempts ?? 0;
  return (
    <div className="rounded-lg border border-border bg-surface-3 px-3 py-2 shadow-overlay">
      <p className="text-2xs font-medium text-content-tertiary">{label}</p>
      <p className="tabular mt-1 text-sm font-medium text-content">
        {formatCount(total)} attempts
      </p>
      <ul className="mt-1.5 space-y-0.5">
        {payload.map((entry) => (
          <li
            key={entry.name}
            className="flex items-center justify-between gap-4 text-2xs"
          >
            <span className="flex items-center gap-1.5 text-content-secondary">
              <span
                aria-hidden
                className="h-1.5 w-1.5 rounded-full"
                style={{ backgroundColor: entry.color }}
              />
              {entry.name}
            </span>
            <span className="tabular text-content">{formatCount(entry.value ?? 0)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
