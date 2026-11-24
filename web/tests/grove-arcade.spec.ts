import { expect, test } from "./coverageFixture";

test("Canopy Breaker can serve, pause, resume, and restart from the arcade", async ({ page }) => {
  await page.goto("/game");
  await page.getByRole("button", { name: "Canopy Breaker", exact: true }).click();
  const game = page.getByRole("region", { name: "Canopy Breaker game" });
  const board = game.getByRole("group", { name: /Canopy Breaker board/ });
  const status = game.getByRole("status");
  await game.getByRole("button", { name: "Start Canopy Breaker" }).click();
  await expect(status).toContainText("Position paddle");
  await board.press("ArrowLeft");
  await board.press("Space");
  await expect(status).toContainText("bricks left");
  await game.getByRole("button", { name: "Pause", exact: true }).click();
  await expect(status).toContainText("Paused");
  await board.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(status).toContainText("bricks left");
  await game.locator(".flight-controls").getByRole("button", { name: "New round" }).click();
  await expect(status).toContainText("Position paddle");
  await board.locator("canvas").click();
  await expect(status).toContainText("bricks left");
});

test("Canopy Breaker keeps paddle and launch controls usable on narrow screens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/game");
  await page.getByRole("button", { name: "Canopy Breaker", exact: true }).click();
  const game = page.getByRole("region", { name: "Canopy Breaker game" });
  await game.getByRole("button", { name: "Start Canopy Breaker" }).click();
  const controls = game.getByRole("group", { name: "Paddle controls" });
  await controls.getByRole("button", { name: "Move paddle left" }).click();
  await controls.getByRole("button", { name: "Move paddle right" }).click();
  await controls.getByRole("button", { name: "Launch" }).click();
  await expect(game.getByRole("status")).toContainText("bricks left");
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test("Grove 2048 accepts moves and restores its board after leaving the arcade", async ({ page }) => {
  await page.goto("/game");
  await page.getByRole("button", { name: "Grove 2048", exact: true }).click();
  const game = page.getByRole("region", { name: "Grove 2048 game" });
  const board = game.getByRole("group", { name: /Grove 2048 board/ });
  const moves = game.locator(".game-score > span").filter({ hasText: "MOVES" }).locator("strong");
  await expect(moves).toHaveText("0");
  for (const key of ["ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown"]) await board.press(key);
  await expect(moves).not.toHaveText("0");
  const savedMoves = await moves.innerText();
  await page.getByRole("button", { name: "Nature Pairs", exact: true }).click();
  await page.getByRole("button", { name: "Grove 2048", exact: true }).click();
  await expect(moves).toHaveText(savedMoves);
  await game.getByRole("button", { name: "New round" }).click();
  await expect(moves).toHaveText("0");
  await expect(board).toBeFocused();
  const box = (await board.boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  for (const [dx, dy] of [[100, 0], [-100, 0], [0, 100], [0, -100]]) {
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + dx, y + dy, { steps: 5 });
    await page.mouse.up();
  }
  await expect(moves).not.toHaveText("0");
});
