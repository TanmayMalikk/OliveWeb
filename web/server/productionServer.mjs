// Web visitors: serves the built site in production.
import "./serverEnvironment.mjs";
import { resolve } from "node:path";
import express from "express";
import { readFile } from "node:fs/promises";
import { renderPageHtml } from "./pageSecurity.mjs";
import { createServerApp } from "./httpApp.mjs";
import { notifyServerFailure, notifyServerLive } from "../scripts/deploy-notify.mjs";

const host = process.env.HOST ?? "0.0.0.0";
const port = Number(process.env.PORT ?? 4173);

try {
  const app = createServerApp();
  const dist = resolve(import.meta.dirname, "../dist");
  const template = await readFile(resolve(dist, "index.html"), "utf8");
  app.use(
    express.static(dist, {
      index: false,
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
    return res.type("html").send(renderPageHtml(template, res.locals.publicAdNonce));
  });

  const onStartupError = async (error) => {
    await notifyServerFailure(error, "startup");
    console.error(error);
    process.exit(1);
  };
  const server = app.listen(port, host, () => {
    server.off("error", onStartupError);
    console.log(`Olive Branch → http://${host}:${port}`);
    void notifyServerLive({ host, port });
  });
  server.once("error", onStartupError);
} catch (error) {
  await notifyServerFailure(error, "startup");
  throw error;
}
