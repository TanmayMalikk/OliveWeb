// Tests lane movement and obstacles in the olive crossing cover game.
import { expect, test } from "./coverageFixture";
import AxeBuilder from "@axe-core/playwright";

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
  await page.getByRole("button", { name: "Start Olive Crossing" }).click();
  await page.waitForFunction(async () => {
    const stage = document.querySelector(".crossing-stage > svg");
    const viewBox = stage?.getAttribute("viewBox");
    await new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
    return Boolean(viewBox) && stage?.getAttribute("viewBox") === viewBox;
  });
  const initialViewBox = await board.getAttribute("viewBox");
  const viewport = initialViewBox?.trim().split(/\s+/).map(Number) ?? [];
  expect(viewport).toHaveLength(4);
  expect(viewport.every(Number.isFinite)).toBe(true);
  expect(viewport[2]).toBeGreaterThan(0);
  expect(viewport[3]).toBeGreaterThan(0);

  for (const key of ["ArrowRight", "ArrowRight", "ArrowLeft", "ArrowLeft"]) {
    await page.keyboard.press(key);
  }
  await expect(board).toHaveAttribute("viewBox", initialViewBox!);
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
