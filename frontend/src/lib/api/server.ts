import { readAccessToken, readRefreshToken, writeSession } from "@/lib/session";
import type { ApiError } from "@/types/api";

/**
 * The server-side API client.
 *
 * Runs only in Next's server runtime: server components, route handlers and
 * server actions. It reads the httpOnly cookie, attaches the bearer token and
 * calls the backend over the internal network.
 *
 * <h2>Transparent refresh</h2>
 *
 * A 401 with code `token_expired` triggers one refresh attempt and one retry.
 * Exactly one, tracked by a flag rather than by recursion, so a backend that
 * keeps answering 401 produces two requests and an error rather than an
 * unbounded loop.
 *
 * <p>Refreshing here rather than in the browser means a user working through a
 * long session never sees a spurious logout at the fifteen-minute mark, and the
 * rotated refresh token is written straight back into the cookie without any
 * client-side coordination.
 */

const INTERNAL_BASE_URL =
  process.env.RELAYFORGE_API_INTERNAL_URL ?? "http://localhost:8080";

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly payload: ApiError,
  ) {
    super(payload.message);
    this.name = "ApiRequestError";
  }

  get code(): string {
    return this.payload.code;
  }

  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

export interface ApiFetchOptions extends Omit<RequestInit, "body"> {
  body?: unknown;
  /** Next's cache behaviour. Defaults to no-store: this data is live. */
  revalidate?: number | false;
}

async function parseError(response: Response): Promise<ApiError> {
  try {
    const payload = (await response.json()) as ApiError;
    if (payload && typeof payload.code === "string") {
      return payload;
    }
  } catch {
    // Fall through to a synthesised error below.
  }
  return {
    code: "upstream_error",
    message: `The API responded with ${response.status}`,
    timestamp: new Date().toISOString(),
  };
}

async function attemptRefresh(): Promise<boolean> {
  const refreshToken = await readRefreshToken();
  if (!refreshToken) {
    return false;
  }
  const response = await fetch(`${INTERNAL_BASE_URL}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
  if (!response.ok) {
    return false;
  }
  const tokens = (await response.json()) as {
    accessToken: string;
    refreshToken: string;
    expiresInSeconds: number;
  };
  await writeSession(tokens);
  return true;
}

async function execute<T>(
  path: string,
  options: ApiFetchOptions,
  allowRefresh: boolean,
): Promise<T> {
  const token = await readAccessToken();
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${INTERNAL_BASE_URL}${path}`, {
    ...options,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    // Dashboard data is live by default. A cached delivery status would be
    // actively misleading on a page whose whole purpose is to show current
    // state.
    cache: options.revalidate === undefined ? "no-store" : undefined,
    next:
      options.revalidate === undefined ? undefined : { revalidate: options.revalidate },
  });

  if (response.status === 401 && allowRefresh) {
    const refreshed = await attemptRefresh();
    if (refreshed) {
      // One retry only. The flag — rather than recursion — is what bounds it.
      return execute<T>(path, options, false);
    }
  }

  if (!response.ok) {
    throw new ApiRequestError(response.status, await parseError(response));
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export function apiGet<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  return execute<T>(path, { ...options, method: "GET" }, true);
}

export function apiPost<T>(
  path: string,
  body?: unknown,
  options: ApiFetchOptions = {},
): Promise<T> {
  return execute<T>(path, { ...options, method: "POST", body }, true);
}

export function apiPatch<T>(
  path: string,
  body?: unknown,
  options: ApiFetchOptions = {},
): Promise<T> {
  return execute<T>(path, { ...options, method: "PATCH", body }, true);
}

export function apiDelete<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  return execute<T>(path, { ...options, method: "DELETE" }, true);
}

/** Calls the backend without a session — registration, login and refresh. */
export async function apiPostAnonymous<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${INTERNAL_BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    throw new ApiRequestError(response.status, await parseError(response));
  }
  return (await response.json()) as T;
}
