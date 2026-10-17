// SPEC §3.5: when two people move the same card at once, the higher HLC wins whole, and the
// loser sees a brief "moved by {name}" transition instead of a silent jump.
import { expect, test, type Page } from "@playwright/test";
import { addMember, converged, createBoard, openBoard, signUp } from "./helpers";

/** Watches a page for the "moved by" mark for `ms`; returns every text it showed. */
async function watch(page: Page, ms: number): Promise<string[]> {
  const seen = new Set<string>();
  const until = Date.now() + ms;
  while (Date.now() < until) {
    for (const t of await page.getByTestId("moved-by").allTextContents()) seen.add(t);
    await page.waitForTimeout(50);
  }
  return [...seen];
}

for (let round = 1; round <= 3; round++) {
  test(`concurrent moves of one card: the loser sees "moved by {name}" (round ${round})`, async ({ browser }) => {
    const a = await browser.newContext(), b = await browser.newContext();
    await signUp(a, "Mover Ann");
    const ben = await signUp(b, "Mover Ben");
    const board = await createBoard(a, "Moves");
    await addMember(a, board, ben.email);
    const pa = await a.newPage(), pb = await b.newPage();
    await openBoard(pa, board);
    const [card, , left, right] = await pa.evaluate(() => {
      const c = window.concord!.client;
      const home = c.addList("Home");
      const left = c.addList("Ann's pick");
      const right = c.addList("Ben's pick");
      return [c.addCard(home, "Contested"), home, left, right];
    });
    await openBoard(pb, board);
    await converged(pa, pb);

    // Both move it at the same moment, to different lists.
    await Promise.all([
      pa.evaluate(([card, to]) => window.concord!.client.moveCard(card!, to!, 0), [card, left]),
      pb.evaluate(([card, to]) => window.concord!.client.moveCard(card!, to!, 0), [card, right]),
    ]);
    const [sawA, sawB] = await Promise.all([watch(pa, 3_000), watch(pb, 3_000)]);
    await converged(pa, pb);

    const where = (p: Page) => p.evaluate((card) => window.concord!.client.view().lists.find((l) => l.cards.some((c) => c.id === card))!.id, card);
    const final = await where(pa);
    expect(await where(pb)).toBe(final);
    expect([left, right]).toContain(final);
    // Exactly the loser was told, by name; the winner saw nothing.
    if (final === left) {
      expect(sawB).toEqual(["moved by Mover Ann"]);
      expect(sawA).toEqual([]);
    } else {
      expect(sawA).toEqual(["moved by Mover Ben"]);
      expect(sawB).toEqual([]);
    }
    console.log(`round ${round}: ${final === left ? "Ann" : "Ben"} won; loser saw ${JSON.stringify(final === left ? sawB : sawA)}`);
    // It is brief: gone within a few seconds.
    const loser = final === left ? pb : pa;
    await expect(loser.getByTestId("moved-by")).toHaveCount(0, { timeout: 6_000 });
    await a.close();
    await b.close();
  });
}
