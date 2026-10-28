// News cover users: fetches and filters public news pages for the web app.
import { lookup } from "node:dns";
import { createHash } from "node:crypto";
import { isIP } from "node:net";
import { Agent, fetch } from "undici";
import { XMLParser } from "fast-xml-parser";
import { parseHTML } from "linkedom";
import { Readability } from "@mozilla/readability";
export function publicIPv4(ip) {
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return false;
  const a = ip.split(".").map(Number);
  if (a.length !== 4 || a.some((n) => !Number.isInteger(n) || n < 0 || n > 255))
    return false;
  const [x, y] = a;
  return !(
    x === 0 ||
    x === 10 ||
    x === 127 ||
    x >= 224 ||
    (x === 169 && y === 254) ||
    (x === 172 && y >= 16 && y <= 31) ||
    (x === 192 && (y === 168 || y === 0 || y === 2)) ||
    (x === 100 && y >= 64 && y <= 127) ||
    (x === 198 && (y === 18 || y === 19 || y === 51)) ||
    (x === 203 && y === 0)
  );
}
const dispatcher = new Agent({
  connect: {
    lookup(host, options, callback) {
      lookup(host, { all: true, family: 4 }, (error, addresses) => {
        if (error) return callback(error);
        if (!addresses.length || addresses.some((a) => !publicIPv4(a.address)))
          return callback(
            new Error("Private network destinations are not permitted."),
          );
        if (options.all) callback(null, addresses);
        else callback(null, addresses[0].address, 4);
      });
    },
  },
});
export async function safeText(
  input,
  { limit = 2_000_000, redirects = 4, accept = "text/html" } = {},
) {
  const url = new URL(input);
  const literalHost = url.hostname.replace(/^\[|\]$/g, "");
  if (isIP(literalHost) && !publicIPv4(literalHost))
    throw new Error("Private network destinations are not permitted.");
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    (url.port && url.port !== "443")
  )
    throw new Error("Only public HTTPS articles are supported.");
  const response = await fetch(url, {
    dispatcher,
    redirect: "manual",
    signal: AbortSignal.timeout(10_000),
    headers: { Accept: accept, "User-Agent": "OliveBranchReader/0.1" },
  });
  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel();
    if (redirects <= 0) throw new Error("Too many redirects.");
    return safeText(new URL(response.headers.get("location"), url), {
      limit,
      redirects: redirects - 1,
      accept,
    });
  }
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error("The publisher could not be reached.");
  }
  const contentType = response.headers.get("content-type") ?? "";
  if (!/html|xml|rss|text\//i.test(contentType)) {
    await response.body?.cancel();
    throw new Error("Unsupported publisher response.");
  }
  let size = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > limit) {
      await response.body?.cancel().catch(() => {});
      throw new Error("Article exceeds reader size limit.");
    }
    chunks.push(chunk);
  }
  return { text: Buffer.concat(chunks).toString("utf8"), url: url.href };
}
const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@_",
  processEntities: true,
});
const feeds = new Map();
const articles = new Map();
const readers = new Map();
function text(value) {
  return typeof value === "object"
    ? String(value?.["#text"] ?? "")
    : String(value ?? "");
}
function plain(value) {
  return (
    parseHTML(`<body>${value}</body>`)
      .document.body.textContent?.replace(/\s+/g, " ")
      .trim()
      .slice(0, 280) ?? ""
  );
}
export async function news(query = "Canada", scope = "local") {
  const clean = query.trim().slice(0, 100) || "Canada";
  const key = `${scope}:${clean}`;
  const cached = feeds.get(key);
  if (cached && Date.now() - cached.at < 300_000) return cached.rows;
  const url =
    scope === "world"
      ? "https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-CA&gl=CA&ceid=CA:en"
      : `https://news.google.com/rss/search?q=${encodeURIComponent(clean)}&hl=en-CA&gl=CA&ceid=CA:en`;
  const { text: xml } = await safeText(url, {
    accept: "application/rss+xml,application/xml",
  });
  const items = parser.parse(xml).rss?.channel?.item ?? [];
  const rows = (Array.isArray(items) ? items : [items])
    .slice(0, 36)
    .map((item) => {
      const link = text(item.link);
      if (!link.startsWith("https://")) return null;
      const id = createHash("sha256").update(link).digest("hex").slice(0, 24);
      const title = text(item.title);
      const row = {
        id,
        title,
        summary: plain(text(item.description)),
        link,
        source: text(item.source) || "Publisher",
        sourceUrl: item.source?.["@_url"] ?? "",
        published: text(item.pubDate),
        topic:
          scope === "world"
            ? "World"
            : /sport|hockey|soccer|olympic/i.test(title)
              ? "Sports"
              : /health|hospital|medical/i.test(title)
                ? "Health"
                : /election|government|minister/i.test(title)
                  ? "Politics"
                  : /tech|science|space/i.test(title)
                    ? "Science & technology"
                    : "Local",
      };
      articles.set(id, { ...row, at: Date.now() });
      return row;
    })
    .filter(Boolean);
  if (feeds.size > 100) feeds.clear();
  if (articles.size > 2000) articles.clear();
  rows.forEach((row) => articles.set(row.id, { ...row, at: Date.now() }));
  feeds.set(key, { at: Date.now(), rows });
  return rows;
}
export async function article(id) {
  const cached = readers.get(id);
  if (cached) return cached;
  const row = articles.get(id);
  if (!row)
    throw new Error("Refresh the news feed before opening this article.");
  let page = await safeText(row.link);
  let document = parseHTML(page.text).document;
  if (new URL(page.url).hostname === "news.google.com") {
    const sourceHost = row.sourceUrl ? new URL(row.sourceUrl).hostname : "";
    const destination = [...document.querySelectorAll("a[href]")]
      .map((a) => a.getAttribute("href"))
      .find((href) => {
        try {
          const u = new URL(href);
          return (
            u.protocol === "https:" &&
            u.hostname === sourceHost &&
            u.pathname.length > 1
          );
        } catch {
          return false;
        }
      });
    if (!destination)
      throw new Error(
        "This publisher’s article is not available in the web reader. Open the original story below.",
      );
    page = await safeText(destination);
    document = parseHTML(page.text).document;
  }
  const jsonLd = [
    ...document.querySelectorAll('script[type="application/ld+json"]'),
  ]
    .map((s) => s.textContent)
    .join("");
  const isPaywalled = /"isAccessibleForFree"\s*:\s*(false|"false")/i.test(
    jsonLd,
  );
  const imageUrl = document
    .querySelector('meta[property="og:image"]')
    ?.getAttribute("content");
  document
    .querySelectorAll("script,style,iframe,form,object,embed")
    .forEach((el) => el.remove());
  const parsed = new Readability(document).parse();
  if (!parsed || parsed.textContent.length < 200)
    throw new Error(
      "The publisher did not provide readable article text. Open the original story below.",
    );
  const result = {
    title: parsed.title || row.title,
    content: parsed.content,
    byline: parsed.byline,
    url: page.url,
    imageUrl: imageUrl?.startsWith("https://") ? imageUrl : undefined,
    isPaywalled,
  };
  if (readers.size > 80) readers.clear();
  readers.set(id, result);
  return result;
}
