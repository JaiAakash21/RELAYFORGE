import { NextResponse } from "next/server";
import { apiPostAnonymous } from "@/lib/api/server";
import { clearSession, readRefreshToken } from "@/lib/session";

/**
 * Signs out.
 *
 * Revokes the refresh token family on the backend <em>and</em> clears the
 * cookies. Clearing the cookie alone would leave a usable refresh token in the
 * database — the user would appear signed out while the credential remained
 * valid for anyone who had captured it.
 *
 * <p>The cookies are cleared even if the backend call fails: a logout the user
 * asked for must not leave them signed in locally because a network hop failed.
 */
export async function POST() {
  const refreshToken = await readRefreshToken();

  if (refreshToken) {
    try {
      await apiPostAnonymous<void>("/api/v1/auth/logout", { refreshToken });
    } catch (error) {
      console.error("Backend logout failed; clearing local session anyway", error);
    }
  }

  await clearSession();
  return new NextResponse(null, { status: 204 });
}
