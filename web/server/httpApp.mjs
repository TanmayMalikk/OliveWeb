// Web visitors: defines site routes, response headers, and the news API.
import express from "express";
import { news, article } from "./newsProxy.mjs";
import siteConfig from "../site.config.json" with { type: "json" };

const ANDROID_APK_STORAGE_PATH =
  "/v0/b/olive-21239.firebasestorage.app/o/public%2Fandroid%2Folive-branch.apk";
const ANDROID_APK_STORAGE_URL = `https://firebasestorage.googleapis.com${ANDROID_APK_STORAGE_PATH}?alt=media`;

export function createServerApp({ development = false } = {}) {
  const app = express();
  app.disable("x-powered-by");
  app.use((req, res, next) => {
    res.set({
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
      "X-Frame-Options": "DENY",
      "Permissions-Policy": "camera=(), microphone=(), geolocation=(self)",
      "Cross-Origin-Opener-Policy": "same-origin",
      "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    });
    if (!development)
      res.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self' https://www.google.com https://www.gstatic.com; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self' https://*.googleapis.com https://*.firebaseio.com wss://*.firebaseio.com https://*.cloudfunctions.net https://*.firebasestorage.app https://*.appspot.com https://*.r2.cloudflarestorage.com; frame-src https://*.firebaseapp.com https://www.google.com https://www.openstreetmap.org; worker-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'; upgrade-insecure-requests",
      );
    next();
  });
  app.use("/api", (_req, res, next) => {
    res.setHeader("Cache-Control", "no-store");
    next();
  });
  app.get("/api/health", (_req, res) => res.json({ ok: true }));
  app.get("/download/android", (_req, res) => {
    let destination;
    try {
      destination = new URL(
        process.env.ANDROID_APK_URL || ANDROID_APK_STORAGE_URL,
      );
    } catch {
      return res
        .status(503)
        .type("text/plain")
        .send("APK download is not configured.");
    }
    if (
      destination.protocol !== "https:" ||
      destination.hostname !== "firebasestorage.googleapis.com" ||
      destination.pathname !== ANDROID_APK_STORAGE_PATH
    )
      return res
        .status(503)
        .type("text/plain")
        .send("APK download is not configured.");
    res.setHeader("Cache-Control", "no-store");
    return res.redirect(302, destination.href);
  });
  app.get(
    [
      "/manifest.webmanifest",
      "/:slug/manifest.webmanifest",
      "/cover/:slug/manifest.webmanifest",
    ],
    (req, res) => {
      res.setHeader("Cache-Control", "no-cache");
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
  // Incident notes use this route for address suggestions without exposing the Geoapify key to browsers.
  app.get("/api/locations", async (req, res) => {
    const key = process.env.GEOAPIFY_API_KEY?.trim();
    if (!key) {
      return res.status(503).json({
        error: "Address suggestions are not configured on this server.",
      });
    }
    const latitude = Number(req.query.latitude);
    const longitude = Number(req.query.longitude);
    const hasCoordinates =
      Number.isFinite(latitude) &&
      Number.isFinite(longitude) &&
      latitude >= -90 &&
      latitude <= 90 &&
      longitude >= -180 &&
      longitude <= 180;
    const query = String(req.query.q ?? "").trim().slice(0, 200);
    if (!hasCoordinates && query.length < 2) {
      return res.status(400).json({ error: "Enter at least two address characters." });
    }
    const endpoint = hasCoordinates
      ? new URL("https://api.geoapify.com/v1/geocode/reverse")
      : new URL("https://api.geoapify.com/v1/geocode/autocomplete");
    if (hasCoordinates) {
      endpoint.searchParams.set("lat", String(latitude));
      endpoint.searchParams.set("lon", String(longitude));
    } else {
      endpoint.searchParams.set("text", query);
      endpoint.searchParams.set("limit", "5");
      endpoint.searchParams.set("filter", "countrycode:ca");
    }
    endpoint.searchParams.set("apiKey", key);
    try {
      const response = await fetch(endpoint, {
        signal: AbortSignal.timeout(5000),
        headers: { Accept: "application/json" },
      });
      if (!response.ok) throw new Error(`Geoapify returned ${response.status}`);
      const payload = await response.json();
      const locations = (payload.features ?? []).slice(0, 5).map((feature) => ({
        label: String(feature.properties?.formatted ?? "Selected map location"),
        latitude: Number(feature.properties?.lat ?? feature.geometry?.coordinates?.[1]),
        longitude: Number(feature.properties?.lon ?? feature.geometry?.coordinates?.[0]),
      })).filter((location) =>
        location.label && Number.isFinite(location.latitude) && Number.isFinite(location.longitude),
      );
      return res.json({ locations });
    } catch {
      return res.status(502).json({
        error: "Address suggestions are temporarily unavailable.",
      });
    }
  });
  return app;
}
