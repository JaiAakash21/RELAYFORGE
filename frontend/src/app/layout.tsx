import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";

/**
 * Self-hosted webfonts via next/font.
 *
 * `next/font` downloads the font files at build time and serves them from the
 * app's own origin, with the `@font-face` rules inlined. That removes the
 * render-blocking request to a third-party CDN and the layout shift that comes
 * with it — which is most of what separates a 70 Lighthouse performance score
 * from a 95 on a font-heavy page.
 *
 * `display: swap` means text paints immediately in the fallback and swaps when
 * the webfont arrives, rather than leaving the page blank while it loads.
 */
const sans = Inter({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-sans",
});

const mono = JetBrains_Mono({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-mono",
});

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "RelayForge — Reliable webhook delivery infrastructure",
    template: "%s · RelayForge",
  },
  description:
    "RelayForge is event delivery infrastructure for applications that cannot afford " +
    "to lose events: transactional outbox publishing, Kafka-backed delivery workers, " +
    "exponential backoff with jitter, dead-letter handling and one-click replay.",
  keywords: [
    "webhooks",
    "webhook delivery",
    "event infrastructure",
    "transactional outbox",
    "exponential backoff",
    "dead letter queue",
    "Kafka",
    "distributed systems",
  ],
  authors: [{ name: "RelayForge" }],
  openGraph: {
    type: "website",
    url: siteUrl,
    title: "RelayForge — Reliable webhook delivery infrastructure",
    description:
      "Transactional outbox publishing, Kafka-backed workers, exponential backoff " +
      "with jitter, dead-letter handling and replay — with a Reliability Playground " +
      "that demonstrates all of it against the real pipeline.",
    siteName: "RelayForge",
  },
  twitter: {
    card: "summary_large_image",
    title: "RelayForge — Reliable webhook delivery infrastructure",
    description:
      "Event delivery infrastructure for applications that cannot afford to lose events.",
  },
  robots: { index: true, follow: true },
};

export const viewport: Viewport = {
  themeColor: "#08080b",
  width: "device-width",
  initialScale: 1,
  // Deliberately not capped. Disabling zoom fails WCAG 1.4.4 and is the single
  // most common accessibility mistake in a "polished" dashboard.
  maximumScale: 5,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${mono.variable}`}>
      <body>
        {/* Skip link: the first tab stop on every page, so a keyboard user can
            bypass the navigation instead of tabbing through it on each view. */}
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[100] focus:rounded focus:bg-accent focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:text-accent-fg"
        >
          Skip to content
        </a>
        {children}
      </body>
    </html>
  );
}
