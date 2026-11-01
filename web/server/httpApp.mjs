// Web visitors: defines site routes, response headers, and the news API.
import express from "express";
import { news, article } from "./newsProxy.mjs";
import siteConfig from "../site.config.json" with { type: "json" };
export function createServerApp({ development = false } = {}) {
  const app = express();
  app.disable("x-powered-by");
  app.use((req, res, next) => {
    res.set({
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "X-Frame-Options": "DENY",
      "Permissions-Policy": "camera=(), microphone=(), geolocation=(self)",
      "Cache-Control": "no-store",
    });
    if (!development)
      res.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self' https://*.googleapis.com https://*.firebaseio.com wss://*.firebaseio.com https://*.cloudfunctions.net https://*.firebasestorage.app https://*.appspot.com; frame-src https://*.firebaseapp.com; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
      );
    next();
  });
  app.get("/api/health", (_req, res) => res.json({ ok: true }));
  app.get("/download/android", (_req, res) =>
    res.status(410).type("text/plain").send("Android downloads are unavailable. Install from Android Studio."),
  );
  app.get(
    [
      "/manifest.webmanifest",
      "/:slug/manifest.webmanifest",
      "/cover/:slug/manifest.webmanifest",
    ],
    (req, res) => {
      const hostSite = siteConfig.covers.find(
        (site) => site.origin && new URL(site.origin).hostname === req.hostname,
      );
      const site = req.params.slug
        ? siteConfig.covers.find((site) => site.slug === req.params.slug)
        : hostSite;
      if (req.params.slug && !site) return res.status(404).end();
      const start = "/";
      const icon = site?.slug ?? "game";
      res.type("application/manifest+json").json({
        id: start,
        name: site?.name ?? "Little Grove",
        short_name: site?.name ?? "Little Grove",
        start_url: start,
        scope: "/",
        display: "standalone",
        background_color: "#f7f7ef",
        theme_color: "#f7f7ef",
        icons: [
          {
            src: `/icons/${icon}-192.png`,
            sizes: "192x192",
            type: "image/png",
            purpose: "any",
          },
          {
            src: `/icons/${icon}-512.png`,
            sizes: "512x512",
            type: "image/png",
            purpose: "any",
          },
          {
            src: `/icons/${icon}-512.png`,
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable",
          },
        ],
      });
    },
  );
  const requests = new Map();
  app.use("/api", (req, res, next) => {
    const key = req.ip;
    const now = Date.now();
    const value = requests.get(key);
    const current =
      value && now - value.since < 60000 ? value : { since: now, count: 0 };
    current.count++;
    requests.set(key, current);
    if (requests.size > 10000) requests.clear();
    if (current.count > 90)
      return res
        .status(429)
        .json({ error: "Please wait before refreshing again." });
    next();
  });
  app.get("/api/news", async (req, res) => {
    try {
      res.json(
        await news(
          String(req.query.q ?? "Canada"),
          req.query.scope === "world" ? "world" : "local",
        ),
      );
    } catch {
      res.status(502).json({
        error: "The news feed is temporarily unavailable. Please try again.",
      });
    }
  });
  app.get("/api/articles/:id", async (req, res) => {
    if (!/^[a-f0-9]{24}$/.test(req.params.id))
      return res.status(400).json({ error: "Invalid article." });
    try {
      res.json(await article(req.params.id));
    } catch (error) {
      res.status(422).json({ error: error.message });
    }
  });
  return app;
}
