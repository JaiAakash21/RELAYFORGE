import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright configuration.
 *
 * Runs against an already-running stack rather than starting one: the backend
 * needs PostgreSQL, Redis and Kafka, which is `docker compose up`, not a
 * `webServer` entry. The config fails fast with a clear message if the stack is
 * not up, instead of producing twenty confusing timeouts.
 */
const baseURL = process.env.PLAYWRIGHT_BASE_URL ?? "http://localhost:3000";

/**
 * Use a pre-installed Chromium when one is provided.
 *
 * Some environments ship a browser that Playwright did not download — a
 * container image, or a CI cache. Without this, Playwright looks for its own
 * pinned build (including the separate `headless_shell` binary that the mobile
 * device profiles request) and fails with "executable doesn't exist". Applying
 * it to every project means no project can silently fall back to a download.
 */
const pinnedBrowser = process.env.PLAYWRIGHT_CHROMIUM_PATH
  ? {
      launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH },
      // The device profiles default to the headless shell, which is a separate
      // binary. Opt into the full browser so the pinned path is the one used.
      channel: undefined,
    }
  : {};

export default defineConfig({
  testDir: "./tests/e2e",
  // Generous: these flows wait on real retry backoff, which is measured in
  // seconds by design.
  timeout: 120_000,
  expect: { timeout: 15_000 },

  // Serial in CI. The tests register their own tenants so they are logically
  // independent, but they share one backend, and running them in parallel makes
  // a failure hard to attribute.
  fullyParallel: false,
  workers: process.env.CI ? 1 : 2,

  // Never allow a committed `test.only` to silently skip the rest of the suite.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,

  reporter: process.env.CI
    ? [["list"], ["html", { open: "never" }], ["github"]]
    : [["list"]],

  use: {
    baseURL,
    // Artefacts only for failures: a trace per passing test is gigabytes of
    // noise and makes the run markedly slower.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], ...pinnedBrowser },
    },
    {
      name: "mobile",
      // Pixel 7 sets its own deviceScaleFactor and user agent; only the
      // executable path is overridden.
      use: { ...devices["Pixel 7"], ...pinnedBrowser },
      // Only the responsive checks. Re-running every flow on a phone viewport
      // would double the suite to prove something these tests already cover.
      testMatch: /responsive\.spec\.ts/,
    },
  ],
});
