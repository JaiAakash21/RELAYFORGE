"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Activity,
  BarChart3,
  FlaskConical,
  Inbox,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Menu,
  Radio,
  Send,
  Settings,
  Webhook,
  X,
} from "lucide-react";
import { cn } from "@/lib/cn";
import type { SessionResponse } from "@/types/api";

/**
 * The authenticated application shell.
 *
 * <h2>Navigation structure</h2>
 *
 * Grouped by the question a user is answering, not by the API that serves it.
 * "Monitor" is where you look when something is wrong; "Configure" is where you
 * change how the system behaves. A flat list of nine items would make the
 * Reliability Playground as prominent as Settings, which gets the emphasis
 * backwards.
 *
 * <h2>Mobile behaviour</h2>
 *
 * The sidebar becomes an overlay drawer below `lg`. It is not merely hidden: the
 * drawer traps nothing and closes on navigation, so a phone user can reach every
 * page. The spec requires the dashboard to work on mobile, and a dashboard whose
 * navigation disappears below 1024px does not.
 */

interface NavItem {
  href: string;
  label: string;
  icon: React.ReactNode;
  description: string;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const NAV_GROUPS: NavGroup[] = [
  {
    label: "Monitor",
    items: [
      {
        href: "/dashboard",
        label: "Overview",
        icon: <LayoutDashboard className="h-4 w-4" aria-hidden />,
        description: "Throughput, success rate and queue health",
      },
      {
        href: "/dashboard/events",
        label: "Events",
        icon: <Send className="h-4 w-4" aria-hidden />,
        description: "Everything published, with its fan-out",
      },
      {
        href: "/dashboard/deliveries",
        label: "Deliveries",
        icon: <Radio className="h-4 w-4" aria-hidden />,
        description: "Per-endpoint delivery state and attempts",
      },
      {
        href: "/dashboard/dead-letters",
        label: "Dead letters",
        icon: <Inbox className="h-4 w-4" aria-hidden />,
        description: "Exhausted deliveries, ready to replay",
      },
      {
        href: "/dashboard/observability",
        label: "Observability",
        icon: <BarChart3 className="h-4 w-4" aria-hidden />,
        description: "Latency, retries and endpoint performance",
      },
    ],
  },
  {
    label: "Demonstrate",
    items: [
      {
        href: "/dashboard/playground",
        label: "Reliability Playground",
        icon: <FlaskConical className="h-4 w-4" aria-hidden />,
        description: "Inject failures and watch the pipeline recover",
      },
    ],
  },
  {
    label: "Configure",
    items: [
      {
        href: "/dashboard/endpoints",
        label: "Endpoints",
        icon: <Webhook className="h-4 w-4" aria-hidden />,
        description: "Destinations, subscriptions and secrets",
      },
      {
        href: "/dashboard/api-keys",
        label: "API keys",
        icon: <KeyRound className="h-4 w-4" aria-hidden />,
        description: "Ingestion credentials",
      },
      {
        href: "/dashboard/settings",
        label: "Settings",
        icon: <Settings className="h-4 w-4" aria-hidden />,
        description: "Organization and account",
      },
    ],
  },
];

export function AppShell({
  session,
  children,
}: {
  session: SessionResponse;
  children: React.ReactNode;
}) {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const pathname = usePathname();

  return (
    <div className="min-h-screen bg-canvas">
      <MobileTopBar onOpenDrawer={() => setDrawerOpen(true)} />

      {/* Mobile drawer */}
      {drawerOpen ? (
        <div className="fixed inset-0 z-50 lg:hidden">
          <button
            type="button"
            aria-label="Close navigation"
            onClick={() => setDrawerOpen(false)}
            className="absolute inset-0 bg-canvas/80 backdrop-blur-sm"
          />
          <div className="absolute inset-y-0 left-0 w-72 max-w-[85vw] animate-slide-in-right border-r border-border bg-surface">
            <div className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
              <Brand />
              <button
                type="button"
                onClick={() => setDrawerOpen(false)}
                aria-label="Close navigation"
                className="rounded p-1 text-content-tertiary hover:bg-surface-3 hover:text-content"
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            </div>
            <SidebarContent
              pathname={pathname}
              session={session}
              onNavigate={() => setDrawerOpen(false)}
            />
          </div>
        </div>
      ) : null}

      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-border bg-surface lg:flex">
        <div className="flex h-14 items-center border-b border-border-subtle px-4">
          <Brand />
        </div>
        <SidebarContent pathname={pathname} session={session} />
      </aside>

      <main id="main" className="lg:pl-64">
        <div className="mx-auto w-full max-w-[1400px] px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          {children}
        </div>
      </main>
    </div>
  );
}

function Brand() {
  return (
    <Link href="/dashboard" className="flex items-center gap-2.5">
      <LogoMark />
      <span className="text-md font-semibold tracking-tight text-content">
        RelayForge
      </span>
    </Link>
  );
}

/**
 * The wordmark glyph.
 *
 * Inline SVG rather than an image file: it is a handful of bytes, needs no
 * network request, and inherits `currentColor` so it cannot fall out of step
 * with the palette.
 */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={cn("h-6 w-6 text-accent", className)}
      fill="none"
      aria-hidden
    >
      {/* Three relay hops: a signal entering, being held, and leaving. */}
      <path
        d="M3 7h5l2.5 5L13 7h5"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M3 17h5l2.5-5L13 17h5"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.45"
      />
      <circle cx="20.5" cy="7" r="1.75" fill="currentColor" />
      <circle cx="20.5" cy="17" r="1.75" fill="currentColor" opacity="0.45" />
    </svg>
  );
}

function MobileTopBar({ onOpenDrawer }: { onOpenDrawer: () => void }) {
  return (
    <div className="sticky top-0 z-40 flex h-14 items-center justify-between border-b border-border bg-surface/95 px-4 backdrop-blur lg:hidden">
      <Brand />
      <button
        type="button"
        onClick={onOpenDrawer}
        aria-label="Open navigation"
        className="rounded border border-border p-1.5 text-content-secondary hover:bg-surface-3 hover:text-content"
      >
        <Menu className="h-4 w-4" aria-hidden />
      </button>
    </div>
  );
}

function SidebarContent({
  pathname,
  session,
  onNavigate,
}: {
  pathname: string;
  session: SessionResponse;
  onNavigate?: () => void;
}) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <nav aria-label="Main" className="flex-1 overflow-y-auto px-2 py-4">
        {NAV_GROUPS.map((group) => (
          <div key={group.label} className="mb-5">
            <p className="rf-label px-2 pb-1.5">{group.label}</p>
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                // Exact match for the index route, prefix match elsewhere, so
                // /dashboard does not light up on every child page.
                const active =
                  item.href === "/dashboard"
                    ? pathname === "/dashboard"
                    : pathname.startsWith(item.href);
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      onClick={onNavigate}
                      aria-current={active ? "page" : undefined}
                      title={item.description}
                      className={cn(
                        "flex items-center gap-2.5 rounded px-2 py-1.5 text-sm transition-colors",
                        active
                          ? "bg-accent-subtle text-accent-hover"
                          : "text-content-secondary hover:bg-surface-2 hover:text-content",
                      )}
                    >
                      <span className={active ? "text-accent-hover" : "text-content-tertiary"}>
                        {item.icon}
                      </span>
                      <span className="truncate">{item.label}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>
      <AccountFooter session={session} />
    </div>
  );
}

function AccountFooter({ session }: { session: SessionResponse }) {
  const [signingOut, setSigningOut] = useState(false);

  const signOut = async () => {
    setSigningOut(true);
    try {
      await fetch("/api/session/logout", { method: "POST" });
      // A full navigation, not router.push: it discards every cached server
      // component and client cache entry, so no fragment of the previous
      // tenant's data survives the sign-out.
      window.location.href = "/login";
    } catch {
      setSigningOut(false);
    }
  };

  const initial = (session.user.displayName ?? session.user.email)
    .charAt(0)
    .toUpperCase();

  return (
    <div className="border-t border-border-subtle p-3">
      <div className="mb-2 flex items-center gap-2.5 px-1">
        <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accent-subtle text-2xs font-semibold text-accent-hover">
          {initial}
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs font-medium text-content">
            {session.user.displayName ?? session.user.email}
          </p>
          <p className="truncate text-2xs text-content-tertiary">
            {session.tenant.name} · {session.tenant.role.toLowerCase()}
          </p>
        </div>
      </div>
      <button
        type="button"
        onClick={signOut}
        disabled={signingOut}
        className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-xs text-content-secondary transition-colors hover:bg-surface-2 hover:text-content disabled:opacity-60"
      >
        <LogOut className="h-3.5 w-3.5" aria-hidden />
        {signingOut ? "Signing out…" : "Sign out"}
      </button>
    </div>
  );
}

/** A small live-polling indicator, shown next to self-refreshing data. */
export function LiveIndicator({ active, label }: { active: boolean; label?: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-2xs text-content-tertiary">
      <span
        aria-hidden
        className={cn(
          "h-1.5 w-1.5 rounded-full",
          active ? "animate-pulse-dot bg-status-success" : "bg-content-tertiary",
        )}
      />
      {label ?? (active ? "Live" : "Paused")}
      {active ? <Activity className="h-3 w-3" aria-hidden /> : null}
    </span>
  );
}
