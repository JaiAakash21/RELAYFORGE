"use client";

import type { ApiError } from "@/types/api";

/**
 * The browser-side API client.
 *
 * Every call goes to a Next route handler under `/api/proxy`, never to the
 * backend directly. The route handler attaches the bearer token from the
 * httpOnly cookie, which is the whole reason this indirection exists — see
 * `src/lib/session.ts`.
 *
 * Because the cookie travels automatically, no token handling happens in the
 * browser at all: there is nothing here for a compromised script to steal.
 */

export class ClientApiError extends Error {
  constructor(
    readonly status: number,
    readonly payload: ApiError,
  ) {
    super(payload.message);
    this.name = "ClientApiError";
  }

  get code(): string {
    return this.payload.code;
  }

  /** Field-level problems, for placing messages inline on a form. */
  get fieldErrors(): Record<string, string[]> {
    return this.payload.details ?? {};
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(`/api/proxy${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    // The session cookie must travel with the request.
    credentials: "same-origin",
    signal,
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload: unknown = text.length > 0 ? JSON.parse(text) : null;

  if (!response.ok) {
    const error: ApiError =
      payload && typeof payload === "object" && "code" in payload
        ? (payload as ApiError)
        : {
            code: "request_failed",
            message: `Request failed with status ${response.status}`,
            timestamp: new Date().toISOString(),
          };
    throw new ClientApiError(response.status, error);
  }

  return payload as T;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) =>
    request<T>("GET", path, undefined, signal),
  post: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>("POST", path, body, signal),
  patch: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>("PATCH", path, body, signal),
  delete: <T>(path: string, signal?: AbortSignal) =>
    request<T>("DELETE", path, undefined, signal),
};
