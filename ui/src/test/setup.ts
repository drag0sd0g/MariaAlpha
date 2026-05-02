import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./mockServer";

beforeAll(() => { server.listen({ onUnhandledRequest: "error" }); });
afterEach(() => { server.resetHandlers(); });
afterAll(() => { server.close(); });

// Stub VITE env for tests
Object.assign(import.meta.env, {
  VITE_MARIAALPHA_API_KEY: "test-key",
  VITE_API_BASE_URL: "",
});

// Recharts' ResponsiveContainer uses ResizeObserver which is absent in jsdom.
global.ResizeObserver = class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
};
