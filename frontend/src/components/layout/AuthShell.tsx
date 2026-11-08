import Link from "next/link";
import { LogoMark } from "./AppShell";

/** The frame around sign-in and registration. */
export function AuthShell({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle: string;
  children: React.ReactNode;
  footer: React.ReactNode;
}) {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-canvas px-4 py-10">
      <div aria-hidden className="rf-grid-bg absolute inset-0 opacity-40" />
      <div
        aria-hidden
        className="absolute left-1/2 top-0 h-80 w-[40rem] -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent/[0.06] blur-3xl"
      />
      <main id="main" className="relative w-full max-w-sm">
        <Link href="/" className="mb-7 flex items-center justify-center gap-2.5">
          <LogoMark />
          <span className="text-lg font-semibold tracking-tight text-content">
            RelayForge
          </span>
        </Link>

        <div className="rf-card p-6 shadow-card">
          <h1 className="text-lg font-semibold text-content">{title}</h1>
          <p className="mt-1 text-sm leading-relaxed text-content-secondary">
            {subtitle}
          </p>
          <div className="mt-6">{children}</div>
        </div>

        <div className="mt-5 text-center text-sm text-content-secondary">{footer}</div>
      </main>
    </div>
  );
}
