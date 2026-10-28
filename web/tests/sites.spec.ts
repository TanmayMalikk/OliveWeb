// Contains the sites.spec code for this part of Olive.
import { expect, test } from "@playwright/test";

test("short cover URLs and legacy bookmarks open the same cover with the right install identity", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  for (const [slug, title, landmark] of [
    ["olive", "Olive", 'button:has-text("Saved")'],
    ["daily-digest", "Daily Digest", ".news-page"],
    ["calculator", "Better Calculator", ".graphing-workspace"],
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
  await expect(
    page.locator('a[href$="/account"],a[href$="/download"]'),
  ).toHaveCount(0);
  await page.locator(".grove-brand").click({ clickCount: 5, delay: 120 });
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
