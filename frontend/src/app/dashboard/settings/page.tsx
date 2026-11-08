import type { Metadata } from "next";
import { Building2, ShieldCheck, Terminal, User } from "lucide-react";
import { PageHeader, DetailRow } from "@/components/ui/PageHeader";
import { Card, CardHeader } from "@/components/ui/Card";
import { Badge } from "@/components/ui/StatusBadge";
import { CodeBlock, IdChip } from "@/components/ui/Code";
import { ErrorState } from "@/components/ui/States";
import { SignOutEverywhere } from "./SignOutEverywhere";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { formatAbsolute } from "@/lib/format";
import type { SessionResponse } from "@/types/api";

export const metadata: Metadata = { title: "Settings" };

/**
 * Settings.
 *
 * <h2>Deliberately read-mostly</h2>
 *
 * The only write action here is "sign out everywhere", which is the one setting
 * that matters during a credential incident. Renaming an organization or editing
 * a profile are not implemented rather than stubbed — a settings page full of
 * inputs that silently discard what is typed into them is worse than one that is
 * honest about its scope. The gap is listed in the project's known limitations.
 */
export default async function SettingsPage() {
  let session: SessionResponse;
  try {
    session = await apiGet<SessionResponse>("/api/v1/auth/session");
  } catch (error) {
    return (
      <>
        <PageHeader title="Settings" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load your settings"
            message={
              error instanceof ApiRequestError
                ? error.message
                : "The API could not be reached."
            }
            traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
          />
        </Card>
      </>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Settings"
        description="Your account, your organization, and how RelayForge handles the credentials behind both."
      />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader
            title={
              <span className="flex items-center gap-2">
                <Building2 className="h-4 w-4 text-content-tertiary" aria-hidden />
                Organization
              </span>
            }
          />
          <dl className="divide-y divide-border-subtle px-4 py-2">
            <DetailRow label="Name">{session.tenant.name}</DetailRow>
            <DetailRow label="Slug">
              <code className="rf-mono">{session.tenant.slug}</code>
            </DetailRow>
            <DetailRow label="Tenant ID">
              <IdChip value={session.tenant.id} length={36} />
            </DetailRow>
            <DetailRow label="Your role">
              <Badge tone="accent">{session.tenant.role.toLowerCase()}</Badge>
            </DetailRow>
            <DetailRow label="Organizations">
              {session.availableTenants.length === 1
                ? "This is your only organization."
                : `${session.availableTenants.length} organizations.`}
            </DetailRow>
          </dl>
        </Card>

        <Card>
          <CardHeader
            title={
              <span className="flex items-center gap-2">
                <User className="h-4 w-4 text-content-tertiary" aria-hidden />
                Account
              </span>
            }
          />
          <dl className="divide-y divide-border-subtle px-4 py-2">
            <DetailRow label="Email">{session.user.email}</DetailRow>
            <DetailRow label="Name">
              {session.user.displayName ?? (
                <span className="text-content-tertiary">not set</span>
              )}
            </DetailRow>
            <DetailRow label="User ID">
              <IdChip value={session.user.id} length={36} />
            </DetailRow>
            <DetailRow label="Joined">{formatAbsolute(session.user.createdAt)}</DetailRow>
          </dl>
          <div className="border-t border-border-subtle p-4">
            <SignOutEverywhere />
          </div>
        </Card>
      </div>

      <Card>
        <CardHeader
          title={
            <span className="flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-accent-hover" aria-hidden />
              How your credentials are handled
            </span>
          }
          description="Stated here rather than buried in documentation, because these are the trade-offs you are relying on."
        />
        <ul className="grid gap-px bg-border sm:grid-cols-2">
          {[
            {
              title: "Passwords: BCrypt, cost 12",
              body: "Deliberately slow, which is the point — it makes an offline attack on a stolen hash table expensive. Input is capped at 72 bytes because BCrypt silently ignores anything beyond that, and accepting more would be a lie about the protection provided.",
            },
            {
              title: "Dashboard tokens live in httpOnly cookies",
              body: "Not in localStorage. Any script on the page can read localStorage, so one compromised dependency would hand over a credential usable from anywhere. The cookie cannot be read by script at all.",
            },
            {
              title: "Access tokens are short-lived and not revocable",
              body: "A JWT is self-contained, so verifying it needs no database round trip — and cannot be cancelled before it expires. The window is capped at 15 minutes. The refresh token is stateful and revocable, which is what makes sign-out meaningful.",
            },
            {
              title: "Refresh tokens rotate, and reuse is detected",
              body: "Every refresh issues a new token. If an already-replaced token is presented, two parties hold it — so the whole token family is revoked and both must sign in again.",
            },
            {
              title: "Webhook secrets are encrypted, not hashed",
              body: "AES-256-GCM, because we have to use the secret to sign with it. GCM is authenticated: tampering with a stored ciphertext fails decryption instead of silently producing a different key.",
            },
            {
              title: "API keys are hashed and immediately revocable",
              body: "SHA-256 of 256 random bits. Every API-key request checks the database, so revoking one takes effect on its very next call.",
            },
          ].map((item) => (
            <li key={item.title} className="bg-surface p-4">
              <h3 className="text-base font-medium text-content">{item.title}</h3>
              <p className="mt-1 text-sm leading-relaxed text-content-secondary">
                {item.body}
              </p>
            </li>
          ))}
        </ul>
      </Card>

      <Card>
        <CardHeader
          title={
            <span className="flex items-center gap-2">
              <Terminal className="h-4 w-4 text-content-tertiary" aria-hidden />
              Publishing events
            </span>
          }
          description="The one call your application needs."
        />
        <div className="flex flex-col gap-4 p-4">
          <CodeBlock
            language="bash"
            code={`curl -X POST http://localhost:8080/api/v1/events \\
  -H "X-API-Key: rf_live_xxxxxxxx_..." \\
  -H "Idempotency-Key: order-1001-created" \\
  -H "Content-Type: application/json" \\
  -d '{
    "eventType": "order.created",
    "payload": { "orderId": "ORD-1001", "amount": 2499, "currency": "INR" }
  }'`}
          />
          <p className="text-xs leading-relaxed text-content-secondary">
            Returns <code className="rf-mono">202 Accepted</code> as soon as the
            database transaction commits, with the delivery ids it created. It does not
            wait for your receivers — that is the asynchrony the whole platform is built
            around. The full reference, including the signature-verification algorithm,
            is at <code className="rf-mono">/swagger-ui.html</code>.
          </p>
        </div>
      </Card>
    </div>
  );
}
