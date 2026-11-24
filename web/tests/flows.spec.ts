// Tests cover entry, sign-in, and safe-app navigation in the browser.
import { test, expect, type Page } from "./coverageFixture";
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
  await page.getByRole("button", { name: "Choose date of birth" }).click();
  const picker = page.getByRole("dialog", { name: "Choose date of birth" });
  await picker.getByRole("button", { name: "2006–1997", exact: true }).click();
  await picker.getByRole("button", { name: "2000", exact: true }).click();
  await picker.getByRole("button", { name: "January", exact: true }).click();
  await picker.getByRole("button", { name: "2", exact: true }).click();
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
test("account settings validate new credentials and rotate only a confirmed entry link", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await expect(page.getByRole("heading", { name: "Nature Pairs" })).toBeVisible();
  await unlock(page, link);
  await page.getByRole("button", { name: "Open settings", exact: true }).click();
  const entryLink = page.getByRole("textbox", { name: "Personal entry link", exact: true });
  await expect(entryLink).toHaveValue(link);
  await page.getByRole("button", { name: "Copy link" }).click();
  await expect(page.getByRole("button", { name: "Copied" })).toBeVisible();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link);
  await page.getByRole("button", { name: "Create a replacement link" }).click();
  await page.getByRole("button", { name: "Keep current link" }).click();
  await expect(entryLink).toHaveValue(link);
  await page.getByRole("button", { name: "Create a replacement link" }).click();
  await page.getByRole("button", { name: "Create replacement", exact: true }).click();
  await expect(entryLink).not.toHaveValue(link);
  await expect(entryLink).toHaveValue(/\/entry#[A-Za-z0-9_-]{43}$/);
  await page.getByRole("button", { name: "Dismiss", exact: true }).click();

  await page.getByLabel("Current PIN", { exact: true }).fill("123456");
  await page.getByLabel("New PIN", { exact: true }).fill("345678");
  await page.getByLabel("Confirm new PIN", { exact: true }).fill("345679");
  await page.getByRole("button", { name: "Change PIN", exact: true }).click();
  await expect(page.getByText("Enter matching PINs with 4–6 digits.")).toBeVisible();
  await page.getByLabel("Confirm new PIN", { exact: true }).fill("345678");
  await page.getByRole("button", { name: "Change PIN", exact: true }).click();
  await expect(page.getByText("Browser PIN updated.")).toBeVisible();
  await page.getByLabel("New trigger phrase", { exact: true }).fill("synthetic fitness cover");
  await page.getByRole("button", { name: "Save trigger" }).click();
  await expect(page.getByText("Cover trigger updated.")).toBeVisible();
});
test("incident entry can be edited, pinned, found and restored from trash", async ({ page }) => {
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await expect(page.getByRole("heading", { name: "Nature Pairs" })).toBeVisible();
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }).click();
  await page.getByRole("button", { name: "New entry" }).click();
  await page.getByRole("tab", { name: "Incident" }).click();
  await page.getByLabel("Title (optional)").fill("Synthetic incident");
  await page.getByLabel("Date of incident").fill("2026-05-15");
  await page.getByLabel("Approx. time of incident").fill("13:20");
  await page.getByLabel("Location of incident").fill("Synthetic location");
  await page.getByLabel("Incident details (required)").fill("First version");
  await page.getByRole("button", { name: "Save entry" }).click();
  await page.getByRole("button", { name: "Notes", exact: true }).click();
  await expect(page.getByText("No entries match your filter.")).toBeVisible();
  await page.getByRole("button", { name: "Clear filters" }).click();
  await page.getByRole("button", { name: "Pin Synthetic incident" }).click();
  await expect(page.getByRole("button", { name: "Unpin Synthetic incident" })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: /Synthetic incident First version/ }).click();
  await expect(page.getByLabel("Date of incident")).toHaveValue("2026-05-15");
  await expect(page.getByLabel("Location of incident")).toHaveValue("Synthetic location");
  await page.getByLabel("Incident details (required)").fill("Revised body");
  await page.getByRole("button", { name: "Save entry" }).click();
  await page.getByRole("button", { name: "Edit history" }).click();
  const history = page.getByRole("dialog", { name: "Entry edit history" });
  await expect(history.getByText("First version")).toBeVisible();
  await expect(history.getByText("Revised body")).toBeVisible();
  await history.getByRole("button", { name: "Close dialog" }).click();
  await page.getByRole("button", { name: "Move Synthetic incident to trash" }).click();
  await page.getByRole("button", { name: "Trash", exact: true }).click();
  await expect(page.getByText(/Expires in/)).toBeVisible();
  await page.getByRole("button", { name: "Restore" }).click();
  await page.getByRole("button", { name: "Back to notes" }).click();
  await expect(page.getByRole("heading", { name: "Synthetic incident" })).toBeVisible();
});
test("incident pin loads and keeps the selected location when address lookup is unavailable", async ({ page }) => {
  await page.route("**/api/locations?**", (route) => route.fulfill({ status: 503, json: {} }));
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }).click();
  await page.getByRole("button", { name: "New entry", exact: true }).click();
  await page.getByRole("tab", { name: "Incident", exact: true }).click();
  await page.getByRole("button", { name: "Choose incident location on map" }).click();

  const location = page.getByLabel("Location of incident");
  await expect(page.getByText("Loading map…")).toHaveCount(0, { timeout: 20000 });
  const unavailable = page.getByText("Map unavailable.");
  if (!process.env.VITE_MAPBOX_ACCESS_TOKEN?.startsWith("pk.") && await unavailable.isVisible()) {
    await location.fill("Typed incident location");
    await expect(location).toHaveValue("Typed incident location");
    return;
  }
  const map = page.getByRole("region", { name: "Incident location map" });
  await expect(unavailable).toHaveCount(0);
  const canvas = map.locator("canvas");
  await canvas.click({ position: { x: 100, y: 100 }, timeout: 5000 });
  const pin = map.locator('[aria-label="Incident pin"]');
  await expect(pin).toBeVisible();
  await expect(location).toHaveValue(/^Dropped pin/);
  const first = await location.inputValue();
  await canvas.click({ position: { x: 170, y: 130 }, timeout: 5000 });
  await expect(location).not.toHaveValue(first);
  await expect(location).toHaveValue(/^Dropped pin/);
});

// Inputs: numeric username, mismatched password, then corrected credentials. Expected: errors stay inline and valid setup advances.
test("account creation enforces current credential rules before onboarding", async ({
  page,
}) => {
  await page.clock.setFixedTime(new Date(2026, 10, 17, 12));
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
  await page.getByLabel("Full name", { exact: true }).fill("Test Person");
  await page.getByLabel("City", { exact: true }).fill("Edmonton");
  await page.getByRole("button", { name: "Choose date of birth" }).click();
  const picker = page.getByRole("dialog", { name: "Choose date of birth" });
  const option = (name: string) => picker.getByRole("button", { name, exact: true });
  await expect(option("2016–2007")).toBeVisible();
  await expect(option("1926")).toHaveCount(0);
  await option("2026–2017").click();
  await option("2026").click();
  await expect(option("December")).toHaveCount(0);
  await option("November").click();
  await expect(option("18")).toHaveCount(0);
  await option("17").click();
  await expect(page.locator('input[name="dob"]')).toHaveValue("2026-11-17");
  await page.getByRole("button", { name: /November 17, 2026/ }).click();
  await option("1936–1927").click();
  await expect(picker.getByRole("button", { name: /^\d{4}$/ })).toHaveText(
    Array.from({ length: 10 }, (_, i) => String(1936 - i)),
  );
  await option("Back").click();
  await option("2006–1997").click();
  await expect(picker.getByRole("button", { name: /^\d{4}$/ })).toHaveText(
    Array.from({ length: 10 }, (_, i) => String(2006 - i)),
  );
  await option("2001").click();
  await option("February").click();
  await expect(option("29")).toHaveCount(0);
  await option("Back").click();
  await option("Back").click();
  await option("2000").click();
  await option("February").click();
  await option("29").click();
  await expect(page.locator('input[name="dob"]')).toHaveValue("2000-02-29");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByLabel("Browser PIN", { exact: true })).toBeVisible();
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

test("hotlines filter by province, city, type, and phone number", async ({ page }) => {
  const link = await onboard(page);
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await unlock(page, link);
  await page.getByRole("main").getByRole("button", { name: "Hotlines", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Support hotlines and shelters" })).toBeVisible();
  await page.getByLabel("Province or territory").selectOption("AB");
  await page.getByLabel("City").selectOption("Edmonton");
  await page.getByLabel("Resource type").selectOption("Hotlines");
  const search = page.getByRole("searchbox", { name: "Search resources" });
  await search.fill("7804824357");
  await expect(page.getByRole("heading", { name: "Distress Line (CMHA Edmonton)" })).toBeVisible();
  await search.fill("synthetic unmatched hotline");
  await expect(page.getByText("No matching resources")).toBeVisible();
  await page.getByRole("button", { name: "Clear filters" }).first().click();
  await expect(search).toHaveValue("");
  await expect(page.getByLabel("Province or territory")).toHaveValue("");
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
    .getByLabel("Note (required)", { exact: true })
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
  // The public cover uses this fresh document to open an existing browser enrollment.
  await page.goto("/entry#open");
  await expect(page.getByLabel("Browser PIN", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/$/);
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
  await page.clock.fastForward(46_000);
  await expect(
    page.getByRole("dialog", { name: "Your Safe Space is about to lock" }),
  ).toBeVisible();
  await page.clock.fastForward(15_000);
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("main").getByRole("button", { name: "Notes & incidents", exact: true }),
  ).toHaveCount(0);
  await unlock(page, link);
  await page.getByRole("button", { name: "Open settings", exact: true }).click();
  const timeout = page.getByLabel("Idle lock delay");
  await expect(timeout.locator("option")).toHaveText([
    "1 min (Recommended)", "2 min (Recommended)", "3 min", "4 min", "5 min", "6 min", "7 min", "7 min 30 sec",
  ]);
  await timeout.selectOption("450");
  await expect(timeout).toHaveValue("450");
  await page.clock.fastForward(434_000);
  const warning = page.getByRole("dialog", { name: "Your Safe Space is about to lock" });
  await expect(warning).toHaveCount(0);
  await page.clock.fastForward(2_000);
  await expect(warning).toBeVisible();
  await page.clock.fastForward(15_000);
  await expect(page.getByRole("heading", { name: "Your Safe Space" })).toHaveCount(0);
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
const responsivePaths = [
  "/",
  "/account",
  "/account/create",
  "/admin",
  "/get",
  "/cover/daily-digest",
  "/cover/fitness",
];

for (const size of [
  { width: 390, height: 844 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
  { width: 1920, height: 1080 },
]) {
  for (const path of responsivePaths) {
    test(`responsive layout for ${path} at ${size.width}×${size.height}`, async ({ page }) => {
      await page.setViewportSize(size);
      await page.goto(path, { waitUntil: "domcontentloaded" });
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
    });
  }
}
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
  test.slow();
  const link = await onboard(page);
  await unlock(page, link);
  const navigation = page.getByRole("navigation", { name: "Safe Space navigation" });
  // Axe scans can outlast the default idle delay. Use the real preference to keep this test session open.
  await navigation.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByLabel("Idle lock delay").selectOption("450");
  await navigation.getByRole("button", { name: "Home", exact: true }).click();
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
      await navigation.getByRole("button", { name: section, exact: true }).click();
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
