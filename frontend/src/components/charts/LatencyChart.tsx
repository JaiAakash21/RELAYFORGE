"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { formatClock, formatDuration } from "@/lib/format";
import type { EndpointPerformance, TimeseriesPoint } from "@/types/api";

/** Mean and peak attempt duration over time. */
export function LatencyChart({ points }: { points: TimeseriesPoint[] }) {
  const data = points.map((point) => ({
    time: formatClock(point.timestamp),
    avg: Math.round(point.avgDurationMs),
    max: point.maxDurationMs,
  }));

  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid stroke="#1a1a21" vertical={false} />
          <XAxis
            dataKey="time"
            tick={{ fill: "#6c6c7c", fontSize: 10 }}
            stroke="#23232c"
            tickLine={false}
            minTickGap={28}
          />
          <YAxis
            tick={{ fill: "#6c6c7c", fontSize: 10 }}
            stroke="#23232c"
            tickLine={false}
            width={46}
            tickFormatter={(value: number) => `${value}ms`}
          />
          <Tooltip
            content={({ active, payload, label }) => {
              if (!active || !payload?.length) return null;
              return (
                <div className="rounded-lg border border-border bg-surface-3 px-3 py-2 shadow-overlay">
                  <p className="text-2xs text-content-tertiary">{label}</p>
                  {payload.map((entry) => (
                    <p key={entry.name} className="tabular mt-1 text-xs text-content">
                      {entry.name === "avg" ? "Mean" : "Peak"}:{" "}
                      {formatDuration(Number(entry.value))}
                    </p>
                  ))}
                </div>
              );
            }}
          />
          {/* Peak drawn first and dimmer, so the mean line reads as the primary
              series with the peak as context behind it. */}
          <Line
            type="monotone"
            dataKey="max"
            name="max"
            stroke="#f0a126"
            strokeWidth={1}
            strokeOpacity={0.5}
            dot={false}
          />
          <Line
            type="monotone"
            dataKey="avg"
            name="avg"
            stroke="#6366f1"
            strokeWidth={1.75}
            dot={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Per-endpoint success rate.
 *
 * Horizontal bars, because endpoint names are long and a vertical bar chart
 * would rotate them to 45 degrees and make them unreadable. Ordered worst-first
 * by the API, so the endpoint needing attention is the top bar.
 */
export function EndpointPerformanceChart({
  endpoints,
}: {
  endpoints: EndpointPerformance[];
}) {
  const data = endpoints
    .filter((endpoint) => endpoint.succeeded + endpoint.failed > 0)
    .slice(0, 10)
    .map((endpoint) => ({
      name: endpoint.name.length > 24 ? `${endpoint.name.slice(0, 23)}…` : endpoint.name,
      rate: Math.round(endpoint.successRate * 1000) / 10,
      succeeded: endpoint.succeeded,
      failed: endpoint.failed,
    }));

  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-content-tertiary">
        No resolved deliveries yet, so there is no success rate to show.
      </p>
    );
  }

  return (
    <div style={{ height: Math.max(140, data.length * 34 + 30) }} className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart
          data={data}
          layout="vertical"
          margin={{ top: 0, right: 40, left: 0, bottom: 0 }}
        >
          <CartesianGrid stroke="#1a1a21" horizontal={false} />
          <XAxis
            type="number"
            domain={[0, 100]}
            tick={{ fill: "#6c6c7c", fontSize: 10 }}
            stroke="#23232c"
            tickLine={false}
            tickFormatter={(value: number) => `${value}%`}
          />
          <YAxis
            type="category"
            dataKey="name"
            tick={{ fill: "#9c9cab", fontSize: 11 }}
            stroke="#23232c"
            tickLine={false}
            width={150}
          />
          <Tooltip
            cursor={{ fill: "#131318" }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null;
              const row = payload[0]?.payload as
                | { name: string; rate: number; succeeded: number; failed: number }
                | undefined;
              if (!row) return null;
              return (
                <div className="rounded-lg border border-border bg-surface-3 px-3 py-2 shadow-overlay">
                  <p className="text-xs font-medium text-content">{row.name}</p>
                  <p className="tabular mt-1 text-2xs text-content-secondary">
                    {row.rate}% success · {row.succeeded} delivered · {row.failed} failed
                  </p>
                </div>
              );
            }}
          />
          <Bar dataKey="rate" radius={[0, 3, 3, 0]} barSize={16}>
            {/* Coloured by threshold so the bar's colour carries the same
                judgement as the health badge in the table. */}
            {data.map((row) => (
              <Cell
                key={row.name}
                fill={row.rate >= 99 ? "#2dd47f" : row.rate >= 90 ? "#f0a126" : "#f2556f"}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
