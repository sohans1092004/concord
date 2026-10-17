// A seeded random editor that runs inside the page, through the same client-core calls the UI
// makes. Heavy on what offline merging must get right: moves, deletes, restores, concurrent
// edits of the same cards.
import type { Page } from "@playwright/test";

export interface Tally {
  [type: string]: number;
}

/** Makes `n` edits in the page; returns how many of each kind. */
export function randomEdits(page: Page, n: number, seed: number): Promise<Tally> {
  return page.evaluate(([n, seed]) => {
    const c = window.concord!.client;
    let s = seed >>> 0;
    const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32);
    const pick = <T,>(xs: readonly T[]): T | undefined => xs[Math.floor(rnd() * xs.length)];
    const tally: Record<string, number> = {};
    const count = (k: string) => (tally[k] = (tally[k] ?? 0) + 1);
    for (let i = 0; i < n; i++) {
      const lists = c.view().lists.filter((l) => l.id !== "00000000-0000-0000-0000-000000000000");
      const cards = c.view().lists.flatMap((l) => l.cards.map((card) => ({ card, list: l.id })));
      const trash = c.trash();
      const r = rnd();
      if (lists.length < 2 || r < 0.05) {
        c.addList(`list ${seed}-${i}`);
        count("add list");
      } else if (cards.length < 5 || r < 0.3) {
        c.addCard(pick(lists)!.id, `card ${seed}-${i}`, Math.floor(rnd() * 4));
        count("add card");
      } else if (r < 0.5) {
        const to = pick(lists)!;
        c.moveCard(pick(cards)!.card.id, to.id, Math.floor(rnd() * (to.cards.length + 1)));
        count("move");
      } else if (r < 0.65) {
        c.setField(pick(cards)!.card.id, "title", `edited ${seed}-${i}`);
        count("edit title");
      } else if (r < 0.72) {
        c.setField(pick(cards)!.card.id, "labels", [...new Set([pick(["a", "b", "c"])!, pick(["d", "e"])!])].sort());
        count("labels");
      } else if (r < 0.85) {
        c.deleteCard(pick(cards)!.card.id);
        count("delete card");
      } else if (r < 0.95 && trash.some((t) => t.kind === "card")) {
        c.restoreCard(pick(trash.filter((t) => t.kind === "card"))!.id);
        count("restore card");
      } else if (lists.length > 3 && r < 0.97) {
        c.deleteList(pick(lists)!.id);
        count("delete list");
      } else if (trash.some((t) => t.kind === "list")) {
        c.restoreList(pick(trash.filter((t) => t.kind === "list"))!.id);
        count("restore list");
      } else {
        c.moveList(pick(lists)!.id, Math.floor(rnd() * lists.length));
        count("move list");
      }
    }
    return tally;
  }, [n, seed] as const);
}
