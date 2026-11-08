import type { NextConfig } from "next";

const config: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,

  // Typed routes and strict builds: a type error or lint failure should break
  // the build, not ship. Both default to failing the build in Next 15; they are
  // restated here so nobody is tempted to switch them off to get green.
  typescript: { ignoreBuildErrors: false },
  eslint: { ignoreDuringBuilds: false },

  // The browser never talks to the API directly and never holds a token in
  // JavaScript. Requests go to Next route handlers, which read the httpOnly
  // cookie server-side and forward the call. See src/lib/api/server.ts.
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
        ],
      },
    ];
  },
};

export default config;
