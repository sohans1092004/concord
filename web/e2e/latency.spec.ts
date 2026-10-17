// Edit-to-screen latency (decision 3, Phase 3 follow-up): from the user's intent (a client-core
// call, as the UI makes) to the edit being in the DOM. Measured on a 200-card board, 100 title
// edits, with the Performance API. Records the numbers; the bound is only a sanity check.
import { expect, test } from "@playwright/test";
import { createBoard, openBoard, settled, signUp } from "./helpers";

test("edit-to-screen latency, 100 title edits on a 200-card board", async ({ browser }) => {
  test.setTimeout(300_000);
  const ctx = await browser.newContext();
  await signUp(ctx, "Latency Lu");
  const board = await createBoard(ctx, "Latency");
  const page = await ctx.newPage();
  await openBoard(page, board);
  await page.evaluate(async () => {
    const c = window.concord!.client;
    const lists = [c.addList("A"), c.addList("B")];
    for (let i = 0; i < 200; i++) {
      c.addCard(lists[i % 2]!, `card ${i}`);
      if (i % 15 === 14) await new Promise((r) => setTimeout(r, 1_000)); // under the gateway's rate limit
    }
  });
  await settled(page);
  await page.waitForTimeout(1_000);
  const samples = await page.evaluate(async () => {
    const c = window.concord!.client;
    const cards = c.view().lists.flatMap((l) => l.cards.map((x) => x.id));
    const out: number[] = [];
    for (let i = 0; i < 100; i++) {
      const id = cards[(i * 7) % cards.length]!;
      const text = `edited ${i}`;
      const el = document.querySelector(`[data-card-id="${id}"] .card-title`)!;
      const shown = new Promise<number>((resolve) => {
        const mo = new MutationObserver(() => {
          if (el.textContent === text) {
            mo.disconnect();
            resolve(performance.now());
          }
        });
        mo.observe(el, { childList: true, characterData: true, subtree: true });
      });
      const start = performance.now();
      performance.mark("concord:intent");
      c.setField(id, "title", text);
      const end = await shown;
      performance.measure("concord:edit-to-screen", { start, end });
      out.push(end - start);
      await new Promise((r) => setTimeout(r, 120)); // a person's pace, under the rate limit
    }
    return out;
  });
  const s = [...samples].sort((a, b) => a - b);
  const q = (p: number) => s[Math.min(s.length - 1, Math.floor(p * s.length))]!;
  console.log(`edit-to-screen ms: n=${s.length} p50=${q(0.5).toFixed(2)} p95=${q(0.95).toFixed(2)} p99=${q(0.99).toFixed(2)} max=${s.at(-1)!.toFixed(2)}`);
  expect(q(0.95)).toBeLessThan(100); // sanity only: the numbers are the result
  await settled(page);
  await ctx.close();
});
