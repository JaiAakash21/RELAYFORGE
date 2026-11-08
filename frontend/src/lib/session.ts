import { cookies } from "next/headers";

/**
 * Server-side session handling.
 *
 * <h2>Why tokens live in httpOnly cookies and never in JavaScript</h2>
 *
 * The browser never sees either token. They are set as httpOnly cookies by the
 * Next route handlers under `/api/session`, and only server code reads them.
 *
 * The alternative — keeping the access token in `localStorage` and attaching it
 * from the browser — is the common pattern and the wrong one here. Any script
 * that runs on the page can read `localStorage`: one compromised dependency, one
 * XSS in a payload viewer, and an attacker walks off with a credential that is
 * valid for its full lifetime and can be used from anywhere. An httpOnly cookie
 * cannot be read by script at all, so the same XSS can at most make requests
 * from the victim's browser while they are on the page.
 *
 * The cost is that the browser can no longer call the API directly, which is why
 * the frontend proxies through its own route handlers. That is a real cost —
 * an extra hop — paid deliberately.
 *
 * `SameSite=Lax` is what stops a cross-site form from using the cookie, which is
 * the CSRF protection for the cookie-bearing proxy routes. `Secure` is set
 * outside development, where there is no TLS to require.
 */

const ACCESS_COOKIE = "rf_access";
const REFRESH_COOKIE = "rf_refresh";

const isProduction = process.env.NODE_ENV === "production";

export interface SessionTokens {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
}

function baseCookieOptions() {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: isProduction,
    path: "/",
  };
}

export async function readAccessToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(ACCESS_COOKIE)?.value ?? null;
}

export async function readRefreshToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(REFRESH_COOKIE)?.value ?? null;
}

export async function writeSession(tokens: SessionTokens): Promise<void> {
  const store = await cookies();

  // The access cookie expires slightly before the token itself, so the proxy
  // learns the token is gone and refreshes, rather than sending one the API will
  // reject.
  store.set(ACCESS_COOKIE, tokens.accessToken, {
    ...baseCookieOptions(),
    maxAge: Math.max(30, tokens.expiresInSeconds - 30),
  });

  store.set(REFRESH_COOKIE, tokens.refreshToken, {
    ...baseCookieOptions(),
    // Seven days, matching the backend's refresh TTL. The backend is still the
    // authority: a token revoked there stops working regardless of this cookie.
    maxAge: 60 * 60 * 24 * 7,
  });
}

export async function clearSession(): Promise<void> {
  const store = await cookies();
  store.delete(ACCESS_COOKIE);
  store.delete(REFRESH_COOKIE);
}

export async function hasSession(): Promise<boolean> {
  return (await readAccessToken()) !== null || (await readRefreshToken()) !== null;
}
