import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

const src = (p: string) => fileURLToPath(new URL(p, import.meta.url));
const gateway = process.env.CONCORD_GATEWAY ?? "http://localhost:8081";
const proxy = {
  "/api": gateway,
  "/ws": { target: gateway.replace(/^http/, "ws"), ws: true },
};

// The app runs against client-core's and the engine's source, like their tests do. In dev, Vite
// proxies the REST API and the WebSocket to a gateway, so the session cookie is same-origin.
// The built app is served by the gateway itself (CONCORD_WEB_DIR), same origin again.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@concord/client-core": src("../client-core/src/index.ts"),
      "@concord/crdt": src("../crdt-ts/src/index.ts"),
    },
  },
  server: { port: 5173, proxy },
  // The production build, as Playwright tests it (performance is measured on this, not dev).
  preview: { port: 4173, strictPort: true, proxy },
  build: { target: "es2022", sourcemap: true, minify: process.env.CONCORD_NO_MINIFY ? false : "esbuild" },
});
