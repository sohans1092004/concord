import fc from "fast-check";
import { readdirSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { canonicalize } from "../src/canonical.js";
import { Board } from "../src/board.js";
import type { Op } from "../src/op.js";
import { opArb } from "./arb.js";

const build = (ops: readonly Op[]): Board => {
  const b = Board.empty();
  for (const o of ops) b.apply(o);
  return b;
};
const boardArb = fc.array(opArb, { maxLength: 25 }).map(build);
const enc = (b: Board) => b.canonical();

describe("board", () => {
  it("merge is commutative, associative and idempotent", () => {
    fc.assert(
      fc.property(boardArb, boardArb, boardArb, (a, b, c) => {
        expect(enc(Board.merge(a, b).board)).toBe(enc(Board.merge(b, a).board));
        expect(enc(Board.merge(Board.merge(a, b).board, c).board)).toBe(enc(Board.merge(a, Board.merge(b, c).board).board));
        expect(enc(Board.merge(a, a).board)).toBe(enc(a));
      }),
      { numRuns: 500 },
    );
  });

  it("apply agrees with merge: applying ops equals merging their single-op states", () => {
    fc.assert(
      fc.property(fc.array(opArb, { maxLength: 25 }), (ops) => {
        let merged = Board.empty();
        for (const o of ops) merged = Board.merge(merged, build([o])).board;
        expect(enc(merged)).toBe(enc(build(ops)));
      }),
    );
  });

  it("apply is idempotent and order-independent", () => {
    fc.assert(
      fc.property(
        fc.array(opArb, { maxLength: 25 }).chain((xs) => fc.tuple(fc.constant(xs), fc.shuffledSubarray(xs, { minLength: xs.length }))),
        ([xs, ys]) => {
          expect(enc(build([...ys, ...xs]))).toBe(enc(build(xs)));
        },
      ),
    );
  });

  it("view is a total function of the canonical state", () => {
    fc.assert(
      fc.property(boardArb, (b) => {
        const v = canonicalize(b.view());
        expect(canonicalize(Board.decode(JSON.parse(enc(b))).view())).toBe(v);
      }),
    );
  });

  it("decode(encode(b)) round-trips and decode rejects malformed state", () => {
    fc.assert(fc.property(boardArb, (b) => {
      expect(enc(Board.decode(JSON.parse(enc(b))))).toBe(enc(b));
    }));
    const empty = JSON.parse(Board.empty().canonical());
    expect(() => Board.decode({ ...empty, extra: {} })).toThrow();
    const hlc = { counter: 0, physical_ms: 1, replica_id: "a0000000-0000-4000-8000-000000000001" };
    const badPlacement = { ...empty, registers: { "cc000000-0000-4000-8000-000000000001": { placement: { hlc, value: "a0" } } } };
    expect(() => Board.decode(badPlacement)).toThrow();
  });
});

describe("package boundary", () => {
  it("src/ has no networking, storage or host imports", () => {
    const dir = new URL("../src/", import.meta.url);
    for (const f of readdirSync(dir)) {
      const text = readFileSync(new URL(f, dir), "utf8");
      for (const m of text.matchAll(/from\s+"([^"]+)"/g)) expect(m[1], `${f} imports ${m[1]}`).toMatch(/^\.\//);
      expect(text, f).not.toMatch(/\b(fetch|WebSocket|XMLHttpRequest|indexedDB|localStorage|sessionStorage|require)\s*\(/);
      expect(text, f).not.toMatch(/\bimport\s*\(/);
    }
  });
});
