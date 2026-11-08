# API reference

Base path `/api/v1`. Interactive OpenAPI at
<http://localhost:8080/swagger-ui.html>; the machine-readable document is at
`/v3/api-docs`.

## Authentication

Two credential types, for two different callers.

| | Header | For | Revocation |
|---|---|---|---|
| **API key** | `X-API-Key: rf_live_…` or `Authorization: Bearer rf_live_…` | Servers publishing events | Immediate — validated against the database every request |
| **Access token** | `Authorization: Bearer <jwt>` | The dashboard | On expiry (15 min). The refresh token is revocable. |

An API key can publish events and read its tenant's data. It deliberately
**cannot** manage API keys or tenant settings: a leaked ingestion credential
must not be able to grant itself permanent access.

## Endpoints

### Authentication
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/register` | Creates the account and its organization together |
| POST | `/auth/login` | One generic error for unknown user and wrong password |
| POST | `/auth/refresh` | Rotates the token; reuse revokes the whole family |
| POST | `/auth/logout` | Revokes the presented token's family |
| POST | `/auth/logout-all` | Revokes every refresh token for the user |
| GET | `/auth/session` | Current user, tenant and available tenants |

### Events
| Method | Path | Notes |
|---|---|---|
| POST | `/events` | `202 Accepted`; honours `Idempotency-Key` |
| GET | `/events` | Filter by `eventType`, `search`, `from`, `to` |
| GET | `/events/{id}` | Includes the payload |
| GET | `/events/{id}/deliveries` | |
| GET | `/events/types` | Distinct types this tenant has published |

### Endpoints
| Method | Path | Notes |
|---|---|---|
| POST | `/endpoints` | Returns the signing secret **once** |
| GET | `/endpoints` | Includes 24-hour health |
| GET / PATCH / DELETE | `/endpoints/{id}` | Delete is soft; open deliveries are cancelled |
| POST | `/endpoints/{id}/test` | A real signed event through the full pipeline |
| POST | `/endpoints/{id}/rotate-secret` | Both secrets valid during the grace window |
| POST | `/endpoints/{id}/revoke-previous-secret` | Ends the window early |
| POST | `/endpoints/{id}/pause` · `/resume` | Pause **holds** events; it does not drop them |

### Deliveries and dead letters
| Method | Path | Notes |
|---|---|---|
| GET | `/deliveries` | Filter by `status`, `endpointId`, `eventId`, date range |
| GET | `/deliveries/{id}` | Delivery, payload, every attempt, every replay |
| GET | `/deliveries/{id}/attempts` | |
| POST | `/deliveries/{id}/replay` | |
| POST | `/deliveries/replay` | Bulk; ineligible items are skipped, not fatal |
| GET | `/dead-letters` | A view over `status = FAILED` |
| POST | `/dead-letters/{id}/replay` · `/replay-all` | Bulk is capped at 500 |

### Metrics and playground
| Method | Path | Notes |
|---|---|---|
| GET | `/metrics/summary?window=24h` | `1h`, `6h`, `24h`, `7d`, `30d` |
| GET | `/metrics/deliveries?window=24h` | Downsampled to ~60 points |
| GET | `/metrics/endpoints` | Worst-performing first |
| GET | `/playground/scenarios` | Each states what it teaches |
| POST | `/playground/test-events` | Starts a run |
| GET | `/playground/runs/{id}` | Poll ~1/s while running |

## Publishing

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-API-Key: rf_live_xxxxxxxx_..." \
  -H "Idempotency-Key: order-1001-created" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"order.created","payload":{"orderId":"ORD-1001"}}'
```

```json
{
  "eventId": "7f3c...",
  "eventType": "order.created",
  "createdAt": "2026-11-08T12:00:00Z",
  "deduplicated": false,
  "deliveriesCreated": 2,
  "deliveries": [
    { "deliveryId": "a1b2...", "endpointId": "c3d4...", "endpointName": "Fulfilment", "status": "PENDING" }
  ]
}
```

`202` for a new event, `200` when an `Idempotency-Key` matched an existing one.
Returning as soon as the transaction commits is deliberate: waiting for the
webhooks would couple your API's latency to the slowest receiver on the list.

### Event types

Dotted, lower-case, matching `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}$`. Constrained
rather than free-form because the type drives wildcard subscription matching
and appears in logs. The `relayforge.` prefix is reserved.

Subscriptions accept an exact type, a trailing wildcard (`order.*`), or an
empty list meaning every type.

## What RelayForge sends

```http
POST /your/webhook HTTP/1.1
Content-Type: application/json; charset=utf-8
X-RelayForge-Signature: t=1762603200,v1=5f8d2c...
X-RelayForge-Timestamp: 1762603200
X-RelayForge-Event-Id: 7f3c1e2a-...
X-RelayForge-Event-Type: order.created
X-RelayForge-Delivery-Id: a1b2c3d4-...
X-RelayForge-Attempt: 1
X-RelayForge-Endpoint-Id: c3d4e5f6-...
traceparent: 00-4bf92f...-00f067...-01
User-Agent: RelayForge/1.0

{"id":"7f3c1e2a-...","type":"order.created","createdAt":"...","data":{...}}
```

**Deduplicate on `X-RelayForge-Event-Id`.** It does not change across retries
or replays. Delivery is at-least-once, so this is the only place the ambiguity
can be resolved.

## Verifying the signature

```
signed material = "<timestamp>.<raw request body>"
signature       = hex(HMAC-SHA256(endpoint_secret, signed_material))
header          = "t=<timestamp>,v1=<signature>"
```

```javascript
import { createHmac, timingSafeEqual } from "node:crypto";

export function verify(secret, header, rawBody, toleranceSec = 300) {
  const parts = Object.fromEntries(
    header.split(",").map((p) => p.trim().split("=")),
  );
  const timestamp = Number(parts.t);
  if (!Number.isFinite(timestamp)) return false;

  // Without this, a captured request is replayable forever.
  if (Math.abs(Date.now() / 1000 - timestamp) > toleranceSec) return false;

  const expected = createHmac("sha256", secret)
    .update(`${timestamp}.`)
    .update(rawBody)   // the exact bytes received, not re-serialised JSON
    .digest();

  // Several v1= values appear during a secret rotation; any match is valid.
  return header
    .split(",")
    .filter((p) => p.trim().startsWith("v1="))
    .some((p) => {
      const sig = Buffer.from(p.trim().slice(3), "hex");
      return sig.length === expected.length && timingSafeEqual(expected, sig);
    });
}
```

Three things that are easy to get wrong:

1. **Use the raw bytes**, not re-serialised JSON. Re-serialising changes key
   order and whitespace, and the signature will never match.
2. **Compare in constant time.** A byte-by-byte early return leaks the
   signature.
3. **Check the timestamp.** Without it the scheme has no replay protection.

## Errors

One shape everywhere:

```json
{
  "code": "validation_failed",
  "message": "The request body failed validation",
  "details": { "url": ["must be an http or https URL"] },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-08T12:00:00Z"
}
```

Branch on `code`, not on prose. `traceId` correlates the error with server logs
and traces — quote it when reporting a problem.

| Status | Codes |
|---|---|
| 400 | `validation_failed`, `malformed_request`, `missing_parameter` |
| 401 | `invalid_credentials`, `token_expired`, `api_key_invalid`, `api_key_revoked` |
| 403 | `forbidden` |
| 404 | `not_found` — also returned for another tenant's resource, so the API is not an existence oracle |
| 409 | `conflict`, `not_replayable`, `concurrent_modification` |
| 422 | `destination_not_allowed`, `payload_too_large`, `reserved_event_type` |
| 429 | `rate_limited` — with `Retry-After` |
| 500 | `internal_error` — trace id only, never an internal message |

## Rate limits

| Limit | Default | On breach |
|---|---|---|
| Tenant ingestion | 100/s, burst 200 | 429 + `Retry-After` |
| API key ingestion | 50/s, burst 100 | 429 + `Retry-After` |
| Per-endpoint delivery | 20/s | **Delivery is delayed, never dropped** |
| Authentication | 20/min per client | 429 |

The endpoint limit protects *your receiver* from us. Breaching it reschedules
the delivery — the event was already accepted, so the only correct response to
"too fast" is "later".

## Pagination

```json
{ "items": [...], "page": 0, "size": 25, "totalItems": 142, "totalPages": 6, "hasNext": true }
```

Offset-based, ordered on `(createdAt DESC, id DESC)` so the order is total and
stable for existing rows. A row inserted while you are paging still shifts the
window; cursor pagination would remove that and is listed as a known
limitation.
