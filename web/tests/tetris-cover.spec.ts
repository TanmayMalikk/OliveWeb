import { test, expect } from "./coverageFixture";

test("Little Grove opens Tetris while keeping Nature Pairs available", async ({ page }) => {
  await page.goto("/game");
  const naturePairs = page.getByRole("button", { name: "Nature Pairs", exact: true });
  await expect(naturePairs).toBeVisible();
  await page.getByRole("button", { name: "Tetris", exact: true }).click();
  const start = page.getByRole("button", { name: "Start Tetris" });
  await expect(start).toBeVisible();
  await start.click();
  await expect(start).toHaveCount(0);
  await expect(page.getByRole("group", { name: "Tetris controls" })).toBeVisible();
  await naturePairs.click();
  await expect(page.getByRole("region", { name: "Nature pairs memory game" })).toBeVisible();
});

test("Tetris supports held controls and starts a fresh round after the stack reaches the top", async ({ page }) => {
  await page.clock.install();
  await page.goto("/game");
  await page.getByRole("button", { name: "Tetris", exact: true }).click();
  await page.getByRole("button", { name: "Start Tetris" }).click();
  const board = page.getByRole("group", { name: /^Tetris board/ });
  const controls = page.getByRole("group", { name: "Tetris controls" });
  await page.keyboard.down("ArrowLeft");
  await page.keyboard.down("ArrowRight");
  await page.clock.runFor(400);
  await page.keyboard.up("ArrowRight");
  await page.keyboard.up("ArrowLeft");
  await page.keyboard.down("ArrowDown");
  await page.clock.runFor(450);
  await page.keyboard.up("ArrowDown");
  await controls.getByRole("button", { name: "Hold piece" }).click();
  await expect(board.locator('[aria-label^="Hold:"]')).not.toHaveAttribute("aria-label", "Hold: empty");
  await board.press("Escape");
  await expect(controls.getByRole("button", { name: "Hard drop" })).toBeDisabled();
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect(board).toBeFocused();
  const drop = controls.getByRole("button", { name: "Hard drop" });
  for (let count = 0; count < 22 && await drop.isEnabled(); count++) await drop.click();
  await expect(page.getByText("Stack reached the top", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Play again" }).click();
  await expect(board.locator('[aria-label="Hold: empty"]')).toBeVisible();
  await expect(drop).toBeEnabled();
});
