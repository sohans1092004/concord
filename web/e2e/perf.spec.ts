// SPEC §1.2, §10.1 "Large board": 2,000 cards and 10 editors; the client renders each update in
// under 16ms. The editors are client-core instances in this Node process (the same sync code
// the browser runs), each making one edit a second; the browser under test only watches. Each
// update is timed in the page with the Performance API (`concord:update`: client-core change
// to React's DOM commit; web/src/useBoard.ts).
import { MemoryStorage, SyncClient } from "@concord/client-core";
import { expect, test } from "@playwright/test";
import { BASE, createBoard, openBoard, signUp } from "./helpers";

const GATEWAY = "http://localhost:8081";
const EDITORS = 10, CARDS = 2_000, SECONDS = Number(process.env.PERF_SECONDS ?? 60);

async function editorSession(i: number): Promise<{ id: string; email: string; session: string }> {
  const email = `perf-${i}-${crypto.randomUUID().slice(0, 8)}@trial.example`;
  const r = await fetch(`${GATEWAY}/api/auth/sign-up`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, name: `Editor ${i}`, password: "correct horse battery" }),
  });
  expect(r.status).toBe(201);
  const session = /concord_session=([^;]+)/.exec(r.headers.get("set-cookie") ?? "")![1]!;
  return { ...((await r.json()) as { id: string }), email, session };
}

function editor(board: string, session: string): Promise<SyncClient> {
  return SyncClient.open({
    urls: ["ws://localhost:8081/ws", "ws://localhost:8082/ws"], boardId: board, storage: new MemoryStorage(),
    ticket: async (url) => {
      const r = await fetch(url.replace(/^ws/, "http").replace(/\/ws$/, "/api/ws-ticket"), {
        method: "POST", headers: { Authorization: `Bearer ${session}`, "Content-Type": "application/json" }, body: "{}",
      });
      return ((await r.json()) as { ticket: string }).ticket;
    },
  });
}

const settled = (c: SyncClient, ms = 300_000) => c.settled(ms);

function stats(xs: number[]) {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p: number) => s[Math.min(s.length - 1, Math.floor(p * s.length))]!;
  return { n: s.length, p50: q(0.5), p95: q(0.95), p99: q(0.99), max: s[s.length - 1]!, over16: s.filter((x) => x >= 16).length };
}

test("2,000 cards, 10 editors: each update renders in under 16ms", async ({ browser }) => {
  test.setTimeout(900_000);
  const ctx = await browser.newContext();
  await signUp(ctx, "Perf Watcher");
  const board = await createBoard(ctx, "Large board");
  const people = await Promise.all(Array.from({ length: EDITORS }, (_, i) => editorSession(i)));
  for (const p of people) {
    const r = await ctx.request.post(`/api/boards/${board}/members`, { data: { email: p.email, role: "editor" }, headers: { Origin: BASE } });
    expect(r.status()).toBe(201);
  }
  const editors = await Promise.all(people.map((p) => editor(board, p.session)));
  await Promise.all(editors.map((e) => e.until(() => e.status === "joined", 60_000)));

  // Seed: 10 lists, then 200 cards per editor (the gateway allows 20 ops/s per connection).
  const seedStart = Date.now();
  const lists = Array.from({ length: EDITORS }, (_, i) => editors[0]!.addList(`List ${i}`));
  await settled(editors[0]!);
  await Promise.all(editors.map((e) => e.until(() => e.view().lists.length >= EDITORS, 60_000)));
  await Promise.all(editors.map(async (e, i) => {
    for (let k = 0; k < CARDS / EDITORS; k++) {
      e.addCard(lists[i]!, `card ${i}-${k}`);
      await new Promise((r) => setTimeout(r, 60)); // under the gateway's 20 ops/s
    }
    await settled(e);
  }));
  const seedMs = Date.now() - seedStart;

  const page = await ctx.newPage();
  await openBoard(page, board);
  await page.waitForFunction((n) => window.concord!.client.view().lists.reduce((s, l) => s + l.cards.length, 0) === n, CARDS, { timeout: 120_000 });
  await page.waitForTimeout(2_000);
  await page.evaluate(() => {
    performance.clearMeasures("concord:update");
    (window as unknown as { longTasks: number[] }).longTasks = [];
    new PerformanceObserver((l) => {
      for (const e of l.getEntries()) (window as unknown as { longTasks: number[] }).longTasks.push(e.duration);
    }).observe({ type: "longtask" });
  });

  if (process.env.PERF_PROBE) {
    const probe = await page.evaluate(() => {
      const c = window.concord!.client as unknown as Record<string, unknown> & { view(): unknown };
      const t = (f: () => unknown) => { const s = performance.now(); for (let i = 0; i < 5; i++) f(); return (performance.now() - s) / 5; };
      const confirmed = c.confirmed as { encode(): unknown; clone(): { view(): unknown } };
      return {
        view_rebuild_ms: t(() => ((c.viewCache = null), c.view())),
        clone_ms: t(() => confirmed.clone()),
        view_only_ms: t(() => confirmed.clone().view()),
        encode_ms: t(() => confirmed.encode()),
        structured_clone_ms: t(() => structuredClone(confirmed.encode())),
      };
    });
    console.log("probe:", JSON.stringify(probe));
  }

  if (process.env.PERF_CPU) await (await ctx.newCDPSession(page)).send("Emulation.setCPUThrottlingRate", { rate: Number(process.env.PERF_CPU) });
  const cdp = process.env.PERF_PROFILE ? await ctx.newCDPSession(page) : null;
  if (cdp) {
    await cdp.send("Profiler.enable");
    await cdp.send("Profiler.setSamplingInterval", { interval: 100 });
    await cdp.send("Profiler.start");
  }

  // Ten editors, one edit a second each: titles, moves within and across lists.
  let sent = 0;
  const rnd = (n: number) => Math.floor(Math.random() * n);
  const stop = Date.now() + SECONDS * 1_000;
  await Promise.all(editors.map(async (e, i) => {
    await new Promise((r) => setTimeout(r, i * 100)); // spread them across the second
    while (Date.now() < stop) {
      const view = e.view().lists;
      const from = view[rnd(view.length)]!;
      const card = from.cards[rnd(from.cards.length)];
      if (card) {
        const r = Math.random();
        if (r < 0.5) e.setField(card.id, "title", `edited ${i} ${Date.now()}`);
        else if (r < 0.8) e.moveCard(card.id, from.id, rnd(from.cards.length));
        else {
          const to = view[rnd(view.length)]!;
          if (to.id !== "00000000-0000-0000-0000-000000000000") e.moveCard(card.id, to.id, rnd(to.cards.length + 1));
        }
        sent++;
      }
      await new Promise((r) => setTimeout(r, 1_000));
    }
  }));
  await Promise.all(editors.map((e) => settled(e)));
  await page.waitForTimeout(2_000);
  if (cdp) {
    const { profile } = await cdp.send("Profiler.stop");
    const self = new Map<string, number>();
    const dt = profile.timeDeltas ?? [];
    const byId = new Map(profile.nodes.map((n) => [n.id, n]));
    (profile.samples ?? []).forEach((id, i) => {
      const n = byId.get(id)!;
      const k = `${n.callFrame.functionName || "(anon)"} ${n.callFrame.url.split("/").pop()}:${n.callFrame.lineNumber}`;
      self.set(k, (self.get(k) ?? 0) + (dt[i] ?? 0) / 1000);
    });
    for (const [k, v] of [...self].sort((a, b) => b[1] - a[1]).slice(0, 25)) console.log(`profile self ms ${v.toFixed(0).padStart(6)} ${k}`);
  }

  const { updates, longTasks, cards } = await page.evaluate(() => ({
    updates: performance.getEntriesByName("concord:update").map((m) => m.duration),
    longTasks: (window as unknown as { longTasks: number[] }).longTasks,
    cards: window.concord!.client.view().lists.reduce((s, l) => s + l.cards.length, 0),
  }));
  const s = stats(updates);
  console.log(`perf: ${cards} cards, ${EDITORS} editors, ${SECONDS}s, ${sent} edits sent, seed ${seedMs}ms; ` +
    `update render ms: n=${s.n} p50=${s.p50.toFixed(2)} p95=${s.p95.toFixed(2)} p99=${s.p99.toFixed(2)} max=${s.max.toFixed(2)} ` +
    `(${s.over16} of ${s.n} at or over 16ms); long tasks: ${longTasks.length} (${longTasks.map((d) => d.toFixed(0)).join(", ")})`);
  for (const e of editors) e.close();
  expect(cards).toBe(CARDS);
  expect(s.n).toBeGreaterThan(sent / 2); // updates were actually measured (React batches some)
  expect(s.max, "every update must render in under 16ms").toBeLessThan(16);
  await ctx.close();
});
