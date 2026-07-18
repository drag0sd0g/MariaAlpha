import { test, expect, type Page } from "@playwright/test";

/**
 * MariaAlpha README demo tour.
 *
 * A single narrated walkthrough of the UI, recorded to video by Playwright and converted to
 * an animated GIF by scripts/make-demo-gif.sh. Runs against the live docker-compose stack
 * with the demo overlay (`just demo-up`: 90-minute drifting replay tape + fast ML bars)
 * AFTER scripts/seed-demo-data.sh has generated positions, algo orders, fills, and
 * analytics — the seed script polls until the ML regime models and TCA rows are warm, so
 * every scene lands on populated panels.
 *
 * Scene order tells the story front-to-back and saves the Dashboard for last: the Daily
 * P&L chart samples client-side once a second while mounted, so the finale dwell is what
 * draws the line — and by then the drifting tape has moved the marks away from the entry
 * prices, so the line actually slopes.
 *
 * Rules that keep the recording robust:
 *  - Every wait is a web-first assertion on a real selector (the data-testids / aria-labels
 *    the app already ships), never a fixed sleep keyed to timing.
 *  - `settle()` pauses are purely cosmetic — they let a viewer's eye rest between scenes.
 *  - Streaming values (prices, P&L) are never asserted by exact value, only by presence, so
 *    tick cadence and fill latency can't flake the tour. The two content assertions
 *    (row-TSLA shows POV, TCA shows TWAP) are on states the seed script guarantees.
 */

// Cosmetic pause so each scene is legible in the GIF. Not used for synchronization.
const settle = (page: Page, ms = 900) => page.waitForTimeout(ms);

test("MariaAlpha guided tour", async ({ page }) => {
  // ── Scene 1 — Strategy Control: live ML columns + hot-bind a new symbol ──────────────
  // Seeded bindings (AAPL→VWAP, MSFT→TWAP, NVDA→MOMENTUM) are already rows with warmed ML
  // signal + regime columns. Binding TSLA→POV makes a brand-new row appear — a visible
  // state change, unlike re-binding an already-bound symbol.
  await page.goto("/strategies");
  await expect(page.getByRole("heading", { name: "Strategy Control" })).toBeVisible();
  await expect(page.getByTestId("row-AAPL")).toBeVisible();
  await expect(page.getByTestId("row-NVDA")).toBeVisible();
  await settle(page, 2500);

  await page.getByTestId("bind-symbol").fill("TSLA");
  await page.getByTestId("bind-strategy").selectOption("POV");
  await settle(page, 400);
  await page.getByTestId("bind-submit").click();
  // The new TSLA row showing its active strategy is the persistent, deterministic signal
  // the bind took (more robust than the transient confirmation banner).
  await expect(page.getByTestId("row-TSLA")).toContainText("POV", { timeout: 10_000 });
  await settle(page, 1800);

  // ── Scene 2 — Order Entry: submit a manual order; algo + manual fills are visible ─────
  await page.getByRole("link", { name: "Orders" }).click();
  await expect(page.getByRole("heading", { name: "Order Entry" })).toBeVisible();

  // A LIMIT BUY priced well below the market rests as a working order — a clean,
  // deterministic beat (a MARKET order would fill and vanish into history before the eye
  // can follow it).
  await page.getByLabel("Symbol", { exact: true }).fill("MSFT");
  await page.getByLabel("Side", { exact: true }).selectOption("BUY");
  await page.getByLabel("Order Type", { exact: true }).selectOption("LIMIT");
  await page.getByLabel("Quantity", { exact: true }).fill("25");
  await page.getByLabel("Limit Price", { exact: true }).fill("100");
  await settle(page, 600);
  await page.getByRole("button", { name: "Submit" }).click();

  // Seeded + just-submitted orders populate Active Orders; seeded MARKET orders and the
  // TWAP/VWAP algo slices show in Recent Fills.
  await expect(page.getByRole("heading", { name: "Active Orders" })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByText("MSFT").first()).toBeVisible();
  await page
    .getByRole("heading", { name: "Recent Fills" })
    .scrollIntoViewIfNeeded()
    .catch(() => undefined);
  await settle(page, 2000);

  // ── Scene 3 — Analytics: TCA, PnL attribution, flow toxicity, axes ──────────────────
  await page.getByRole("link", { name: "Analytics" }).click();
  await expect(page.getByRole("heading", { name: "Analytics" })).toBeVisible();
  await expect(page.getByTestId("tca-table")).toBeVisible();
  // The seed script waited for a strategy-labelled algo fill to reach post-trade, so TCA
  // shows per-strategy rows (TWAP/VWAP benchmark + slippage), not just MANUAL flow.
  await expect(page.getByTestId("tca-table")).toContainText("TWAP", { timeout: 10_000 });
  await settle(page, 2200);

  await page.getByTestId("tab-pnl").click();
  await expect(page.getByTestId("pnl-chart")).toBeVisible();
  await settle(page, 2200);

  await page.getByTestId("tab-toxicity").click();
  await expect(page.getByTestId("toxicity-section")).toBeVisible();
  await settle(page, 2200);

  await page.getByTestId("tab-axes").click();
  await expect(page.getByTestId("axes-section")).toBeVisible();
  await settle(page, 2200);

  // ── Scene 4 — Options: Black-Scholes fair value + Greeks (deterministic showpiece) ───
  await page.getByRole("link", { name: "Options" }).click();
  await expect(page.getByRole("heading", { name: "Options Pricing" })).toBeVisible();
  await page.getByTestId("opt-symbol").fill("AAPL");
  await page.getByTestId("opt-spot").fill("185");
  await page.getByTestId("opt-strike").fill("190");
  await page.getByTestId("opt-vol").fill("0.28");
  await settle(page, 400);
  await page.getByTestId("opt-price-btn").click();
  await expect(page.getByTestId("opt-price")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("opt-greeks")).toBeVisible();
  await settle(page, 2500);

  // ── Scene 5 — Dashboard finale: live positions, P&L, exposure ────────────────────────
  // The headline scene, saved for last: marks have drifted away from the seeded entry
  // prices, so the summary cards and per-position P&L are non-zero, and the Daily P&L
  // line visibly builds during the dwell (the chart samples once a second while mounted).
  await page.getByRole("link", { name: "Dashboard" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard", level: 1 })).toBeVisible();
  // "Total P&L" appears as a summary card and as the positions-table column header — match
  // the first so the assertion is unambiguous.
  await expect(page.getByText("Total P&L").first()).toBeVisible();
  await settle(page, 3000);
  // Feature the live P&L chart and let it accumulate a visible line.
  await page
    .getByText("Daily P&L")
    .scrollIntoViewIfNeeded()
    .catch(() => undefined);
  await settle(page, 11_000);
});
