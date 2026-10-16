import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    testTimeout: 0,
    coverage: {
      provider: "v8",
      include: ["src/**"],
      exclude: ["src/index.ts"], // re-exports only
      reporter: ["text", "json-summary"],
    },
  },
});
