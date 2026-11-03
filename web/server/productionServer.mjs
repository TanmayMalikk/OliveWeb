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
      }
    },
  }),
);
app.get("/{*path}", (_req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.sendFile(resolve(dist, "index.html"));
});
app.listen(
  Number(process.env.PORT ?? 4173),
  process.env.HOST ?? "0.0.0.0",
  () =>
    console.log(
      `Olive Branch → http://${process.env.HOST ?? "0.0.0.0"}:${process.env.PORT ?? 4173}`,
    ),
);
