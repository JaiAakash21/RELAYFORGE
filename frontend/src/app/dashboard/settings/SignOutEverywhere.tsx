"use client";

import { useState } from "react";
import { LogOut } from "lucide-react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { useToast } from "@/components/ui/Toast";

/**
 * Revokes every refresh token for the account.
 *
 * <h2>Why this is here and what it does not do</h2>
 *
 * This is the action to take when a laptop is lost or a session is suspected
 * stolen. It revokes every refresh token, so no device can obtain a new access
 * token.
 *
 * <p>It does <em>not</em> invalidate access tokens that have already been issued
 * — those are self-contained JWTs and cannot be recalled. The copy says so
 * plainly, because a user taking this action during an incident needs to know the
 * window is up to fifteen minutes rather than instant. Overstating it would be
 * worse than the limitation itself.
 */
export function SignOutEverywhere() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  async function signOutEverywhere() {
    setBusy(true);
    try {
      // One call: the route handler revokes every refresh token on the backend
      // and then clears this browser's cookies.
      const response = await fetch("/api/session/logout-all", { method: "POST" });
      if (!response.ok) {
        throw new Error("revocation failed");
      }
      // Full navigation, so no cached server-component output survives.
      window.location.href = "/login";
    } catch {
      toast.error(
        "Could not sign out everywhere",
        "The request failed. Try again, or revoke your API keys if you believe your account is compromised.",
      );
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        variant="danger"
        onClick={() => setOpen(true)}
        leadingIcon={<LogOut className="h-3.5 w-3.5" aria-hidden />}
      >
        Sign out everywhere
      </Button>
      <p className="mt-2 text-xs leading-relaxed text-content-tertiary">
        Revokes every refresh token for your account, on every device.
      </p>

      <ConfirmDialog
        open={open}
        onClose={() => setOpen(false)}
        onConfirm={signOutEverywhere}
        loading={busy}
        title="Sign out of every device?"
        description="Every refresh token is revoked, so no device can obtain a new access token. Access tokens already issued remain valid until they expire — up to 15 minutes — because a JWT cannot be recalled. If you believe your account is compromised, also revoke your API keys."
        confirmLabel="Sign out everywhere"
      />
    </>
  );
}
