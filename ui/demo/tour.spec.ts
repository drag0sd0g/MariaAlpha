import { test, expect, type Page } from "@playwright/test";

/**
 * MariaAlpha README demo tour.
 *
 * A single narrated walkthrough of the UI, recorded to video by Playwright and converted to
 * an animated GIF by scripts/make-demo-gif.sh. Runs against the live docker-compose stack
 * (default profile = simulated CSV replay) AFTER scripts/seed-demo-data.sh has generated
 * positions, fills, and analytics, so the Dashboard and Analytics pages are populated.
 *
 * Rules that keep the recording robust:
 *  - Every wait is a web-first assertion on a real selector (the data-testids / aria-labels
 *    the app already ships), never a fixed sleep keyed to timing.
 *  - `settle()` pauses are purely cosmetic — they let a viewer's eye rest between scenes.
 *  - Streaming values (prices, P&L) are never asserted by exact value, only by presence, so
 *    tick cadence and fill latency can't flake the tour.
 */

// Cosmetic pause so each scene is legible in the GIF. Not used for synchronization.
const settle = (page: Page, ms = 900) => page.waitForTimeout(ms);

test("MariaAlpha guided tour", async ({ page }) => {
  // ── Scene 1 — Dashboard: live positions, P&L, exposure ──────────────────────────────
  // The headline scene. Seeded positions are already open, so the summary cards show real
  // exposure and the "Daily P&L" line builds live as marks update — hence the longer dwell.
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
  // "Total P&L" appears as a summary card and as the positions-table column header — match
  // the first so the assertion is unambiguous.
  await expect(page.getByText("Total P&L").first()).toBeVisible();
  await settle(page, 3000);
  // Feature the live P&L chart, then let it accumulate a visible line (samples once a second).
  await page
    .getByText("Daily P&L")
    .scrollIntoViewIfNeeded()
    .catch(() => undefined);
  await settle(page, 7000);

  // ── Scene 2 — Strategy Control: bind AAPL → VWAP (hot-swap at runtime) ───────────────
  await page.getByRole("link", { name: "Strategies" }).click();
  await expect(page.getByRole("heading", { name: "Strategy Control" })).toBeVisible();
  await expect(page.getByTestId("row-AAPL")).toBeVisible();

  await page.getByTestId("bind-symbol").fill("AAPL");
  await page.getByTestId("bind-strategy").selectOption("VWAP");
  await settle(page, 500);
  await page.getByTestId("bind-submit").click();
  // The AAPL row reflecting its new active strategy is the persistent, deterministic signal
  // the bind took (more robust than the transient confirmation banner).
  await expect(page.getByTestId("row-AAPL")).toContainText("VWAP", { timeout: 10_000 });
  // The ML signal + regime columns refresh from the ML Signal Service every 5 s.
  await settle(page, 2200);

  // ── Scene 3 — Order Entry: submit a manual order; resting + filled orders are visible ──
  await page.getByRole("link", { name: "Orders" }).click();
  await expect(page.getByRole("heading", { name: "Order Entry" })).toBeVisible();

  // A LIMIT BUY priced well below the market rests as a working order — a clean, deterministic
  // beat (a MARKET order would fill and vanish into history before the eye can follow it).
  await page.getByLabel("Symbol", { exact: true }).fill("MSFT");
  await page.getByLabel("Side", { exact: true }).selectOption("BUY");
  await page.getByLabel("Order Type", { exact: true }).selectOption("LIMIT");
  await page.getByLabel("Quantity", { exact: true }).fill("25");
  await page.getByLabel("Limit Price", { exact: true }).fill("100");
  await settle(page, 700);
  await page.getByRole("button", { name: "Submit" }).click();

  // Seeded + just-submitted orders populate Active Orders; seeded MARKET orders show in fills.
  await expect(page.getByRole("heading", { name: "Active Orders" })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByText("MSFT").first()).toBeVisible();
  await page
    .getByRole("heading", { name: "Recent Fills" })
    .scrollIntoViewIfNeeded()
    .catch(() => undefined);
  await settle(page, 2200);

  // ── Scene 4 — Analytics: TCA, PnL attribution, flow toxicity, axes ──────────────────
  await page.getByRole("link", { name: "Analytics" }).click();
  await expect(page.getByRole("heading", { name: "Analytics" })).toBeVisible();
  await expect(page.getByTestId("tca-table")).toBeVisible();
  await settle(page, 2500);

  await page.getByTestId("tab-pnl").click();
  await expect(page.getByTestId("pnl-chart")).toBeVisible();
  await settle(page, 2500);

  await page.getByTestId("tab-toxicity").click();
  await expect(page.getByTestId("toxicity-section")).toBeVisible();
  await settle(page, 2500);

  await page.getByTestId("tab-axes").click();
  await expect(page.getByTestId("axes-section")).toBeVisible();
  await settle(page, 2500);
});
