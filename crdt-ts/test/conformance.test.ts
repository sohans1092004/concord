// Runs every conformance/vectors/*.json exactly as SPEC §9.2.5 describes.
// The vectors live outside the package; CONCORD_VECTORS overrides the path.
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { canonicalize, type Json } from "../src/canonical.js";
import { Board } from "../src/board.js";
import { checkClock, checkShape, type Op } from "../src/op.js";

const dir = process.env.CONCORD_VECTORS ?? fileURLToPath(new URL("../../conformance/vectors/", import.meta.url));
const sha256 = (s: string) => createHash("sha256").update(s, "utf8").digest("hex");

interface Vector {
  name: string;
  initial_state: unknown;
  server_time_ms?: number;
  ops: unknown[];
  expected_rejected: Record<string, string>;
  expected_collisions: number;
  expected_view: Json;
  expected_state: Json;
  expected_state_sha256: string;
}

export function runVector(v: Vector): void {
  const board = Board.decode(v.initial_state);
  const rejected: Record<string, string> = {};
  const accepted: Op[] = [];
  for (const raw of v.ops) {
    let r = checkShape(raw);
    if (r.ok && v.server_time_ms !== undefined) r = checkClock(raw as Op, v.server_time_ms);
    if (!r.ok) rejected[(raw as { op_id: string }).op_id] = r.code;
    else accepted.push(raw as Op);
  }
  expect(rejected).toEqual(v.expected_rejected);
  let collisions = 0;
  for (const o of accepted) collisions += board.apply(o);
  expect(collisions).toBe(v.expected_collisions);
  expect(canonicalize(board.view())).toBe(canonicalize(v.expected_view));
  expect(board.canonical()).toBe(canonicalize(v.expected_state));
  expect(sha256(board.canonical())).toBe(v.expected_state_sha256);

  const reversed = Board.decode(v.initial_state);
  for (const o of [...accepted].reverse()) reversed.apply(o);
  expect(reversed.canonical()).toBe(board.canonical());
}

describe("conformance vectors", () => {
  it("vector directory exists", () => {
    expect(existsSync(dir), dir).toBe(true);
  });
  const files = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith(".json")).sort() : [];
  for (const f of files) {
    it(f, () => runVector(JSON.parse(readFileSync(join(dir, f), "utf8")) as Vector));
  }
});
