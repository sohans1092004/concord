// A random editing session for one client, heavy on the spec's hard cases: concurrent moves
// of one "hot" card, deletes against edits, deletes against restores, inserts into one gap.
import type { SyncClient } from "../../src/index.js";

/** mulberry32: a seeded PRNG so a failing session can be replayed. */
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

const TITLES = ["a", "b", "Ünïcødé", "😀 emoji", "tab\there", ""];

/** Makes one random edit from this client's current view. Returns the op count it submitted. */
export function randomEdit(c: SyncClient, rnd: () => number, deleted: Set<string>): number {
  const before = c.pendingOps().length;
  const pick = <T>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)]!;
  const lists = c.view().lists.filter((l) => l.id !== "00000000-0000-0000-0000-000000000000");
  const cards = c.view().lists.flatMap((l) => l.cards.map((card) => ({ card: card.id, list: l.id })));
  const hot = () => (cards.length > 0 && rnd() < 0.6 ? cards[0]!.card : cards.length > 0 ? pick(cards).card : null);
  const k = rnd();
  if (lists.length === 0 || k < 0.04) {
    c.addList(pick(TITLES));
  } else if (k < 0.3) {
    const l = rnd() < 0.7 ? lists[0]! : pick(lists);
    c.addCard(l.id, pick(TITLES), rnd() < 0.4 ? Math.min(1, l.cards.length) : undefined); // 40%: the same gap
  } else if (k < 0.5) {
    const card = hot();
    if (!card) return 0;
    const l = pick(lists);
    c.moveCard(card, l.id, Math.floor(rnd() * (l.cards.length + 1)));
  } else if (k < 0.68) {
    const card = hot();
    if (!card) return 0;
    const field = pick(["title", "description", "assignee", "labels"] as const);
    const value = field === "labels" ? pick([[], ["bug"], ["bug", "p1"]]) : field === "assignee" ? null : pick(TITLES);
    c.setField(card, field, value);
  } else if (k < 0.8) {
    const card = hot();
    if (!card) return 0;
    c.deleteCard(card);
    deleted.add(card);
  } else if (k < 0.92) {
    if (deleted.size === 0) return 0;
    const card = pick([...deleted]);
    c.restoreCard(card);
    deleted.delete(card);
  } else if (k < 0.96) {
    c.setField(pick(lists).id, "title", pick(TITLES));
  } else if (lists.length > 1) {
    const l = pick(lists);
    if (rnd() < 0.5) c.deleteList(l.id);
    else c.moveList(l.id, Math.floor(rnd() * lists.length));
  }
  return c.pendingOps().length - before;
}
