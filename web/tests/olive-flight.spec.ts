// Tests controls and collisions in the olive flight cover game.
import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

// Inputs: the values in this case. Expected: each game stays within page width, with controls reachable below the play area.
test("all games stay within the page width and keep controls reachable", async ({
  page,
}) => {
  for (const [width, height] of [
    [1024, 600],
    [1280, 720],
    [1366, 768],
    [1440, 900],
    [1920, 1080],
    [2560, 1080],
  ]) {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    for (const name of ["Nature Pairs", "Olive Flight", "Olive Crossing"]) {
      await page.getByRole("button", { name, exact: true }).click();
      const layout = await page.evaluate(() => {
        const game = document
          .querySelector(".grove-game")!
          .getBoundingClientRect();
        const main = document
          .querySelector(".grove-main")!
          .getBoundingClientRect();
        const intro = document
          .querySelector(".grove-intro")!
          .getBoundingClientRect();
        const play = document
          .querySelector(".flight-stage, .memory-grid, .crossing-stage")!
          .getBoundingClientRect();
        const controls = document
          .querySelector(".game-bottom")!
          .getBoundingClientRect();
        return {
          fullWidth: document.querySelector(".flight-stage")
            ? Math.abs(
                play.width -
                  document
                    .querySelector(".flight-arena")!
                    .getBoundingClientRect().width,
              ) < 2
            : document.querySelector(".crossing-stage")
              ? Math.abs(
                  play.width -
                    document
                      .querySelector(".crossing-arena")!
                      .getBoundingClientRect().width,
                ) < 2
              : true,
          documentHeight: document.documentElement.scrollHeight,
          scrollWidth: document.documentElement.scrollWidth,
          height: innerHeight,
          width: innerWidth,
          gameWidth: game.width,
          mainWidth: main.width,
          gameLeft: game.left,
          gameRight: game.right,
          mainRight: main.right,
          introRight: intro.right,
          top: game.top,
          bottom: game.bottom,
          controlsBottom: controls.bottom,
          documentHeight: document.documentElement.scrollHeight,
          playHeight: play.height,
          playBottom: play.bottom,
          controlsTop: controls.top,
          panelScrolls:
            document.querySelector<HTMLElement>(".grove-game")!.scrollWidth >
            document.querySelector<HTMLElement>(".grove-game")!.clientWidth + 1,
        };
      });
      expect(layout.scrollWidth).toBeLessThanOrEqual(width);
      expect(
        layout.panelScrolls,
        `${name} panel should not scroll horizontally at ${width}x${height}`,
      ).toBe(false);
      expect(
        layout.gameWidth / layout.mainWidth,
        `${name} should fill the right game column at ${width}x${height}`,
      ).toBeGreaterThan(0.5);
      expect(layout.gameLeft).toBeGreaterThanOrEqual(layout.introRight - 2);
      expect(Math.abs(layout.gameRight - layout.mainRight)).toBeLessThan(2);
      expect(layout.fullWidth).toBe(true);
      expect(layout.top).toBeGreaterThanOrEqual(0);
      expect(layout.bottom).toBeLessThanOrEqual(layout.documentHeight + 1);
      expect(layout.controlsBottom).toBeLessThanOrEqual(layout.documentHeight + 1);
      expect(layout.playHeight).toBeGreaterThan(180);
      expect(layout.playBottom).toBeLessThanOrEqual(layout.controlsTop);
    }
    if (width === 1366 || width === 1920)
      await page.screenshot({
        path: `test-results/olive-flight-fit-${width}.png`,
        fullPage: true,
      });
  }
  // Resize the current game without reloading or losing the selected game.
  await page.getByRole("button", { name: "Olive Flight", exact: true }).click();
  await page.setViewportSize({ width: 1440, height: 640 });
  await expect(
    page.getByRole("button", { name: "Start Olive Flight" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
  await page.goto("/cover/game");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth + 1,
    ),
  ).toBe(true);
});

// Inputs: the values in this case. Expected: Olive Flight supports keyboard flight, pause, collision, restart and returning to Nature Pairs.
test("Olive Flight supports keyboard flight, pause, collision, restart and returning to Nature Pairs", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.locator('a[href="/account"], a[href="/download"]'),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Olive Flight", exact: true }).click();
  const start = page.getByRole("button", {
    name: "Start Olive Flight",
    exact: true,
  });
  await start.focus();
  await page.keyboard.press("Space");
  await expect(
    page.getByRole("button", { name: "Pause", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Paused. Take your time.");
  const olive = page.locator(".flight-stage svg > g").last();
  const position = await olive.getAttribute("transform");
  await page.waitForTimeout(200);
  await expect(olive).toHaveAttribute("transform", position!);
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Play Olive Flight again" }),
  ).toBeVisible();
  await expect(page.getByRole("status")).toContainText("Round over");
  await page.getByRole("button", { name: "New round", exact: true }).click();
  await expect(start).toBeVisible();
  await page.getByRole("button", { name: "Nature Pairs", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Turn card 1", exact: true }),
  ).toBeVisible();
});

// Inputs: the values in this case. Expected: Olive Flight fits phone and desktop widths and accepts touch input.
test("Olive Flight fits phone and desktop widths and accepts touch input", async ({
  browser,
}) => {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:5173/");
  await page.getByRole("button", { name: "Olive Flight", exact: true }).tap();
  await page.getByRole("button", { name: "Start Olive Flight" }).tap();
  await page.getByRole("button", { name: "Pause", exact: true }).tap();
  await expect(page.getByRole("status")).toHaveText("Paused. Take your time.");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/olive-flight-phone.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/olive-flight-desktop.png",
    fullPage: true,
  });
  await context.close();
});
