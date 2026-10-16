// Board state: two OR-Sets (lists, cards) and one LWW map (SPEC §3, §4.4).
// Canonical encoding §9.2.3; view §3.6 and §9.2.4.

import { canonicalize, compareUtf8, type Json } from "./canonical.js";
import { isUuid } from "./hlc.js";
import { Lww, type LwwJson } from "./lww.js";
import { NIL_UUID, type Op } from "./op.js";
import { OrSet, type OrSetJson } from "./orset.js";

export type BoardJson = {
  readonly cards: OrSetJson;
  readonly lists: OrSetJson;
  readonly registers: LwwJson;
};

export type CardView = {
  readonly id: string;
  readonly position_key: string;
  readonly title?: Json;
  readonly description?: Json;
  readonly assignee?: Json;
  readonly labels?: Json;
};
export type ListView =
  | { readonly id: string; readonly position_key: string; readonly title?: Json; readonly cards: readonly CardView[] }
  | { readonly id: typeof NIL_UUID; readonly cards: readonly CardView[] };
export type View = { readonly lists: readonly ListView[] };

const CARD_FIELDS = ["title", "description", "assignee", "labels"] as const;

function fail(why: string): never {
  throw new Error(`board: ${why}`);
}

export class Board {
  private constructor(
    private readonly lists: OrSet,
    private readonly cards: OrSet,
    private readonly registers: Lww,
  ) {}

  static empty(): Board {
    return new Board(new OrSet(), new OrSet(), new Lww());
  }

  /** Applies a validated op. Returns the number of LWW tie-breaks that fired (0 or 1). */
  apply(op: Op): number {
    const r = this.registers;
    switch (op.type) {
      case "ADD_LIST":
        this.lists.add(op.list_id, op.op_id);
        return r.set(op.list_id, "title", op.title, op.hlc) + r.set(op.list_id, "placement", op.placement, op.hlc);
      case "ADD_CARD":
        this.cards.add(op.card_id, op.op_id);
        return r.set(op.card_id, "title", op.title, op.hlc) + r.set(op.card_id, "placement", op.placement, op.hlc);
      case "SET_FIELD":
        return r.set(op.entity_id, op.field, op.value, op.hlc);
      case "SET_PLACEMENT":
        return r.set(op.entity_id, "placement", op.placement, op.hlc);
      case "DELETE_LIST":
        this.lists.remove(op.list_id, op.observed_tags);
        return 0;
      case "DELETE_CARD":
        this.cards.remove(op.card_id, op.observed_tags);
        return 0;
      case "RESTORE_LIST":
        this.lists.add(op.list_id, op.op_id);
        return 0;
      case "RESTORE_CARD":
        this.cards.add(op.card_id, op.op_id);
        return 0;
    }
  }

  static merge(a: Board, b: Board): { board: Board; collisions: number } {
    const board = a.clone();
    return { board, collisions: board.mergeFrom(b) };
  }

  /** In-place merge; returns the collision count. */
  mergeFrom(other: Board): number {
    this.lists.mergeFrom(other.lists);
    this.cards.mergeFrom(other.cards);
    return this.registers.mergeFrom(other.registers);
  }

  clone(): Board {
    return new Board(this.lists.clone(), this.cards.clone(), this.registers.clone());
  }

  /** Live tags of a list or card: the `observed_tags` a DELETE from this replica carries. */
  liveTags(kind: "list" | "card", id: string): string[] {
    return (kind === "list" ? this.lists : this.cards).liveTags(id);
  }

  view(): View {
    const placementKey = (id: string): string | undefined => {
      const p = this.registers.get(id, "placement")?.value as { position_key: string } | undefined;
      return p?.position_key;
    };
    const byKeyThenId = <T extends { id: string; position_key: string }>(xs: T[]): T[] =>
      xs.sort((x, y) => compareUtf8(x.position_key, y.position_key) || compareUtf8(x.id, y.id));

    const lists = new Map<string, { id: string; position_key: string; title?: Json; cards: CardView[] }>();
    for (const id of this.lists.ids()) {
      const key = placementKey(id);
      if (key === undefined || !this.lists.contains(id)) continue;
      const title = this.registers.get(id, "title");
      lists.set(id, { id, position_key: key, ...(title && { title: title.value }), cards: [] });
    }

    const orphaned: CardView[] = [];
    for (const id of this.cards.ids()) {
      const key = placementKey(id);
      if (key === undefined || !this.cards.contains(id)) continue;
      const card: Record<string, Json> = { id, position_key: key };
      for (const f of CARD_FIELDS) {
        const reg = this.registers.get(id, f);
        if (reg) card[f] = reg.value;
      }
      const listId = (this.registers.get(id, "placement")!.value as { list_id?: string }).list_id;
      const home = listId === undefined ? undefined : lists.get(listId);
      (home ? home.cards : orphaned).push(card as CardView);
    }

    const out: ListView[] = byKeyThenId([...lists.values()]);
    for (const l of out) byKeyThenId(l.cards as CardView[]);
    if (orphaned.length > 0) out.push({ id: NIL_UUID, cards: byKeyThenId(orphaned) });
    return { lists: out };
  }

  encode(): BoardJson {
    return { cards: this.cards.encode(), lists: this.lists.encode(), registers: this.registers.encode() };
  }

  /** The canonical bytes (as a string) whose SHA-256 is the state hash. */
  canonical(): string {
    return canonicalize(this.encode());
  }

  static decode(x: unknown): Board {
    if (typeof x !== "object" || x === null || Array.isArray(x)) fail("not an object");
    const o = x as Record<string, unknown>;
    const keys = Object.keys(o).sort();
    if (keys.join() !== "cards,lists,registers") fail("members must be cards, lists, registers");
    const registers = Lww.decode(o.registers);
    // The view reads placements, so they must have the §3.5 shape.
    for (const [entity, fields] of Object.entries(registers.encode())) {
      const p = fields.placement;
      if (!p) continue;
      const v = p.value as Record<string, unknown> | null;
      const ok =
        typeof v === "object" && v !== null && !Array.isArray(v) &&
        typeof v.position_key === "string" &&
        Object.keys(v).every((k) => k === "position_key" || (k === "list_id" && isUuid(v.list_id)));
      if (!ok) fail(`${entity}.placement has the wrong shape`);
    }
    return new Board(OrSet.decode(o.lists), OrSet.decode(o.cards), registers);
  }
}
