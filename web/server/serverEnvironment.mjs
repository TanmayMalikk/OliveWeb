// Web visitors: loads server-only settings from the local environment files.
import { existsSync } from "node:fs";
import { resolve } from "node:path";

// Server-only configuration stays out of the browser's VITE_ variables.
for (const filename of ["../.env.local", "../.env"]) {
  const path = resolve(import.meta.dirname, filename);
  if (typeof process.loadEnvFile === "function" && existsSync(path)) {
    process.loadEnvFile(path);
  }
}
