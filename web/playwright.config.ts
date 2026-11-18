// Web developers: runs browser tests for public covers and safe-app flows.
import { defineConfig } from "@playwright/test";
const production = process.env.OLIVE_TEST_PUBLIC_ADS === "1";
const baseURL = production ? "http://127.0.0.1:5190" : "http://127.0.0.1:5173";
export default defineConfig({
  testDir: "./tests",
  ...(production ? { testMatch: "public-ads.spec.ts" } : { testIgnore: "public-ads.spec.ts" }),
  timeout: 60000,
  expect: { timeout: 10000 },
  fullyParallel: false,
  workers: 1,
  reporter: [
    ["list"],
    ["junit", { outputFile: "../build/reports/web-browser-tests.xml" }],
  ],
  use: {
    baseURL,
    channel: "chrome",
    headless: true,
    trace: "retain-on-failure",
  },
  webServer: {
    command: production ? "npm run build && npm run start" : "npm run dev",
    url: baseURL + "/api/health",
    reuseExistingServer: !production,
    timeout: 30000,
    env: {
      ...process.env,
      VITE_FIREBASE_API_KEY: "",
      VITE_FIREBASE_PROJECT_ID: "",
      VITE_FIREBASE_STORAGE_BUCKET: "",
      VITE_FIREBASE_APP_ID: "",
      ...(production ? { PORT: "5190", HOST: "127.0.0.1" } : {}),
    },
  },
});
