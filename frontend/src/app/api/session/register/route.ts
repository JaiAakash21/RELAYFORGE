import { NextRequest, NextResponse } from "next/server";
import { ApiRequestError, apiPostAnonymous } from "@/lib/api/server";
import { writeSession } from "@/lib/session";

/** Creates an account and signs it straight in. */
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
    }>("/api/v1/auth/register", body);

    await writeSession(tokens);

    return NextResponse.json(
      {
        user: tokens.user,
        tenant: tokens.tenant,
        availableTenants: tokens.availableTenants,
      },
      { status: 201 },
    );
  } catch (error) {
    if (error instanceof ApiRequestError) {
      return NextResponse.json(error.payload, { status: error.status });
    }
    console.error("Registration failed", error);
    return NextResponse.json(
      { code: "registration_failed", message: "The account could not be created" },
      { status: 502 },
    );
  }
}
