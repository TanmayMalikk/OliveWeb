import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, publicPageNonce, renderPageHtml } from "./pageSecurity.mjs";

const loader = await readFile(new URL("../public/public-ads.js", import.meta.url), "utf8");

describe("public advertising boundary", () => {
  it("limits advertising permission to explicit public pages and rotates the nonce", () => {
    for (const path of ["/", "/get", "/download", "/game/", "/cover/game"]) {
      const nonce = publicPageNonce(path);
      expect(nonce).toMatch(/^[A-Za-z0-9+/]{24}$/);
      expect(publicPageNonce(path)).not.toBe(nonce);
    }
    for (const path of ["/admin", "/account/sign-in", "/account/create", "/entry", "/safe", "/api/health", "/unknown"]) {
      expect(publicPageNonce(path)).toBeNull();
    }
  });

  it("keeps ad capabilities out of private CSP and uses nonce-based trust on public pages", () => {
    const privatePolicy = contentSecurityPolicy(null);
    expect(privatePolicy).not.toContain("unsafe-eval");
    expect(privatePolicy).not.toContain("strict-dynamic");
    expect(privatePolicy).toContain("frame-ancestors 'none'");
    const nonce = publicPageNonce("/");
    const publicPolicy = contentSecurityPolicy(nonce);
    expect(publicPolicy).toContain(`'nonce-${nonce}'`);
    expect(publicPolicy).toContain("'strict-dynamic'");
    expect(publicPolicy).not.toContain("script-src *");
  });

  it("marks every trusted entry script while leaving private HTML ad-free", () => {
    const template = '<head><script src="/public-ads.js" data-public-ads="false"></script></head><body><script type="module" src="/app.js"></script></body>';
    const nonce = publicPageNonce("/");
    const html = renderPageHtml(template, nonce);
    expect(html.split(`nonce="${nonce}"`)).toHaveLength(3);
    expect(html).toContain('data-public-ads="true"');
    expect(renderPageHtml(template, null)).toBe(template);
    expect(() => renderPageHtml(template, '"injected')).toThrow("Invalid page nonce");
  });

  function runLoader({ allowed = true, hash = "", pathname = "/" } = {}) {
    const appended = [];
    const navigations = [];
    const root = { dataset: {} };
    const context = {
      location: { pathname, hash, replace: value => navigations.push(value) },
      window: { addEventListener: () => {} },
      document: {
        currentScript: { dataset: { publicAds: String(allowed) }, nonce: "fixture-nonce" },
        documentElement: root,
        querySelector: () => appended[0] ?? null,
        createElement: () => ({ dataset: {} }),
        head: { appendChild: script => appended.push(script) },
      },
    };
    runInNewContext(loader, context);
    return { appended, navigations, root, context };
  }

  it("adds the supplied AdSense script once, with the page nonce and anonymous CORS", () => {
    const state = runLoader();
    runInNewContext(loader, state.context);
    expect(state.appended).toHaveLength(1);
    expect(state.appended[0]).toMatchObject({
      async: true,
      crossOrigin: "anonymous",
      nonce: "fixture-nonce",
      src: "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-3216123362065615",
    });
    expect(state.root.dataset.olivePublicAds).toBe("true");
  });

  it("never requests ads on private documents, entry fragments, or anchor links", () => {
    for (const options of [{ allowed: false }, { hash: "#section" }, { allowed: false, hash: "#" + "a".repeat(43), pathname: "/entry" }]) {
      const state = runLoader(options);
      expect(state.appended).toEqual([]);
      expect(state.root.dataset.olivePublicAds).toBeUndefined();
    }
  });

  it("moves legacy private-entry fragments to a fresh protected document before ads run", () => {
    const hash = "#" + "a".repeat(43);
    const state = runLoader({ hash });
    expect(state.appended).toEqual([]);
    expect(state.navigations).toEqual(["/entry" + hash]);
    expect(state.root.dataset.olivePrivateRedirect).toBe("true");
  });
});
