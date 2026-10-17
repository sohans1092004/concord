import { defineConfig } from "@playwright/test";

// Against the compose stack (deploy/docker-compose.yml must be up) through the production build
// served by `vite preview`, which proxies /api and /ws to gateway1: same origin, as in production.
export default defineConfig({
  testDir: "e2e",
  timeout: 120_000, // long tests set their own
  expect: { timeout: 20_000 },
  workers: 1,
  reporter: [["list"]],
  use: { baseURL: "http://localhost:4173", trace: "retain-on-failure" },
  webServer: {
    command: "npx vite build && npx vite preview",
    url: "http://localhost:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
