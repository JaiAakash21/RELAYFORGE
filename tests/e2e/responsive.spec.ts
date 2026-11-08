import { expect, test } from "@playwright/test";
import { expectNoHorizontalOverflow, register, uniqueAccount } from "./helpers";

/**
 * Responsive layout checks.
 *
 * The spec requires no horizontal overflow anywhere. This is worth testing
 * rather than eyeballing: the usual cause is a CSS grid or flex item refusing to
 * shrink below its content, which looks fine at desktop width and silently
 * breaks the page on a phone.
 */
test.describe("responsive layout", () => {
  const authedPages = [
    "/dashboard",
    "/dashboard/events",
    "/dashboard/endpoints",
    "/dashboard/deliveries",
    "/dashboard/dead-letters",
    "/dashboard/playground",
    "/dashboard/api-keys",
    "/dashboard/observability",
    "/dashboard/settings",
  ];

  test("public pages do not scroll sideways", async ({ page }) => {
    for (const path of ["/", "/login", "/register"]) {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      await expectNoHorizontalOverflow(page);
    }
  });

  test("dashboard pages do not scroll sideways", async ({ page }) => {
    await register(page, uniqueAccount("responsive"));
    for (const path of authedPages) {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      await expectNoHorizontalOverflow(page);
    }
  });

  test("the navigation drawer is reachable on a phone", async ({ page }, testInfo) => {
    // The drawer replaces the sidebar below the lg breakpoint, so this assertion
    // is only meaningful on a narrow viewport. Skipped rather than deleted: it
    // runs in the `mobile` project, and the skip documents why it does not run
    // in the desktop one.
    const width = page.viewportSize()?.width ?? 0;
    test.skip(width >= 1024, "the drawer only exists below the lg breakpoint");

    await register(page, uniqueAccount("drawer"));
    await page.goto("/dashboard");

    // A dashboard whose navigation simply disappears below 1024px is not
    // responsive, so the drawer is asserted on rather than assumed.
    await page.getByRole("button", { name: "Open navigation" }).click();
    await expect(page.getByRole("link", { name: "Reliability Playground" })).toBeVisible();

    await page.getByRole("link", { name: "Endpoints" }).click();
    await page.waitForURL("**/dashboard/endpoints");
    // The drawer closes on navigation rather than covering the page it opened.
    await expect(page.getByRole("link", { name: "Reliability Playground" })).toBeHidden();
  });
});
