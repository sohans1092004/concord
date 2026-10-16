// Permutation fuzz (SPEC §9.1). FUZZ_RUNS sets the number of scenarios (seeds);
// FUZZ_SEED replays a reported run. A failure is shrunk by fast-check, then cut to a
// minimal op set by delta debugging and written to .fuzz-failures/ as a regression
// candidate for conformance/vectors.
import fc from "fast-check";
import { mkdirSync, writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { minimize, run, scenarioArb, type Scenario } from "./fuzz/sim.js";

const RUNS = Number(process.env.FUZZ_RUNS ?? 500);
const SEED = process.env.FUZZ_SEED === undefined ? undefined : Number(process.env.FUZZ_SEED);
const ORDERS = [1, 2, 3, 4, 5, 6, 7, 8];

function problem(s: Scenario): string | null {
  const o = run(s);
  if (o.collisions !== 0) return `${o.collisions} LWW tie-breaks fired on valid ops`;
  const bad = o.states.findIndex((x) => x !== o.states[0]);
  if (bad !== -1) return `replica ${bad} diverged from replica 0`;
  if (o.merged !== o.states[0]) return "merge of pre-delivery states differs from delivered state";
  return null;
}

// Yield to the event loop between scenarios: a long synchronous run starves Vitest's
// worker RPC, which then fails the run with "Timeout calling onTaskUpdate".
const yieldToEventLoop = () => new Promise<void>((resolve) => setImmediate(resolve));

async function fuzz(name: string, arb: fc.Arbitrary<Scenario>, runs: number): Promise<void> {
  let ops = 0;
  const started = Date.now();
  const result = await fc.check(
    fc.asyncProperty(arb, async (s) => {
      await yieldToEventLoop();
      ops += s.steps.length;
      return problem(s) === null;
    }),
    { numRuns: runs, ...(SEED === undefined ? {} : { seed: SEED }) },
  );
  if (result.failed && result.counterexample) {
    const [s] = result.counterexample;
    const why = problem(s);
    const all = run(s).ops;
    const minimal = minimize(all, ORDERS);
    mkdirSync(".fuzz-failures", { recursive: true });
    const file = `.fuzz-failures/${name}-seed-${result.seed}.json`;
    writeFileSync(file, JSON.stringify({ seed: result.seed, path: result.counterexamplePath, why, scenario: s, minimal_ops: minimal }, null, 2));
    expect.fail(`${name} failure (seed ${result.seed}): ${why}; minimal failing op set: ${minimal.length} of ${all.length} ops; written to ${file}`);
  }
  if (result.failed) throw result.errorInstance ?? new Error(`${name} failed (seed ${result.seed})`);
  console.log(`${name}: ${result.numRuns} scenarios, seed ${result.seed}, ${ops} steps, ${((Date.now() - started) / 1000).toFixed(1)}s`);
}

describe("permutation fuzz", () => {
  it(`every replica converges to byte-identical state (${RUNS} scenarios)`, async () => {
    await fuzz("fuzz", scenarioArb(), RUNS);
  });

  // Ordinary scenarios rarely drive a key past the 40-character rebalance mark, so this
  // one forces same-gap inserts with replica 0 making ~80% of them (SPEC §3.7).
  const rebalanceRuns = Math.max(50, Math.round(RUNS / 10));
  it(`converges while keys are rebalanced under same-gap pressure (${rebalanceRuns} scenarios)`, async () => {
    const arb = scenarioArb(450, 500, true).map((s) => ({
      ...s,
      steps: s.steps.map((st) => (st.x % 5 !== 0 ? { ...st, r: 0 } : st)),
    }));
    await fuzz("rebalance-fuzz", arb, rebalanceRuns);
  });
});
