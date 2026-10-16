// Shared fast-check arbitraries. Small ID pools make collisions between random
// states likely, which is where merge bugs live.
import fc from "fast-check";
import type { Hlc } from "../src/hlc.js";
import type { Op } from "../src/op.js";

export const uuidN = (prefix: string, n: number): string =>
  `${prefix}-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;

export const replicaArb = fc.integer({ min: 1, max: 4 }).map((n) => uuidN("a0000000", n));

export const hlcArb: fc.Arbitrary<Hlc> = fc.record({
  physical_ms: fc.integer({ min: 0, max: 6 }).map((n) => 1760000000000 + n),
  counter: fc.integer({ min: 0, max: 3 }),
  replica_id: replicaArb,
});

export const wideHlcArb: fc.Arbitrary<Hlc> = fc.record({
  physical_ms: fc.maxSafeNat(),
  counter: fc.integer({ min: 0, max: 2 ** 31 - 2 }),
  replica_id: fc.uuid().map((u) => u.toLowerCase()),
});

// ---- ops ------------------------------------------------------------------

export const BOARD = uuidN("b0a4d000", 1);
export const listIdArb = fc.integer({ min: 1, max: 3 }).map((n) => uuidN("11000000", n));
export const cardIdArb = fc.integer({ min: 1, max: 4 }).map((n) => uuidN("cc000000", n));
export const opIdArb = fc.integer({ min: 1, max: 40 }).map((n) => uuidN("0e000000", n));
export const keyArb = fc.constantFrom("a0", "a1", "a1V", "a2", "Zz", "b00", "a0G", "a0V", "Zzz");
const placementArb = fc.record({ list_id: listIdArb, position_key: keyArb });
const labelsArb = fc
  .uniqueArray(fc.constantFrom("bug", "p1", "ux", "émoji"), { maxLength: 3 })
  .map((a) => [...a].sort());

/** Any op that passes validateShape, drawn from small pools so states overlap. */
export const opArb: fc.Arbitrary<Op> = fc.tuple(opIdArb, hlcArb).chain(([op_id, hlc]) => {
  const base = { op_id, board_id: BOARD, hlc, replica_id: hlc.replica_id };
  return fc.oneof(
    fc
      .record({ list_id: listIdArb, key: keyArb, title: fc.constantFrom("To Do", "Done") })
      .map((r): Op => ({ ...base, type: "ADD_LIST", list_id: r.list_id, placement: { position_key: r.key }, title: r.title })),
    fc
      .record({ card_id: cardIdArb, placement: placementArb, title: fc.constantFrom("x", "y") })
      .map((r): Op => ({ ...base, type: "ADD_CARD", ...r })),
    fc
      .oneof(
        fc.record({ field: fc.constant("title" as const), value: fc.constantFrom("x", "y", "z") }),
        fc.record({ field: fc.constant("description" as const), value: fc.constantFrom("", "d1", "d2") }),
        fc.record({ field: fc.constant("assignee" as const), value: fc.constantFrom(null, uuidN("ee000000", 1)) }),
        fc.record({ field: fc.constant("labels" as const), value: labelsArb }),
      )
      .chain((fv) => fc.oneof(cardIdArb, listIdArb).map((entity_id): Op => ({ ...base, type: "SET_FIELD", entity_id, ...fv }))),
    fc.record({ entity_id: cardIdArb, placement: placementArb }).map((r): Op => ({ ...base, type: "SET_PLACEMENT", ...r })),
    fc
      .record({ entity_id: listIdArb, key: keyArb })
      .map((r): Op => ({ ...base, type: "SET_PLACEMENT", entity_id: r.entity_id, placement: { position_key: r.key } })),
    fc
      .record({ list_id: listIdArb, observed_tags: fc.array(opIdArb, { maxLength: 3 }) })
      .map((r): Op => ({ ...base, type: "DELETE_LIST", ...r })),
    fc
      .record({ card_id: cardIdArb, observed_tags: fc.array(opIdArb, { maxLength: 3 }) })
      .map((r): Op => ({ ...base, type: "DELETE_CARD", ...r })),
    listIdArb.map((list_id): Op => ({ ...base, type: "RESTORE_LIST", list_id })),
    cardIdArb.map((card_id): Op => ({ ...base, type: "RESTORE_CARD", card_id })),
  );
});
