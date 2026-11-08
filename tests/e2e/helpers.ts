import { expect, type Page } from "@playwright/test";

/**
 * Shared helpers for the end-to-end suite.
 *
 * <h2>Each test registers its own tenant</h2>
 *
 * Rather than sharing a fixture account. Tenant isolation means a fresh tenant
 * sees an empty dashboard, so a test can assert on exact counts without being
 * perturbed by whatever another test left behind — and a failure is attributable
 * to the test that caused it.
 */

export interface TestAccount {
  email: string;
  password: string;
  organization: string;
}

export function uniqueAccount(prefix: string): TestAccount {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  return {
    email: `${prefix}-${suffix}@relayforge.test`,
    password: "an-adequately-long-test-password",
    organization: `${prefix} ${suffix}`,
  };
}

/** Registers a new account and lands on the dashboard. */
export async function register(page: Page, account: TestAccount): Promise<void> {
  await page.goto("/register");
  await page.fill('input[name="email"]', account.email);
  await page.fill('input[name="organizationName"]', account.organization);
  await page.fill('input[name="password"]', account.password);
  await page.click('button[type="submit"]');
  await page.waitForURL("**/dashboard", { timeout: 45_000 });
}

export async function signIn(page: Page, account: TestAccount): Promise<void> {
  await page.goto("/login");
  await page.fill('input[name="email"]', account.email);
  await page.fill('input[name="password"]', account.password);
  await page.click('button[type="submit"]');
  await page.waitForURL("**/dashboard", { timeout: 45_000 });
}

/** Creates an API key and returns the plaintext, which is shown exactly once. */
export async function createApiKey(page: Page, name: string): Promise<string> {
  await page.goto("/dashboard/api-keys");
  const emptyStateButton = page.getByRole("button", { name: "Create your first key" });
  if (await emptyStateButton.isVisible().catch(() => false)) {
    await emptyStateButton.click();
  } else {
    await page.getByRole("button", { name: "New API key" }).click();
  }
  await page.fill("#api-key-form input", name);
  // Scoped to the dialog: the page behind it has its own create action.
  await page.locator('dialog button:has-text("Create key")').click();
  await expect(page.getByText("API key created")).toBeVisible();
  const key = await page.locator("dialog code").first().innerText();
  await page.getByRole("button", { name: "I have stored it" }).click();
  return key.trim();
}

/**
 * Runs a Reliability Playground scenario and waits for it to finish.
 *
 * @returns the terminal status text shown on the run panel
 */
export async function runPlaygroundScenario(
  page: Page,
  scenarioName: string,
  overrides: Record<string, string> = {},
): Promise<string> {
  await page.goto("/dashboard/playground");
  await page.locator(`button[role="radio"]:has-text("${scenarioName}")`).click();

  for (const [label, value] of Object.entries(overrides)) {
    const input = page.getByLabel(label, { exact: false });
    await input.fill(value);
  }

  await page.getByRole("button", { name: "Publish a test event" }).click();

  // Waits for a terminal chip. The timeout is generous because the run is
  // waiting on real exponential backoff, not on a simulated delay.
  const terminal = page.locator("text=/Succeeded|Dead-lettered/").first();
  await expect(terminal).toBeVisible({ timeout: 110_000 });
  return (await terminal.innerText()).trim();
}

/** Asserts the page does not scroll horizontally at the current viewport. */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(
    overflow,
    `page overflows horizontally by ${overflow}px`,
  ).toBeLessThanOrEqual(0);
}
