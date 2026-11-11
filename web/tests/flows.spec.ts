// Tests cover entry, sign-in, and safe-app navigation in the browser.
import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const password = "Test-password1!";
async function onboard(page: Page) {
  await page.goto("/account/create");
  await page.getByLabel("Username", { exact: true }).fill("website_tester");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password", { exact: true }).fill(password);
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Set up your profile" }),
  ).toBeVisible();
  await expect(page.getByLabel("Language", { exact: true })).toHaveCount(0);
  await page.getByLabel("Full name", { exact: true }).fill("Test Person");
  await page.getByLabel("Date of birth", { exact: true }).fill("2000-01-02");
  await page.getByLabel("City", { exact: true }).fill("Edmonton");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page
    .getByLabel("Trigger word or phrase", { exact: true })
    .fill("moon garden");
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page.getByLabel("Confirm PIN", { exact: true }).fill("123456");
  await page.getByRole("button", { name: "Open my everyday app" }).click();
  await expect(
    page.getByRole("heading", { name: "Your own doorway." }),
  ).toBeVisible();
  const link = await page
    .getByRole("textbox", { name: "Your personal entry link" })
    .inputValue();
  expect(link).toMatch(/\/entry#[A-Za-z0-9_-]{43}$/);
  return link;
}
async function unlock(page: Page, link: string) {
  await page.goto(link);
  await expect(page.getByLabel("Browser PIN", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page.getByRole("button", { name: "Unlock", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Your Safe Space" }),
  ).toBeVisible();
}
test("incident pin loads and keeps the selected location when address lookup is unavailable", async ({ page }) => {
  await page.route("**/api/map-tiles/**", (route) => route.fulfill({ status: 404 }));
  await page.route("**/api/locations?**", (route) => route.fulfill({ status: 503, json: {} }));
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }).click();
  await page.getByRole("button", { name: "New entry", exact: true }).click();
  await page.getByRole("tab", { name: "Incident", exact: true }).click();
  await page.getByRole("button", { name: "Choose incident location on map" }).click();

  const map = page.getByRole("application", { name: /Incident location map/ });
  await map.click({ position: { x: 100, y: 100 } });
  const pin = map.getByAltText("Incident pin");
  await expect(pin).toBeVisible();
  await expect.poll(() => pin.evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  const location = page.getByLabel("Location of incident");
  await expect(location).toHaveValue(/^Dropped pin/);
  const first = await location.inputValue();
  await map.click({ position: { x: 170, y: 130 } });
  await expect(location).not.toHaveValue(first);
  await expect(location).toHaveValue(/^Dropped pin/);
});

// Inputs: numeric username, mismatched password, then corrected credentials. Expected: errors stay inline and valid setup advances.
test("account creation enforces current credential rules before onboarding", async ({
  page,
}) => {
  await page.goto("/account/create");
  const username = page.getByLabel("Username", { exact: true });
  const passwordField = page.getByLabel("Password", { exact: true });
  const confirmation = page.getByLabel("Confirm password", { exact: true });
  const submit = page.getByRole("button", {
    name: "Create account",
    exact: true,
  });
  await username.fill("12345678");
  await passwordField.fill(password);
  await confirmation.fill(password);
  await submit.click();
  await expect(page.getByRole("alert")).toContainText("lowercase letters");
  await username.fill("boundary_user");
  await confirmation.fill("Different123!");
  await submit.click();
  await expect(page.getByRole("alert")).toContainText("Passwords do not match");
  await confirmation.fill(password);
  await submit.click();
  await expect(
    page.getByRole("heading", { name: "Set up your profile" }),
  ).toBeVisible();
});
// Inputs: the values in this case. Expected: plain domain and guessed private paths show Little Grove.
test("plain domain and guessed private paths show Little Grove", async ({
  page,
}) => {
  for (const path of ["/", "/safe", "/entry"]) {
    await page.goto(path);
    await expect(
      page.locator(".grove-games"),
    ).toBeVisible();
    await expect(
      page.getByText("Your personal entry", { exact: true }),
    ).toHaveCount(0);
  }
});
// Inputs: the values in this case. Expected: account onboarding, unique-link gate, encrypted notes, contact CRUD, settings, exit and re-entry.
test("account onboarding, unique-link gate, encrypted notes, contact CRUD, settings, exit and re-entry", async ({
  page,
}) => {
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/");
  await expect(
    page.locator(".grove-games"),
  ).toBeVisible();
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }).click();
  await page.getByRole("button", { name: "New entry", exact: true }).click();
  await page.getByLabel("Title (optional)").fill("Private fixture note");
  await page
    .getByLabel("Your words")
    .fill("Details only inside the encrypted vault.");
  await page.getByRole("button", { name: "Save entry", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Private fixture note" }),
  ).toBeVisible();
  const vaults = await page.evaluate(async () => {
    const request = indexedDB.open("olive-branch-web");
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const read = db.transaction("vaults").objectStore("vaults").getAll();
    const records = await new Promise((resolve) => {
      read.onsuccess = () => resolve(read.result);
    });
    db.close();
    return JSON.stringify(records);
  });
  expect(vaults).not.toContain("Private fixture note");
  expect(vaults).not.toContain("Details only inside");
  await page.getByRole("button", { name: "Trusted contacts", exact: true }).click();
  await page.getByRole("button", { name: "Add a contact" }).click();
  await page.getByLabel("Name", { exact: true }).fill("Trusted Friend");
  await page
    .getByLabel("Phone, email, or other details")
    .fill("friend@example.invalid");
  await page.getByRole("button", { name: "Save contact" }).click();
  await expect(
    page.getByRole("heading", { name: "Trusted Friend" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(page.getByRole("combobox", { name: /language/i })).toHaveCount(0);
  await expect(page.getByLabel("Personal entry link", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Quick Exit", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText("Private fixture note")).toHaveCount(0);
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Private fixture note" }),
  ).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(page.getByText("Private fixture note")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Quick Exit", exact: true }),
  ).toHaveCount(0);
});
// Inputs: the values in this case. Expected: invalid personal links cannot open an account even with valid account credentials.
test("invalid personal links cannot open an account even with valid account credentials", async ({
  page,
}) => {
  await onboard(page);
  await page.goto(`/entry#${"a".repeat(43)}`);
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page.getByRole("button", { name: "Unlock", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("current personal link");
  await expect(
    page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }),
  ).toHaveCount(0);
});
// Inputs: the values in this case. Expected: returning account opens the PIN gate at the root URL without revealing private content.
test("returning account opens the PIN gate at the root URL without revealing private content", async ({
  page,
}) => {
  await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await page.goto("/account/sign-in");
  await page.getByLabel("Username", { exact: true }).fill("website_tester");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByLabel("Browser PIN", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Quick Exit", exact: true })).toBeVisible();
  await expect(
    page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }),
  ).toHaveCount(0);
});

test("a browser enrollment with a phone cover migrates to its own game field", async ({ page }) => {
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await page.evaluate(async () => {
    const { database } = await import("/src/services/LocalDataStore.ts");
    const db = await database;
    const { webCover, ...enrollment } = await db.get("device", "current");
    await db.put("device", { ...enrollment, cover: "NEWS" }, "current");
  });
  await page.goto("/fitness");
  await expect(page.locator(".grove-games")).toBeVisible();
  await expect.poll(() => page.evaluate(async () => {
    const { database } = await import("/src/services/LocalDataStore.ts");
    const saved = await (await database).get("device", "current");
    return [saved.webCover, "cover" in saved];
  })).toEqual(["GAME", false]);
  await unlock(page, link);
});
// Inputs: the values in this case. Expected: personal links can be rotated and wrong PINs do not reveal private data.
test("personal links can be rotated and wrong PINs do not reveal private data", async ({
  page,
}) => {
  const original = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await page.goto(original);
  await page.getByLabel("Browser PIN", { exact: true }).fill("9999");
  await page.getByRole("button", { name: "Unlock", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Incorrect PIN");
  await expect(
    page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page.getByRole("button", { name: "Unlock", exact: true }).click();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByRole("button", { name: "Create a replacement link" }).click();
  await page
    .getByRole("button", { name: "Create replacement", exact: true })
    .click();
  await expect(
    page.getByLabel("Personal entry link", { exact: true }),
  ).not.toHaveValue(original);
  const replacement = await page
    .getByLabel("Personal entry link", { exact: true })
    .inputValue();
  expect(replacement).not.toBe(original);
  await page.getByRole("button", { name: "Quick Exit", exact: true }).click();
  await page.goto(original);
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page.getByRole("button", { name: "Unlock", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("current personal link");
  await unlock(page, replacement);
});
// Inputs: the values in this case. Expected: Quick Exit cancels an in-flight PIN operation and idle dialog autofocus does not extend the timeout.
test("Quick Exit cancels an in-flight PIN operation and idle dialog autofocus does not extend the timeout", async ({
  page,
}) => {
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await page.goto(link);
  await page.getByLabel("Browser PIN", { exact: true }).fill("123456");
  await page
    .getByRole("button", { name: "Unlock", exact: true })
    .click({ noWaitAfter: true });
  await page.getByRole("button", { name: "Quick Exit", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("heading", { name: "Your Safe Space" }),
  ).toHaveCount(0);
  await unlock(page, link);
  await page.clock.install();
  await page.clock.fastForward(61_000);
  await expect(
    page.getByRole("dialog", { name: "Your Safe Space is about to lock" }),
  ).toBeVisible();
  await page.clock.fastForward(15_000);
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }),
  ).toHaveCount(0);
});
// Inputs: the values in this case. Expected: PWA manifests use the Little Grove identity and the download page offers the signed APK.
test("PWA manifests use the Little Grove identity and the download page offers the signed APK", async ({
  page,
  request,
}) => {
  const manifest = await (
    await request.get("/cover/daily-digest/manifest.webmanifest")
  ).json();
  expect(manifest.name).toBe("Little Grove");
  expect(manifest.start_url).toBe("/");
  expect(manifest.icons[0].src).toContain("game-192");
  await page.goto("/get");
  await expect(
    page.getByRole("heading", { name: "iPhone and iPad" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Create an account" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Android", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Download signed APK/i }),
  ).toHaveAttribute("href", /\/download\/android$/);
  await expect(
    page.getByAltText("QR code for the latest signed Android app"),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Use Olive Branch in your browser" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Open the web app/i }),
  ).toHaveAttribute("href", "https://yourolivebranch.ca/account/sign-in");
});
for (const size of [
  { width: 390, height: 844 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
  { width: 1920, height: 1080 },
])
  // Inputs: the values in this case. Expected: responsive layouts at ${size.width}×${size.height}.
  test(`responsive layouts at ${size.width}×${size.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(size);
    for (const path of [
      "/",
      "/account",
      "/admin",
      "/get",
      "/cover/daily-digest",
      "/cover/fitness",
    ]) {
      await page.goto(path);
      await expect(page.locator("h1:visible,h2:visible").first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth + 1,
      );
      expect(overflow, `${path} overflows ${size.width}px`).toBe(false);
      if (path === "/" || path === "/account")
        await page.screenshot({
          path: `test-results/${size.width}-${path === "/" ? "game" : "account"}.png`,
          fullPage: true,
        });
    }
  });
test("Safe Space navigation fits narrow phones through wide desktops", async ({ page }) => {
  const link = await onboard(page);
  await unlock(page, link);
  for (const width of [320, 390, 768, 1280, 1920]) {
    await page.setViewportSize({ width, height: 850 });
    const overflow = () => page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
    expect(await overflow(), `Safe Space at ${width}px`).toBe(false);
    const menu = page.getByRole("button", { name: "Navigation", exact: true });
    if (width <= 960) {
      await menu.focus();
      await menu.press("Enter");
      await expect(menu).toHaveAttribute("aria-expanded", "true");
    }
    const navigation = page.getByRole("navigation", { name: "Safe Space navigation" });
    await expect(navigation.getByRole("button", { name: "Camera", exact: true })).toHaveCount(0);
    await navigation.getByRole("button", { name: "Safety plan", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Safety plan", exact: true })).toBeFocused();
    if (width <= 960) await expect(menu).toHaveAttribute("aria-expanded", "false");
    expect(await overflow(), `Safety plan at ${width}px`).toBe(false);
    await page.getByRole("button", { name: "Back to Safe Space" }).click();
  }
});

// Inputs: the values in this case. Expected: public game and account screens meet core accessibility checks.
test("public game and account screens meet core accessibility checks", async ({
  page,
}) => {
  for (const path of ["/", "/account/create", "/account/sign-in", "/admin", "/get"]) {
    await page.goto(path);
    await expect(page.locator("h1,h2").first()).toBeVisible();
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      results.violations.map((v) => ({
        id: v.id,
        description: v.description,
        nodes: v.nodes.map((n) => n.target),
      })),
    ).toEqual([]);
  }
});

// Inputs: the values in this case. Expected: signed-in safe app pages meet core accessibility checks.
test("signed-in safe app pages meet core accessibility checks", async ({
  page,
}) => {
  const link = await onboard(page);
  await unlock(page, link);
  for (const section of [
    "Home",
    "Notes & incidents",
    "Friends & messages",
    "Safety plan",
    "Trusted contacts",
    "My profile",
    "Settings",
    "Account activity",
  ]) {
    if (section !== "Home") {
      await page.getByRole("navigation", { name: "Safe Space navigation" }).getByRole("button", { name: section, exact: true }).click();
    }
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      results.violations.map((violation) => ({
        page: section,
        rule: violation.id,
        elements: violation.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
  }
});
