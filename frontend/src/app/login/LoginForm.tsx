"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { TextInput } from "@/components/ui/Input";
import { InlineError } from "@/components/ui/States";

/**
 * Sign-in form.
 *
 * Posts to `/api/session/login`, which sets the httpOnly cookie server-side. The
 * tokens never enter this component's state — there is deliberately nothing here
 * to leak.
 */
export function LoginForm({ next }: { next?: string }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const destination = safeDestination(next);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const response = await fetch("/api/session/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: email.trim(), password }),
      });

      if (!response.ok) {
        const payload = (await response.json().catch(() => null)) as
          | { message?: string }
          | null;
        // The backend returns one generic message for "no such user" and "wrong
        // password", so there is nothing more specific to show — and showing
        // more would hand an attacker a user-enumeration oracle.
        setError(payload?.message ?? "Sign-in failed. Check your details and retry.");
        setSubmitting(false);
        return;
      }

      // Full navigation so the server components re-render with the new session.
      window.location.href = destination;
    } catch {
      setError("Could not reach RelayForge. Check that the API is running.");
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      {error ? <InlineError message={error} /> : null}

      <TextInput
        label="Email"
        type="email"
        name="email"
        autoComplete="email"
        required
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        placeholder="you@company.com"
      />

      <TextInput
        label="Password"
        type="password"
        name="password"
        autoComplete="current-password"
        required
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        placeholder="••••••••••••"
      />

      <Button type="submit" variant="primary" size="lg" loading={submitting}>
        Sign in
      </Button>

      <button
        type="button"
        onClick={() => router.push("/")}
        className="text-center text-xs text-content-tertiary transition-colors hover:text-content-secondary"
      >
        Back to the overview
      </button>
    </form>
  );
}

/**
 * Validates the post-login redirect target.
 *
 * An unchecked `next` parameter is an open redirect: `?next=https://evil.test`
 * would send a freshly authenticated user to an attacker's page that looks like
 * the dashboard. Only same-origin absolute paths are accepted, and a
 * protocol-relative `//host` form is rejected explicitly because it looks like a
 * path but is not one.
 */
function safeDestination(next: string | undefined): string {
  if (!next) return "/dashboard";
  if (!next.startsWith("/") || next.startsWith("//")) return "/dashboard";
  return next;
}
