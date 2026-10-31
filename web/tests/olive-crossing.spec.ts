// Tests lane movement and obstacles in the olive crossing cover game.
import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

// Inputs: the values in this case. Expected: Olive Crossing supports keyboard play, pausing and restarting.
test("Olive Crossing supports keyboard play, pausing and restarting", async ({
  page,
}) => {
  await page.goto("/");
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

  await page.getByRole("button", { name: "New crossing" }).click();
  await expect(page.getByRole("status")).toContainText("0 lanes cleared");
});

// Inputs: the values in this case. Expected: Olive Crossing has touch controls and fits a phone.
test("Olive Crossing has touch controls and fits a phone", async ({
  browser,
}) => {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:5173/");
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
