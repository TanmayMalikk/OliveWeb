// Web deployers: blocks common private credential formats from entering the browser bundle.
import { readdir, readFile } from "node:fs/promises";
import { extname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const ignored = new Set([
  "dist",
  "node_modules",
  "playwright-report",
  "test-results",
  ".vercel",
]);
const readableExtensions = new Set([
  ".css",
  ".html",
  ".js",
  ".json",
  ".mjs",
  ".ts",
  ".tsx",
  ".xml",
]);
const privatePatterns = [
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /\b(?:sk_live|sk_test|ghp_|github_pat_|xox[baprs]-|AKIA)[A-Za-z0-9_-]{12,}/,
  /"private_key"\s*:\s*"(?!REPLACE|EXAMPLE)[^"]+"/i,
  /(?:CLIENT_SECRET|SERVICE_ACCOUNT_KEY|PRIVATE_KEY)\s*=\s*(?!$|REPLACE|EXAMPLE)[^\s#]+/i,
];

async function files(directory) {
  const results = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (ignored.has(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) results.push(...(await files(path)));
    else if (readableExtensions.has(extname(entry.name)) || entry.name.startsWith(".env")) {
      results.push(path);
    }
  }
  return results;
}

const findings = [];
for (const path of await files(root)) {
  const text = await readFile(path, "utf8");
  if (privatePatterns.some((pattern) => pattern.test(text))) {
    findings.push(relative(root, path));
  }
}

if (findings.length) {
  console.error(`Private credential pattern found in: ${findings.join(", ")}`);
  process.exit(1);
}
console.log("Public web source contains no recognized private credential formats.");
