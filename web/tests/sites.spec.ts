// Tests domain routing for public visitors and signed-in users.
import { expect, test } from "@playwright/test";

// The download and organization pages use paths on the main website instead of separate subdomains.
test("download and organization pages open from the main website", async ({
  page,
}) => {
  await page.goto("/get");
  await expect(
    page.getByRole("heading", { name: "Make a little room on your screen." }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/get$/);

  await page.goto("/admin");
  await expect(
    page.getByRole("option", { name: "Organization staff" }),
  ).toBeAttached();
  await expect(page).toHaveURL(/\/admin$/);
});

// Inputs: the values in this case. Expected: short cover URLs and legacy bookmarks open the same cover with the right install identity.
test("short cover URLs and legacy bookmarks open the same cover with the right install identity", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  for (const [slug, title, landmark] of [
    ["olive", "Olive", 'button:has-text("Saved")'],
    ["daily-digest", "Daily Digest", ".news-page"],
    ["fitness", "Fitness Tracker", ".fitness-page"],
    ["game", "Game", ".grove-games"],
  ]) {
    for (const prefix of ["", "/cover"]) {
      await page.goto(`${prefix}/${slug}`);
      await expect(page.locator(landmark).first()).toBeVisible();
      await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
        "href",
        `/${slug}/manifest.webmanifest`,
      );
      await expect(page.locator(".private-shell")).toHaveCount(0);
    }
    const manifest = await (
      await request.get(`/${slug}/manifest.webmanifest`)
    ).json();
    expect(manifest.start_url).toBe("/");
    expect(manifest.icons[0].src).toBe(`/icons/${slug}-192.png`);
    const legacy = await (
      await request.get(`/cover/${slug}/manifest.webmanifest`)
    ).json();
    expect(legacy).toEqual(manifest);
  }
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Nature Pairs", exact: true }),
  ).toBeVisible();
  await expect(page.locator('a[href$="/account"],a[href$="/get"]')).toHaveCount(
    0,
  );
  await page.getByRole("tab", { name: "Account", exact: true }).click();
  await page.getByRole("button", { name: "olive icon", exact: true }).click({
    clickCount: 2,
    delay: 120,
  });
  await expect(
    page.getByRole("button", { name: "Sign in", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/account");
  await expect(
    page.getByRole("button", { name: "Sign in", exact: true }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});
