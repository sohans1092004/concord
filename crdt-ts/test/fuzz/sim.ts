// Permutation-fuzz simulator (SPEC §9.1). Shared by the TS fuzz test and the
// differential harness in conformance/runner.
//
// A Scenario is a list of small random choices. Interpreting it simulates 3–5
// replicas that each make ops from their own partial view, pulling in other
// replicas' ops only now and then, so most ops are concurrent. The generator leans on
// the spec's hard cases: one "hot" card takes most moves, edits, deletes and
// restores; inserts favour the end of a list so concurrent replicas pick the same key.
import fc from "fast-check";
import { Board } from "../../src/board.js";
import { receive, tick, zero, type Hlc } from "../../src/hlc.js";
import { planInsert, rebalance } from "../../src/keys.js";
import { checkShape, NIL_UUID, type Op } from "../../src/op.js";

export interface Step {
  readonly r: number; // acting replica
  readonly pull: number; // unseen ops it receives first
  readonly kind: number; // 0..99, weighted op kind
  readonly x: number;
  readonly y: number;
  readonly z: number;
  readonly dt: number; // ms the shared clock advances
}

export interface Scenario {
  readonly seed: number;
  /** Always insert just after the first entry: drives keys past the rebalance mark. */
  readonly sameGap: boolean;
  readonly skews: readonly number[]; // per-replica clock offset, ms
  readonly steps: readonly Step[];
}

const T0 = 1760000000000;
const REBALANCE_AT = 40;

export function scenarioArb(minSteps = 50, maxSteps = 500, sameGap?: boolean): fc.Arbitrary<Scenario> {
  return fc.integer({ min: 3, max: 5 }).chain((n) =>
    fc.record({
      seed: fc.integer({ min: 0, max: 0x7fffffff }),
      sameGap:
        sameGap === undefined
          ? fc.oneof({ weight: 3, arbitrary: fc.constant(false) }, { weight: 1, arbitrary: fc.constant(true) })
          : fc.constant(sameGap),
      skews: fc.array(fc.integer({ min: -120_000, max: 59_000 }), { minLength: n, maxLength: n }),
      steps: fc.array(
        fc.record({
          r: fc.integer({ min: 0, max: n - 1 }),
          pull: fc.oneof(
            { weight: 6, arbitrary: fc.constant(0) },
            { weight: 3, arbitrary: fc.integer({ min: 1, max: 3 }) },
            { weight: 1, arbitrary: fc.integer({ min: 4, max: 60 }) },
          ),
          kind: fc.integer({ min: 0, max: 99 }),
          x: fc.nat(),
          y: fc.nat(),
          z: fc.nat(),
          dt: fc.oneof(fc.constant(0), fc.integer({ min: 1, max: 2000 })),
        }),
        { minLength: minSteps, maxLength: maxSteps, size: "max" },
      ),
    }),
  );
}

/** mulberry32: small deterministic PRNG, so the Java port can match it bit for bit. */
export function prng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function idFor(prefix: string, i: number, seed: number): string {
  const r = prng(seed ^ Math.imul(i + 1, 0x9e3779b1));
  // One PRNG draw per hex digit: a single 32-bit draw spread over 12 digits left the
  // low digits always 0 and IDs with ~32 bits of entropy (likely tag collisions at 1e5 seeds).
  const hex = (n: number) => Array.from({ length: n }, () => Math.floor(r() * 16).toString(16)).join("");
  return `${prefix}-${hex(4)}-4${hex(3)}-8${hex(3)}-${hex(12)}`;
}

const TITLES = ["a", "b", "Ünïcødé", "😀 emoji", "tab\there", "line\nbreak", ""];
const DESCRIPTIONS = ["", "d1", "longer description", "\u0000ctl"];
const LABELS: readonly (readonly string[])[] = [[], ["bug"], ["bug", "p1"], ["p1", "ux"], ["é"]];

class Replica {
  board = Board.empty();
  hlc: Hlc;
  readonly known = new Set<number>();
  readonly lists: string[] = []; // entity IDs this replica has heard of, in creation order
  readonly cards: string[] = [];
  constructor(id: string) {
    this.hlc = zero(id);
  }
}

export interface Generated {
  readonly ops: readonly Op[];
  /** Each replica's state before the final delivery. */
  readonly partial: readonly Board[];
  /** Per replica, the op indices it has not yet seen. */
  readonly unseen: readonly (readonly number[])[];
  readonly collisions: number;
}

export function generate(s: Scenario): Generated {
  const reps = s.skews.map((_, i) => new Replica(idFor("a0000000", i, s.seed)));
  const ops: Op[] = [];
  const opIndex = new Map<string, number>();
  const board = idFor("b0a4d000", 0, s.seed);
  let t = 0;
  let collisions = 0;
  let nextList = 0;
  let nextCard = 0;

  const learn = (rep: Replica, op: Op) => {
    if (op.type === "ADD_LIST") rep.lists.push(op.list_id);
    if (op.type === "ADD_CARD") rep.cards.push(op.card_id);
  };
  const deliver = (rep: Replica, i: number, now: number) => {
    const op = ops[i]!;
    collisions += rep.board.apply(op);
    if (!rep.known.has(i)) learn(rep, op);
    rep.known.add(i);
    rep.hlc = receive(rep.hlc, op.hlc, now);
  };

  s.steps.forEach((step, stepNo) => {
    t += step.dt;
    const rep = reps[step.r % reps.length]!;
    const now = T0 + t + s.skews[step.r % reps.length]!;
    const rnd = prng(step.x ^ s.seed);
    const pick = <T>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)]!;

    // 1. Receive some unseen ops (sometimes one already seen: a duplicate).
    if (step.pull > 0) {
      const unseen = ops.map((_, i) => i).filter((i) => !rep.known.has(i));
      for (let k = 0; k < step.pull && unseen.length > 0; k++) deliver(rep, unseen.splice(Math.floor(rnd() * unseen.length), 1)[0]!, now);
      if (rep.known.size > 0 && rnd() < 0.2) deliver(rep, pick([...rep.known]), now);
    }

    // 2. Make ops from this replica's own view.
    const emit = (body: Record<string, unknown>) => {
      rep.hlc = tick(rep.hlc, now);
      const op = {
        op_id: idFor("0e000000", ops.length, s.seed),
        board_id: board,
        hlc: rep.hlc,
        replica_id: rep.hlc.replica_id,
        ...body,
      } as unknown as Op;
      const check = checkShape(op);
      if (!check.ok) throw new Error(`generator bug at step ${stepNo}: ${check.reason}: ${JSON.stringify(op)}`);
      opIndex.set(op.op_id, ops.length);
      ops.push(op);
      deliver(rep, ops.length - 1, now);
    };

    const view = rep.board.view();
    const listView = (id: string) => view.lists.find((l) => l.id === id);
    const cardsIn = (listId: string, except?: string) =>
      (listView(listId)?.cards ?? []).filter((c) => c.id !== except);
    const hot = () => (rnd() < 0.7 && rep.cards.length > 0 ? rep.cards[0]! : pick(rep.cards));
    const insertIndex = (n: number) =>
      s.sameGap ? Math.min(1, n) : step.z % 3 === 0 ? n : step.y % (n + 1); // bias to append

    /** Places `card` at an insert point of `listId`, re-keying neighbours as §3.7 says. */
    const place = (card: string, listId: string): Record<string, unknown> => {
      let cards = cardsIn(listId, card);
      let plan = planInsert(cards.map((c) => c.position_key), insertIndex(cards.length));
      if (plan.key.length > REBALANCE_AT || plan.rekeys.some((r) => r.key.length > REBALANCE_AT)) {
        const fresh = rebalance(cards.map((c) => c.position_key));
        cards.forEach((c, i) => emit({ type: "SET_PLACEMENT", entity_id: c.id, placement: { list_id: listId, position_key: fresh[i] } }));
        cards = cards.map((c, i) => ({ ...c, position_key: fresh[i]! }));
        plan = planInsert(cards.map((c) => c.position_key), insertIndex(cards.length));
      }
      for (const r of plan.rekeys) {
        emit({ type: "SET_PLACEMENT", entity_id: cards[r.index]!.id, placement: { list_id: listId, position_key: r.key } });
      }
      return { list_id: listId, position_key: plan.key };
    };
    const placeList = (except?: string): string => {
      let lists = view.lists.filter((l) => "position_key" in l && l.id !== except) as { id: string; position_key: string }[];
      let plan = planInsert(lists.map((l) => l.position_key), insertIndex(lists.length));
      if (plan.key.length > REBALANCE_AT || plan.rekeys.some((r) => r.key.length > REBALANCE_AT)) {
        const fresh = rebalance(lists.map((l) => l.position_key));
        lists.forEach((l, i) => emit({ type: "SET_PLACEMENT", entity_id: l.id, placement: { position_key: fresh[i] } }));
        lists = lists.map((l, i) => ({ ...l, position_key: fresh[i]! }));
        plan = planInsert(lists.map((l) => l.position_key), insertIndex(lists.length));
      }
      for (const r of plan.rekeys) emit({ type: "SET_PLACEMENT", entity_id: lists[r.index]!.id, placement: { position_key: r.key } });
      return plan.key;
    };

    let kind = step.kind;
    if (s.sameGap && kind >= 6) kind = 6 + (kind % 20); // only ADD_CARD
    if (rep.lists.length === 0) kind = 0;
    else if (rep.cards.length === 0 && kind >= 26) kind = 6;

    if (kind < 6) {
      const id = idFor("11000000", nextList++, s.seed);
      emit({ type: "ADD_LIST", list_id: id, placement: { position_key: placeList() }, title: pick(TITLES) });
    } else if (kind < 26) {
      const id = idFor("cc000000", nextCard++, s.seed);
      const listId = s.sameGap || rnd() < 0.6 ? rep.lists[0]! : pick(rep.lists);
      emit({ type: "ADD_CARD", card_id: id, placement: place(id, listId), title: pick(TITLES) });
    } else if (kind < 46) {
      const card = hot();
      emit({ type: "SET_PLACEMENT", entity_id: card, placement: place(card, s.sameGap ? rep.lists[0]! : pick(rep.lists)) });
    } else if (kind < 56) {
      // Reorder within the card's current list (or move it out of Orphaned).
      const card = hot();
      const home = view.lists.find((l) => l.id !== NIL_UUID && l.cards.some((c) => c.id === card))?.id ?? pick(rep.lists);
      emit({ type: "SET_PLACEMENT", entity_id: card, placement: place(card, home) });
    } else if (kind < 68) {
      const field = pick(["title", "description", "assignee", "labels"] as const);
      const value =
        field === "title" ? pick(TITLES)
        : field === "description" ? pick(DESCRIPTIONS)
        : field === "assignee" ? pick([null, idFor("ee000000", 1, 0), idFor("ee000000", 2, 0)])
        : pick(LABELS);
      emit({ type: "SET_FIELD", entity_id: hot(), field, value });
    } else if (kind < 78) {
      const card = hot();
      emit({ type: "DELETE_CARD", card_id: card, observed_tags: rep.board.liveTags("card", card) });
    } else if (kind < 86) {
      const deleted = rep.cards.filter((c) => rep.board.liveTags("card", c).length === 0);
      emit({ type: "RESTORE_CARD", card_id: deleted.length > 0 && rnd() < 0.8 ? pick(deleted) : hot() });
    } else if (kind < 91) {
      const list = pick(rep.lists);
      emit({ type: "DELETE_LIST", list_id: list, observed_tags: rep.board.liveTags("list", list) });
    } else if (kind < 95) {
      const deleted = rep.lists.filter((l) => rep.board.liveTags("list", l).length === 0);
      emit({ type: "RESTORE_LIST", list_id: deleted.length > 0 ? pick(deleted) : pick(rep.lists) });
    } else if (kind < 98) {
      const list = pick(rep.lists);
      emit({ type: "SET_PLACEMENT", entity_id: list, placement: { position_key: placeList(list) } });
    } else {
      emit({ type: "SET_FIELD", entity_id: pick(rep.lists), field: "title", value: pick(TITLES) });
    }
  });

  return {
    ops,
    partial: reps.map((r) => r.board.clone()),
    unseen: reps.map((r) => ops.map((_, i) => i).filter((i) => !r.known.has(i))),
    collisions,
  };
}

/** A random delivery order of `indices`, with ~20% of them delivered twice. */
export function deliveryOrder(indices: readonly number[], seed: number): number[] {
  const rnd = prng(seed);
  const out = [...indices];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1));
    [out[i], out[j]] = [out[j]!, out[i]!];
  }
  for (const i of indices) if (rnd() < 0.2) out.splice(Math.floor(rnd() * (out.length + 1)), 0, i);
  return out;
}

export interface Outcome {
  readonly ops: readonly Op[];
  /** Canonical state of every replica after full delivery, generators first. */
  readonly states: readonly string[];
  /** Canonical state of the merge of all pre-delivery states. */
  readonly merged: string;
  readonly collisions: number;
}

export const FRESH_REPLICAS = 2;

export function run(s: Scenario): Outcome {
  const g = generate(s);
  let collisions = g.collisions;
  const states: string[] = [];
  g.partial.forEach((p, r) => {
    const b = p.clone();
    for (const i of deliveryOrder(g.unseen[r]!, s.seed + r + 1)) collisions += b.apply(g.ops[i]!);
    states.push(b.canonical());
  });
  const all = g.ops.map((_, i) => i);
  for (let f = 0; f < FRESH_REPLICAS; f++) {
    const b = Board.empty();
    for (const i of deliveryOrder(all, s.seed + 1000 + f)) collisions += b.apply(g.ops[i]!);
    states.push(b.canonical());
  }
  let merged = Board.empty();
  for (const p of g.partial) merged = Board.merge(merged, p).board;
  return { ops: g.ops, states, merged: merged.canonical(), collisions };
}

/** Fresh replicas fed `ops` in `orders` random orders: do they agree, with no tie-breaks? */
export function converges(ops: readonly Op[], orders: readonly number[]): boolean {
  let first: string | undefined;
  for (const seed of orders) {
    const b = Board.empty();
    for (const i of deliveryOrder(ops.map((_, k) => k), seed)) if (b.apply(ops[i]!) !== 0) return false;
    const c = b.canonical();
    if (first === undefined) first = c;
    else if (c !== first) return false;
  }
  return true;
}

/** Delta debugging (ddmin) over the op list: a smallest subset that still fails `converges`. */
export function minimize(ops: readonly Op[], orders: readonly number[]): Op[] {
  let cur = [...ops];
  let n = 2;
  while (cur.length >= 2) {
    const size = Math.ceil(cur.length / n);
    let reduced = false;
    for (let i = 0; i < cur.length; i += size) {
      const without = [...cur.slice(0, i), ...cur.slice(i + size)];
      if (!converges(without, orders)) {
        cur = without;
        n = Math.max(n - 1, 2);
        reduced = true;
        break;
      }
    }
    if (!reduced) {
      if (n >= cur.length) break;
      n = Math.min(n * 2, cur.length);
    }
  }
  return cur;
}

/** `n` scenarios drawn deterministically from `seed` (for harnesses without their own fast-check). */
export function sampleScenarios(n: number, seed: number, minSteps = 50, maxSteps = 500, sameGap?: boolean): Scenario[] {
  return fc.sample(scenarioArb(minSteps, maxSteps, sameGap), { numRuns: n, seed });
}
