// Web developers: runs unit tests for cover games, accounts, and routing.
import { defineConfig } from "vitest/config";
export default defineConfig({
  test: {
    include: ["src/**/*.test.{ts,tsx}", "server/**/*.test.mjs"],
    environment: "node",
    reporters: ["default", "junit"],
    outputFile: { junit: "../build/reports/web-unit-tests.xml" },
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}", "server/**/*.{js,mjs}"],
      exclude: ["src/test-utils/**", "**/*.test.{ts,tsx}"],
      reporter: ["text", "lcov", "html", "json"],
    },
  },
});
