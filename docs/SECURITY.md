# Security

## Threat model

A webhook platform has an unusual shape: its entire purpose is to make outbound
HTTP requests to addresses its users choose, and to hold credentials that let
it impersonate itself to those addresses. The controls below follow from that.

| Asset | Threat | Control |
|---|---|---|
| Webhook signing secrets | Database dump yields usable secrets | AES-256-GCM at rest; key from the environment |
| API keys | Database dump yields usable credentials | SHA-256 hash only; plaintext returned once |
| Passwords | Offline guessing of a stolen hash table | BCrypt cost 12 |
| Session tokens | XSS steals a credential usable anywhere | httpOnly cookies; no token in JavaScript |
| Internal network | SSRF via user-supplied webhook URLs | Resolved-address validation + DNS pinning |
| Tenant data | Cross-tenant read via a guessed id | Tenant-scoped queries; 404 not 403 |
| Platform capacity | One tenant floods the pipeline | Redis token-bucket rate limiting |

## Credential handling

**Passwords — BCrypt, cost 12.** Deliberately slow: that is what makes an
offline attack on a stolen hash expensive. Input is capped at 72 bytes because
BCrypt silently ignores anything beyond that, and accepting a 200-character
password while using only the first 72 would be a lie about the protection
provided.

**API keys — SHA-256, unsalted.** A different problem, so a different answer. A
key is 256 bits from a CSPRNG: there is no dictionary to attack, so a slow hash
buys nothing and costs a great deal — the key is verified on *every* ingestion
request, and a 250ms KDF there would cap throughput at a few requests per
second per core. No salt because the lookup *is* by hash, and with
full-entropy inputs a salt adds nothing.

**Webhook secrets — AES-256-GCM.** Encrypted, not hashed, because we must use
the secret to sign with it. GCM rather than CBC because it is authenticated:
tampering with a stored ciphertext fails decryption rather than silently
yielding a different key an attacker influenced. A fresh random IV per
encryption — reusing an IV under GCM is catastrophic, not merely weak.

**Refresh tokens — SHA-256, rotating, with reuse detection.** Each refresh
issues a new token and marks the old one replaced. If an already-replaced token
is presented, two parties hold it, so the whole family is revoked. Losing a
session is a small cost next to leaving a stolen token usable.

## Webhook signatures

```
X-RelayForge-Signature: t=<unix-seconds>,v1=<hex HMAC-SHA256>
signed material: "<t>.<raw request body>"
```

The timestamp is **inside** the MAC. A signature over the body alone is
replayable forever; binding the timestamp in lets a receiver reject anything
outside a tolerance window, and because it is covered it cannot be edited to
make an old capture look fresh.

Verification uses `MessageDigest.isEqual`. A naive `equals` returns as soon as
two bytes differ, and that timing difference is enough to recover a valid
signature byte by byte.

During a rotation the header carries one `v1=` element per valid secret, so a
receiver can roll its configuration without dropping events. The superseded
secret expires after a configured grace window — a previous secret that stayed
valid forever would mean rotating a leaked secret never actually revoked it.

## SSRF

This is the control that matters most, because the attack is not hypothetical:
a tenant who registers `http://169.254.169.254/latest/meta-data/` is asking our
infrastructure to fetch cloud credentials and hand back the response body,
which the delivery-attempt viewer would faithfully display.

**What is enforced** (`DestinationPolicy`):

- **Scheme** — `https` only in the `prod` profile.
- **Port** — an explicit allowlist. Blocks using the sender as a port scanner.
- **Userinfo** — rejected. `https://trusted.test@attacker.test/` reads as one
  host and resolves to another.
- **Every resolved address** — loopback, private (RFC1918), link-local
  (which contains every cloud metadata service), carrier-grade NAT,
  unspecified, multicast, reserved, IPv6 unique-local, and the IPv4-mapped IPv6
  form of all of them.
- **Cloud metadata addresses by name and by address**, never unlockable by the
  development allowlist.
- **DNS resolution on a bounded executor**, so a deliberately slow authoritative
  server cannot pin a worker thread.
- **Redirects are not followed.** Following one would send a signed payload to
  a destination that never passed the policy.

**DNS rebinding.** Validating a hostname and then handing it to an HTTP client
means two resolutions, and a short-TTL record can return a public address to
the check and a private one to the connection. RelayForge closes this:
`PinnedDnsResolver` replaces the HTTP client's resolver and returns only the
addresses the policy approved. There is no second lookup to poison. A hostname
that was never pinned is refused rather than resolved, so a future code path
that forgets to validate breaks loudly in tests instead of quietly reaching the
internal network.

**Honest limitation.** None of this defends against a destination that is
public at connect time and proxies internally, nor against a route that becomes
internal below the IP layer. **In production the correct final control is
network-level egress filtering** — a NAT gateway or egress proxy that cannot
reach RFC1918 space at all. Application-level validation is defence in depth,
not a substitute.

**The development allowlist** exempts named Docker service hostnames from the
private-address block so the webhook simulator is reachable. It applies only in
the `dev` profile, matches exactly (never as a prefix or suffix), normalises
trailing dots, and never unlocks metadata addresses. It must not be enabled in
production.

Covered by 48 tests in `DestinationPolicyTest`.

## Tenant isolation

Enforced in the service and repository layers, not the frontend. Every
tenant-owned table carries `tenant_id` directly, and every read path uses a
tenant-scoped query rather than `findById` followed by a check — isolation is a
property of the query, not of a later `if`.

Cross-tenant references return **404, not 403**. Returning 403 would confirm
that an identifier exists, turning the API into an existence oracle for other
tenants' data.

Covered by `TenantIsolationIT`, which calls the services directly with a
foreign tenant id — the way a future bug would.

## Session security

Tokens live in httpOnly cookies and the browser never holds one. Any script on
the page can read `localStorage`, so one compromised dependency would hand over
a credential usable from anywhere; an httpOnly cookie cannot be read by script
at all. The cost is that the browser cannot call the API directly, which is why
the frontend proxies through its own route handlers — and that proxy allowlists
the path prefixes the dashboard uses, so it cannot become a general-purpose
confused deputy.

`SameSite=Lax` is the CSRF protection for those cookie-bearing routes. The API
itself needs none: it requires an explicit `Authorization` or `X-API-Key`
header, which a cross-site form cannot set.

**Access tokens cannot be revoked before they expire.** A JWT is self-contained,
which is what makes it cheap enough to verify on every request without a
database round trip. The window is capped at 15 minutes. Refresh tokens are
stateful and revocable, so "sign out everywhere" takes effect at the next
refresh; API keys are checked against the database on every request, so
revoking one is immediate. The settings page states this rather than implying
instant revocation.

## Other controls

- **Request size limits** — 256 KiB per event payload, measured after parsing
  so whitespace padding cannot game the figure. 64 KiB cap on response bodies
  read from receivers, read incrementally so an endless response cannot exhaust
  a worker's heap.
- **Response headers** are stored from a small allowlist. Receiver headers are
  attacker-influenced and can carry their own credentials.
- **Error responses** never include a stack trace or an internal message. Every
  error carries a trace id, which is the one internal detail that is both safe
  and useful to expose.
- **Logging** never emits a raw secret or API key — only the non-secret prefix.
  Hibernate SQL logging is off, because it would log bound parameters
  including payloads and sealed secrets.
- **CORS** origins come from configuration and are never wildcarded;
  `allowCredentials` with `*` is both forbidden and exactly the mistake that
  makes an API readable by any site the user visits.
- **SQL injection** — every query is parameterised, including the raw-SQL DAOs.
  No string concatenation of user input anywhere.
- **Containers** run as a non-root user with no shell tooling beyond `curl` for
  the healthcheck.

## Secrets in the repository

`.env` is git-ignored; `.env.example` contains only `CHANGE_ME` placeholders.
The application refuses to start on a placeholder key — in production that
would otherwise mean every tenant's secrets encrypted under a published value.

CI runs a secret scan on every push over the full history. It requires entropy
rather than just length — a real key's secret segment carries an uppercase
letter and a digit, a test fixture does not — so fixtures do not trip it and
the alert stays meaningful.

## What a production deployment still needs

This is a demonstration project. Before real traffic it would need:

1. **Network-level egress filtering.** The single most important addition.
2. **A secret manager** (Vault, AWS Secrets Manager) instead of environment
   variables, with key rotation for the AES key.
3. **TLS everywhere**, including between services.
4. **An edge proxy that overwrites `X-Forwarded-For`.** The auth rate limiter
   trusts it, which is fine for throttling unsophisticated credential stuffing
   and is not a security boundary.
5. **Audit logging** of administrative actions to an append-only store.
6. **Per-tenant encryption keys**, so one key compromise is not total.
7. **A dependency-scanning gate**, not just the advisory report CI produces now.
