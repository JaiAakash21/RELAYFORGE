# =============================================================================
# RelayForge frontend image.
#
# Uses Next.js standalone output: the build traces exactly which node_modules
# files the server actually reaches and copies only those. The result is ~120 MB
# instead of ~600 MB, and nothing unused is present to be exploited.
# =============================================================================

FROM node:22-alpine AS deps
WORKDIR /app
# Dependencies in their own layer, keyed on the lockfile only — a source change
# does not re-run the install.
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY frontend/ ./

# NEXT_PUBLIC_* values are inlined into the client bundle at build time, so they
# have to be present now rather than at runtime. Only non-secret values may
# appear here: anything passed as a build arg ends up readable in the shipped
# JavaScript.
ARG NEXT_PUBLIC_SITE_URL=http://localhost:3000
ARG NEXT_PUBLIC_GITHUB_URL=https://github.com/JaiAakash21/RELAYFORGE
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL \
    NEXT_PUBLIC_GITHUB_URL=$NEXT_PUBLIC_GITHUB_URL \
    NEXT_TELEMETRY_DISABLED=1

RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

RUN apk add --no-cache curl \
 && addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 --ingroup nodejs nextjs

# The standalone server, then the assets it serves. `public` and `.next/static`
# are not part of the traced output and must be copied explicitly.
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:3000/ || exit 1

CMD ["node", "server.js"]
