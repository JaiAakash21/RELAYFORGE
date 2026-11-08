import { expect, test } from "@playwright/test";
import { register, signIn, uniqueAccount } from "./helpers";

test.describe("authentication", () => {
  test("a new user can register, sign out and sign back in", async ({ page }) => {
    const account = uniqueAccount("auth");

    await register(page, account);
    await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
    await expect(page.getByText(account.organization)).toBeVisible();

    await page.getByRole("button", { name: /Sign out/ }).click();
    await page.waitForURL("**/login", { timeout: 30_000 });

    await signIn(page, account);
    await expect(page.getByRole("heading", { name: "Overview" })).toBeVisible();
  });

  test("the dashboard is not reachable without a session", async ({ page }) => {
    await page.goto("/dashboard");
    // The auth gate lives in the layout, which actually validates the session
    // against the backend rather than merely checking that a cookie exists.
    await page.waitForURL("**/login", { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  });

  test("wrong credentials are rejected without revealing whether the account exists", async ({
    page,
  }) => {
    const account = uniqueAccount("auth-wrong");
    await register(page, account);
    await page.getByRole("button", { name: /Sign out/ }).click();
    await page.waitForURL("**/login");

    await page.fill('input[name="email"]', account.email);
    await page.fill('input[name="password"]', "definitely-the-wrong-password");
    await page.click('button[type="submit"]');

    const error = page.getByRole("alert").first();
    await expect(error).toBeVisible();
    // One message for both "no such user" and "wrong password", so the form
    // cannot be used to enumerate accounts.
    await expect(error).toContainText(/incorrect/i);
    await expect(page).toHaveURL(/\/login/);
  });

  test("an unknown email gives the same message as a wrong password", async ({ page }) => {
    await page.goto("/login");
    await page.fill('input[name="email"]', `nobody-${Date.now()}@relayforge.test`);
    await page.fill('input[name="password"]', "some-long-enough-password");
    await page.click('button[type="submit"]');

    await expect(page.getByRole("alert").first()).toContainText(/incorrect/i);
  });

  test("registration rejects a short password before submitting", async ({ page }) => {
    await page.goto("/register");
    await page.fill('input[name="email"]', `short-${Date.now()}@relayforge.test`);
    await page.fill('input[name="password"]', "tooshort");
    await page.click('button[type="submit"]');

    // Length is the requirement that actually resists guessing, and the form
    // shows it live rather than failing on submit.
    await expect(page.getByText("At least 12 characters")).toBeVisible();
    await expect(page).toHaveURL(/\/register/);
  });
});
