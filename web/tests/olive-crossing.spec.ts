// Tests lane movement and obstacles in the olive crossing cover game.
import { expect, test } from "./coverageFixture";
import AxeBuilder from "@axe-core/playwright";

test("crossing keeps the selected character and can restart after the hawk", async ({ page }) => {
  await page.clock.install();
  await page.goto("/game");
  await page.getByRole("button", { name: "Olive Crossing", exact: true }).click();
  const character = page.getByRole("button", { name: "Select Sproutling", exact: true });
  await character.click();
  await expect(character).toHaveAttribute("aria-pressed", "true");
  await expect.poll(() => page.evaluate(async () => {
    const path = "/src/services/CoverStateStore.ts";
    const { readCover } = await import(path);
    return readCover("olive-crossing-character", "");
  })).toBe("sproutling");
  await page.reload();
  await page.getByRole("button", { name: "Olive Crossing", exact: true }).click();
  await expect(character).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Start Olive Crossing" }).click();
  const board = page.getByRole("group", { name: /Olive Crossing board/ });
  await board.press("w");
  await expect(page.getByRole("status")).toContainText("1 lanes cleared");
  await board.press("Enter");
  await expect(page.getByRole("status")).toContainText("Paused");
  await page.getByRole("button", { name: "Resume Olive Crossing" }).click();
  await expect(page.getByRole("status")).toContainText("1 lanes cleared");
  for (let seconds = 0; seconds < 13; seconds++) await page.clock.runFor(1000);
  await expect(page.getByText("Swooped by the orchard hawk", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Play Olive Crossing again" }).click();
  await expect(page.getByRole("status")).toContainText("0 lanes cleared");
});

// Inputs: the values in this case. Expected: Olive Crossing supports keyboard play, pausing and restarting.
test("Olive Crossing supports keyboard play, pausing and restarting", async ({
  page,
}) => {
  await page.goto("/game");
  await page
    .getByRole("button", { name: "Olive Crossing", exact: true })
    .click();
  const stageWidth = await page
    .locator(".crossing-stage")
    .evaluate((stage) => ({
      stage: stage.getBoundingClientRect().width,
      arena: stage.parentElement!.getBoundingClientRect().width,
    }));
  expect(Math.abs(stageWidth.stage - stageWidth.arena)).toBeLessThan(2);
  const startButton = page.getByRole("button", {
    name: "Start Olive Crossing",
  });
  const startSize = await startButton.evaluate((button) => ({
    width: button.getBoundingClientRect().width,
    height: button.getBoundingClientRect().height,
    iconWidth: button.querySelector("svg")!.getBoundingClientRect().width,
  }));
  expect(startSize.width).toBeLessThan(180);
  expect(startSize.height).toBeLessThan(60);
  expect(startSize.iconWidth).toBeLessThanOrEqual(18);
  await startButton.click();

  const board = page.getByRole("group", { name: /Olive Crossing board/ });
  await expect(board).toBeFocused();
  await page.keyboard.press("ArrowUp");
  await expect(page.getByRole("status")).toContainText("1 lanes cleared");
  await expect(page.locator(".crossing-player-hop")).toHaveCSS(
    "animation-name",
    "crossing-hop",
  );
  await page.keyboard.press("ArrowLeft");

  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Paused. Take your time.");
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);

  await page.locator(".game-bottom").getByRole("button", { name: "New crossing", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("0 lanes cleared");
});

test("Olive Crossing shows the complete world width without side panning on PC", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await page.goto("/game");
  await page.getByRole("button", { name: "Olive Crossing", exact: true }).click();

  const board = page.locator(".crossing-stage > svg");
  const laneEdges = board.locator('.crossing-lane:last-child polygon[fill="#253b2533"]');
  await expect(laneEdges).toHaveCount(2);
  await expect.poll(() => board.evaluate(svg => {
    const { x, width } = (svg as SVGSVGElement).viewBox.baseVal;
    const edges = [...svg.querySelectorAll<SVGPolygonElement>('.crossing-lane:last-child polygon[fill="#253b2533"]')];
    return edges.every(edge => {
      const bounds = edge.getBBox();
      return bounds.x >= x && bounds.x + bounds.width <= x + width;
    });
  })).toBe(true);
  const initialEdges = await laneEdges.evaluateAll(edges => edges.map(edge => edge.getAttribute("points")));
  const player = board.locator(".crossing-player");
  const startingX = Number(await player.getAttribute("data-world-x"));

  await page.getByRole("button", { name: "Start Olive Crossing" }).click();
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowRight");
  await expect.poll(async () => Number(await player.getAttribute("data-world-x"))).toBeGreaterThan(startingX);
  expect(await laneEdges.evaluateAll(edges => edges.map(edge => edge.getAttribute("points")))).toEqual(initialEdges);
  expect(
    await page.locator(".crossing-stage").evaluate(
      (stage) => stage.scrollWidth <= stage.clientWidth + 1,
    ),
  ).toBe(true);
});

// Inputs: the values in this case. Expected: Olive Crossing has touch controls and fits a phone.
test("Olive Crossing has touch controls and fits a phone", async ({
  browser,
  baseURL,
}) => {
  const context = await browser.newContext({
    baseURL,
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
  const page = await context.newPage();
  await page.goto("/game");
  await page.getByRole("button", { name: "Olive Crossing", exact: true }).tap();
  await page.getByRole("button", { name: "Start Olive Crossing" }).tap();
  await page.getByRole("button", { name: "Move up" }).tap();
  await expect(page.getByRole("status")).toContainText("1 lanes cleared");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/olive-crossing-phone.png",
    fullPage: true,
  });
  await context.close();
});
