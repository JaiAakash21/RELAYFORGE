import { cn } from "@/lib/cn";

/** The heading block at the top of every dashboard page. */
export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: string;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}) {
  return (
    <header
      className={cn(
        "flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between",
        className,
      )}
    >
      <div className="min-w-0">
        <h1 className="text-xl font-semibold tracking-tight text-content">{title}</h1>
        {description ? (
          <p className="mt-1 max-w-2xl text-sm leading-relaxed text-content-secondary">
            {description}
          </p>
        ) : null}
      </div>
      {actions ? (
        <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>
      ) : null}
    </header>
  );
}

/** A labelled key/value row, for detail panes. */
export function DetailRow({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-1 py-2 sm:flex-row sm:items-baseline sm:gap-4",
        className,
      )}
    >
      <dt className="rf-label shrink-0 sm:w-40">{label}</dt>
      <dd className="min-w-0 flex-1 break-words text-sm text-content">{children}</dd>
    </div>
  );
}
