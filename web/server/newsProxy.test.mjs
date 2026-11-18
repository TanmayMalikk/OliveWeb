// Tests news proxy URL and text checks for news cover users.
import { beforeEach, describe, it, expect, vi } from "vitest";
import { article, news, publicIPv4, safeText } from "./newsProxy.mjs";

const upstream = vi.hoisted(() => vi.fn());
vi.mock("undici", async (importOriginal) => ({ ...(await importOriginal()), fetch: upstream }));
beforeEach(() => upstream.mockReset());
describe("publisher network boundaries", () => {
  it("rejects private, loopback, link-local and reserved destinations", () => {
    for (const address of [
      "127.0.0.1",
      "10.1.2.3",
      "192.168.1.2",
      "172.16.0.1",
      "169.254.169.254",
      "100.64.0.1",
      "0.0.0.0",
      "224.1.1.1",
      "::1",
      "999.1.1.1",
    ])
      expect(publicIPv4(address)).toBe(false);
    expect(publicIPv4("8.8.8.8")).toBe(true);
  });
  it("does not accept non-HTTPS URLs or URL credentials", async () => {
    await expect(safeText("http://example.com")).rejects.toThrow("HTTPS");
    await expect(safeText("https://user:pass@example.com")).rejects.toThrow(
      "HTTPS",
    );
  });
  it("rejects literal private IPs before the HTTP client can bypass DNS validation", async () => {
    for (const url of [
      "https://127.0.0.1",
      "https://2130706433",
      "https://[::1]",
      "https://169.254.169.254",
    ])
      await expect(safeText(url)).rejects.toThrow("Private network");
  });
});

it("validates redirect targets and refuses unsupported or oversized publisher responses", async () => {
  upstream.mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: "https://127.0.0.1/" } }));
  await expect(safeText("https://publisher.example/redirect")).rejects.toThrow("Private network");
  expect(upstream).toHaveBeenCalledTimes(1);
  upstream.mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: "/next" } }));
  await expect(safeText("https://publisher.example/redirect", { redirects: 0 })).rejects.toThrow("Too many redirects");
  upstream.mockResolvedValueOnce(new Response(null, { status: 503 }));
  await expect(safeText("https://publisher.example/offline")).rejects.toThrow("could not be reached");
  upstream.mockResolvedValueOnce(new Response("image", { headers: { "content-type": "image/png" } }));
  await expect(safeText("https://publisher.example/photo")).rejects.toThrow("Unsupported publisher response");
  upstream.mockResolvedValueOnce(new Response("oversized text"));
  await expect(safeText("https://publisher.example/large", { limit: 4 })).rejects.toThrow("size limit");
});

it("caches a filtered RSS feed and renders a readable article from its known publisher", async () => {
  // The reader takes article IDs from its validated feed instead of accepting arbitrary URLs.
  upstream.mockResolvedValueOnce(new Response(`<rss><channel>
    <item><title>Health update</title><link>https://news.google.com/read/fixture</link>
      <source url="https://publisher.example">Local newsroom</source></item>
    <item><title>Unsafe article</title><link>http://publisher.example/unsafe</link></item>
  </channel></rss>`, { headers: { "content-type": "application/rss+xml" } }));
  const rows = await news("coverage fixture");
  expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({ title: "Health update", topic: "Health", source: "Local newsroom" });
  expect(rows[0].id).toMatch(/^[a-f0-9]{24}$/);
  expect(await news("coverage fixture")).toEqual(rows);
  expect(upstream).toHaveBeenCalledTimes(1);
  await expect(article("unknown")).rejects.toThrow("Refresh the news feed");

  const wrapper = `<a href="https://unrelated.example/skip">Wrong source</a><a href="https://publisher.example/report">Read</a>`;
  const paragraph = "The fixture town announced that its public library will extend evening hours this week. The report describes the new schedule and how residents can visit. ";
  upstream.mockResolvedValueOnce(new Response(wrapper));
  upstream.mockResolvedValueOnce(new Response(`<html><head><title>Library hours extended</title>
    <meta property="og:image" content="https://publisher.example/library.png">
    <script type="application/ld+json">{"isAccessibleForFree":false}</script></head>
    <body><article><h1>Library hours extended</h1><p>${paragraph.repeat(3)}</p>
    <iframe src="https://unrelated.example/embedded"></iframe></article></body></html>`));
  const reading = await article(rows[0].id);
  expect(reading).toMatchObject({ title: "Library hours extended", url: "https://publisher.example/report",
    imageUrl: "https://publisher.example/library.png", isPaywalled: true });
  expect(reading.content).toContain("public library");
  expect(reading.content).not.toContain("iframe");
  expect(await article(rows[0].id)).toEqual(reading);
  expect(upstream).toHaveBeenCalledTimes(3);
});
