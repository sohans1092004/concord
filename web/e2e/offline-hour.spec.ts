// The scripted, real-time offline hour (SPEC §10 Phase 3): browser B is offline for 60 real
// minutes, editing at a person's pace, with a reload at 15 minutes and a tab closed and
// reopened at 30, while A keeps editing online. Then B reconnects on its own and both must
// converge with nothing unsent. Run on demand only (it takes an hour):
//   OFFLINE_HOUR=1 npx playwright test e2e/offline-hour.spec.ts
// The result is recorded in docs/offline-hour-scripted.md, labelled as scripted. The manual
// run is docs/manual-offline-run.md.
import { expect, test } from "@playwright/test";
import { addMember, converged, createBoard, hash, openBoard, pending, signUp, unsent, version } from "./helpers";
import { randomEdits } from "./workload";

const MINUTES = Number(process.env.OFFLINE_MINUTES ?? 60);

// No trace: an hour-long trace is large, and other Playwright runs in this directory clear
// test-results/ under it (the first recorded run failed in teardown on exactly that).
test.use({ trace: "off" });

test("offline for an hour, in real time", async ({ browser }) => {
  test.skip(!process.env.OFFLINE_HOUR, "an hour long: run with OFFLINE_HOUR=1");
  test.setTimeout((MINUTES + 20) * 60_000);
  const log = (s: string) => console.log(`${new Date().toISOString()} ${s}`);
  const a = await browser.newContext(), b = await browser.newContext();
  await signUp(a, "Hour Online");
  const bee = await signUp(b, "Hour Offline");
  const board = await createBoard(a, "Offline hour");
  await addMember(a, board, bee.email);
  const pa = await a.newPage();
  let pb = await b.newPage();
  await openBoard(pa, board);
  await randomEdits(pa, 20, 100);
  await openBoard(pb, board);
  await converged(pa, pb);

  await b.setOffline(true);
  await expect(pb.getByTestId("status")).toHaveAttribute("data-status", /offline|connecting/, { timeout: 30_000 });
  const start = Date.now();
  log(`B offline; board ${board}`);
  let bEdits = 0, aEdits = 0, minute = -1;
  const reloaded = { at15: false, at30: false };
  while (Date.now() - start < MINUTES * 60_000) {
    const m = Math.floor((Date.now() - start) / 60_000);
    if (m >= MINUTES / 4 && !reloaded.at15) {
      const before = await pending(pb);
      await pb.reload();
      await pb.waitForFunction(() => window.concord !== undefined);
      expect(await pending(pb)).toBe(before);
      log(`reload at minute ${m}: ${before} pending before, ${await pending(pb)} after`);
      reloaded.at15 = true;
    }
    if (m >= MINUTES / 2 && !reloaded.at30) {
      const before = await pending(pb);
      await pb.close();
      pb = await b.newPage();
      await pb.goto(`/#/b/${board}`);
      await pb.waitForFunction(() => window.concord !== undefined);
      const adopted = await pb.evaluate(() => window.concord!.storage.adopted.reduce((n, x) => n + x.pending, 0));
      expect(await pending(pb)).toBe(before);
      log(`tab closed and reopened at minute ${m}: ${before} pending, adopted ${adopted}`);
      reloaded.at30 = true;
    }
    await randomEdits(pb, 1, 1_000 + bEdits);
    bEdits++;
    if (bEdits % 2 === 0) {
      await randomEdits(pa, 1, 5_000 + aEdits);
      aEdits++;
    }
    if (m !== minute && m % 10 === 0) log(`minute ${m}: B ${bEdits} edits (${await pending(pb)} pending), A ${aEdits} edits`);
    minute = m;
    await pb.waitForTimeout(15_000); // one edit every 15s on B, every 30s on A
  }
  const waiting = await pending(pb);
  log(`hour over: B made ${bEdits} edits offline, ${waiting} pending; reconnecting`);
  const back = Date.now();
  await b.setOffline(false);
  await converged(pa, pb);
  const reconnectMs = Date.now() - back;
  const h = await hash(pa);
  expect(await hash(pb)).toBe(h);
  expect(await unsent(pa)).toBe(0);
  expect(await unsent(pb)).toBe(0);
  log(`converged ${reconnectMs}ms after reconnecting; version ${await version(pa)}; hash ${h}; unsent 0 and 0`);
  await a.close();
  await b.close();
});
