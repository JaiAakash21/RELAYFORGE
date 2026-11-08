"use client";

import { forwardRef } from "react";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "ghost" | "danger" | "outline";
type Size = "sm" | "md" | "lg" | "icon";

const VARIANTS: Record<Variant, string> = {
  primary:
    "bg-accent text-accent-fg hover:bg-accent-hover active:bg-accent disabled:bg-accent/40",
  secondary:
    "bg-surface-3 text-content hover:bg-surface-4 border border-border hover:border-border-strong",
  ghost: "text-content-secondary hover:bg-surface-2 hover:text-content",
  // Destructive actions get the failure colour, and every caller is expected to
  // pair them with a confirmation — see ConfirmDialog.
  danger:
    "bg-status-failed/15 text-status-failed border border-status-failed/35 hover:bg-status-failed/25",
  outline:
    "border border-border bg-transparent text-content hover:bg-surface-2 hover:border-border-strong",
};

const SIZES: Record<Size, string> = {
  sm: "h-7 px-2.5 text-xs gap-1.5",
  md: "h-9 px-3.5 text-base gap-2",
  lg: "h-11 px-5 text-md gap-2",
  icon: "h-9 w-9",
};

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  /** Shows a spinner and disables the button. */
  loading?: boolean;
  leadingIcon?: React.ReactNode;
  trailingIcon?: React.ReactNode;
}

/**
 * The single button primitive.
 *
 * While `loading`, the button is disabled as well as spinning. A spinner alone
 * leaves the button clickable, and the resulting double-submit is how one
 * replay request becomes two.
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    className,
    variant = "secondary",
    size = "md",
    loading = false,
    leadingIcon,
    trailingIcon,
    disabled,
    children,
    ...props
  },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      // Communicates the busy state to assistive technology, which cannot see
      // the spinner.
      aria-busy={loading || undefined}
      className={cn(
        "inline-flex select-none items-center justify-center whitespace-nowrap rounded font-medium",
        "transition-colors duration-150",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent focus-visible:ring-offset-2 focus-visible:ring-offset-canvas",
        "disabled:cursor-not-allowed disabled:opacity-55",
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...props}
    >
      {loading ? (
        <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
      ) : (
        leadingIcon
      )}
      {children}
      {!loading && trailingIcon}
    </button>
  );
});
