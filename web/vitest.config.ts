// Contains the vitest.config code for this part of Olive.
import { defineConfig } from "vitest/config";
export default defineConfig({
  test: {
    include: ["src/**/*.test.ts", "server/**/*.test.mjs"],
    environment: "node",
  },
});
