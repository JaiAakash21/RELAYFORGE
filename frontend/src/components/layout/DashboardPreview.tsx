/**
 * The dashboard preview on the landing page.
 *
 * <h2>Why this is labelled, and what it is not</h2>
 *
 * A marketing screenshot of a dashboard is the one place where a project is most
 * tempted to invent impressive numbers. This component does not. It is a static,
 * explicitly-labelled sample built from a plausible run, and it is marked
 * "Sample data" in the chrome where a viewer will actually read it.
 *
 * The real dashboard is one click away and shows the viewer's own figures, which
 * for a brand-new account are zeros. That is the honest trade: the preview shows
 * the <em>shape</em> of the product, and the product shows the truth.
 */
export function DashboardPreview() {
  const stats = [
    { label: "Events", value: "18,402" },
    { label: "Success rate", value: "99.4%" },
    { label: "p95 latency", value: "184ms" },
    { label: "Dead letters", value: "11", tone: "failed" as const },
  ];

  const rows = [
    {
      event: "order.created",
      endpoint: "fulfilment-prod",
      status: "SUCCESS",
      attempts: "1/6",
      code: "200",
    },
    {
      event: "invoice.paid",
      endpoint: "billing-webhook",
      status: "SUCCESS",
      attempts: "3/6",
      code: "200",
    },
    {
      event: "user.deleted",
      endpoint: "crm-sync",
      status: "RETRY_SCHEDULED",
      attempts: "2/6",
      code: "503",
    },
    {
      event: "order.shipped",
      endpoint: "partner-eu",
      status: "FAILED",
      attempts: "6/6",
      code: "500",
    },
    {
      event: "payment.failed",
      endpoint: "dunning-service",
      status: "IN_PROGRESS",
      attempts: "1/6",
      code: null,
    },
  ];

  return (
    <div className="rf-card min-w-0 overflow-hidden shadow-overlay">
      {/* Window chrome, with the honesty label in it. */}
      <div className="flex items-center justify-between gap-3 border-b border-border-subtle bg-surface-2 px-3 py-2">
        <div className="flex items-center gap-1.5" aria-hidden>
          <span className="h-2.5 w-2.5 rounded-full bg-surface-4" />
          <span className="h-2.5 w-2.5 rounded-full bg-surface-4" />
          <span className="h-2.5 w-2.5 rounded-full bg-surface-4" />
        </div>
        <span className="rf-mono truncate text-content-tertiary">
          relayforge.dev/dashboard
        </span>
        <span className="shrink-0 rounded-full border border-border bg-surface-3 px-2 py-0.5 text-2xs text-content-tertiary">
          Sample data
        </span>
      </div>

      <div className="p-3 sm:p-4">
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 sm:gap-3">
          {stats.map((stat) => (
            <div
              key={stat.label}
              className="rounded border border-border bg-surface-2 p-2.5"
            >
              <p className="rf-label">{stat.label}</p>
              <p
                className={`tabular mt-1 text-lg font-semibold ${
                  stat.tone === "failed" ? "text-status-failed" : "text-content"
                }`}
              >
                {stat.value}
              </p>
            </div>
          ))}
        </div>

        <SparkChart />

        <div className="mt-3 overflow-hidden rounded border border-border">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[520px] text-left">
              <thead className="border-b border-border-subtle bg-surface-2">
                <tr>
                  {["Event", "Endpoint", "Status", "Attempts", "Code"].map((header) => (
                    <th
                      key={header}
                      className="px-2.5 py-1.5 text-2xs font-medium uppercase tracking-wider text-content-tertiary"
                    >
                      {header}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle">
                {rows.map((row) => (
                  <tr key={row.event}>
                    <td className="px-2.5 py-1.5 font-mono text-2xs text-content">
                      {row.event}
                    </td>
                    <td className="px-2.5 py-1.5 text-2xs text-content-secondary">
                      {row.endpoint}
                    </td>
                    <td className="px-2.5 py-1.5">
                      <StatusPill status={row.status} />
                    </td>
                    <td className="tabular px-2.5 py-1.5 text-2xs text-content-secondary">
                      {row.attempts}
                    </td>
                    <td className="px-2.5 py-1.5 font-mono text-2xs text-content-secondary">
                      {row.code ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

function StatusPill({ status }: { status: string }) {
  const styles: Record<string, string> = {
    SUCCESS: "border-status-success-border bg-status-success-bg text-status-success",
    RETRY_SCHEDULED: "border-status-retry-border bg-status-retry-bg text-status-retry",
    FAILED: "border-status-failed-border bg-status-failed-bg text-status-failed",
    IN_PROGRESS:
      "border-status-progress-border bg-status-progress-bg text-status-progress",
  };
  const labels: Record<string, string> = {
    SUCCESS: "Success",
    RETRY_SCHEDULED: "Retry",
    FAILED: "Failed",
    IN_PROGRESS: "In progress",
  };
  return (
    <span
      className={`inline-flex items-center rounded-full border px-1.5 py-0.5 text-2xs ${styles[status]}`}
    >
      {labels[status]}
    </span>
  );
}

/**
 * A static area chart.
 *
 * Hand-drawn SVG rather than Recharts: the landing page is statically rendered
 * and should not pull a charting library into its bundle for a decorative
 * illustration. The real observability page uses Recharts, where interactivity
 * earns its weight.
 */
function SparkChart() {
  // A plausible delivery curve with one visible dip, drawn once rather than
  // generated, so the illustration is stable across builds.
  const values = [
    38, 42, 40, 47, 52, 49, 58, 61, 57, 64, 60, 68, 71, 66, 59, 41, 34, 46, 63, 72,
    76, 74, 79, 83,
  ];
  const width = 560;
  const height = 72;
  const max = Math.max(...values);
  const step = width / (values.length - 1);

  const points = values.map((value, index) => {
    const x = index * step;
    const y = height - (value / max) * (height - 8) - 4;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  const line = `M ${points.join(" L ")}`;
  const area = `${line} L ${width},${height} L 0,${height} Z`;

  return (
    <div className="mt-3 rounded border border-border bg-surface-2 p-2.5">
      <div className="flex items-center justify-between">
        <p className="rf-label">Delivery throughput · last 24h</p>
        <p className="text-2xs text-content-tertiary">attempts / 10 min</p>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="mt-1.5 h-16 w-full"
        preserveAspectRatio="none"
        role="img"
        aria-label="Sample delivery throughput over the last 24 hours, rising overall with one dip"
      >
        <defs>
          <linearGradient id="spark-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#6366f1" stopOpacity="0.28" />
            <stop offset="100%" stopColor="#6366f1" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={area} fill="url(#spark-fill)" />
        <path
          d={line}
          fill="none"
          stroke="#6366f1"
          strokeWidth="1.5"
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  );
}
