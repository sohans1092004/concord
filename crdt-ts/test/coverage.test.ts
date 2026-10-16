// Guards the fuzz generator: each hard case must occur at least 50 times per 500
// scenarios, or the permutation fuzz is not testing it (SPEC §9.1).
import { describe, expect, it } from "vitest";
import { CASES, coverage } from "./fuzz/coverage.js";
import { sampleScenarios } from "./fuzz/sim.js";

describe("generator coverage", () => {
  it("every hard case occurs at least 50 times in 500 scenarios", () => {
    const counts = coverage(sampleScenarios(500, 42));
    for (const c of CASES) console.log(`coverage: ${c}: ${counts[c].pairs} pairs in ${counts[c].scenarios}/500 scenarios`);
    for (const c of CASES) expect(counts[c].pairs, c).toBeGreaterThanOrEqual(50);
  });
});
