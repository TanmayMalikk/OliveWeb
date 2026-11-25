import { test, expect } from "./coverageFixture";

test("public pages load AdSense with CSP nonces and private pages never load it", async ({ page, baseURL }) => {
  const adRequests: string[] = [];
  await page.route("https://pagead2.googlesyndication.com/**", async route => {
    adRequests.push(route.request().url());
    await route.fulfill({ contentType: "application/javascript", headers: { "access-control-allow-origin": "*" },
      body: "window.__fixtureAdsense = true;" });
  });
  await page.addInitScript(() => {
    (window as any).__cspViolations = [];
    document.addEventListener("securitypolicyviolation", event => {
      (window as any).__cspViolations.push(event.violatedDirective + ":" + event.blockedURI);
    });
  });
  const response = await page.goto("/");
  const policy = response!.headers()["content-security-policy"];
  const script = page.locator("head script[data-olive-adsense]");
  await expect(script).toHaveAttribute("src", /client=ca-pub-3216123362065615$/);
  const nonce = await script.evaluate((element: HTMLScriptElement) => element.nonce);
  expect(policy).toContain(`'nonce-${nonce}'`);
  await expect(page.getByRole("button", { name: "Nature Pairs", exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => (window as any).__fixtureAdsense)).toBe(true);
  expect(await page.evaluate(() => (window as any).__cspViolations)).toEqual([]);

  await page.goto("/get");
  await expect.poll(() => page.evaluate(() => (window as any).__fixtureAdsense)).toBe(true);
  expect(adRequests).toHaveLength(2);
  for (const path of ["/account/sign-in", "/account/create", "/account/link", "/admin", "/entry"]) {
    const privateResponse = await page.goto(baseURL + path);
    expect(privateResponse!.headers()["content-security-policy"]).not.toContain("'unsafe-eval'");
    await expect(page.locator("script[data-olive-adsense]")).toHaveCount(0);
    expect(await page.evaluate(() => (window as any).__fixtureAdsense)).toBeUndefined();
  }
  expect(adRequests).toHaveLength(2);
});

test("public cover opens a new ad-free document before private entry", async ({ page }) => {
  await page.route("https://pagead2.googlesyndication.com/**", route => route.fulfill({
    contentType: "application/javascript", headers: { "access-control-allow-origin": "*" },
    body: "window.__fixtureAdsense = true;",
  }));
  await page.goto("/");
  await expect.poll(() => page.evaluate(() => (window as any).__fixtureAdsense)).toBe(true);
  await page.getByRole("tab", { name: "Account", exact: true }).click();
  const navigation = page.waitForRequest(request => request.isNavigationRequest() && new URL(request.url()).pathname === "/entry");
  const loaded = page.waitForEvent("domcontentloaded");
  await page.getByRole("button", { name: "olive icon" }).dblclick();
  await navigation;
  await loaded;
  await expect.poll(() => page.evaluate(() => (window as any).__fixtureAdsense)).toBeUndefined();
  await expect(page.locator("script[data-olive-adsense]")).toHaveCount(0);
});

test("legacy private-entry fragments redirect without making an ad request", async ({ page }) => {
  const adRequests: string[] = [];
  await page.route("https://pagead2.googlesyndication.com/**", route => {
    adRequests.push(route.request().url());
    return route.abort();
  });
  const entryRequest = page.waitForRequest(request => request.isNavigationRequest() && new URL(request.url()).pathname === "/entry");
  await page.goto("/#" + "a".repeat(43));
  await entryRequest;
  await expect(page.locator("script[data-olive-adsense]")).toHaveCount(0);
  expect(adRequests).toEqual([]);
});
