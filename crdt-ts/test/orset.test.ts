import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { canonicalize } from "../src/canonical.js";
import { OrSet } from "../src/orset.js";
import { cardIdArb, opIdArb } from "./arb.js";

type Action = { kind: "add"; id: string; tag: string } | { kind: "remove"; id: string; tags: string[] };

const actionArb: fc.Arbitrary<Action> = fc.oneof(
  fc.record({ kind: fc.constant("add" as const), id: cardIdArb, tag: opIdArb }),
  fc.record({ kind: fc.constant("remove" as const), id: cardIdArb, tags: fc.array(opIdArb, { maxLength: 3 }) }),
);

const build = (actions: readonly Action[]): OrSet => {
  const s = new OrSet();
  for (const a of actions) a.kind === "add" ? s.add(a.id, a.tag) : s.remove(a.id, a.tags);
  return s;
};

export const orsetArb = fc.array(actionArb, { maxLength: 20 }).map(build);
const enc = (s: OrSet) => canonicalize(s.encode());

describe("orset", () => {
  it("merge is commutative, associative and idempotent", () => {
    fc.assert(
      fc.property(orsetArb, orsetArb, orsetArb, (a, b, c) => {
        expect(enc(OrSet.merge(a, b))).toBe(enc(OrSet.merge(b, a)));
        expect(enc(OrSet.merge(OrSet.merge(a, b), c))).toBe(enc(OrSet.merge(a, OrSet.merge(b, c))));
        expect(enc(OrSet.merge(a, a))).toBe(enc(a));
      }),
    );
  });

  it("merge leaves its inputs unchanged", () => {
    fc.assert(
      fc.property(orsetArb, orsetArb, (a, b) => {
        const [ea, eb] = [enc(a), enc(b)];
        OrSet.merge(a, b);
        expect([enc(a), enc(b)]).toEqual([ea, eb]);
      }),
    );
  });

  it("applying actions in any order gives the same set", () => {
    fc.assert(
      fc.property(fc.array(actionArb, { maxLength: 20 }).chain((xs) => fc.tuple(fc.constant(xs), fc.shuffledSubarray(xs, { minLength: xs.length }))), ([xs, ys]) => {
        expect(enc(build(xs))).toBe(enc(build(ys)));
      }),
    );
  });

  it("an add is cancelled only by a remove that observed its tag", () => {
    const s = new OrSet();
    const [id, t1, t2] = ["cc000000-0000-4000-8000-000000000001", "0e000000-0000-4000-8000-000000000001", "0e000000-0000-4000-8000-000000000002"];
    s.remove(id, [t1]); // delete arrives before its add
    expect(s.everAdded(id)).toBe(false); // a tombstone alone is not an add
    s.add(id, t1);
    expect(s.contains(id)).toBe(false);
    expect(s.everAdded(id)).toBe(true); // added, even though deleted
    s.add(id, t2); // a restore's fresh tag
    expect(s.contains(id)).toBe(true);
    expect(s.liveTags(id)).toEqual([t2]);
  });

  it("a remove naming one ID never changes whether another ID is present", () => {
    fc.assert(
      fc.property(orsetArb, cardIdArb, cardIdArb, fc.array(opIdArb, { maxLength: 3 }), (s, x, y, tags) => {
        fc.pre(x !== y);
        const before = s.contains(y);
        const after = s.clone();
        after.remove(x, [...tags, ...s.liveTags(y)]); // even listing every live tag of y
        expect(after.contains(y)).toBe(before);
      }),
    );
  });

  it("decode(encode(s)) round-trips and decode rejects malformed input", () => {
    fc.assert(fc.property(orsetArb, (s) => {
      expect(enc(OrSet.decode(JSON.parse(enc(s))))).toBe(enc(s));
    }));
    expect(() => OrSet.decode({ adds: {}, tombstones: {}, extra: [] })).toThrow();
    expect(() => OrSet.decode({ adds: { "cc000000-0000-4000-8000-000000000001": [] }, tombstones: {} })).toThrow();
    expect(() => OrSet.decode({ adds: {}, tombstones: ["0e000000-0000-4000-8000-000000000001"] })).toThrow(); // old flat shape
    expect(() => OrSet.decode({ adds: {}, tombstones: { "cc000000-0000-4000-8000-000000000001": [] } })).toThrow();
    expect(() => OrSet.decode({ adds: {}, tombstones: { "cc000000-0000-4000-8000-000000000001": ["NOT-A-UUID"] } })).toThrow();
  });
});
