import { redirect } from "next/navigation";
import { AppShell } from "@/components/layout/AppShell";
import { ToastProvider } from "@/components/ui/Toast";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { hasSession } from "@/lib/session";
import type { SessionResponse } from "@/types/api";

/**
 * The authenticated layout.
 *
 * <h2>Where the auth gate lives</h2>
 *
 * Here, in a server component, rather than in middleware. A layout can do the
 * thing middleware cannot: actually ask the backend whether the session is still
 * valid, and get the user and tenant back in the same call. Middleware could
 * only check that a cookie exists, which is not the same as the session being
 * usable — a revoked refresh token leaves the cookie in place.
 *
 * <p>So an expired or revoked session is detected here and redirected to sign-in
 * once, instead of every child page rendering and then failing individually.
 */
export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  if (!(await hasSession())) {
    redirect("/login");
  }

  let session: SessionResponse;
  try {
    session = await apiGet<SessionResponse>("/api/v1/auth/session");
  } catch (error) {
    if (error instanceof ApiRequestError && error.isUnauthenticated) {
      // The cookie exists but the session is gone: the refresh token was
      // revoked, or the backend's signing secret changed.
      redirect("/login");
    }
    throw error;
  }

  return (
    <ToastProvider>
      <AppShell session={session}>{children}</AppShell>
    </ToastProvider>
  );
}
