import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { AuthShell } from "@/components/layout/AuthShell";
import { RegisterForm } from "./RegisterForm";
import { hasSession } from "@/lib/session";

export const metadata: Metadata = {
  title: "Create an account",
  robots: { index: false, follow: false },
};

export default async function RegisterPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string }>;
}) {
  if (await hasSession()) {
    redirect("/dashboard");
  }
  const { next } = await searchParams;

  return (
    <AuthShell
      title="Create your organization"
      subtitle="Registration creates both your account and the organization that owns your endpoints and events."
      footer={
        <>
          Already have an account?{" "}
          <Link href="/login" className="text-accent-hover hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <RegisterForm next={next} />
    </AuthShell>
  );
}
