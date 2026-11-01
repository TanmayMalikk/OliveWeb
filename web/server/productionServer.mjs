// Web visitors: serves the built site in production.
import "./serverEnvironment.mjs";
import { resolve } from "node:path";
import express from "express";
import { createServerApp } from "./httpApp.mjs";
const app = createServerApp();
const dist = resolve(import.meta.dirname, "../dist");
app.use(express.static(dist));
app.get("/{*path}", (_req, res) => res.sendFile(resolve(dist, "index.html")));
app.listen(
  Number(process.env.PORT ?? 4173),
  process.env.HOST ?? "127.0.0.1",
  () =>
    console.log(
      `Olive Branch → http://${process.env.HOST ?? "127.0.0.1"}:${process.env.PORT ?? 4173}`,
    ),
);
