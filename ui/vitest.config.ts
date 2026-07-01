import { defineConfig, configDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    // demo/ holds the Playwright tour (*.spec.ts) — it is not a vitest unit test.
    exclude: [...configDefaults.exclude, "demo/**"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "lcov"],
      thresholds: { lines: 70, functions: 70, statements: 70, branches: 60 },
      exclude: ["src/main.tsx", "src/test/**", "**/*.config.{ts,js}"],
    },
  },
});
