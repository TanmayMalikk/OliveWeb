// Web visitors: starts the local site server with Vite for development.
import "./serverEnvironment.mjs";
import { createServer } from "vite";
import { createServerApp } from "./httpApp.mjs";
const rawPort =
  process.env.NGINX_PORT && process.env.PORT === process.env.NGINX_PORT
    ? undefined
    : process.env.PORT;
const host =
  process.env.HOST ??
  (process.env.DEFAULT_APP_PORT ? "0.0.0.0" : "127.0.0.1");
const port = Number(rawPort ?? process.env.DEFAULT_APP_PORT ?? 5173);
const app = createServerApp({ development: true });
const vite = await createServer({
  server: {
    middlewareMode: true,
    allowedHosts: true,
    ...(host === "0.0.0.0" || port === 3000 ? { hmr: false } : {}),
  },
  appType: "spa",
});
app.use(vite.middlewares);
const server = app.listen(port, host, () =>
  console.log(`Olive Branch → http://${host}:${port}`),
);
async function close() {
  await vite.close();
  server.close(() => process.exit(0));
}
process.on("SIGINT", close);
process.on("SIGTERM", close);
