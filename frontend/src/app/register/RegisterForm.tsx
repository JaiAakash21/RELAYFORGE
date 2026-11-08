"use client";

import { useMemo, useState } from "react";
import { Check, X } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { TextInput } from "@/components/ui/Input";
import { InlineError } from "@/components/ui/States";
import { cn } from "@/lib/cn";

const MIN_PASSWORD_LENGTH = 12;
/** BCrypt ignores input beyond 72 bytes, so accepting more would be a lie. */
const MAX_PASSWORD_LENGTH = 72;

export function RegisterForm({ next }: { next?: string }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [organizationName, setOrganizationName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const checks = useMemo(
    () => [
      {
        label: `At least ${MIN_PASSWORD_LENGTH} characters`,
        met: password.length >= MIN_PASSWORD_LENGTH,
      },
      {
        label: `No more than ${MAX_PASSWORD_LENGTH} characters`,
        met: password.length > 0 && password.length <= MAX_PASSWORD_LENGTH,
      },
      {
        label: "Not an obvious sequence",
        met: password.length >= 8 && !isObvious(password),
      },
    ],
    [password],
  );

  const passwordAcceptable = checks.every((check) => check.met);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});

    if (!passwordAcceptable) {
      setFieldErrors({ password: "Choose a longer, less predictable password." });
      return;
    }

    setSubmitting(true);
    try {
      const response = await fetch("/api/session/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: email.trim(),
          password,
          displayName: displayName.trim() || null,
          organizationName: organizationName.trim() || null,
        }),
      });

      if (!response.ok) {
        const payload = (await response.json().catch(() => null)) as
          | { message?: string; details?: Record<string, string[]> }
          | null;

        // Place per-field problems on their field; a form-level banner for the
        // rest. A validation error shown only at the top makes the user hunt for
        // which input it refers to.
        if (payload?.details) {
          const mapped: Record<string, string> = {};
          for (const [field, messages] of Object.entries(payload.details)) {
            if (messages[0]) mapped[field] = messages[0];
          }
          setFieldErrors(mapped);
        }
        setError(payload?.message ?? "Registration failed.");
        setSubmitting(false);
        return;
      }

      window.location.href = safeDestination(next);
    } catch {
      setError("Could not reach RelayForge. Check that the API is running.");
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      {error ? <InlineError message={error} /> : null}

      <TextInput
        label="Work email"
        type="email"
        name="email"
        autoComplete="email"
        required
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        error={fieldErrors.email}
        placeholder="you@company.com"
      />

      <TextInput
        label="Your name"
        name="displayName"
        autoComplete="name"
        value={displayName}
        onChange={(event) => setDisplayName(event.target.value)}
        error={fieldErrors.displayName}
        placeholder="Optional"
      />

      <TextInput
        label="Organization name"
        name="organizationName"
        autoComplete="organization"
        value={organizationName}
        onChange={(event) => setOrganizationName(event.target.value)}
        error={fieldErrors.organizationName}
        help="Everything you create belongs to this organization. Defaults to your email's local part."
        placeholder="Optional"
      />

      <div>
        <TextInput
          label="Password"
          type="password"
          name="password"
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD_LENGTH}
          maxLength={MAX_PASSWORD_LENGTH}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={fieldErrors.password}
        />
        {/* Length first, no symbol-class requirements. Composition rules mostly
            produce "Password1!"; length is what actually resists guessing. */}
        <ul className="mt-2 space-y-1" aria-live="polite">
          {checks.map((check) => (
            <li
              key={check.label}
              className={cn(
                "flex items-center gap-1.5 text-2xs",
                check.met ? "text-status-success" : "text-content-tertiary",
              )}
            >
              {check.met ? (
                <Check className="h-3 w-3 shrink-0" aria-hidden />
              ) : (
                <X className="h-3 w-3 shrink-0" aria-hidden />
              )}
              {check.label}
            </li>
          ))}
        </ul>
      </div>

      <Button type="submit" variant="primary" size="lg" loading={submitting}>
        Create account
      </Button>
    </form>
  );
}

/** Catches the handful of patterns that defeat a length requirement. */
function isObvious(password: string): boolean {
  const lower = password.toLowerCase();
  if (/^(.)\1+$/.test(password)) return true;
  const sequences = ["0123456789", "abcdefghijklmnopqrstuvwxyz", "qwertyuiop"];
  return sequences.some(
    (sequence) => lower.length >= 6 && sequence.includes(lower.slice(0, 6)),
  );
}

function safeDestination(next: string | undefined): string {
  if (!next) return "/dashboard";
  if (!next.startsWith("/") || next.startsWith("//")) return "/dashboard";
  return next;
}
