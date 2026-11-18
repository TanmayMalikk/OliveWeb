import { test as base } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";
import v8ToIstanbul from "v8-to-istanbul";
import istanbul from "istanbul-lib-coverage";
export { expect, type Page } from "@playwright/test";

// Capture the real browser flows, including navigation and dialogs, only in coverage runs.
export const test = base.extend<{ browserCoverage: void }>({
  browserCoverage: [async ({ page }, use, info) => {
    if (process.env.OLIVE_WEB_COVERAGE !== "1") { await use(); return; }
    await page.coverage.startJSCoverage({ resetOnNavigation: false });
    try { await use(); } finally {
      const entries = await page.coverage.stopJSCoverage();
      const coverage = istanbul.createCoverageMap({});
      const root = process.cwd();
      for (const entry of entries) {
        const url = new URL(entry.url || "about:blank");
        if (!url.pathname.startsWith("/src/") || !entry.source) continue;
        const sourcePath = path.resolve(root, `.${decodeURIComponent(url.pathname)}`);
        if (!sourcePath.startsWith(path.resolve(root, "src") + path.sep)) continue;
        const converter = v8ToIstanbul(sourcePath, 0, { source: entry.source });
        await converter.load();
        converter.applyCoverage(entry.functions);
        coverage.merge(converter.toIstanbul());
        converter.destroy();
      }
      const directory = path.resolve(root, "../build/reports/web-browser-coverage");
      await fs.mkdir(directory, {recursive: true});
      const snapshot = path.join(directory, `${info.testId}-${info.retry}`);
      await fs.writeFile(`${snapshot}.json`, JSON.stringify(coverage));
      await fs.writeFile(`${snapshot}.meta`, JSON.stringify({
        file: path.relative(info.config.rootDir, info.file).split(path.sep).join("/"),
        title: info.titlePath.slice(1).join(" › "),
        retry: info.retry,
        status: info.status,
      }));
    }
  }, { auto: true }],
});
