import { expect, test } from "@playwright/test";
import { register, uniqueAccount } from "./helpers";

test.describe("endpoint management", () => {
  test("an endpoint can be created and its secret is shown exactly once", async ({ page }) => {
    await register(page, uniqueAccount("ep"));

    await page.goto("/dashboard/endpoints");
    await expect(page.getByText("No endpoints yet")).toBeVisible();

    await page.getByRole("button", { name: "Add your first endpoint" }).click();
    await page.getByLabel("Name").fill("Order fulfilment");
    await page.getByLabel("Destination URL").fill("https://example.com/webhooks/orders");
    await page.getByLabel("Subscribed event types").fill("order.created, order.*");
    await page.getByRole("button", { name: "Create endpoint" }).click();

    // The secret appears once, in a blocking dialog that must be acknowledged —
    // a toast that faded would lose a value that cannot be recovered.
    await expect(page.getByText("Endpoint created")).toBeVisible();
    const secret = await page.locator("dialog code").first().innerText();
    expect(secret).toMatch(/^whsec_/);
    await page.getByRole("button", { name: "I have stored it" }).click();

    await expect(page.getByRole("link", { name: "Order fulfilment" })).toBeVisible();

    // And it is not shown again anywhere on the detail page.
    await page.getByRole("link", { name: "Order fulfilment" }).click();
    // The exact detail-row text: the creation dialog's description contains
    // the same phrase and is still in the DOM, merely closed.
    await expect(
      page.getByText("encrypted at rest · shown only when created or rotated"),
    ).toBeVisible();
    await expect(page.locator("body")).not.toContainText(secret);
  });

  test("the SSRF policy refuses a private destination with a usable reason", async ({ page }) => {
    await register(page, uniqueAccount("ssrf"));

    await page.goto("/dashboard/endpoints");
    await page.getByRole("button", { name: "Add your first endpoint" }).click();
    await page.getByLabel("Name").fill("Metadata probe");
    await page.getByLabel("Destination URL").fill("http://169.254.169.254/latest/meta-data/");
    await page.getByRole("button", { name: "Create endpoint" }).click();

    // The message names the category, which is what tells the user what to
    // change — a generic "invalid URL" would not.
    const error = page.getByRole("alert").first();
    await expect(error).toBeVisible();
    await expect(error).toContainText(/link-local|metadata/i);
  });

  test("an endpoint can be paused and resumed", async ({ page }) => {
    await register(page, uniqueAccount("pause"));

    await page.goto("/dashboard/endpoints");
    await page.getByRole("button", { name: "Add your first endpoint" }).click();
    await page.getByLabel("Name").fill("Pausable");
    await page.getByLabel("Destination URL").fill("https://example.com/hook");
    await page.getByRole("button", { name: "Create endpoint" }).click();
    await page.getByRole("button", { name: "I have stored it" }).click();

    await page.getByRole("button", { name: "Pause" }).first().click();
    await expect(page.getByText("Delivery paused")).toBeVisible();
    await expect(page.getByText("Paused", { exact: true }).first()).toBeVisible();

    await page.getByRole("button", { name: "Resume" }).first().click();
    await expect(page.getByText("Delivery resumed")).toBeVisible();
  });
});
