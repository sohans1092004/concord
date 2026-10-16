// Generator coverage: how often the fuzz actually produces the spec's hard cases.
// Two ops are concurrent when the later one's replica had not seen the earlier one.
import type { Op } from "../../src/op.js";
import { generate, type Scenario } from "./sim.js";

export const CASES = [
  "concurrent moves of the same card", // two SET_PLACEMENTs of one card
  "delete vs concurrent edit", // DELETE_* that tombstoned something vs SET_FIELD, same entity
  "delete vs concurrent restore", // DELETE_* that tombstoned something vs RESTORE_*, same entity
  "concurrent equal-key inserts", // two different entities placed at the same (list, key)
] as const;
export type Case = (typeof CASES)[number];
export type Counts = Record<Case, { pairs: number; scenarios: number }>;

const entity = (op: Op): string | null =>
  "entity_id" in op ? op.entity_id : "card_id" in op ? op.card_id : "list_id" in op ? op.list_id : null;
const slot = (op: Op): string | null =>
  "placement" in op ? `${"list_id" in op.placement ? op.placement.list_id : "lists"}/${op.placement.position_key}` : null;
const isDelete = (op: Op) => (op.type === "DELETE_CARD" || op.type === "DELETE_LIST") && op.observed_tags.length > 0;
const isRestore = (op: Op) => op.type === "RESTORE_CARD" || op.type === "RESTORE_LIST";

function classify(a: Op, b: Op): Case | null {
  const ea = entity(a);
  if (ea !== null && ea === entity(b)) {
    if (a.type === "SET_PLACEMENT" && b.type === "SET_PLACEMENT" && "list_id" in a.placement) return CASES[0];
    if ((isDelete(a) && b.type === "SET_FIELD") || (isDelete(b) && a.type === "SET_FIELD")) return CASES[1];
    if ((isDelete(a) && isRestore(b)) || (isDelete(b) && isRestore(a))) return CASES[2];
    return null;
  }
  const sa = slot(a);
  return sa !== null && sa === slot(b) ? CASES[3] : null;
}

export function coverage(scenarios: readonly Scenario[]): Counts {
  const counts = Object.fromEntries(CASES.map((c) => [c, { pairs: 0, scenarios: 0 }])) as Counts;
  for (const s of scenarios) {
    const seen = new Set<Case>();
    const ops: Op[] = [];
    generate(s, (op, index, known) => {
      for (let i = 0; i < index; i++) {
        if (known.has(i)) continue;
        const c = classify(ops[i]!, op);
        if (c) {
          counts[c].pairs++;
          seen.add(c);
        }
      }
      ops.push(op);
    });
    for (const c of seen) counts[c].scenarios++;
  }
  return counts;
}
