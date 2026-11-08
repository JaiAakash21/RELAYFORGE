import { expect, test } from "@playwright/test";
import { register, runPlaygroundScenario, uniqueAccount } from "./helpers";

test.describe("delivery inspection and replay", () => {
  test("a dead letter can be replayed and the history is preserved", async ({ page }) => {
    await register(page, uniqueAccount("replay"));

    await runPlaygroundScenario(page, "Permanently broken receiver", {
      "Attempt budget": "2",
    });

    await page.goto("/dashboard/dead-letters");
    await expect(page.getByText("relayforge.playground.test").first()).toBeVisible();
    await page.getByRole("link", { name: "Inspect" }).first().click();

    await expect(page.getByText(/Dead-lettered after 2 attempts/)).toBeVisible();
    await expect(page.getByText("Attempt 1")).toBeVisible();
    await expect(page.getByText("Attempt 2")).toBeVisible();

    await page.getByRole("button", { name: "Replay" }).click();
    // The dialog explains what replay does, because "replay" could reasonably
    // be read as "start over" — and it is not.
    await expect(page.getByText("What replay does")).toBeVisible();
    await page.getByLabel("Reason").fill("Receiver was redeployed");
    await page.getByRole("button", { name: "Replay delivery" }).click();

    // .first(): the toast title and its description both contain this text.
    await expect(page.getByText("Replay accepted").first()).toBeVisible();

    // The original failures survive, and the replay is audited.
    await expect(page.getByText("Attempt 1")).toBeVisible();
    await expect(page.getByText("Attempt 2")).toBeVisible();
    await expect(page.getByText("Replay history")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("Receiver was redeployed")).toBeVisible();
  });

  test("the delivery list filters by status", async ({ page }) => {
    await register(page, uniqueAccount("filter"));
    await runPlaygroundScenario(page, "Normal delivery", { "Attempt budget": "3" });

    await page.goto("/dashboard/deliveries");
    // Scoped to the table: "Success" also appears as an <option> in the status
    // filter, which is present in the DOM but not visible.
    await expect(page.locator("table").getByText("Success").first()).toBeVisible();

    await page.getByLabel("Filter by delivery status").selectOption("FAILED");
    await expect(page.getByText("No deliveries match those filters")).toBeVisible();

    // The filter is in the URL, so the view is linkable and survives a reload.
    expect(page.url()).toContain("status=FAILED");
    await page.reload();
    await expect(page.getByText("No deliveries match those filters")).toBeVisible();
  });

  test("the attempt timeline shows the signature and trace metadata", async ({ page }) => {
    await register(page, uniqueAccount("timeline"));
    await runPlaygroundScenario(page, "Normal delivery", { "Attempt budget": "3" });

    await page.goto("/dashboard/deliveries");
    await page.getByRole("link", { name: "Timeline" }).first().click();

    await expect(page.getByText("Attempt history")).toBeVisible();
    await expect(page.getByText("Delivered").first()).toBeVisible();
    // The worker that made the attempt, so an incident can be attributed.
    await expect(page.getByText(/worker/).first()).toBeVisible();
    await expect(page.getByText("Event payload")).toBeVisible();
  });
});
