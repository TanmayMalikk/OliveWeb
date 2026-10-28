import { existsSync } from "node:fs";
import { resolve } from "node:path";

// Server-only configuration stays out of the browser's VITE_ variables.
const path = resolve(import.meta.dirname, "../.env.local");
if (existsSync(path)) process.loadEnvFile(path);
