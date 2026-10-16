import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { compare, decode, encode, receive, tick, zero } from "../src/hlc.js";
import { hlcArb, wideHlcArb } from "./arb.js";

const nowArb = fc.integer({ min: 0, max: 2 ** 45 });

describe("hlc", () => {
  it("tick is strictly increasing, even when the wall clock goes backwards", () => {
    fc.assert(
      fc.property(wideHlcArb.filter((h) => h.counter < 2 ** 31 - 100), fc.array(nowArb, { maxLength: 50 }), (start, nows) => {
        let h = start;
        for (const now of nows) {
          const next = tick(h, now);
          expect(compare(next, h)).toBe(1);
          expect(next.physical_ms).toBeGreaterThanOrEqual(now);
          h = next;
        }
      }),
    );
  });

  it("receive exceeds both the local and the remote clock", () => {
    fc.assert(
      fc.property(wideHlcArb, wideHlcArb, nowArb, (local, remote, now) => {
        const r = receive(local, remote, now);
        expect(compare(r, local)).toBe(1);
        expect(compare(r, remote)).toBe(1);
        expect(r.replica_id).toBe(local.replica_id);
      }),
    );
  });

  it("compare is a total order: antisymmetric, transitive, zero only on equal fields", () => {
    fc.assert(
      fc.property(hlcArb, hlcArb, hlcArb, (a, b, c) => {
        expect(Math.sign(compare(a, b)) + Math.sign(compare(b, a))).toBe(0);
        if (compare(a, b) <= 0 && compare(b, c) <= 0) expect(compare(a, c)).toBeLessThanOrEqual(0);
        expect(compare(a, b) === 0).toBe(JSON.stringify(encode(a)) === JSON.stringify(encode(b)));
      }),
    );
  });

  it("orders physical_ms, then counter, then replica_id as bytes", () => {
    const r1 = "a0000000-0000-4000-8000-000000000001";
    const r2 = "a0000000-0000-4000-8000-000000000002";
    expect(compare({ physical_ms: 2, counter: 0, replica_id: r1 }, { physical_ms: 1, counter: 9, replica_id: r2 })).toBe(1);
    expect(compare({ physical_ms: 1, counter: 1, replica_id: r1 }, { physical_ms: 1, counter: 0, replica_id: r2 })).toBe(1);
    expect(compare({ physical_ms: 1, counter: 0, replica_id: r2 }, { physical_ms: 1, counter: 0, replica_id: r1 })).toBe(1);
    // Byte order, not java.util.UUID's signed order: f... sorts after 0...
    const hi = "f0000000-0000-4000-8000-000000000000";
    expect(compare({ physical_ms: 1, counter: 0, replica_id: hi }, { physical_ms: 1, counter: 0, replica_id: r1 })).toBe(1);
  });

  it("encode/decode round-trips and decode rejects malformed clocks", () => {
    fc.assert(fc.property(wideHlcArb, (h) => {
      expect(decode(encode(h))).toEqual(h);
    }));
    const ok = encode(tick(zero("a0000000-0000-4000-8000-000000000001"), 5));
    expect(decode(ok)).not.toBeNull();
    expect(decode({ ...ok, counter: 1.5 })).toBeNull();
    expect(decode({ ...ok, counter: -1 })).toBeNull();
    expect(decode({ ...ok, counter: 2 ** 31 })).toBeNull();
    expect(decode({ ...ok, replica_id: ok.replica_id.toUpperCase() })).toBeNull();
    expect(decode({ ...ok, extra: 1 })).toBeNull();
    expect(decode([1, 2, 3])).toBeNull();
  });

  it("tick refuses to overflow the counter", () => {
    const h = { physical_ms: 10, counter: 2 ** 31 - 1, replica_id: "a0000000-0000-4000-8000-000000000001" };
    expect(() => tick(h, 5)).toThrow(RangeError);
  });
});
