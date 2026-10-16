import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { canonicalize, type Json } from "../src/canonical.js";
import { Lww } from "../src/lww.js";
import { cardIdArb, hlcArb } from "./arb.js";

// Tiny HLC and value pools: equal HLCs with different values (the tie-break case)
// come up constantly, which a valid op stream would never produce.
const valueArb: fc.Arbitrary<Json> = fc.oneof(
  fc.constantFrom<Json>("a", "b", "é", "😀", "｡", null, ["x"], ["x", "y"], { list_id: "l", position_key: "a0" }),
  fc.integer({ min: -3, max: 3 }),
);
const writeArb = fc.record({
  entity: cardIdArb,
  field: fc.constantFrom("title", "labels"),
  value: valueArb,
  hlc: hlcArb,
});
type Write = typeof writeArb extends fc.Arbitrary<infer W> ? W : never;

const build = (ws: readonly Write[]): Lww => {
  const l = new Lww();
  for (const w of ws) l.set(w.entity, w.field, w.value, w.hlc);
  return l;
};
const lwwArb = fc.array(writeArb, { maxLength: 15 }).map(build);
const enc = (l: Lww) => canonicalize(l.encode());

describe("lww", () => {
  it("merge is commutative, associative and idempotent, even with HLC collisions", () => {
    fc.assert(
      fc.property(lwwArb, lwwArb, lwwArb, (a, b, c) => {
        expect(enc(Lww.merge(a, b).lww)).toBe(enc(Lww.merge(b, a).lww));
        expect(enc(Lww.merge(Lww.merge(a, b).lww, c).lww)).toBe(enc(Lww.merge(a, Lww.merge(b, c).lww).lww));
        expect(enc(Lww.merge(a, a).lww)).toBe(enc(a));
        expect(Lww.merge(a, a).collisions).toBe(0);
      }),
      { numRuns: 1000 },
    );
  });

  it("the collision count is symmetric", () => {
    fc.assert(
      fc.property(lwwArb, lwwArb, (a, b) => {
        expect(Lww.merge(a, b).collisions).toBe(Lww.merge(b, a).collisions);
      }),
    );
  });

  it("writes in any order give the same registers", () => {
    fc.assert(
      fc.property(
        fc.array(writeArb, { maxLength: 15 }).chain((xs) => fc.tuple(fc.constant(xs), fc.shuffledSubarray(xs, { minLength: xs.length }))),
        ([xs, ys]) => {
          expect(enc(build(xs))).toBe(enc(build(ys)));
        },
      ),
    );
  });

  it("the higher HLC wins; on equal HLCs the greater unsigned UTF-8 encoding wins and is reported", () => {
    const e = "cc000000-0000-4000-8000-000000000001";
    const h = (ms: number) => ({ physical_ms: ms, counter: 0, replica_id: "a0000000-0000-4000-8000-000000000001" });
    const l = new Lww();
    expect(l.set(e, "title", "old", h(1))).toBe(0);
    expect(l.set(e, "title", "new", h(2))).toBe(0);
    expect(l.set(e, "title", "older", h(0))).toBe(0);
    expect(l.get(e, "title")?.value).toBe("new");
    expect(l.set(e, "title", "new", h(2))).toBe(0); // same value again: not a collision
    // "｡" encodes as EF BD A1, "😀" as F0 9F 98 80: the emoji wins on bytes,
    // although it loses on UTF-16 code units (D83D < FF61).
    expect(l.set(e, "title", "｡", h(3))).toBe(0);
    expect(l.set(e, "title", "😀", h(3))).toBe(1);
    expect(l.get(e, "title")?.value).toBe("😀");
    expect(l.set(e, "title", "｡", h(3))).toBe(1);
    expect(l.get(e, "title")?.value).toBe("😀");
  });

  it("decode(encode(l)) round-trips and decode rejects malformed input", () => {
    fc.assert(fc.property(lwwArb, (l) => {
      expect(enc(Lww.decode(JSON.parse(enc(l))))).toBe(enc(l));
    }));
    const e = "cc000000-0000-4000-8000-000000000001";
    const hlc = { counter: 0, physical_ms: 1, replica_id: "a0000000-0000-4000-8000-000000000001" };
    expect(() => Lww.decode({ [e]: {} })).toThrow();
    expect(() => Lww.decode({ [e]: { title: { hlc, value: 1.5 } } })).toThrow();
    expect(() => Lww.decode({ [e]: { title: { hlc, value: "x", extra: 1 } } })).toThrow();
    expect(() => Lww.decode({ "not-a-uuid": { title: { hlc, value: "x" } } })).toThrow();
  });
});
