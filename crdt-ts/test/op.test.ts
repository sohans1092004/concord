import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { checkClock, checkShape, newOpId, NIL_UUID, validateShape, type Op } from "../src/op.js";
import { opArb, uuidN } from "./arb.js";

const R1 = uuidN("a0000000", 1);
const H = { counter: 0, physical_ms: 1760000000000, replica_id: R1 };
const base = { op_id: uuidN("0e000000", 1), board_id: uuidN("b0a4d000", 1), hlc: H, replica_id: R1 };
const CARD = uuidN("cc000000", 1);
const addCard = {
  ...base,
  type: "ADD_CARD",
  card_id: CARD,
  placement: { list_id: uuidN("11000000", 1), position_key: "a0" },
  title: "t",
};
const setField = (field: string, value: unknown) => ({ ...base, type: "SET_FIELD", entity_id: CARD, field, value });
const labels = (n: number) => Array.from({ length: n }, (_, i) => `l${String(i).padStart(2, "0")}`);

describe("op.validateShape", () => {
  it("accepts every generated op, and its JSON round-trip", () => {
    fc.assert(
      fc.property(opArb, (op) => {
        expect(checkShape(op)).toEqual({ ok: true });
        expect(validateShape(JSON.parse(JSON.stringify(op)))).toBe(true);
      }),
    );
  });

  it("rejects any op with a member removed or an unknown member added", () => {
    fc.assert(
      fc.property(opArb, fc.nat(), (op, i) => {
        const keys = Object.keys(op);
        const without: Record<string, unknown> = { ...op };
        delete without[keys[i % keys.length]!];
        expect(validateShape(without)).toBe(false);
        expect(validateShape({ ...op, extra: 1 })).toBe(false);
      }),
    );
  });

  it("rejects IDs that are not lowercase canonical UUIDs, and the nil UUID", () => {
    expect(validateShape(addCard)).toBe(true);
    for (const bad of [CARD.toUpperCase(), `{${CARD}}`, CARD.replaceAll("-", ""), NIL_UUID, "", 7]) {
      expect(validateShape({ ...addCard, card_id: bad })).toBe(false);
      expect(validateShape({ ...addCard, op_id: bad })).toBe(false);
    }
    // ADD_CARD and SET_PLACEMENT cannot target Orphaned.
    expect(validateShape({ ...addCard, placement: { list_id: NIL_UUID, position_key: "a0" } })).toBe(false);
    expect(
      validateShape({ ...base, type: "SET_PLACEMENT", entity_id: CARD, placement: { list_id: NIL_UUID, position_key: "a0" } }),
    ).toBe(false);
    expect(validateShape({ ...base, type: "DELETE_CARD", card_id: CARD, observed_tags: [base.op_id.toUpperCase()] })).toBe(false);
    expect(validateShape(setField("assignee", NIL_UUID))).toBe(false);
  });

  it("rejects a replica_id that disagrees with the HLC, and non-integer numbers", () => {
    expect(validateShape({ ...addCard, replica_id: uuidN("a0000000", 2) })).toBe(false);
    expect(validateShape({ ...addCard, hlc: { ...H, counter: 0.5 } })).toBe(false);
    expect(validateShape({ ...addCard, hlc: { ...H, physical_ms: 2 ** 53 } })).toBe(false);
  });

  it("enforces title and description limits in code points", () => {
    expect(validateShape({ ...addCard, title: "😀".repeat(200) })).toBe(true); // 400 UTF-16 units
    expect(validateShape({ ...addCard, title: "x".repeat(201) })).toBe(false);
    expect(validateShape({ ...addCard, title: "\ud800" })).toBe(false);
    expect(validateShape(setField("description", "d".repeat(10_000)))).toBe(true);
    expect(validateShape(setField("description", "d".repeat(10_001)))).toBe(false);
  });

  it("enforces labels: at most 20, each ≤ 50 code points, NFC, strictly ascending bytes", () => {
    expect(validateShape(setField("labels", []))).toBe(true);
    expect(validateShape(setField("labels", ["a", "b"]))).toBe(true);
    expect(validateShape(setField("labels", ["b", "a"]))).toBe(false);
    expect(validateShape(setField("labels", ["a", "a"]))).toBe(false);
    expect(validateShape(setField("labels", labels(20)))).toBe(true);
    expect(validateShape(setField("labels", labels(21)))).toBe(false);
    expect(validateShape(setField("labels", ["x".repeat(50)]))).toBe(true);
    expect(validateShape(setField("labels", ["x".repeat(51)]))).toBe(false);
    expect(validateShape(setField("labels", ["é"]))).toBe(true); // precomposed é: NFC
    expect(validateShape(setField("labels", ["é"]))).toBe(false); // e + combining acute: not NFC
    // Byte order, not UTF-16 order: U+FF61 (EF BD A1) sorts before U+1F600 (F0 9F 98 80).
    expect(validateShape(setField("labels", ["｡", "😀"]))).toBe(true);
    expect(validateShape(setField("labels", ["😀", "｡"]))).toBe(false);
  });

  it("rejects placement in SET_FIELD and the wrong placement form", () => {
    expect(validateShape(setField("placement", "a0"))).toBe(false);
    expect(validateShape({ ...addCard, placement: { position_key: "a0" } })).toBe(false);
    const listWithCardPlacement = {
      ...base,
      type: "ADD_LIST",
      list_id: uuidN("11000000", 1),
      placement: { list_id: uuidN("11000000", 2), position_key: "a0" },
      title: "t",
    };
    expect(validateShape(listWithCardPlacement)).toBe(false);
  });

  it("applies Greenspan's full key check", () => {
    const key = (position_key: string) => validateShape({ ...addCard, placement: { list_id: addCard.placement.list_id, position_key } });
    for (const good of ["a0", "a1", "Zz", "a0V", "b00", "b00V", "A" + "0".repeat(25) + "1"]) expect(key(good)).toBe(true);
    for (const bad of ["", "a", "b0", "a0V0", "a00", "0a", "a0-", "a0é", "A" + "0".repeat(26), "a" + "V".repeat(64)]) {
      expect(key(bad)).toBe(false);
    }
  });
});

describe("op.checkClock", () => {
  const at = (physical_ms: number) => ({ ...addCard, hlc: { ...H, physical_ms } }) as Op;

  it("rejects iff physical_ms > server_time_ms + 60000", () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 2 ** 50 }), fc.integer({ min: -200_000, max: 200_000 }), (server, delta) => {
        const pm = Math.max(0, server + delta);
        expect(checkClock(at(pm), server).ok).toBe(pm <= server + 60_000);
      }),
    );
    expect(checkClock(at(1_060_000), 1_000_000).ok).toBe(true);
    expect(checkClock(at(1_060_001), 1_000_000)).toMatchObject({ ok: false, code: "CLOCK_SKEW" });
    expect(checkClock(at(0), 1_000_000).ok).toBe(true); // age never rejects
  });
});

describe("op.newOpId", () => {
  it("makes lowercase canonical UUID v4s", () => {
    const id = newOpId();
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(newOpId()).not.toBe(id);
  });
});
