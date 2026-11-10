// Web developers: runs unit tests for cover games, accounts, and routing.
import { defineConfig } from "vitest/config";
export default defineConfig({
  test: {
    include: ["src/**/*.test.ts", "server/**/*.test.mjs"],
    environment: "node",
    reporters: ["default", "junit"],
    outputFile: { junit: "../build/reports/web-unit-tests.xml" },
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}", "server/**/*.{js,mjs}"],
      reporter: ["text", "lcov", "html"],
    },
  },
});
