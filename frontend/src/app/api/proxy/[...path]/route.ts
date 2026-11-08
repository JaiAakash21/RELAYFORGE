import { NextRequest, NextResponse } from "next/server";
import { ApiRequestError, apiDelete, apiGet, apiPatch, apiPost } from "@/lib/api/server";

/**
 * The browser-to-backend proxy.
 *
 * <h2>Why this route exists</h2>
 *
 * The access token lives in an httpOnly cookie that JavaScript cannot read, so
 * the browser cannot attach it to a request itself. This handler reads the
 * cookie server-side, attaches the bearer token and forwards the call. It is the
 * cost of keeping credentials out of reach of page scripts — see
 * `src/lib/session.ts` for why that trade is worth making.
 *
 * <h2>Why the path is allowlisted</h2>
 *
 * A proxy that forwards any path is a confused-deputy: it would let a page
 * script reach anything the session can reach, including routes the UI never
 * uses. Only the prefixes the dashboard actually calls are forwarded, so adding
 * a new backend route does not silently widen what the browser can do.
 *
 * <p>Authentication routes are deliberately excluded. Login and registration go
 * through `/api/session/*`, which also writes the cookie; routing them through
 * here would let a script mint a session and read the tokens from the response.
 */

/** Path prefixes the browser may reach through this proxy. */
const ALLOWED_PREFIXES = [
  "api-keys",
  "endpoints",
  "events",
  "deliveries",
  "dead-letters",
  "metrics",
  "playground",
] as const;

function isAllowed(segments: string[]): boolean {
  const first = segments[0];
  if (!first) return false;
  return (ALLOWED_PREFIXES as readonly string[]).includes(first);
}

function toBackendPath(request: NextRequest, segments: string[]): string {
  const search = request.nextUrl.search;
  return `/api/v1/${segments.map(encodeURIComponent).join("/")}${search}`;
}

function errorResponse(error: unknown): NextResponse {
  if (error instanceof ApiRequestError) {
    return NextResponse.json(error.payload, { status: error.status });
  }
  // Never surface an internal message. The server log has the detail.
  console.error("Proxy request failed", error);
  return NextResponse.json(
    {
      code: "proxy_error",
      message: "The request could not be completed",
      timestamp: new Date().toISOString(),
    },
    { status: 502 },
  );
}

async function readJsonBody(request: NextRequest): Promise<unknown> {
  const text = await request.text();
  if (text.length === 0) {
    return undefined;
  }
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

type RouteContext = { params: Promise<{ path: string[] }> };

export async function GET(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  if (!isAllowed(path)) {
    return NextResponse.json({ code: "not_found", message: "Unknown route" }, { status: 404 });
  }
  try {
    const data = await apiGet<unknown>(toBackendPath(request, path));
    return NextResponse.json(data ?? null);
  } catch (error) {
    return errorResponse(error);
  }
}

export async function POST(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  if (!isAllowed(path)) {
    return NextResponse.json({ code: "not_found", message: "Unknown route" }, { status: 404 });
  }
  try {
    const body = await readJsonBody(request);
    const data = await apiPost<unknown>(toBackendPath(request, path), body);
    return data === undefined
      ? new NextResponse(null, { status: 204 })
      : NextResponse.json(data);
  } catch (error) {
    return errorResponse(error);
  }
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  if (!isAllowed(path)) {
    return NextResponse.json({ code: "not_found", message: "Unknown route" }, { status: 404 });
  }
  try {
    const body = await readJsonBody(request);
    const data = await apiPatch<unknown>(toBackendPath(request, path), body);
    return data === undefined
      ? new NextResponse(null, { status: 204 })
      : NextResponse.json(data);
  } catch (error) {
    return errorResponse(error);
  }
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  const { path } = await context.params;
  if (!isAllowed(path)) {
    return NextResponse.json({ code: "not_found", message: "Unknown route" }, { status: 404 });
  }
  try {
    const data = await apiDelete<unknown>(toBackendPath(request, path));
    return data === undefined
      ? new NextResponse(null, { status: 204 })
      : NextResponse.json(data);
  } catch (error) {
    return errorResponse(error);
  }
}
