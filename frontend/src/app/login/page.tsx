import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { AuthShell } from "@/components/layout/AuthShell";
import { LoginForm } from "./LoginForm";
import { hasSession } from "@/lib/session";

export const metadata: Metadata = {
  title: "Sign in",
  // A sign-in page has no business in search results.
  robots: { index: false, follow: false },
};

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string }>;
}) {
  // Already signed in: go straight through rather than showing a form that
  // would immediately redirect after a pointless round trip.
  if (await hasSession()) {
    redirect("/dashboard");
  }
  const { next } = await searchParams;

  return (
    <AuthShell
      title="Sign in"
      subtitle="Access your organization's delivery pipeline."
      footer={
        <>
          No account yet?{" "}
          <Link href="/register" className="text-accent-hover hover:underline">
            Create one
          </Link>
        </>
      }
    >
      <LoginForm next={next} />
    </AuthShell>
  );
}
