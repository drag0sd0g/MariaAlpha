import { defineConfig } from "@playwright/test";

/**
 * Playwright config dedicated to the README demo recording (ui/demo/tour.spec.ts).
 *
 * This is NOT the component-test config (those run under vitest). It drives the real,
 * deployed UI — the nginx bundle served by docker-compose on :5173 — so the recording
 * reflects what users actually see. Bring the stack up first (`just run`) or use the
 * hermetic `just demo-full`.
 *
 * Determinism over speed: a single chromium worker, no retries, deliberate slowMo so the
 * cursor/typing is legible in the GIF. Each tour step waits on a selector (web-first
 * assertion), never a fixed sleep, so variable fill latency / tick cadence can't desync it.
 */
export default defineConfig({
  testDir: "./demo",
  outputDir: "./demo/.output",
  timeout: 240_000,
  retries: 0,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: process.env.DEMO_BASE_URL ?? "http://localhost:5173",
    actionTimeout: 15_000,
    viewport: { width: 1440, height: 900 },
    video: { mode: "on", size: { width: 1440, height: 900 } },
    trace: "retain-on-failure",
    launchOptions: {
      // Slow each action down so the recording reads as a guided walkthrough, not a blur.
      slowMo: 450,
    },
  },
  projects: [{ name: "chromium-demo", use: { browserName: "chromium" } }],
});
