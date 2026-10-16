import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { compareUtf8 } from "../src/canonical.js";
import { DIGITS, keyBetween, keysBetween, planInsert, rebalance, validateKey } from "../src/keys.js";

const digit = fc.constantFrom(...DIGITS.split(""));
const heads = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".split("");
const intLen = (h: string) => (h >= "a" ? h.charCodeAt(0) - 97 + 2 : 90 - h.charCodeAt(0) + 2);

/** Any valid key of at most 40 characters: well-formed integer part + fraction not ending in 0. */
const anyKey: fc.Arbitrary<string> = fc
  .constantFrom(...heads.filter((h) => intLen(h) <= 8)) // long integer parts are legal but exotic
  .chain((h) =>
    fc.tuple(
      fc.array(digit, { minLength: intLen(h) - 1, maxLength: intLen(h) - 1 }),
      fc.array(digit, { maxLength: 30 }).map((f) => f.join("").replace(/0+$/, "")),
    ).map(([i, f]) => h + i.join("") + f),
  )
  .filter((k) => validateKey(k));

const pair: fc.Arbitrary<[string, string]> = fc
  .tuple(anyKey, anyKey)
  .filter(([a, b]) => a !== b)
  .map(([a, b]) => (a < b ? [a, b] : [b, a]));

describe("keys", () => {
  it("a < keyBetween(a, b) < b, and the result is a valid key", () => {
    fc.assert(
      fc.property(pair, ([a, b]) => {
        const k = keyBetween(a, b);
        expect(a < k && k < b).toBe(true);
        expect(validateKey(k)).toBe(true);
      }),
      { numRuns: 2000 },
    );
  });

  it("null bounds work", () => {
    expect(keyBetween(null, null)).toBe("a0");
    fc.assert(
      fc.property(anyKey, (k) => {
        const before = keyBetween(null, k);
        const after = keyBetween(k, null);
        expect(before < k && k < after).toBe(true);
        expect(validateKey(before) && validateKey(after)).toBe(true);
      }),
    );
  });

  it("is deterministic, so concurrent inserts into one gap collide (SPEC §3.7)", () => {
    fc.assert(fc.property(pair, ([a, b]) => {
      expect(keyBetween(a, b)).toBe(keyBetween(a, b));
    }));
  });

  it("throws unless a < b, and on invalid keys", () => {
    expect(() => keyBetween("a1", "a1")).toThrow();
    expect(() => keyBetween("a2", "a1")).toThrow();
    expect(() => keyBetween("a10", null)).toThrow(); // fraction ends in 0
    expect(() => keyBetween("b0", null)).toThrow(); // integer part too short
  });

  it("follows Greenspan's reference outputs", () => {
    // Values from rocicorp/fractional-indexing's own test table.
    expect(keyBetween(null, null)).toBe("a0");
    expect(keyBetween(null, "a0")).toBe("Zz");
    expect(keyBetween(null, "Zz")).toBe("Zy");
    expect(keyBetween("a0", null)).toBe("a1");
    expect(keyBetween("a1", null)).toBe("a2");
    expect(keyBetween("a0", "a1")).toBe("a0V");
    expect(keyBetween("a1", "a2")).toBe("a1V");
    expect(keyBetween("a0V", "a1")).toBe("a0l");
    expect(keyBetween("Zz", "a0")).toBe("ZzV");
    expect(keyBetween("Zz", "a1")).toBe("a0");
    expect(keyBetween(null, "Y00")).toBe("Xzzz");
    expect(keyBetween("bzz", null)).toBe("c000");
    expect(keyBetween("a0", "a0V")).toBe("a0G");
    expect(keyBetween("a0", "a0G")).toBe("a08");
    expect(keyBetween("b125", "b129")).toBe("b127");
    expect(keyBetween("a0", "a1V")).toBe("a1");
    expect(keyBetween("Zz", "a01")).toBe("a0");
    expect(keyBetween(null, "a0V")).toBe("a0");
    expect(keyBetween(null, "b999")).toBe("b99");
    expect(() => keyBetween(null, "A00000000000000000000000000")).toThrow();
    expect(keyBetween(null, "A000000000000000000000000001")).toBe("A000000000000000000000000000V");
    expect(keyBetween("zzzzzzzzzzzzzzzzzzzzzzzzzzy", null)).toBe("zzzzzzzzzzzzzzzzzzzzzzzzzzz");
    expect(keyBetween("zzzzzzzzzzzzzzzzzzzzzzzzzzz", null)).toBe("zzzzzzzzzzzzzzzzzzzzzzzzzzzV");
    expect(() => keyBetween("a00", null)).toThrow();
    expect(() => keyBetween("a00", "a1")).toThrow();
    expect(() => keyBetween("0", "1")).toThrow();
    // Deliberate difference: upstream swaps reversed bounds and returns "a0V";
    // SPEC §3.7 requires a throw so a caller bug cannot pass silently.
    expect(() => keyBetween("a1", "a0")).toThrow();
  });

  it("keysBetween gives n ascending valid keys inside the bounds", () => {
    const bound = fc.option(anyKey, { nil: null });
    fc.assert(
      fc.property(bound, bound, fc.integer({ min: 0, max: 30 }), (x, y, n) => {
        const [a, b] = x !== null && y !== null && x > y ? [y, x] : [x, y];
        fc.pre(a === null || b === null || a < b);
        const ks = keysBetween(a, b, n);
        expect(ks).toHaveLength(n);
        ks.forEach((k, i) => {
          expect(validateKey(k)).toBe(true);
          if (i > 0) expect(ks[i - 1]! < k).toBe(true);
          if (a !== null) expect(a < k).toBe(true);
          if (b !== null) expect(k < b).toBe(true);
        });
      }),
    );
  });

  it("rebalance returns short ascending keys of the same count", () => {
    const ks = rebalance(Array.from({ length: 2000 }, () => "a" + "V".repeat(45)));
    expect(ks).toHaveLength(2000);
    for (let i = 1; i < ks.length; i++) expect(ks[i - 1]! < ks[i]!).toBe(true);
    expect(Math.max(...ks.map((k) => k.length))).toBeLessThanOrEqual(4);
  });

  it("2,000 appends to the end of a list stay far below the 40-character rebalance mark", () => {
    let k = keyBetween(null, null);
    let longest = k.length;
    for (let i = 0; i < 2000; i++) {
      k = keyBetween(k, null);
      longest = Math.max(longest, k.length);
    }
    expect(longest).toBeLessThanOrEqual(4);
  });

  it("planInsert places the new entry exactly at the index, even between equal keys", () => {
    // Keys from a tiny pool so neighbours share keys often; IDs are random.
    const entries = fc.array(fc.tuple(fc.constantFrom("a0", "a1", "a1V", "a2"), fc.uuid()), { maxLength: 8 });
    fc.assert(
      fc.property(entries, fc.nat(), fc.uuid(), (raw, at, newId) => {
        const order = (x: [string, string], y: [string, string]) => compareUtf8(x[0], y[0]) || compareUtf8(x[1], y[1]);
        const sorted = [...raw].sort(order);
        const index = at % (sorted.length + 1);
        const plan = planInsert(sorted.map(([k]) => k), index);
        const after = sorted.map(([k, id]) => [k, id] as [string, string]);
        for (const r of plan.rekeys) after[r.index]![0] = r.key;
        expect(validateKey(plan.key)).toBe(true);
        const withNew = [...after, [plan.key, newId] as [string, string]].sort(order);
        const expected = [...sorted.slice(0, index).map(([, id]) => id), newId, ...sorted.slice(index).map(([, id]) => id)];
        expect(withNew.map(([, id]) => id)).toEqual(expected);
      }),
      { numRuns: 2000 },
    );
  });

  it("planInsert re-keys every upper neighbour sharing the key", () => {
    const plan = planInsert(["a0", "a1", "a1", "a1", "a2"], 2);
    expect(plan.rekeys.map((r) => r.index)).toEqual([2, 3]);
    expect(planInsert(["a0", "a1"], 1).rekeys).toEqual([]);
  });
});
