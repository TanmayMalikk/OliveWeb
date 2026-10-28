// Web developers: builds the cover sites and safe-app pages with Vite.
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  // The calculator loads mathjs inside a worker. Pre-bundle it so the first
  // calculation cannot trigger Vite's dependency-discovery page reload.
  optimizeDeps: { include: ["mathjs"] },
  server: { host: "127.0.0.1", port: 5173, strictPort: true },
  build: { chunkSizeWarningLimit: 900 },
});
