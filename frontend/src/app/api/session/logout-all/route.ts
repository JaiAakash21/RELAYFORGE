import { NextResponse } from "next/server";
import { ApiRequestError, apiPost } from "@/lib/api/server";
import { clearSession } from "@/lib/session";

/**
 * Revokes every refresh token for the signed-in user.
 *
 * Needs an authenticated call (unlike plain logout, which authenticates with the
 * refresh token itself), so it goes through the session-aware server client. The
 * local cookies are cleared afterwards regardless of the outcome: a sign-out the
 * user asked for must not leave them signed in here because a network hop
 * failed.
 */
export async function POST() {
  try {
    await apiPost<void>("/api/v1/auth/logout-all");
  } catch (error) {
    if (error instanceof ApiRequestError && !error.isUnauthenticated) {
      console.error("Account-wide sign-out failed", error);
      await clearSession();
      return NextResponse.json(error.payload, { status: error.status });
    }
    // Already unauthenticated: there is nothing left to revoke.
  }
  await clearSession();
  return new NextResponse(null, { status: 204 });
}
