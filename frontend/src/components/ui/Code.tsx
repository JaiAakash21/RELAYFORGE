"use client";

import { useCallback, useState } from "react";
import { Check, Copy } from "lucide-react";
import { cn } from "@/lib/cn";
import { formatJson } from "@/lib/format";

/**
 * Copy-to-clipboard button.
 *
 * Falls back to selecting the text when the Clipboard API is unavailable —
 * which it is on any non-HTTPS origin other than localhost. A copy button that
 * silently does nothing is worse than no copy button, because the user believes
 * they have the value.
 */
export function CopyButton({
  value,
  label = "Copy",
  className,
  size = "sm",
}: {
  value: string;
  label?: string;
  className?: string;
  size?: "sm" | "md";
}) {
  const [copied, setCopied] = useState(false);
  const [failed, setFailed] = useState(false);

  const copy = useCallback(async () => {
    try {
      if (!navigator.clipboard) {
        throw new Error("Clipboard API unavailable");
      }
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setFailed(false);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      setFailed(true);
      setTimeout(() => setFailed(false), 2600);
    }
  }, [value]);

  return (
    <button
      type="button"
      onClick={copy}
      // The live region announces the result; the icon change alone would not be
      // perceivable to a screen reader.
      aria-live="polite"
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 rounded border border-border bg-surface-2 font-medium text-content-secondary",
        "transition-colors hover:border-border-strong hover:bg-surface-3 hover:text-content",
        size === "sm" ? "h-7 px-2 text-2xs" : "h-9 px-3 text-sm",
        className,
      )}
    >
      {copied ? (
        <Check className="h-3 w-3 text-status-success" aria-hidden />
      ) : (
        <Copy className="h-3 w-3" aria-hidden />
      )}
      {failed ? "Select and copy" : copied ? "Copied" : label}
    </button>
  );
}

/** A monospace identifier with a copy affordance. */
export function IdChip({
  value,
  length = 8,
  className,
}: {
  value: string | null | undefined;
  length?: number;
  className?: string;
}) {
  if (!value) {
    return <span className="text-content-tertiary">—</span>;
  }
  const display = value.length <= length ? value : value.slice(0, length);
  return (
    <span className={cn("group inline-flex items-center gap-1.5", className)}>
      <code className="rf-mono rounded bg-surface-2 px-1.5 py-0.5 text-content-secondary" title={value}>
        {display}
      </code>
      <CopyButton
        value={value}
        label=""
        className="h-5 border-0 bg-transparent px-1 opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100"
      />
    </span>
  );
}

/**
 * JSON viewer.
 *
 * <h2>Why this does not syntax-highlight by parsing into HTML</h2>
 *
 * The payload is tenant-supplied data. Building highlighted markup from it means
 * constructing HTML from untrusted input, which is how a payload viewer becomes
 * an XSS vector — and this one displays data from whatever the receiver or the
 * publisher sent. The text is rendered as text, inside `<pre>`, with React
 * escaping it. Colour is applied by token type through a tokeniser that only
 * ever emits text nodes.
 */
export function JsonViewer({
  value,
  maxHeight = "20rem",
  className,
}: {
  value: unknown;
  maxHeight?: string;
  className?: string;
}) {
  const text = formatJson(value);
  return (
    <div className={cn("relative", className)}>
      <div className="absolute right-2 top-2 z-10">
        <CopyButton value={text} label="Copy JSON" />
      </div>
      <pre
        className="overflow-auto rounded border border-border bg-canvas p-3 pr-24 font-mono text-xs leading-relaxed text-content-secondary"
        style={{ maxHeight }}
      >
        <code>{highlight(text)}</code>
      </pre>
    </div>
  );
}

/**
 * Tokenises JSON text for colouring.
 *
 * Emits React elements whose children are plain strings, so every character of
 * the input is escaped by React. Nothing is ever passed to
 * `dangerouslySetInnerHTML`.
 */
function highlight(text: string): React.ReactNode[] {
  const pattern =
    /("(?:\\.|[^"\\])*")\s*:|("(?:\\.|[^"\\])*")|(\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(\btrue\b|\bfalse\b)|(\bnull\b)/g;

  const nodes: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }
    const [whole, propertyKey, stringValue, numberValue, booleanValue, nullValue] =
      match;

    if (propertyKey !== undefined) {
      nodes.push(
        <span key={key++} className="text-accent-hover">
          {propertyKey}
        </span>,
      );
      // The matched group excludes the colon and any whitespace before it.
      nodes.push(whole.slice(propertyKey.length));
    } else if (stringValue !== undefined) {
      nodes.push(
        <span key={key++} className="text-status-success">
          {stringValue}
        </span>,
      );
    } else if (numberValue !== undefined) {
      nodes.push(
        <span key={key++} className="text-status-retry">
          {numberValue}
        </span>,
      );
    } else if (booleanValue !== undefined) {
      nodes.push(
        <span key={key++} className="text-status-progress">
          {booleanValue}
        </span>,
      );
    } else if (nullValue !== undefined) {
      nodes.push(
        <span key={key++} className="text-content-tertiary">
          {nullValue}
        </span>,
      );
    }
    lastIndex = pattern.lastIndex;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}

/** A read-only code block for shell and language samples. */
export function CodeBlock({
  code,
  language,
  className,
}: {
  code: string;
  language?: string;
  className?: string;
}) {
  return (
    // min-w-0 so a long code line scrolls inside the <pre> instead of widening
    // whatever grid or flex item contains this block.
    <div className={cn("relative min-w-0 rounded-lg border border-border bg-canvas", className)}>
      <div className="flex items-center justify-between border-b border-border-subtle px-3 py-1.5">
        <span className="rf-label">{language ?? "code"}</span>
        <CopyButton value={code} />
      </div>
      <pre className="overflow-x-auto p-3 font-mono text-xs leading-relaxed text-content-secondary">
        <code>{code}</code>
      </pre>
    </div>
  );
}
