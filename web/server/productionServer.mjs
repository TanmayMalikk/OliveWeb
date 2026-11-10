// Web visitors: serves the built site in production.
import "./serverEnvironment.mjs";
import { resolve } from "node:path";
import express from "express";
import { createServerApp } from "./httpApp.mjs";
const app = createServerApp();
const dist = resolve(import.meta.dirname, "../dist");
app.use(
  express.static(dist, {
    maxAge: "1h",
    setHeaders(res, path) {
      if (/[/\\]assets[/\\]/i.test(path)) {
        res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
      } else if (path.endsWith("android-download-qr.svg")) {
        res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
      }
    },
  }),
);
// Browser pages such as /get and /admin are handled by React after index.html loads.
// Use plain middleware here so Hostinger does not need to interpret an Express wildcard route.
app.use((req, res, next) => {
  if (
    (req.method !== "GET" && req.method !== "HEAD") ||
    req.path.startsWith("/api/")
  ) {
    return next();
  }
  res.setHeader("Cache-Control", "no-store");
  return res.sendFile(resolve(dist, "index.html"));
});
app.listen(
  Number(process.env.PORT ?? 4173),
  process.env.HOST ?? "0.0.0.0",
  () =>
    console.log(
      `Olive Branch → http://${process.env.HOST ?? "0.0.0.0"}:${process.env.PORT ?? 4173}`,
    ),
);
