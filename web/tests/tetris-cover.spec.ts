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
