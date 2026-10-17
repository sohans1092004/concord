// SPEC §10 Phase 3 exit criterion and §7: offline editing, reload while offline, tabs.
import { expect, test } from "@playwright/test";
import { addMember, converged, createBoard, hash, joined, openBoard, pending, settled, signUp, unsent, version } from "./helpers";
import { randomEdits } from "./workload";

test("exit criterion: one browser offline for 200+ edits with a reload, both converge, nothing unsent", async ({ browser }) => {
  test.setTimeout(600_000);
  const a = await browser.newContext(), b = await browser.newContext();
  await signUp(a, "Online Olga");
  const bob = await signUp(b, "Offline Omar");
  const board = await createBoard(a, "Offline trial");
  await addMember(a, board, bob.email);
  const pa = await a.newPage(), pb = await b.newPage();
  await openBoard(pa, board);
  await randomEdits(pa, 30, 1);
  await settled(pa);
  await openBoard(pb, board);
  await converged(pa, pb);

  // Omar goes offline. The indicator says so; his edits wait on his device.
  await b.setOffline(true);
  await expect(pb.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  await expect(pb.getByTestId("status")).toContainText("Offline");

  // Many edits through the same client-core calls the UI makes, while Olga edits too...
  const omar1 = await randomEdits(pb, 120, 2);
  const olga = await randomEdits(pa, 60, 3);
  // ...and some through the UI itself.
  const firstList = pb.getByTestId("list").first();
  await firstList.getByLabel("New card title").fill("typed while offline");
  await firstList.getByLabel("New card title").press("Enter");
  await pb.getByTestId("card").filter({ hasText: "typed while offline" }).click();
  await pb.getByTestId("card-dialog").getByLabel("Description").fill("written on the train");
  await pb.getByTestId("card-dialog").getByLabel("Close").click();
  // The description is saved on blur, and an edit counts as pending once its IndexedDB append
  // lands (amendment 21): wait for that before counting, or the count can be one short.
  await pb.waitForFunction(() => window.concord!.client.pendingOps().some((o) => o.type === "SET_FIELD" && o.field === "description" && o.value === "written on the train"));
  const before = await pending(pb);
  expect(before).toBeGreaterThanOrEqual(120);
  await expect(pb.getByTestId("pending")).toHaveText(`${before} pending`);

  // A reload while offline: the app shell comes from the service worker, the edits from IndexedDB.
  await pb.reload();
  await pb.waitForFunction(() => window.concord !== undefined);
  expect(await pending(pb)).toBe(before);
  await expect(pb.getByTestId("card").filter({ hasText: "typed while offline" })).toBeVisible();
  const omar2 = await randomEdits(pb, 90, 4);
  const total = await pending(pb);
  expect(total).toBeGreaterThanOrEqual(200);

  // Back online: reconnects on its own, resends, both converge on the same state hash.
  await b.setOffline(false);
  await converged(pa, pb);
  expect(await unsent(pa)).toBe(0);
  expect(await unsent(pb)).toBe(0);
  const h = await hash(pa);
  console.log(`exit criterion: Omar ${total} offline edits (UI 2, ${JSON.stringify(omar1)}, after reload ${JSON.stringify(omar2)}), Olga ${JSON.stringify(olga)}, board version ${await version(pa)}, hash ${h}`);
  await expect(pb.getByTestId("card").filter({ hasText: "typed while offline" })).toHaveCount(await pa.getByTestId("card").filter({ hasText: "typed while offline" }).count());
  await a.close();
  await b.close();
});

test("a reload mid-offline loses nothing (§7.1)", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Reload Rita");
  const board = await createBoard(a, "Reloads");
  const p = await a.newPage();
  await openBoard(p, board);
  await a.setOffline(true);
  await expect(p.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  const ids = await p.evaluate(async () => {
    const c = window.concord!.client;
    const l = c.addList("Offline list");
    const ids = [l, c.addCard(l, "first"), c.addCard(l, "second")];
    await c.saved();
    return ids;
  });
  for (let i = 0; i < 3; i++) {
    await p.reload();
    await p.waitForFunction(() => window.concord !== undefined);
    expect(await pending(p)).toBe(3);
  }
  await expect(p.getByTestId("card")).toHaveText([/first/, /second/]);
  await a.setOffline(false);
  await settled(p);
  const stored = await p.evaluate(() => window.concord!.client.view().lists.flatMap((l) => [l.id, ...l.cards.map((c) => c.id)]));
  expect(stored.sort()).toEqual([...ids].sort());
  await a.close();
});

test("a duplicated tab gets its own replica ID (amendment 17)", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Dup Tab");
  const board = await createBoard(a, "Dup");
  const first = await a.newPage();
  await openBoard(first, board);
  const replica = () => window.concord!.client.replica;
  const id1 = await first.evaluate(replica);
  // Duplicating a tab copies its sessionStorage into the new tab, exactly this:
  const copied = await first.evaluate(() => JSON.stringify(Object.entries(sessionStorage)));
  const dup = await a.newPage();
  await dup.addInitScript((entries) => {
    if (!sessionStorage.getItem("concord:copied")) {
      for (const [k, v] of JSON.parse(entries) as [string, string][]) sessionStorage.setItem(k, v);
      sessionStorage.setItem("concord:copied", "1");
    }
  }, copied);
  await openBoard(dup, board);
  const id2 = await dup.evaluate(replica);
  expect(await dup.evaluate((b) => sessionStorage.getItem(`concord:replica:${b}`), board)).toBe(id2);
  expect(id2).not.toBe(id1);
  // The original tab keeps its replica across a reload; the duplicate keeps its own.
  await first.reload();
  await joined(first);
  expect(await first.evaluate(replica)).toBe(id1);
  await a.close();
});

test("tabs adopt a closed tab's queue exactly once (§7.1, Web Locks)", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Tab Tess");
  const board = await createBoard(a, "Tabs");
  const closed = await a.newPage();
  await openBoard(closed, board);
  await a.setOffline(true);
  await expect(closed.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  const made = await closed.evaluate(async () => {
    const c = window.concord!.client;
    const l = c.addList("from the closed tab");
    for (let i = 0; i < 9; i++) c.addCard(l, `orphan ${i}`);
    await c.saved(); // each one durable, then shown
    return c.pendingOps().map((o) => o.op_id);
  });
  await closed.close(); // its lock is released; its saved queue stays in IndexedDB

  // Four new tabs start at once; each tries to adopt the closed tab's queue.
  const tabs = await Promise.all([0, 1, 2, 3].map(() => a.newPage()));
  await Promise.all(tabs.map((t) => t.goto(`/#/b/${board}`)));
  await Promise.all(tabs.map((t) => t.waitForFunction(() => window.concord !== undefined)));
  const adopted = await Promise.all(tabs.map((t) => t.evaluate(() => window.concord!.storage.adopted.reduce((n, x) => n + x.pending, 0))));
  expect(adopted.filter((n) => n > 0)).toEqual([made.length]); // exactly one tab adopted it, all of it
  const queues = await Promise.all(tabs.map((t) => t.evaluate(() => window.concord!.client.pendingOps().map((o) => o.op_id))));
  expect(queues.flat().sort()).toEqual([...made].sort()); // and no op is in two queues

  await a.setOffline(false);
  for (const t of tabs) await settled(t);
  const ids = await tabs[0]!.evaluate(() => window.concord!.client.view().lists.flatMap((l) => l.cards.map((c) => c.title)));
  expect(ids).toHaveLength(9);
  await a.close();
});

test("edits rejected after access is revoked offline go to Unsent edits, readable and exportable (§7.4)", async ({ browser }) => {
  const a = await browser.newContext(), b = await browser.newContext({ acceptDownloads: true });
  await signUp(a, "Owner Ola");
  const bea = await signUp(b, "Revoked Bea");
  const board = await createBoard(a, "Revocation");
  await addMember(a, board, bea.email);
  const pa = await a.newPage(), pb = await b.newPage();
  await openBoard(pa, board);
  await pa.evaluate(() => window.concord!.client.addList("Shared"));
  await settled(pa);
  await openBoard(pb, board);
  await settled(pb);
  await b.setOffline(true);
  await expect(pb.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  await pb.evaluate(() => {
    const c = window.concord!.client;
    const l = c.view().lists[0]!.id;
    c.addCard(l, "Bea's offline card");
    c.addCard(l, "Another one");
  });
  const { removeMember } = await import("./helpers");
  await removeMember(a, board, bea.id);
  await b.setOffline(false);
  await expect(pb.getByTestId("unsent-count")).toHaveText("2 unsent", { timeout: 60_000 });
  await expect(pb.getByTestId("status")).toContainText("no longer have access");
  await pb.getByTestId("unsent-count").click();
  const items = pb.getByTestId("unsent-item");
  await expect(items).toHaveCount(2);
  await expect(items.first()).toContainText("You added card “Bea's offline card”");
  await expect(items.first()).toContainText("no longer had access");
  const [download] = await Promise.all([pb.waitForEvent("download"), pb.getByRole("button", { name: "Export as JSON" }).click()]);
  const exported = JSON.parse(await (await import("node:fs/promises")).readFile((await download.path())!, "utf8")) as { op: { title: string }; code: string }[];
  expect(exported.map((u) => [u.op.title, u.code])).toEqual([["Bea's offline card", "UNAUTHORIZED"], ["Another one", "UNAUTHORIZED"]]);
  await a.close();
  await b.close();
});

test("an edit on screen survives the tab crashing at once (§7.2 order, amendment 21)", async ({ browser }) => {
  const a = await browser.newContext();
  await signUp(a, "Quick Closer");
  const board = await createBoard(a, "Close fast");
  const first = await a.newPage();
  await openBoard(first, board);
  await first.evaluate(() => window.concord!.client.addList("Inbox"));
  await settled(first);
  await a.setOffline(true); // so the edit can only come back from this device
  await expect(first.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  await first.getByLabel("New card title").fill("typed, then the tab crashed");
  await first.getByLabel("New card title").press("Enter");
  await first.getByTestId("card").filter({ hasText: "typed, then the tab crashed" }).waitFor();
  // A crash the moment it is on screen: take exactly what is on disk now (IndexedDB, cookies,
  // localStorage) into a fresh browser context. No pagehide, no unload, no later snapshot.
  const disk = await a.storageState({ indexedDB: true });
  await a.close();

  const b = await browser.newContext({ storageState: disk });
  // The WebSocket answers nothing, so whatever comes back can only have come from the disk,
  // and stays in the pending queue for us to inspect (online, it would be sent and ACKed).
  await b.routeWebSocket(/\/ws/, () => {});
  const again = await b.newPage();
  await again.goto(`/#/b/${board}`);
  await again.waitForFunction(() => window.concord !== undefined);
  await expect(again.getByTestId("card").filter({ hasText: "typed, then the tab crashed" })).toBeVisible();
  // It came back as an adopted, still-pending edit (the ACKed list op may come too, until a
  // snapshot drops it from the log; resending it is answered by dedup, §4.3).
  const adopted = await again.evaluate(() => window.concord!.client.pendingOps().map((o) => (o as { title?: string }).title));
  expect(adopted).toContain("typed, then the tab crashed");
  await b.close();
});
