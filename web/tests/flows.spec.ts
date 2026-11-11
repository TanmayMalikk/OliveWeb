// Tests cover entry, sign-in, and safe-app navigation in the browser.
import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const password = "Test-password1!";
async function onboard(page: Page, cover = "Fitness Tracker") {
  await page.goto("/account/create");
  await page.getByLabel("Username", { exact: true }).fill("website_tester");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password", { exact: true }).fill(password);
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Choose your familiar." }),
  ).toBeVisible();
  await page.getByRole("button", { name: cover, exact: true }).click();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
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
    page.getByRole("heading", { name: "Welcome, Test." }),
  ).toBeVisible();
}
async function newsFixture(page: Page) {
  await page.route("**/api/news?*", (route) =>
    route.fulfill({
      json: [
        {
          id: "a".repeat(24),
          title: "A neighbourhood garden brings people together",
          summary: "Volunteers build a welcoming garden for their community.",
          link: "https://publisher.example/story",
          source: "Test Gazette",
          published: "2026-10-15T12:00:00Z",
          topic: "Community",
        },
      ],
    }),
  );
  await page.route("**/api/articles/*", (route) =>
    route.fulfill({
      json: {
        title: "A neighbourhood garden brings people together",
        content:
          '<p>Full article text from the publisher.</p><script>window.injected=true</script><a href="javascript:alert(1)">Bad link</a>',
        url: "https://publisher.example/story",
        byline: "Test Author",
      },
    }),
  );
}

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
    page.getByRole("heading", { name: "Choose your familiar." }),
  ).toBeVisible();
});
// Inputs: the values in this case. Expected: plain domain and guessed private paths show Fitness, never a private workspace.
test("plain domain and guessed private paths show Fitness, never a private workspace", async ({
  page,
}) => {
  for (const path of ["/", "/safe", "/entry"]) {
    await page.goto(path);
    await expect(
      page.getByRole("heading", { name: "Fitness Tracker" }),
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
    page.getByRole("heading", { name: "Fitness Tracker" }),
  ).toBeVisible();
  await unlock(page, link);
  await page.getByRole("button", { name: "Notes", exact: true }).click();
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
  await page.getByRole("button", { name: "Contacts", exact: true }).click();
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
  await page
    .getByRole("combobox", { name: "Cover app", exact: true })
    .selectOption("NEWS");
  await page.getByRole("button", { name: "Save preferences" }).click();
  await expect(page.getByRole("status")).toContainText("Changes saved.");
  await page.getByRole("button", { name: "Quick Exit", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText("Private fixture note")).toHaveCount(0);
  await unlock(page, link);
  await page.getByRole("button", { name: "Notes", exact: true }).click();
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
    page.getByRole("button", { name: "Notes", exact: true }),
  ).toHaveCount(0);
});
// Inputs: the values in this case. Expected: returning account opens the PIN gate at the root URL without revealing private content.
test("returning account opens the PIN gate at the root URL without revealing private content", async ({
  page,
}) => {
  await onboard(page, "Daily Digest");
  await page.getByRole("button", { name: "Continue to my cover" }).click();
  await page.goto("/account/sign-in");
  await page.getByLabel("Username", { exact: true }).fill("website_tester");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByLabel("Browser PIN", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Notes", exact: true }),
  ).toHaveCount(0);
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
    page.getByRole("button", { name: "Notes", exact: true }),
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
    page.getByRole("heading", { name: "Welcome, Test." }),
  ).toHaveCount(0);
  await unlock(page, link);
  await page.clock.install();
  await page.clock.fastForward(61_000);
  await expect(
    page.getByRole("dialog", { name: "Would you like a little more time?" }),
  ).toBeVisible();
  await page.clock.fastForward(15_000);
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("button", { name: "Notes", exact: true }),
  ).toHaveCount(0);
});
// Inputs: each fitness navigation tab. Expected: every tracker summary opens without exposing private screens.
test("fitness cover opens activity, vitals, and nutrition summaries", async ({
  page,
}) => {
  await page.goto("/cover/fitness");
  await expect(
    page.getByRole("heading", { name: "Fitness Tracker" }),
  ).toBeVisible();
  await page.getByRole("tab", { name: /Activity/ }).click();
  await expect(
    page.getByRole("img", { name: "Steps, last 14 days" }),
  ).toBeVisible();
  await page.getByRole("tab", { name: /Vitals/ }).click();
  await expect(page.getByText("— No data —")).toBeVisible();
  await page.getByRole("tab", { name: /Nutrition/ }).click();
  await expect(
    page.getByRole("heading", { name: "0 of 2000 kcal" }),
  ).toBeVisible();
});
// Inputs: the values in this case. Expected: news reader sanitizes publisher HTML and bookmarks stay available.
test("news reader sanitizes publisher HTML and bookmarks stay available", async ({
  page,
}) => {
  await newsFixture(page);
  await page.goto("/cover/daily-digest");
  await page.getByRole("button", { name: /Save article:/ }).click();
  await page.getByRole("button", { name: "Saved stories" }).click();
  await page
    .getByRole("button", { name: /Community A neighbourhood garden/ })
    .click();
  await expect(
    page.getByText("Full article text from the publisher."),
  ).toBeVisible();
  expect(
    await page.evaluate(() =>
      Boolean((window as unknown as { injected?: boolean }).injected),
    ),
  ).toBe(false);
  await expect(page.locator(".reader-body a")).not.toHaveAttribute(
    "href",
    /javascript:/,
  );
});
// Inputs: the values in this case. Expected: PWA manifests use cover-specific identities and the download page offers the signed APK.
test("PWA manifests use cover-specific identities and the download page offers the signed APK", async ({
  page,
  request,
}) => {
  const manifest = await (
    await request.get("/cover/daily-digest/manifest.webmanifest")
  ).json();
  expect(manifest.name).toBe("Daily Digest");
  expect(manifest.start_url).toBe("/");
  expect(manifest.icons[0].src).toContain("daily-digest-192");
  await page.goto("/get");
  await expect(
    page.getByRole("heading", { name: "A place on your Home Screen." }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Begin onboarding" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Download the signed Android app." }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Download signed APK/i }),
  ).toHaveAttribute("href", /\/download\/android$/);
  await expect(
    page.getByAltText("QR code for the latest signed Android app"),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Your space, on the web." }),
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
    await newsFixture(page);
    for (const path of [
      "/",
      "/account",
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
          path: `test-results/${size.width}-${path === "/" ? "fitness" : "account"}.png`,
          fullPage: true,
        });
    }
  });
// Inputs: the values in this case. Expected: public Fitness and account screens meet core accessibility checks.
test("public Fitness and account screens meet core accessibility checks", async ({
  page,
}) => {
  for (const path of ["/", "/account/create", "/get"]) {
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
    "Notes",
    "Messages",
    "SafetyPlan",
    "Contacts",
    "Profile",
    "Settings",
    "Activity",
  ]) {
    if (section !== "Home") {
      await page.getByRole("button", { name: section, exact: true }).click();
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
