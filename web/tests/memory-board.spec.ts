// Tests the olive memory cover game's board and controls.
import { expect, test } from "@playwright/test";

// Inputs: the values in this case. Expected: every board stays inside its play area and controls remain reachable without horizontal page scrolling.
test("all board sizes stay usable without horizontal scrolling", async ({
  page,
}) => {
  for (const [width, height] of [
    [1024, 600],
    [1366, 768],
    [1920, 1080],
  ]) {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    for (let size = 5; size <= 8; size++) {
      await page
        .getByLabel("Board size", { exact: true })
        .selectOption(String(size));
      await expect(page.locator(".memory-card")).toHaveCount(size * size);
      await expect(page.locator(".memory-card button")).toHaveCount(0);
      await expect(page.locator("button.memory-card")).toHaveCount(
        size * size - (size % 2),
      );
      await expect(page.locator(".memory-free")).toHaveCount(size % 2);
      const layout = await page.evaluate(() => {
        const board = document
          .querySelector(".memory-grid")!
          .getBoundingClientRect();
        const cards = [...document.querySelectorAll(".memory-card")].map(
          (card) => card.getBoundingClientRect(),
        );
        const bottom = document
          .querySelector(".game-bottom")!
          .getBoundingClientRect();
        return {
          pageWidthFits:
            document.documentElement.scrollWidth <= innerWidth,
          inside: cards.every(
            (card) =>
              card.left >= board.left - 1 &&
              card.right <= board.right + 1 &&
              card.top >= board.top - 1 &&
              card.bottom <= board.bottom + 1,
          ),
          controls: bottom.bottom <= document.documentElement.scrollHeight + 1,
          columns: new Set(cards.map((card) => Math.round(card.left))).size,
          rows: new Set(cards.map((card) => Math.round(card.top))).size,
        };
      });
      expect(layout, `${size}x${size} at ${width}x${height}`).toEqual({
        pageWidthFits: true,
        inside: true,
        controls: true,
        columns: size,
        rows: size,
      });
    }
    if (width === 1366)
      await page.screenshot({ path: "test-results/memory-8x8-laptop.png" });
  }
});

// Inputs: the values in this case. Expected: changing size cancels pending flips, and odd boards can be completed.
test("changing size cancels pending flips, and odd boards can be completed", async ({
  page,
}) => {
  await page.clock.install();
  await page.goto("/");
  await page.getByLabel("Board size", { exact: true }).selectOption("8");
  await page.getByRole("button", { name: "Turn card 1", exact: true }).click();
  await page.getByRole("button", { name: "Turn card 2", exact: true }).click();
  await page.getByLabel("Board size", { exact: true }).selectOption("5");
  await page.clock.runFor(1100);
  await expect(page.locator(".memory-card.turned")).toHaveCount(0);
  await expect(page.locator(".memory-card.matched")).toHaveCount(0);
  const pairs = new Map<string, number[]>();
  const indices = Array.from({ length: 25 }, (_, i) => i + 1).filter(
    (index) => index !== 13,
  );
  for (let i = 0; i < indices.length; i += 2) {
    for (const index of indices.slice(i, i + 2)) {
      const card = page.getByRole("button", {
        name: `Turn card ${index}`,
        exact: true,
      });
      await card.click();
      const label = await page
        .locator(".memory-card")
        .nth(index - 1)
        .getAttribute("aria-label");
      const pair = label!.match(/pair (\d+)/)![1];
      pairs.set(pair, [...(pairs.get(pair) ?? []), index]);
    }
    await page.clock.runFor(1100);
  }
  for (const indices of pairs.values()) {
    if (
      (await page
        .locator(".memory-card")
        .nth(indices[0] - 1)
        .getAttribute("class"))!.includes("matched")
    )
      continue;
    for (const index of indices)
      await page
        .getByRole("button", { name: `Turn card ${index}`, exact: true })
        .click();
    await page.clock.runFor(400);
  }
  await expect(page.locator(".memory-card.matched")).toHaveCount(24);
  await expect(page.getByRole("status")).toContainText(
    "A lovely little finish",
  );
  await expect(page.locator(".game-score > span").last()).not.toContainText(
    "—",
  );
  await page.getByRole("button", { name: "New round", exact: true }).click();
  await expect(page.locator(".memory-card.matched")).toHaveCount(0);
  await expect(page.locator(".memory-card")).toHaveCount(25);
});
