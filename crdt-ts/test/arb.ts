// Shared fast-check arbitraries. Small ID pools make collisions between random
// states likely, which is where merge bugs live.
import fc from "fast-check";
import type { Hlc } from "../src/hlc.js";

export const uuidN = (prefix: string, n: number): string =>
  `${prefix}-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;

export const replicaArb = fc.integer({ min: 1, max: 4 }).map((n) => uuidN("a0000000", n));

export const hlcArb: fc.Arbitrary<Hlc> = fc.record({
  physical_ms: fc.integer({ min: 0, max: 6 }).map((n) => 1760000000000 + n),
  counter: fc.integer({ min: 0, max: 3 }),
  replica_id: replicaArb,
});

export const wideHlcArb: fc.Arbitrary<Hlc> = fc.record({
  physical_ms: fc.maxSafeNat(),
  counter: fc.integer({ min: 0, max: 2 ** 31 - 2 }),
  replica_id: fc.uuid().map((u) => u.toLowerCase()),
});
