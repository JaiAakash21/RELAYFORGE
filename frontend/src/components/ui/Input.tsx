"use client";

import { forwardRef, useId } from "react";
import { cn } from "@/lib/cn";
import { InlineError } from "./States";

export interface FieldProps {
  label: string;
  /** Guidance shown under the control. Not a substitute for a clear label. */
  help?: string;
  error?: string;
  required?: boolean;
  className?: string;
}

/**
 * Wraps a control with its label, help text and error.
 *
 * Generates the id and wires `aria-describedby` / `aria-invalid` itself, because
 * those are precisely the attributes that get forgotten when each form does its
 * own markup — and without them a screen reader announces a field with no name
 * and no error.
 */
function Field({
  label,
  help,
  error,
  required,
  className,
  children,
  controlId,
}: FieldProps & { children: React.ReactNode; controlId: string }) {
  const helpId = `${controlId}-help`;
  const errorId = `${controlId}-error`;
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label htmlFor={controlId} className="text-sm font-medium text-content">
        {label}
        {required ? (
          <span className="ml-1 text-status-failed" aria-hidden>
            *
          </span>
        ) : null}
      </label>
      {children}
      {help && !error ? (
        <p id={helpId} className="text-xs leading-relaxed text-content-tertiary">
          {help}
        </p>
      ) : null}
      {error ? (
        <div id={errorId}>
          <InlineError message={error} />
        </div>
      ) : null}
    </div>
  );
}

const CONTROL_CLASSES =
  "w-full rounded border border-border bg-surface-2 px-3 py-2 text-base text-content placeholder:text-content-tertiary " +
  "transition-colors focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent " +
  "disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-status-failed";

export interface TextInputProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "className">,
    FieldProps {
  inputClassName?: string;
}

export const TextInput = forwardRef<HTMLInputElement, TextInputProps>(
  function TextInput(
    { label, help, error, required, className, inputClassName, id, ...props },
    ref,
  ) {
    const generated = useId();
    const controlId = id ?? generated;
    return (
      <Field
        label={label}
        help={help}
        error={error}
        required={required}
        className={className}
        controlId={controlId}
      >
        <input
          ref={ref}
          id={controlId}
          required={required}
          aria-invalid={error ? true : undefined}
          aria-describedby={
            error ? `${controlId}-error` : help ? `${controlId}-help` : undefined
          }
          className={cn(CONTROL_CLASSES, inputClassName)}
          {...props}
        />
      </Field>
    );
  },
);

export interface TextAreaProps
  extends Omit<React.TextareaHTMLAttributes<HTMLTextAreaElement>, "className">,
    FieldProps {}

export const TextArea = forwardRef<HTMLTextAreaElement, TextAreaProps>(
  function TextArea({ label, help, error, required, className, id, ...props }, ref) {
    const generated = useId();
    const controlId = id ?? generated;
    return (
      <Field
        label={label}
        help={help}
        error={error}
        required={required}
        className={className}
        controlId={controlId}
      >
        <textarea
          ref={ref}
          id={controlId}
          required={required}
          aria-invalid={error ? true : undefined}
          aria-describedby={
            error ? `${controlId}-error` : help ? `${controlId}-help` : undefined
          }
          className={cn(CONTROL_CLASSES, "min-h-[80px] resize-y font-mono text-xs")}
          {...props}
        />
      </Field>
    );
  },
);

export interface SelectProps
  extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "className">,
    FieldProps {
  options: { value: string; label: string }[];
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { label, help, error, required, className, options, id, ...props },
  ref,
) {
  const generated = useId();
  const controlId = id ?? generated;
  return (
    <Field
      label={label}
      help={help}
      error={error}
      required={required}
      className={className}
      controlId={controlId}
    >
      <select
        ref={ref}
        id={controlId}
        required={required}
        aria-invalid={error ? true : undefined}
        className={cn(CONTROL_CLASSES, "appearance-none pr-8")}
        {...props}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  );
});

/** A bare select for toolbars, where a visible label would be noise. */
export function InlineSelect({
  ariaLabel,
  options,
  className,
  ...props
}: Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "className"> & {
  ariaLabel: string;
  options: { value: string; label: string }[];
  className?: string;
}) {
  return (
    <select
      aria-label={ariaLabel}
      className={cn(
        "h-9 rounded border border-border bg-surface-2 px-2.5 pr-7 text-sm text-content",
        "focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent",
        className,
      )}
      {...props}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

export function Checkbox({
  label,
  description,
  className,
  id,
  ...props
}: Omit<React.InputHTMLAttributes<HTMLInputElement>, "className" | "type"> & {
  label: string;
  description?: string;
  className?: string;
}) {
  const generated = useId();
  const controlId = id ?? generated;
  return (
    <div className={cn("flex items-start gap-2.5", className)}>
      <input
        id={controlId}
        type="checkbox"
        className="mt-0.5 h-4 w-4 shrink-0 rounded border-border bg-surface-2 text-accent focus:ring-1 focus:ring-accent"
        {...props}
      />
      <div className="min-w-0">
        <label htmlFor={controlId} className="text-sm text-content">
          {label}
        </label>
        {description ? (
          <p className="mt-0.5 text-xs leading-relaxed text-content-tertiary">
            {description}
          </p>
        ) : null}
      </div>
    </div>
  );
}
