import { cn } from "@/lib/cn";

/**
 * Table primitives for dense data.
 *
 * <h2>Horizontal scrolling, not wrapping</h2>
 *
 * The wrapper scrolls horizontally on narrow screens rather than letting cells
 * wrap. A delivery row holds a UUID, a URL, a status, counters and timestamps;
 * wrapping turns one row into five and destroys the ability to scan down a
 * column, which is the only reason to use a table at all. `overflow-x-auto`
 * here, combined with `overflow-x-hidden` on the body, is what keeps the page
 * itself from scrolling sideways.
 */
export function TableWrapper({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("w-full overflow-x-auto", className)}>
      <table className="w-full min-w-[760px] border-collapse text-left">
        {children}
      </table>
    </div>
  );
}

export function Thead({ children }: { children: React.ReactNode }) {
  return (
    <thead className="border-b border-border-subtle bg-surface-2/50">{children}</thead>
  );
}

export function Th({
  children,
  className,
  align = "left",
}: {
  children?: React.ReactNode;
  className?: string;
  align?: "left" | "right" | "center";
}) {
  return (
    <th
      scope="col"
      className={cn(
        "whitespace-nowrap px-3 py-2.5 text-2xs font-medium uppercase tracking-wider text-content-tertiary",
        align === "right" && "text-right",
        align === "center" && "text-center",
        className,
      )}
    >
      {children}
    </th>
  );
}

export function Tbody({ children }: { children: React.ReactNode }) {
  return <tbody className="divide-y divide-border-subtle">{children}</tbody>;
}

export function Tr({
  children,
  className,
  interactive = false,
  ...props
}: React.HTMLAttributes<HTMLTableRowElement> & { interactive?: boolean }) {
  return (
    <tr
      className={cn(
        "transition-colors",
        interactive && "cursor-pointer hover:bg-surface-2",
        className,
      )}
      {...props}
    >
      {children}
    </tr>
  );
}

export function Td({
  children,
  className,
  align = "left",
  ...props
}: React.TdHTMLAttributes<HTMLTableCellElement> & {
  align?: "left" | "right" | "center";
}) {
  return (
    <td
      className={cn(
        "px-3 py-2.5 text-sm text-content-secondary",
        align === "right" && "text-right",
        align === "center" && "text-center",
        className,
      )}
      {...props}
    >
      {children}
    </td>
  );
}
