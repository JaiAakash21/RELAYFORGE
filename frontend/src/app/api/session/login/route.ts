import { NextRequest, NextResponse } from "next/server";
import { ApiRequestError, apiPostAnonymous } from "@/lib/api/server";
import { writeSession } from "@/lib/session";

/**
 * Exchanges credentials for a session cookie.
 *
 * <p>The response body deliberately contains only the user and tenant — never
 * the tokens. Returning them would put a credential into a JavaScript variable,
 * which is precisely what the httpOnly cookie exists to avoid.
 */
export async function POST(request: NextRequest) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json(
      { code: "malformed_request", message: "Expected a JSON body" },
      { status: 400 },
    );
  }

  try {
    const tokens = await apiPostAnonymous<{
      accessToken: string;
      refreshToken: string;
      expiresInSeconds: number;
      user: unknown;
      tenant: unknown;
      availableTenants: unknown;
    }>("/api/v1/auth/login", body);

    await writeSession(tokens);

    return NextResponse.json({
      user: tokens.user,
      tenant: tokens.tenant,
      availableTenants: tokens.availableTenants,
    });
  } catch (error) {
    if (error instanceof ApiRequestError) {
      return NextResponse.json(error.payload, { status: error.status });
    }
    console.error("Login failed", error);
    return NextResponse.json(
      { code: "login_failed", message: "Sign-in could not be completed" },
      { status: 502 },
    );
  }
}
