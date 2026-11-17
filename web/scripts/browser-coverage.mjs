import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import istanbul from "istanbul-lib-coverage";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const directory = path.resolve(root, "../build/reports/web-browser-coverage");
const workspace = path.resolve(root, "..");
if (!directory.startsWith(workspace + path.sep)) throw new Error("Coverage directory must remain inside the workspace");
if (!process.argv.includes("--merge-only")) {
  fs.rmSync(directory, {recursive: true, force: true});
  const result = spawnSync(process.execPath, [path.join(root, "node_modules/@playwright/test/cli.js"), "test", ...process.argv.slice(2)], {
    cwd: root, stdio: "inherit", env: {...process.env, OLIVE_WEB_COVERAGE: "1"},
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
const unitReport = path.join(root, "coverage/coverage-final.json");
if (!fs.existsSync(unitReport)) throw new Error("Run npm run test:coverage before browser coverage");
const coverage = istanbul.createCoverageMap(JSON.parse(fs.readFileSync(unitReport, "utf8")));
const browserReports = fs.readdirSync(directory).filter(file => file.endsWith(".json"));
if (!browserReports.length) throw new Error("Browser tests did not produce coverage");
for (const file of browserReports) {
  const browser = istanbul.createCoverageMap(JSON.parse(fs.readFileSync(path.join(directory, file), "utf8")));
  for (const source of browser.files()) {
    if (!source.startsWith(path.join(root, "src") + path.sep)) throw new Error(`Unexpected coverage source: ${source}`);
  }
  coverage.merge(browser);
}
// Preserve the executable-line denominator from the unit baseline. V8 source maps
// also contain comments/type-only lines, which must not inflate the reported coverage.
const baseline = fs.readFileSync(path.join(root, "coverage/lcov.info"), "utf8");
const details = [];
const combined = baseline.split("end_of_record").map(block => {
  const lines = block.trim().split(/\r?\n/);
  const source = lines.find(line => line.startsWith("SF:"))?.slice(3);
  if (!source) return "";
  const filename = path.resolve(root, source);
  const browserLines = coverage.files().includes(filename) ? coverage.fileCoverageFor(filename).getLineCoverage() : {};
  let total = 0, hit = 0;
  const output = lines.map(line => {
    if (!line.startsWith("DA:")) return line;
    const [number, count, ...rest] = line.slice(3).split(",");
    const executions = Math.max(Number(count), browserLines[number] ?? 0);
    total++; if (executions > 0) hit++;
    return `DA:${number},${executions}${rest.length ? `,${rest.join(",")}` : ""}`;
  }).map(line => line.startsWith("LH:") ? `LH:${hit}` : line);
  details.push({source, hit, total});
  return output.join("\n") + "\nend_of_record\n";
}).join("");
const output = path.join(root, "coverage/combined");
fs.mkdirSync(output, {recursive: true});
fs.writeFileSync(path.join(output, "lcov.info"), combined);
const hit = details.reduce((sum, row) => sum + row.hit, 0);
const total = details.reduce((sum, row) => sum + row.total, 0);
const percent = (100 * hit / total).toFixed(2);
const escape = value => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
fs.writeFileSync(path.join(output, "index.html"), `<!doctype html><html lang="en"><meta charset="utf-8"><title>Combined web line coverage</title><style>body{font:16px system-ui;margin:2rem}td,th{text-align:left;padding:.5rem;border-bottom:1px solid #ccc}</style><h1>Combined web line coverage: ${percent}%</h1><p>${hit}/${total} executable lines. Unit and browser test hits are combined against the unchanged unit baseline. Branch and function counters in LCOV remain unit-only.</p><table><tr><th>Source</th><th>Covered</th><th>Total</th><th>Coverage</th></tr>${details.map(row => `<tr><td>${escape(row.source)}</td><td>${row.hit}</td><td>${row.total}</td><td>${row.total ? (100 * row.hit / row.total).toFixed(2) : "100.00"}%</td></tr>`).join("")}</table></html>`);
console.log(`Combined web line coverage: ${percent}% (${hit}/${total}). Branch/function counters remain unit-only.`);
console.log("Report: web/coverage/combined/index.html");
