// Web visitors: starts the local site server with Vite for development.
import "./serverEnvironment.mjs";
import { createServer } from "vite";
import { createServerApp } from "./httpApp.mjs";
const app = createServerApp({ development: true });
const vite = await createServer({
  server: { middlewareMode: true },
  appType: "spa",
});
app.use(vite.middlewares);
const host = process.env.HOST ?? "127.0.0.1";
const port = Number(process.env.PORT ?? 5173);
const server = app.listen(port, host, () =>
  console.log(`Olive Branch → http://${host}:${port}`),
);
async function close() {
  await vite.close();
  server.close(() => process.exit(0));
}
process.on("SIGINT", close);
process.on("SIGTERM", close);
