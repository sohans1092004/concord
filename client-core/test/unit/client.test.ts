// client-core against a scripted fake gateway: every §7 path without a network.
import { Board, hlc as H, op as O, type Op } from "@concord/crdt";
import { describe, expect, it, vi } from "vitest";
import { backoffMs, JOIN_TIMEOUT_MS, MemoryStorage, SEND_BURST, SEND_PER_SECOND, SyncClient, type Socket } from "../../src/index.js";

const BOARD = "b0a4d000-0000-4000-8000-000000000001";
const T0 = 1_760_000_000_000;

/** One fake connection: records what the client sends; the test plays the server. */
class FakeSocket implements Socket {
  readyState = 0;
  sent: Record<string, unknown>[] = [];
  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: unknown) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  constructor(readonly url: string) {}
  send(data: string): void {
    this.sent.push(JSON.parse(data));
  }
  close(): void {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.onclose?.({});
  }
  open(): void {
    this.readyState = 1;
    this.onopen?.({});
  }
  push(m: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
  ofType(t: string) {
    return this.sent.filter((m) => m.type === t);
  }
}

async function setup(opts: { now?: () => number; storage?: MemoryStorage } = {}) {
  const sockets: FakeSocket[] = [];
  let t = T0;
  const client = await SyncClient.open({
    urls: ["ws://g1/ws", "ws://g2/ws"],
    boardId: BOARD,
    ticket: () => "tkt",
    storage: opts.storage ?? new MemoryStorage(),
    connect: (url) => {
      const s = new FakeSocket(url);
      sockets.push(s);
      return s;
    },
    now: opts.now ?? (() => t),
    random: () => 0, // backoff 0: reconnects and resends happen on the next tick
  });
  await tick();
  const ws = () => sockets[sockets.length - 1]!;
  const join = (version = 0, extra: Record<string, unknown> = {}) => {
    ws().open();
    ws().push({ type: "JOINED", snapshot: Board.empty().encode(), version, server_time_ms: T0, role: "editor", ...extra });
  };
  return { client, sockets, ws, join, advance: (ms: number) => (t += ms) };
}

const tick = () => new Promise((r) => setTimeout(r, 5));

/** An op from another replica, as a server would broadcast it. */
function remoteList(n: number): Op {
  const replica = "a0000000-0000-4000-8000-000000000099";
  return {
    op_id: `0e000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`,
    board_id: BOARD,
    hlc: H.encode({ physical_ms: T0 + n, counter: 0, replica_id: replica }),
    replica_id: replica,
    type: "ADD_LIST",
    list_id: `11000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`,
    placement: { position_key: `a${n}` },
    title: `remote ${n}`,
  } as Op;
}

describe("client-core", () => {
  it("joins with since_version null, applies edits optimistically and sends them", async () => {
    const { client, ws, join } = await setup();
    ws().open();
    expect(ws().ofType("JOIN")[0]).toEqual({ type: "JOIN", board_id: BOARD, since_version: null });
    ws().push({ type: "JOINED", snapshot: Board.empty().encode(), version: 0, server_time_ms: T0, role: "editor" });
    const list = client.addList("To Do");
    expect(client.view().lists.map((l) => l.id)).toEqual([list]);
    expect(ws().ofType("OP")).toHaveLength(1);
    expect(client.pendingOps()).toHaveLength(1);
    void join;
  });

  it("keeps edits made offline and resends the whole queue after JOINED, in order", async () => {
    const { client, ws, join } = await setup();
    const list = client.addList("offline 1");
    client.addCard(list, "offline 2");
    expect(ws().ofType("OP")).toHaveLength(0); // not joined yet: they wait (§7.2 step 4)
    join();
    expect(ws().ofType("OP").map((m) => (m.op as Op).type)).toEqual(["ADD_LIST", "ADD_CARD"]);
  });

  it("an ACK moves the op into the confirmed replica; a broadcast of it later changes nothing", async () => {
    const { client, ws, join } = await setup();
    join();
    client.addList("mine");
    const op = ws().ofType("OP")[0]!.op as Op;
    ws().push({ type: "OP_ACK", op_id: op.op_id, version: 1 });
    expect(client.pendingOps()).toHaveLength(0);
    expect(client.confirmedVersion).toBe(1);
    const before = client.confirmedState();
    ws().push({ type: "OP_BROADCAST", op, version: 1 });
    expect(client.confirmedState()).toBe(before);
  });

  it("gap detection: version v after v-2 sends SYNC_REQUEST and waits; SYNC fills the gap", async () => {
    const { client, ws, join } = await setup();
    join(0);
    ws().push({ type: "OP_BROADCAST", op: remoteList(1), version: 1 });
    ws().push({ type: "OP_BROADCAST", op: remoteList(3), version: 3 }); // 2 was lost
    expect(ws().ofType("SYNC_REQUEST")).toEqual([{ type: "SYNC_REQUEST", board_id: BOARD, since_version: 1 }]);
    expect(client.confirmedVersion).toBe(1); // 3 is held, not applied
    expect(client.view().lists).toHaveLength(2); // but it is visible
    ws().push({ type: "SYNC", ops: [{ op: remoteList(2), version: 2 }, { op: remoteList(3), version: 3 }], version: 3 });
    expect(client.confirmedVersion).toBe(3);
    expect(client.view().lists).toHaveLength(3);
  });

  it("BOARD_VERSION ahead of the client triggers SYNC_REQUEST (§6.4)", async () => {
    const { ws, join } = await setup();
    join(5);
    ws().push({ type: "BOARD_VERSION", version: 5 });
    expect(ws().ofType("SYNC_REQUEST")).toHaveLength(0);
    ws().push({ type: "BOARD_VERSION", version: 7 });
    expect(ws().ofType("SYNC_REQUEST")).toEqual([{ type: "SYNC_REQUEST", board_id: BOARD, since_version: 5 }]);
  });

  it("CLOCK_SKEW re-stamps with the corrected offset and resends under the same op_id", async () => {
    let t = T0 + 120_000; // the local clock is two minutes fast
    const { client, ws } = await setup({ now: () => t });
    ws().open();
    ws().push({ type: "JOINED", snapshot: Board.empty().encode(), version: 0, server_time_ms: T0 + 120_000, role: "editor" });
    client.addList("skewed");
    const first = ws().ofType("OP")[0]!.op as Op;
    ws().push({ type: "ERROR", code: "CLOCK_SKEW", op_id: first.op_id, detail: "ahead", server_time_ms: T0 });
    const second = ws().ofType("OP")[1]!.op as Op;
    expect(second.op_id).toBe(first.op_id);
    expect(second.hlc.physical_ms).toBeLessThanOrEqual(T0 + 60_000);
    expect(O.checkClock(second, T0).ok).toBe(true);
    expect(second.replica_id).not.toBe(first.replica_id); // a fresh replica: HLCs never go back
    expect(client.pendingOps()).toHaveLength(1);
  });

  it("a long offline queue is sent at the gateway's pace, and RETRY_LATER does not re-flood it", async () => {
    const { client, ws, join } = await setup();
    const list = client.addList("offline");
    for (let i = 0; i < 199; i++) client.addCard(list, `card ${i}`); // 200 ops queued while offline
    await tick();
    join();
    const sent = () => ws().ofType("OP").length;
    expect(sent(), "the burst goes out at once, no more").toBe(SEND_BURST);
    await new Promise((r) => setTimeout(r, 1_000));
    const afterOneSecond = sent();
    expect(afterOneSecond).toBeGreaterThanOrEqual(SEND_BURST + SEND_PER_SECOND - 2);
    expect(afterOneSecond).toBeLessThanOrEqual(SEND_BURST + SEND_PER_SECOND + 2);
    // The gateway refuses one: the queue is resent from the start, still paced.
    const first = ws().ofType("OP")[0]!.op as Op;
    ws().push({ type: "ERROR", code: "RETRY_LATER", op_id: first.op_id, detail: "rate limited" });
    await tick();
    expect(sent() - afterOneSecond, "no burst beyond what the bucket holds").toBeLessThanOrEqual(2);
    expect(client.pendingOps()).toHaveLength(200);
    client.close();
  });

  it("RETRY_LATER and UNAVAILABLE keep the op and resend it", async () => {
    const { client, ws, join } = await setup();
    join();
    client.addList("retry me");
    const op = ws().ofType("OP")[0]!.op as Op;
    ws().push({ type: "ERROR", code: "RETRY_LATER", op_id: op.op_id, detail: "commit failed" });
    await tick();
    ws().push({ type: "ERROR", code: "UNAVAILABLE", op_id: op.op_id, detail: "owner unreachable" });
    await tick();
    expect(ws().ofType("OP").map((m) => (m.op as Op).op_id)).toEqual([op.op_id, op.op_id, op.op_id]);
    expect(client.pendingOps()).toHaveLength(1);
    expect(client.unsentEdits()).toHaveLength(0);
  });

  it("INVALID_OP behind an unacknowledged op of ours is deferred, then judged again in order", async () => {
    const { client, ws, join } = await setup();
    join();
    const list = client.addList("L");
    client.addCard(list, "C");
    const [addList, addCard] = ws().ofType("OP").map((m) => m.op as Op);
    // The owner died between the two: the list failed, the card was judged without it.
    ws().push({ type: "ERROR", code: "UNAVAILABLE", op_id: addList!.op_id, detail: "owner unreachable" });
    ws().push({ type: "ERROR", code: "INVALID_OP", op_id: addCard!.op_id, detail: "placement.list_id names no list on this board" });
    expect(client.unsentEdits()).toHaveLength(0);
    expect(client.pendingOps()).toHaveLength(2);
    await tick(); // the queue is resent in order
    const resent = ws().ofType("OP").slice(2).map((m) => (m.op as Op).op_id);
    expect(resent).toEqual([addList!.op_id, addCard!.op_id]);
    ws().push({ type: "OP_ACK", op_id: addList!.op_id, version: 1 });
    ws().push({ type: "ERROR", code: "INVALID_OP", op_id: addCard!.op_id, detail: "still bad" }); // now at the head: final
    expect(client.unsentEdits().map((u) => u.op.op_id)).toEqual([addCard!.op_id]);
  });

  it("a deferred op is resent once everything ahead of it is ACKed", async () => {
    const { client, ws, join } = await setup();
    join();
    const list = client.addList("L");
    client.addCard(list, "C");
    const [addList, addCard] = ws().ofType("OP").map((m) => m.op as Op);
    ws().push({ type: "ERROR", code: "INVALID_OP", op_id: addCard!.op_id, detail: "judged early" });
    expect(ws().ofType("OP")).toHaveLength(2);
    ws().push({ type: "OP_ACK", op_id: addList!.op_id, version: 1 });
    expect(ws().ofType("OP").map((m) => (m.op as Op).op_id)[2]).toBe(addCard!.op_id);
    ws().push({ type: "OP_ACK", op_id: addCard!.op_id, version: 2 });
    expect(client.unsentEdits()).toHaveLength(0);
    expect(client.confirmedVersion).toBe(2);
  });

  it("INVALID_OP and UNAUTHORIZED move the op to Unsent edits and rebuild the view", async () => {
    const { client, ws, join } = await setup();
    join();
    const a = client.addList("invalid");
    const b = client.addList("revoked");
    const [opA, opB] = ws().ofType("OP").map((m) => m.op as Op);
    ws().push({ type: "ERROR", code: "INVALID_OP", op_id: opA!.op_id, detail: "bad" });
    ws().push({ type: "ERROR", code: "UNAUTHORIZED", op_id: opB!.op_id, detail: "viewers cannot edit" });
    expect(client.pendingOps()).toHaveLength(0);
    expect(client.unsentEdits().map((u) => [u.op.op_id, u.code])).toEqual([[opA!.op_id, "INVALID_OP"], [opB!.op_id, "UNAUTHORIZED"]]);
    expect(client.view().lists.map((l) => l.id)).not.toContain(a);
    expect(client.view().lists.map((l) => l.id)).not.toContain(b);
  });

  it("access revoked while offline: JOIN UNAUTHORIZED moves the whole queue to Unsent edits", async () => {
    const { client, ws, sockets } = await setup();
    client.addList("offline 1");
    client.addList("offline 2");
    ws().open();
    ws().push({ type: "ERROR", code: "UNAUTHORIZED", op_id: null, detail: "not a member" });
    expect(client.status).toBe("unauthorized");
    expect(client.unsentEdits()).toHaveLength(2);
    expect(client.pendingOps()).toHaveLength(0);
    await tick();
    expect(sockets).toHaveLength(1); // no reconnect loop
  });

  it("reconnects to the next gateway after a drop, resumes from its version, and resends", async () => {
    const { client, ws, join, sockets } = await setup();
    join(4);
    const first = ws();
    first.close(); // the gateway died
    client.addList("while reconnecting");
    await tick();
    expect(sockets).toHaveLength(2);
    expect(sockets[0]!.url.startsWith("ws://g1/ws?")).toBe(true);
    expect(sockets[1]!.url.startsWith("ws://g2/ws?")).toBe(true); // the next gateway
    ws().open();
    expect(ws().ofType("JOIN")[0]!.since_version).toBe(4);
    ws().push({ type: "JOINED", ops: [], version: 4, server_time_ms: T0, role: "editor" });
    expect(ws().ofType("OP")).toHaveLength(1);
  });

  it("a connection that hangs before JOINED is abandoned after 10s for the next gateway", async () => {
    vi.useFakeTimers();
    try {
      const sockets: FakeSocket[] = [];
      const client = await SyncClient.open({
        urls: ["ws://g1/ws", "ws://g2/ws"], boardId: BOARD, ticket: () => "tkt", random: () => 0,
        connect: (url) => {
          const s = new FakeSocket(url);
          sockets.push(s);
          return s;
        },
      });
      await vi.advanceTimersByTimeAsync(1);
      expect(sockets).toHaveLength(1); // never opens, never closes
      await vi.advanceTimersByTimeAsync(JOIN_TIMEOUT_MS + 1);
      expect(sockets).toHaveLength(2);
      expect(sockets[1]!.url.startsWith("ws://g2/ws?")).toBe(true);
      client.close();
    } finally {
      vi.useRealTimers();
    }
  });

  it("RECONNECT_ELSEWHERE closes and reconnects at once", async () => {
    const { ws, join, sockets } = await setup();
    join();
    ws().push({ type: "RECONNECT_ELSEWHERE" });
    await tick();
    expect(sockets).toHaveLength(2);
  });

  it("a reload resumes from storage: confirmed replica, version, pending queue, replica ID", async () => {
    const storage = new MemoryStorage();
    const one = await setup({ storage });
    one.join();
    one.client.addList("survives a reload");
    const replica = one.client.pendingOps()[0]!.replica_id;
    one.client.close();
    await tick();
    const two = await setup({ storage });
    expect(two.client.pendingOps()).toHaveLength(1);
    expect(two.client.pendingOps()[0]!.replica_id).toBe(replica);
    two.ws().open();
    expect(two.ws().ofType("JOIN")[0]!.since_version).toBe(0);
  });

  it("deletes carry the tags this replica observed", async () => {
    const { client, ws, join } = await setup();
    join();
    const list = client.addList("L");
    const card = client.addCard(list, "C");
    client.deleteCard(card);
    const del = ws().ofType("OP").map((m) => m.op as Op).find((o) => o.type === "DELETE_CARD")!;
    const add = ws().ofType("OP").map((m) => m.op as Op).find((o) => o.type === "ADD_CARD")!;
    expect((del as unknown as { observed_tags: string[] }).observed_tags).toEqual([add.op_id]);
    expect(client.view().lists[0]!.cards).toHaveLength(0);
  });

  it("each connection gets a fresh ticket, in the URL but never in a log event (SPEC §9.4)", async () => {
    vi.useFakeTimers();
    try {
      let n = 0;
      const asked: string[] = [];
      const events: string[] = [];
      const sockets: FakeSocket[] = [];
      const client = await SyncClient.open({
        urls: ["ws://g1/ws", "ws://g2/ws"], boardId: BOARD, random: () => 0,
        ticket: (url) => (asked.push(url), `secret-ticket-${n++}`),
        log: (event, data) => events.push(JSON.stringify({ event, data })),
        connect: (url) => {
          const s = new FakeSocket(url);
          sockets.push(s);
          return s;
        },
      });
      await vi.advanceTimersByTimeAsync(1);
      expect(sockets[0]!.url).toBe("ws://g1/ws?ticket=secret-ticket-0");
      await vi.advanceTimersByTimeAsync(JOIN_TIMEOUT_MS + 1); // join_timeout and reconnect_scheduled log the URL
      sockets[1]!.close(); // and a dropped connection
      await vi.advanceTimersByTimeAsync(1_000);
      expect(events.length).toBeGreaterThan(2);
      expect(sockets[1]!.url).toBe("ws://g2/ws?ticket=secret-ticket-1"); // never reused
      expect(asked.slice(0, 2)).toEqual(["ws://g1/ws", "ws://g2/ws"]);
      for (const e of events) expect(e).not.toContain("secret");
      client.close();
    } finally {
      vi.useRealTimers();
    }
  });

  it("backoff is 0.5s × 2^attempt, capped at 30s, full jitter", () => {
    expect(backoffMs(0, () => 1)).toBe(500);
    expect(backoffMs(3, () => 1)).toBe(4_000);
    expect(backoffMs(10, () => 1)).toBe(30_000);
    expect(backoffMs(10, () => 0.5)).toBe(15_000);
  });
});
