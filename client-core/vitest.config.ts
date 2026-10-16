import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // Run against the engine's source, exactly what the conformance suite checks.
    alias: { "@concord/crdt": fileURLToPath(new URL("../crdt-ts/src/index.ts", import.meta.url)) },
  },
  test: { include: ["test/**/*.test.ts"], testTimeout: 600_000, hookTimeout: 120_000 },
});
