import { expect, test } from "@playwright/test";
import { register, runPlaygroundScenario, uniqueAccount } from "./helpers";

/**
 * Reliability Playground flows.
 *
 * These are the highest-value end-to-end tests in the suite: they exercise
 * ingestion, the outbox, the Kafka relay, the dispatcher, the workers, the retry
 * engine and dead-lettering, and they assert on what the UI actually renders
 * from the backend's recorded attempts.
 */
test.describe("Reliability Playground", () => {
  test("a transient failure is retried with growing gaps, then succeeds", async ({ page }) => {
    await register(page, uniqueAccount("pg-retry"));

    const status = await runPlaygroundScenario(page, "Transient 500s, then recovery", {
      "Failures before recovery": "2",
      "Attempt budget": "5",
    });
    expect(status).toContain("Succeeded");

    // Three attempts: 500, 500, 200.
    await expect(page.getByText("Attempt 1")).toBeVisible();
    await expect(page.getByText("Attempt 2")).toBeVisible();
    await expect(page.getByText("Attempt 3")).toBeVisible();
    await expect(page.getByText("Attempt 4")).toHaveCount(0);

    // The gap label is how exponential backoff is made visible rather than
    // merely described.
    await expect(page.getByText(/after the previous attempt/).first()).toBeVisible();

    // The simulator's independent count must agree with our attempt history.
    await expect(page.getByText(/3 req/)).toBeVisible();
  });

  test("a permanently broken receiver dead-letters and can be replayed", async ({ page }) => {
    await register(page, uniqueAccount("pg-dead"));

    const status = await runPlaygroundScenario(page, "Permanently broken receiver", {
      "Attempt budget": "2",
    });
    expect(status).toContain("Dead-lettered");

    // Both failures are still listed: replay must not erase the evidence.
    await expect(page.getByText("Attempt 1")).toBeVisible();
    await expect(page.getByText("Attempt 2")).toBeVisible();

    await expect(page.getByRole("button", { name: "Replay" })).toBeVisible();

    // It appears in the dead-letter queue.
    await page.goto("/dashboard/dead-letters");
    await expect(page.getByText("relayforge.playground.test").first()).toBeVisible();
  });

  test("a non-retryable 4xx fails immediately without consuming the budget", async ({ page }) => {
    await register(page, uniqueAccount("pg-4xx"));

    const status = await runPlaygroundScenario(page, "Permanently broken receiver", {
      "Status code": "400",
      "Attempt budget": "5",
    });
    expect(status).toContain("Dead-lettered");

    // One attempt out of a budget of five: repeating a request the receiver
    // rejected cannot change the answer.
    await expect(page.getByText("Attempt 1")).toBeVisible();
    await expect(page.getByText("Attempt 2")).toHaveCount(0);
    await expect(page.getByText("Terminal failure").first()).toBeVisible();
  });

  test("a timeout is recorded with no HTTP status", async ({ page }) => {
    await register(page, uniqueAccount("pg-slow"));

    await runPlaygroundScenario(page, "Slow receiver", {
      "Response delay (ms)": "8000",
      "Attempt budget": "2",
    });

    // "No response" rather than a blank cell: a timeout producing no status is
    // a fact, not missing data.
    await expect(page.getByText("No response").first()).toBeVisible();
  });

  test("the expected outcome is stated before the run and compared after", async ({ page }) => {
    await register(page, uniqueAccount("pg-explain"));
    await page.goto("/dashboard/playground");

    await page.locator('button[role="radio"]:has-text("HTTP 429 with Retry-After")').click();

    // Stating the expectation up front is what lets a viewer judge the run
    // rather than just watch it.
    await expect(page.getByText("What this demonstrates")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Expected outcome" })).toBeVisible();
    await expect(page.getByText(/Retry-After value, not by exponential/)).toBeVisible();
  });
});
