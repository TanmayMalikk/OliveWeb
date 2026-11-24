import { test, expect } from "./coverageFixture";

test("an invite download link displays the code and supports copying before download", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/get/?invite=5BGbsH13");
  const dialog = page.getByRole("dialog", { name: "You have an invite" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("5BGbsH13", { exact: true })).toBeVisible();
  await expect(dialog.getByText(/Already installed/)).toBeVisible();
  await dialog.getByRole("button", { name: "Copy code", exact: true }).click();
  await expect(dialog.getByRole("status")).toHaveText("Code copied.");
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe("5BGbsH13");
  await expect(dialog.getByRole("link", { name: "Download Android app" })).toHaveAttribute("href", "/download/android");
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await page.getByRole("button", { name: "View invite" }).click();
  await expect(dialog).toBeVisible();
});

test("invalid invite parameters do not create a popup or render markup", async ({ page }) => {
  await page.goto("/get/?invite=%3Cscript%3Ebad%3C%2Fscript%3E");
  await expect(page.getByRole("heading", { name: "Get Olive Branch" })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
