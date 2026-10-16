// Differential fuzz (SPEC §9.2). The TypeScript side generates inputs; the Java core answers
// through its line-protocol CLI (crdt-java/.../cli/Main.java). Any disagreement fails.
//
// Build the jar first: (cd crdt-java && ./gradlew jar). Env: DIFF_RUNS (scenarios, default
// 500), DIFF_SEED, JAVA_HOME, CONCORD_JAR.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { Board, canonicalize, utf8, type Json } from "../../crdt-ts/src/index.js";
import { checkClock, checkShape, type Op } from "../../crdt-ts/src/op.js";
import { deliveryOrder, generate, prng, sampleScenarios } from "../../crdt-ts/test/fuzz/sim.js";

const RUNS = Number(process.env.DIFF_RUNS ?? 500);
const SEED = Number(process.env.DIFF_SEED ?? 20261016);
const JAR = process.env.CONCORD_JAR ?? fileURLToPath(new URL("../../crdt-java/build/libs/crdt-java-0.1.0.jar", import.meta.url));
const JAVA = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java";

const sha256 = (s: string) => createHash("sha256").update(s, "utf8").digest("hex");
const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");

/** Sends requests to one Java process; returns one parsed response per request. */
function java(requests: readonly object[]): Record<string, unknown>[] {
  const r = spawnSync(JAVA, ["-cp", JAR, "dev.concord.crdt.cli.Main"], {
    input: requests.map((q) => JSON.stringify(q)).join("\n") + "\n",
    encoding: "utf8",
    maxBuffer: 1 << 30,
  });
  if (r.status !== 0) throw new Error(`java exited ${r.status}: ${r.stderr}`);
  const lines = r.stdout.trimEnd().split("\n");
  expect(lines).toHaveLength(requests.length);
  return lines.map((l) => JSON.parse(l) as Record<string, unknown>);
}

function inBatches<T, R>(xs: readonly T[], size: number, f: (batch: readonly T[]) => R[]): R[] {
  const out: R[] = [];
  for (let i = 0; i < xs.length; i += size) out.push(...f(xs.slice(i, i + size)));
  return out;
}

describe("differential: crdt-ts vs crdt-java", () => {
  it("the Java jar exists", () => {
    expect(existsSync(JAR), `${JAR} missing; run ./gradlew jar in crdt-java`).toBe(true);
  });

  it(`same state and view hashes over ${RUNS} fuzz scenarios, Java fed a different order with duplicates`, () => {
    const scenarios = [...sampleScenarios(Math.ceil(RUNS * 0.9), SEED), ...sampleScenarios(Math.floor(RUNS * 0.1), SEED + 1, 450, 500, true)];
    const cases = scenarios.map((s) => {
      const ops = generate(s).ops;
      const b = Board.empty();
      let collisions = 0;
      for (const o of ops) collisions += b.apply(o);
      return {
        ops: deliveryOrder(ops.map((_, i) => i), s.seed ^ 0x5eed).map((i) => ops[i]!),
        expected: { collisions, rejected: {}, state_sha256: sha256(b.canonical()), view_sha256: sha256(canonicalize(b.view())) },
      };
    });
    const answers = inBatches(cases, 50, (batch) => java(batch.map((c) => ({ cmd: "run", ops: c.ops }))));
    let opCount = 0;
    cases.forEach((c, i) => {
      opCount += c.ops.length;
      expect(answers[i], `scenario ${i}`).toEqual(c.expected);
    });
    console.log(`differential state: ${cases.length} scenarios, ${opCount} op deliveries, all hashes equal`);
  });

  it("same validateShape and checkClock verdicts on hostile mutations of real ops", () => {
    const rnd = prng(SEED);
    const pick = <T>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)]!;
    const nasty: Json[] = [
      null, true, 0, -1, 1.5, 2 ** 53, 2 ** 31, "", "1", "x".repeat(201), "😀".repeat(200), "\ud800", "é", "é",
      "A0000000-0000-4000-8000-000000000001", "00000000-0000-0000-0000-000000000000", "a0000000-0000-4000-8000-000000000001",
      "a0", "a00", "Zz", "b0", "a0V0", "A" + "0".repeat(26), "a" + "V".repeat(64), "title", "labels", "placement", "assignee",
      [], ["b", "a"], ["a", "a"], ["｡", "😀"], ["😀", "｡"], ["x".repeat(51)], Array.from({ length: 21 }, (_, i) => `l${i}`),
      {}, { position_key: "a0" }, { list_id: "11000000-0000-4000-8000-000000000001", position_key: "a1" },
      { counter: 1.5, physical_ms: 1, replica_id: "a0000000-0000-4000-8000-000000000001" },
    ];
    const mutate = (x: Json, depth = 0): Json => {
      if (typeof x !== "object" || x === null || Array.isArray(x) || depth > 1) return pick(nasty);
      const o: Record<string, Json> = { ...(x as Record<string, Json>) };
      const keys = Object.keys(o);
      const k = pick(keys);
      const roll = rnd();
      if (roll < 0.15) delete o[k];
      else if (roll < 0.25) o[pick(["extra", "type", "value", "placement", "observed_tags"])] = pick(nasty);
      else if (roll < 0.6 && typeof o[k] === "object" && o[k] !== null) o[k] = mutate(o[k]!, depth + 1);
      else o[k] = pick(nasty);
      return o;
    };
    const ops = sampleScenarios(40, SEED + 2).flatMap((s) => generate(s).ops);
    const requests: { cmd: string; op: Json; server_time_ms?: number }[] = [];
    for (let i = 0; i < 20_000; i++) {
      const base = pick(ops) as unknown as Json;
      const candidate = rnd() < 0.15 ? base : mutate(base);
      const op = JSON.parse(JSON.stringify(candidate)) as Json; // exactly what Java receives
      const pm = (base as { hlc: { physical_ms: number } }).hlc.physical_ms;
      requests.push(rnd() < 0.5 ? { cmd: "validate", op } : { cmd: "validate", op, server_time_ms: pm - 60_000 + Math.floor(rnd() * 3) - 1 });
    }
    const answers = java(requests);
    let valid = 0, skew = 0;
    requests.forEach((q, i) => {
      let c = checkShape(q.op);
      if (c.ok && q.server_time_ms !== undefined) c = checkClock(q.op as unknown as Op, q.server_time_ms);
      const ts = c.ok ? { ok: true } : { ok: false, code: c.code };
      if (c.ok) valid++;
      if (!c.ok && c.code === "CLOCK_SKEW") skew++;
      expect(answers[i], `${JSON.stringify(q)}\nTS reason: ${c.ok ? "-" : c.reason}`).toEqual(ts);
    });
    console.log(`differential validation: ${requests.length} ops, ${valid} valid, ${skew} CLOCK_SKEW, ${requests.length - valid - skew} INVALID_OP, all verdicts equal`);
  });

  it("same canonical bytes for random Unicode strings and objects (control chars, emoji, astral, lone surrogates)", () => {
    const rnd = prng(SEED + 3);
    const unit = (): string => {
      const r = rnd();
      if (r < 0.2) return String.fromCharCode(Math.floor(rnd() * 0x20)); // control
      if (r < 0.4) return String.fromCharCode(0x20 + Math.floor(rnd() * 0x60)); // ASCII incl. " \ and DEL
      if (r < 0.6) return String.fromCodePoint(0x80 + Math.floor(rnd() * (0xd800 - 0x80))); // BMP below surrogates
      if (r < 0.7) return String.fromCodePoint(0xe000 + Math.floor(rnd() * 0x2000)); // BMP above surrogates
      if (r < 0.95) return String.fromCodePoint(0x10000 + Math.floor(rnd() * 0x100000)); // astral: emoji and beyond
      return String.fromCharCode(0xd800 + Math.floor(rnd() * 0x800)); // lone surrogate
    };
    const str = () => Array.from({ length: Math.floor(rnd() * 12) }, unit).join("");
    const values: Json[] = [];
    for (let i = 0; i < 20_000; i++) {
      const r = rnd();
      if (r < 0.6) values.push(str());
      else if (r < 0.9) values.push(Object.fromEntries(Array.from({ length: 1 + Math.floor(rnd() * 5) }, () => [str(), str()])));
      else values.push([str(), Math.floor((rnd() - 0.5) * 2 ** 54), rnd() < 0.5 ? null : true]);
    }
    const answers = java(values.map((value) => ({ cmd: "canonical", value })));
    let errors = 0;
    values.forEach((v, i) => {
      let ts: { hex: string } | { error: true };
      try {
        ts = { hex: hex(utf8(canonicalize(v))) };
      } catch {
        ts = { error: true };
        errors++;
      }
      const j = answers[i]!;
      expect("error" in j ? { error: true } : { hex: j.hex }, JSON.stringify(v)).toEqual(ts);
    });
    console.log(`differential canonical: ${values.length} values, ${errors} rejected by both, all bytes equal`);
  });
});
