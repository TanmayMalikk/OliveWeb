// Web developers: runs unit tests for cover games, accounts, and routing.
import { defineConfig } from "vitest/config";
export default defineConfig({
  test: {
    include: ["src/**/*.test.ts", "server/**/*.test.mjs"],
    environment: "node",
  },
});
