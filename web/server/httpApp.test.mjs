import { once } from "node:events";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createServerApp } from "./httpApp.mjs";
import { article, news } from "./newsProxy.mjs";

vi.mock("./newsProxy.mjs", () => ({ news: vi.fn(), article: vi.fn() }));

const localFetch = globalThis.fetch;
const upstream = vi.fn();
let server;
let origin;

beforeEach(async () => {
  vi.resetAllMocks();
  vi.stubEnv("GEOAPIFY_API_KEY", "test-map-key");
  vi.stubGlobal("fetch", upstream);
  server = createServerApp().listen(0, "127.0.0.1");
  await once(server, "listening");
  origin = `http://127.0.0.1:${server.address().port}`;
});

afterEach(async () => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  if (server) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
});

const get = (path) => localFetch(origin + path, { redirect: "manual" });

it("serves one install identity on supported aliases and rejects unknown covers", async () => {
  const home = await get("/manifest.webmanifest");
  const alias = await get("/cover/fitness/manifest.webmanifest");
  expect(home.headers.get("content-type")).toContain("application/manifest+json");
  expect(home.headers.get("content-security-policy")).toContain("default-src 'self'");
  const manifest = await home.json();
  expect(manifest).toMatchObject({ id: "/", scope: "/", start_url: "/", display: "standalone" });
  expect(await alias.json()).toEqual(manifest);
  expect((await get("/unrecognized/manifest.webmanifest")).status).toBe(404);
  expect(await (await get("/api/health")).json()).toEqual({ ok: true });
});

it("rejects impossible map coordinates before reaching Geoapify and proxies valid tiles", async () => {
  for (const path of ["/api/map-tiles/1/2/0.png", "/api/map-tiles/2/-1/0.png", "/api/map-tiles/no/0/0.png"]) {
    expect((await get(path)).status).toBe(400);
  }
  expect(upstream).not.toHaveBeenCalled();
  const bytes = Uint8Array.of(137, 80, 78, 71);
  upstream.mockResolvedValueOnce(new Response(bytes, { headers: { "content-type": "image/png" } }));
  const tile = await get("/api/map-tiles/1/0/1.png");
  expect(tile.headers.get("cache-control")).toBe("public, max-age=86400");
  expect(tile.headers.get("content-type")).toBe("image/png");
  expect(new Uint8Array(await tile.arrayBuffer())).toEqual(bytes);
  const [target] = upstream.mock.calls[0];
  expect(target.href).toBe("https://maps.geoapify.com/v1/tile/osm-bright/1/0/1.png?apiKey=test-map-key");
  upstream.mockResolvedValueOnce(new Response(null, { status: 503 }));
  expect((await get("/api/map-tiles/1/0/1.png")).status).toBe(502);
});

it("normalizes address suggestions, drops invalid results and reverse geocodes a pin", async () => {
  expect((await get("/api/locations?q=x")).status).toBe(400);
  expect(upstream).not.toHaveBeenCalled();
  const features = [
    { properties: { formatted: "Fixture address", lat: 53.5, lon: -113.5 } },
    { geometry: { coordinates: [-114, 51] } },
    { properties: { formatted: "Invalid", lat: "unknown", lon: -113.5 } },
  ];
  upstream.mockResolvedValueOnce(Response.json({ features }));
  expect(await (await get("/api/locations?q=Edmonton")).json()).toEqual({ locations: [
    { label: "Fixture address", latitude: 53.5, longitude: -113.5 },
    { label: "Selected map location", latitude: 51, longitude: -114 },
  ] });
  const [suggestion] = upstream.mock.calls[0];
  expect(suggestion.pathname).toBe("/v1/geocode/autocomplete");
  expect(suggestion.searchParams.get("filter")).toBe("countrycode:ca");
  upstream.mockResolvedValueOnce(Response.json({}));
  expect(await (await get("/api/locations?latitude=53.5&longitude=-113.5")).json()).toEqual({ locations: [] });
  const [reverse] = upstream.mock.calls[1];
  expect(reverse.pathname).toBe("/v1/geocode/reverse");
  expect(reverse.searchParams.get("lat")).toBe("53.5");
  expect(reverse.searchParams.get("lon")).toBe("-113.5");
  upstream.mockResolvedValueOnce(new Response(null, { status: 500 }));
  expect((await get("/api/locations?q=Edmonton")).status).toBe(502);
});

it("returns service unavailable for maps without a key and never makes an upstream request", async () => {
  vi.stubEnv("GEOAPIFY_API_KEY", "");
  expect((await get("/api/map-tiles/0/0/0.png")).status).toBe(503);
  expect((await get("/api/locations?q=Edmonton")).status).toBe(503);
  expect(upstream).not.toHaveBeenCalled();
});

it("serves the release manifest and redirects downloads only to a validated APK", async () => {
  const apkUrl = "https://firebasestorage.googleapis.com/v0/b/olive-21239.firebasestorage.app/o/public%2Fandroid%2Ffixture.apk";
  const manifest = { packageName: "com.olive.app", versionCode: 123, versionName: " 1.2.3 ",
    sizeBytes: 900, sha256: "b".repeat(64), releaseNotes: ["Fixture", 42] };
  upstream.mockImplementation(async () => Response.json({ ...manifest, apkUrl }));
  expect(await (await get("/api/android/releases/latest")).json()).toMatchObject({
    apkUrl, versionName: "1.2.3", releaseNotes: ["Fixture"], minimumSupportedVersionCode: 1,
  });
  const download = await get("/download/android");
  expect(download.status).toBe(302);
  expect(download.headers.get("location")).toBe(apkUrl);
  upstream.mockResolvedValueOnce(Response.json({ ...manifest, apkUrl: "https://example.test/unsigned.apk" }));
  const rejected = await get("/download/android");
  expect(rejected.status).toBe(503);
  expect(rejected.headers.get("location")).toBeNull();
  upstream.mockResolvedValueOnce(new Response(null, { status: 404 }));
  expect((await get("/api/android/releases/latest")).status).toBe(503);
});

it("rejects malformed article IDs and returns actionable upstream reader errors", async () => {
  expect((await get("/api/articles/invalid")).status).toBe(400);
  expect(article).not.toHaveBeenCalled();
  article.mockResolvedValueOnce({ title: "Synthetic article" });
  expect(await (await get(`/api/articles/${"a".repeat(24)}`)).json()).toEqual({ title: "Synthetic article" });
  article.mockRejectedValueOnce(new Error("Refresh the feed"));
  const stale = await get(`/api/articles/${"b".repeat(24)}`);
  expect(stale.status).toBe(422);
  expect(await stale.json()).toEqual({ error: "Refresh the feed" });
  news.mockResolvedValueOnce([{ title: "Synthetic feed" }]);
  expect(await (await get("/api/news?scope=world&q=Canada")).json()).toEqual([{ title: "Synthetic feed" }]);
  expect(news).toHaveBeenCalledWith("Canada", "world");
  news.mockRejectedValueOnce(new Error("Publisher offline"));
  expect((await get("/api/news")).status).toBe(502);
});
