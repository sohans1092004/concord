// SPEC §8 scenarios 1, 7, 9, 10 and 12 end to end, against the compose stack. (2–6 are the
// drills in test/drills; 13, a corrupt snapshot, is SnapshotTest in server/session.)
import { describe, expect, it } from "vitest";
import type { Socket } from "../../src/index.js";
import { client, compose, GATEWAYS, psql, seed, sleep, storedOpIds } from "./stack.js";
import { prng, randomEdit } from "./workload.js";

async function settleBoth(board: string, ...xs: Awaited<ReturnType<typeof client>>[]) {
  for (const x of xs) await x.c.settled(120_000);
  const version = Number(psql(`SELECT version FROM boards WHERE id = '${board}'`)[0]);
  for (const x of xs) await x.c.until(() => x.c.confirmedVersion === version, 60_000);
  return { version, hashes: await Promise.all(xs.map((x) => x.c.confirmedHash())) };
}

/** A raw socket for sending frames no well-behaved client would send. */
async function raw(token: string, gateway = 0): Promise<{ ws: WebSocket; inbox: Record<string, unknown>[] }> {
  const ws = new WebSocket(`${GATEWAYS[gateway]}?access_token=${token}`);
  const inbox: Record<string, unknown>[] = [];
  ws.onmessage = (e) => inbox.push(JSON.parse(String(e.data)));
  await new Promise<void>((resolve, reject) => {
    ws.onopen = () => resolve();
    ws.onerror = () => reject(new Error("connect failed"));
  });
  return { ws, inbox };
}

async function reply(inbox: Record<string, unknown>[], test: (m: Record<string, unknown>) => boolean) {
  for (let i = 0; i < 100; i++) {
    const m = inbox.find(test);
    if (m) return m;
    await sleep(100);
  }
  throw new Error("no reply");
}

describe("§8 failure scenarios", () => {
  /** Row 1: a gateway crashes; its clients reconnect to another and resend their queues. */
  it("1: gateway crash", async () => {
    const s = seed(1);
    const a = await client(s.board, s.users[0]!.token, 0); // on gateway1, which dies
    const b = await client(s.board, s.users[1]!.token, 1);
    await a.c.until(() => a.c.status === "joined");
    await b.c.until(() => b.c.status === "joined");
    const ra = prng(1), rb = prng(2), da = new Set<string>(), db = new Set<string>();
    for (let i = 0; i < 30; i++) {
      randomEdit(a.c, ra, da);
      randomEdit(b.c, rb, db);
      await sleep(60);
    }
    compose("kill", "-s", "SIGKILL", "gateway1");
    const killedAt = Date.now();
    for (let i = 0; i < 30; i++) {
      randomEdit(a.c, ra, da); // offline: queued
      randomEdit(b.c, rb, db);
      await sleep(60);
    }
    await a.c.until(() => a.c.status === "joined", 60_000);
    const rejoined = a.events.filter((e) => e.event === "joined").length;
    const { hashes } = await settleBoth(s.board, a, b);
    compose("start", "gateway1");
    console.log(`gateway crash: client back on gateway2 ${((a.events.findLast((e) => e.event === "joined")!.at - killedAt) / 1000).toFixed(1)}s after the kill`);
    expect(rejoined).toBe(2);
    expect(hashes[0]).toBe(hashes[1]);
    const stored = storedOpIds(s.board);
    for (const id of [...a.acked.keys(), ...b.acked.keys()]) expect(stored.has(id)).toBe(true);
    a.c.close();
    b.c.close();
  });

  /** Row 7: a Pub/Sub message is lost; the client sees a gap and sends SYNC_REQUEST. */
  it("7: lost broadcast is detected and recovered", async () => {
    const s = seed(1);
    let dropped = 0;
    // Fault injection: this client's socket silently loses every 5th OP_BROADCAST.
    const lossy = (url: string): Socket => {
      const ws = new WebSocket(url) as unknown as Socket;
      let n = 0;
      const wrapper: Socket = {
        get readyState() {
          return ws.readyState;
        },
        send: (d) => ws.send(d),
        close: (c, r) => ws.close(c, r),
        onopen: null,
        onmessage: null,
        onclose: null,
        onerror: null,
      };
      ws.onopen = (e) => wrapper.onopen?.(e);
      ws.onclose = (e) => wrapper.onclose?.(e);
      ws.onerror = (e) => wrapper.onerror?.(e);
      ws.onmessage = (e) => {
        if (String(e.data).includes('"type":"OP_BROADCAST"') && ++n % 5 === 0) {
          dropped++;
          return;
        }
        wrapper.onmessage?.(e);
      };
      return wrapper;
    };
    const writer = await client(s.board, s.users[0]!.token, 0);
    const reader = await client(s.board, s.users[1]!.token, 1, { connect: lossy });
    await writer.c.until(() => writer.c.status === "joined");
    await reader.c.until(() => reader.c.status === "joined");
    const list = writer.c.addList("L");
    for (let i = 0; i < 40; i++) {
      writer.c.addCard(list, `c${i}`);
      await sleep(30);
    }
    const { hashes } = await settleBoth(s.board, writer, reader);
    const syncs = reader.events.filter((e) => e.event === "sync_request").length;
    console.log(`lost broadcasts: ${dropped} dropped, ${syncs} SYNC_REQUESTs, converged`);
    expect(dropped).toBeGreaterThan(0);
    expect(syncs).toBeGreaterThan(0);
    expect(hashes[0]).toBe(hashes[1]);
    writer.c.close();
    reader.c.close();
  });

  /** Row 9: the same op sent many times; original ACK, stored once, broadcast once. */
  it("9: same op many times", async () => {
    const s = seed(1);
    const watcher = await client(s.board, s.users[1]!.token, 1);
    await watcher.c.until(() => watcher.c.status === "joined");
    const { ws, inbox } = await raw(s.users[0]!.token);
    ws.send(JSON.stringify({ type: "JOIN", board_id: s.board, since_version: null }));
    await reply(inbox, (m) => m.type === "JOINED");
    const op = {
      op_id: crypto.randomUUID(), board_id: s.board, replica_id: crypto.randomUUID(), type: "ADD_LIST",
      list_id: crypto.randomUUID(), placement: { position_key: "a0" }, title: "once",
    } as Record<string, unknown>;
    op.hlc = { counter: 0, physical_ms: Date.now(), replica_id: op.replica_id };
    for (let i = 0; i < 5; i++) ws.send(JSON.stringify({ type: "OP", op }));
    await sleep(1_500);
    const acks = inbox.filter((m) => m.type === "OP_ACK");
    expect(acks).toHaveLength(5);
    expect(new Set(acks.map((a) => a.version))).toEqual(new Set([1]));
    expect(psql(`SELECT count(*) FROM board_ops WHERE board_id = '${s.board}'`)[0]).toBe("1");
    expect(inbox.filter((m) => m.type === "OP_BROADCAST")).toHaveLength(1); // to the sender's own connection
    await watcher.c.until(() => watcher.c.confirmedVersion === 1);
    ws.close();
    watcher.c.close();
  });

  /** Row 10: a client clock far ahead gets CLOCK_SKEW, corrects its offset and re-stamps. */
  it("10: client clock two minutes fast", async () => {
    const s = seed(0);
    let jump = 0;
    const fast = await client(s.board, s.users[0]!.token, 0, { now: () => Date.now() + jump });
    // JOINED sets the offset from server time, so the skew must appear after joining: the
    // device clock jumps two minutes forward (a bad NTP step, a manual change).
    await fast.c.until(() => fast.c.status === "joined");
    jump = 120_000;
    fast.c.addList("from the future");
    await fast.c.settled(30_000);
    const skews = fast.events.filter((e) => e.event === "error" && e.data.code === "CLOCK_SKEW").length;
    const stored = Number(psql(`SELECT hlc_physical_ms FROM board_ops WHERE board_id = '${s.board}'`)[0]);
    console.log(`clock skew: ${skews} CLOCK_SKEW, stored physical_ms ${stored - Date.now()}ms from now`);
    expect(skews).toBe(1);
    expect(stored).toBeLessThanOrEqual(Date.now() + 60_000);
    expect(fast.c.unsentEdits()).toEqual([]);
    fast.c.close();
  });

  /** Row 12: malformed or malicious ops are rejected before persist and nothing is stored. */
  it("12: malformed and malicious frames", async () => {
    const s = seed(0, 1);
    const { ws, inbox } = await raw(s.users[0]!.token);
    ws.send(JSON.stringify({ type: "JOIN", board_id: s.board, since_version: null }));
    await reply(inbox, (m) => m.type === "JOINED");
    const good = (extra: Record<string, unknown>) => {
      const replica = crypto.randomUUID();
      return {
        op_id: crypto.randomUUID(), board_id: s.board, hlc: { counter: 0, physical_ms: Date.now(), replica_id: replica },
        replica_id: replica, type: "ADD_LIST", list_id: crypto.randomUUID(), placement: { position_key: "a0" }, title: "t", ...extra,
      };
    };
    const bad = [
      good({ title: "x".repeat(201) }),
      good({ placement: { position_key: "a00" } }), // fractional part ends in 0
      good({ list_id: "00000000-0000-0000-0000-000000000000" }), // reserved for Orphaned
      good({ extra: 1 }),
      good({ op_id: "NOT-A-UUID" }),
      { ...good({}), type: "DROP_TABLE" },
    ];
    for (const op of bad) ws.send(JSON.stringify({ type: "OP", op }));
    const dupKeys = `{"type":"OP","op":${JSON.stringify(good({}))},"type":"OP"}`; // duplicate member names
    ws.send(dupKeys);
    ws.send("not json at all");
    await sleep(1_500);
    const errors = inbox.filter((m) => m.type === "ERROR");
    expect(errors.filter((e) => e.code === "INVALID_OP").length).toBe(bad.length + 2);
    expect(psql(`SELECT count(*) FROM board_ops WHERE board_id = '${s.board}'`)[0]).toBe("0");
    // A viewer's op is refused as UNAUTHORIZED.
    const viewer = await raw(s.users[1]!.token, 1);
    viewer.ws.send(JSON.stringify({ type: "JOIN", board_id: s.board, since_version: null }));
    await reply(viewer.inbox, (m) => m.type === "JOINED");
    const vop = good({});
    viewer.ws.send(JSON.stringify({ type: "OP", op: vop }));
    expect((await reply(viewer.inbox, (m) => m.type === "ERROR")).code).toBe("UNAUTHORIZED");
    ws.close();
    viewer.ws.close();
  });
});
