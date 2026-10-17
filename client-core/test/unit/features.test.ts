// client-core's Phase 3 features against a scripted fake gateway: equal-key inserts (§3.7),
// "moved by" (§3.5), the trash view, presence (§7.5), labels (§4.2) and the member map.
import { Board, hlc as H, type Op } from "@concord/crdt";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryStorage, MOVED_BY_MS, normalizeLabels, SILENCE_MS, SyncClient, type Socket } from "../../src/index.js";

const BOARD = "b0a4d000-0000-4000-8000-000000000002";
const T0 = 1_760_000_000_000;
const OTHER = "a0000000-0000-4000-8000-0000000000aa"; // another replica
const ALICE = "e0000000-0000-4000-8000-0000000000a1";

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
  push(m: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
  ops(): Op[] {
    return this.sent.filter((m) => m.type === "OP").map((m) => m.op as Op);
  }
}

async function joined(extra: Record<string, unknown> = {}) {
  let t = T0;
  const sockets: FakeSocket[] = [];
  const client = await SyncClient.open({
    urls: ["ws://g/ws"], boardId: BOARD, ticket: () => "t", storage: new MemoryStorage(),
    connect: (url) => {
      const s = new FakeSocket(url);
      sockets.push(s);
      return s;
    },
    now: () => t, random: () => 0,
  });
  await new Promise((r) => setTimeout(r, 5));
  const ws = sockets[0]!;
  ws.readyState = 1;
  ws.onopen?.({});
  ws.push({ type: "JOINED", snapshot: Board.empty().encode(), version: 0, server_time_ms: T0, role: "editor", ...extra });
  let version = 0;
  /** ACK every op the client has sent and not yet had ACKed, in order. */
  const ackAll = () => {
    for (const op of ws.ops()) if (client.pendingOps().some((p) => p.op_id === op.op_id)) ws.push({ type: "OP_ACK", op_id: op.op_id, version: ++version });
  };
  /** An op from another user, committed and broadcast after ours. */
  const remote = (body: Record<string, unknown>, user = ALICE, at = 1): Op => {
    const op = { op_id: crypto.randomUUID(), board_id: BOARD, hlc: H.encode({ physical_ms: t + at, counter: 0, replica_id: OTHER }), replica_id: OTHER, ...body } as Op;
    ws.push({ type: "OP_BROADCAST", op, version: ++version, user_id: user });
    return op;
  };
  return { client, ws, ackAll, remote, advance: (ms: number) => (t += ms) };
}

describe("§3.7 equal-key inserts (rule B)", () => {
  /** Two (or three) cards that a concurrent append gave the same key, as the server would broadcast them. */
  async function withEqualCards(n: number) {
    const x = await joined();
    const list = x.client.addList("L");
    x.ackAll();
    const ids = Array.from({ length: n }, (_, i) => `c${i}000000-0000-4000-8000-000000000000`.replace(/^c/, "c"));
    for (const id of ids) x.remote({ type: "ADD_CARD", card_id: id, placement: { list_id: list, position_key: "a0" }, title: id });
    return { ...x, list, ids };
  }

  it("inserting between two equal neighbours re-keys the upper one first", async () => {
    const { client, ws, list, ids } = await withEqualCards(2);
    const before = ws.ops().length;
    const mine = client.addCard(list, "between", 1);
    const sent = ws.ops().slice(before);
    expect(sent.map((o) => o.type)).toEqual(["SET_PLACEMENT", "ADD_CARD"]);
    expect((sent[0] as unknown as { entity_id: string }).entity_id).toBe(ids[1]);
    const cards = client.view().lists[0]!.cards;
    expect(cards.map((c) => c.id)).toEqual([ids[0], mine, ids[1]]);
    const keysInOrder = cards.map((c) => c.position_key);
    expect(new Set(keysInOrder).size).toBe(3);
    expect([...keysInOrder].sort()).toEqual(keysInOrder); // strictly increasing, distinct
  });

  it("with three or more equal neighbours it re-keys each one above the gap, then inserts", async () => {
    const { client, ws, list, ids } = await withEqualCards(4);
    const before = ws.ops().length;
    const mine = client.addCard(list, "after the first", 1);
    const sent = ws.ops().slice(before);
    expect(sent.map((o) => o.type)).toEqual(["SET_PLACEMENT", "SET_PLACEMENT", "SET_PLACEMENT", "ADD_CARD"]);
    expect(sent.slice(0, 3).map((o) => (o as unknown as { entity_id: string }).entity_id)).toEqual(ids.slice(1));
    const cards = client.view().lists[0]!.cards;
    expect(cards.map((c) => c.id)).toEqual([ids[0], mine, ...ids.slice(1)]);
    const k = cards.map((c) => c.position_key);
    for (let i = 1; i < k.length; i++) expect(k[i]! > k[i - 1]!).toBe(true);
  });

  it("moving a card between equal neighbours re-keys too", async () => {
    const { client, ws, list, ids } = await withEqualCards(3);
    const other = client.addList("M");
    const moving = client.addCard(other, "mover");
    const before = ws.ops().length;
    client.moveCard(moving, list, 2);
    expect(ws.ops().slice(before).map((o) => o.type)).toEqual(["SET_PLACEMENT", "SET_PLACEMENT"]);
    expect(client.view().lists[0]!.cards.map((c) => c.id)).toEqual([ids[0], ids[1], moving, ids[2]]);
  });
});

describe("§3.5 moved by {name}", () => {
  it("a concurrent move that wins over ours is reported with the winner's user, briefly", async () => {
    const { client, list, card, remote, advance } = await (async () => {
      const x = await joined({ members: { [ALICE]: { name: "Alice", role: "editor" } } });
      const list = x.client.addList("A");
      const other = x.client.addList("B");
      const card = x.client.addCard(list, "c");
      x.ackAll();
      return { ...x, list: other, card };
    })();
    client.moveCard(card, list, 0); // ours: pending
    // Alice moved the same card back to its first list a moment later: her HLC is higher.
    const firstList = client.view().lists[0]!.id;
    remote({ type: "SET_PLACEMENT", entity_id: card, placement: { list_id: firstList, position_key: "Zz" } }, ALICE, 50);
    expect(client.movedBy().get(card)?.userId).toBe(ALICE);
    expect(client.members().get(ALICE)?.name).toBe("Alice");
    expect(client.view().lists[0]!.cards.map((c) => c.id)).toContain(card);
    advance(MOVED_BY_MS + 1);
    expect(client.movedBy().has(card)).toBe(false);
  });

  it("no report when our move wins, or for a remote move of a card we did not move", async () => {
    const x = await joined();
    const a = x.client.addList("A");
    const b = x.client.addList("B");
    const card = x.client.addCard(a, "c");
    const quiet = x.client.addCard(a, "q");
    x.ackAll();
    // A remote move from before ours (lower HLC) arrives after we moved: ours stays.
    x.client.moveCard(card, b, 0);
    x.remote({ type: "SET_PLACEMENT", entity_id: card, placement: { list_id: a, position_key: "a5" } }, ALICE, -1_000);
    expect(x.client.movedBy().size).toBe(0);
    x.remote({ type: "SET_PLACEMENT", entity_id: quiet, placement: { list_id: b, position_key: "a9" } });
    expect(x.client.movedBy().size).toBe(0);
  });
});

describe("trash, presence, labels", () => {
  it("trash lists deleted lists and cards; restoring takes them out", async () => {
    const { client } = await joined();
    const list = client.addList("Doomed list");
    const card = client.addCard(list, "Doomed card");
    const keep = client.addCard(list, "kept");
    client.deleteCard(card);
    expect(client.trash()).toEqual([{ kind: "card", id: card, title: "Doomed card", listId: list }]);
    client.deleteList(list);
    expect(client.trash().map((t) => t.id).sort()).toEqual([card, list].sort());
    // §3.6: the live card of a deleted list is in Orphaned, not in the trash.
    expect(client.view().lists.at(-1)!.cards.map((c) => c.id)).toEqual([keep]);
    client.restoreList(list);
    client.restoreCard(card);
    expect(client.trash()).toEqual([]);
  });

  it("sends PRESENCE on join and change, tracks others per connection, and treats 'left' idempotently", async () => {
    const { client, ws } = await joined();
    client.setPresence(null);
    client.setPresence("card-1");
    client.setPresence("card-1"); // unchanged: not resent
    expect(ws.sent.filter((m) => m.type === "PRESENCE")).toEqual([
      { type: "PRESENCE", board_id: BOARD, card_id: null, state: "active" },
      { type: "PRESENCE", board_id: BOARD, card_id: "card-1", state: "active" },
    ]);
    // Alice on two connections (two tabs): one has a card open, the other is idle.
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, connection_id: "c1", card_id: "card-9", state: "active" });
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, connection_id: "c2", card_id: null, state: "idle" });
    expect(client.presence().get(ALICE)).toEqual({ cardId: "card-9", state: "active" });
    // c1 leaves (twice: a second gateway's sweep): Alice is still here, on c2.
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, connection_id: "c1", card_id: null, state: "left" });
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, connection_id: "c1", card_id: null, state: "left" });
    expect(client.presence().get(ALICE)).toEqual({ cardId: null, state: "idle" });
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, connection_id: "c2", card_id: null, state: "left" });
    expect(client.presence().size).toBe(0);
  });

  it("labels are trimmed, NFC, unique and sorted by UTF-8 bytes; too many or too long throws", () => {
    expect(normalizeLabels([" b ", "a", "b", "", "é", "Z"])).toEqual(["Z", "a", "b", "é"]);
    expect(() => normalizeLabels(Array.from({ length: 21 }, (_, i) => `l${i}`))).toThrow(/20/);
    expect(() => normalizeLabels(["x".repeat(51)])).toThrow(/50/);
    expect(normalizeLabels(["😀".repeat(50)])).toHaveLength(1); // 50 code points, 100 UTF-16 units
  });
});

describe("§7.3 liveness (amendment 18)", () => {
  async function fakeTimed() {
    vi.useFakeTimers();
    const listeners: Record<string, () => void> = {};
    vi.stubGlobal("addEventListener", (t: string, f: () => void) => (listeners[t] = f));
    vi.stubGlobal("removeEventListener", () => {});
    const sockets: FakeSocket[] = [];
    const client = await SyncClient.open({
      urls: ["ws://g/ws"], boardId: BOARD, ticket: () => "t", storage: new MemoryStorage(), random: () => 0,
      connect: (url) => {
        const s = new FakeSocket(url);
        sockets.push(s);
        return s;
      },
    });
    await vi.advanceTimersByTimeAsync(1);
    const join = () => {
      const ws = sockets.at(-1)!;
      ws.readyState = 1;
      ws.onopen?.({});
      ws.push({ type: "JOINED", snapshot: Board.empty().encode(), version: 0, server_time_ms: Date.now(), role: "editor" });
    };
    join();
    return { client, sockets, listeners, join };
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("a joined connection that hears nothing for 15s is dropped and re-dialled", async () => {
    const { client, sockets } = await fakeTimed();
    expect(client.status).toBe("joined");
    await vi.advanceTimersByTimeAsync(SILENCE_MS + 5_001);
    expect(sockets[0]!.readyState).toBe(3);
    expect(sockets.length).toBeGreaterThan(1); // reconnecting on its own
    client.close();
  });

  it("BOARD_VERSION every 5s keeps it alive", async () => {
    const { client, sockets } = await fakeTimed();
    for (let i = 0; i < 8; i++) {
      await vi.advanceTimersByTimeAsync(5_000);
      sockets[0]!.push({ type: "BOARD_VERSION", version: 0 });
    }
    expect(client.status).toBe("joined");
    expect(sockets).toHaveLength(1);
    client.close();
  });

  it("the browser's offline event drops the socket at once; online reconnects without waiting for backoff", async () => {
    const { client, sockets, listeners } = await fakeTimed();
    listeners.offline!();
    expect(client.status).toBe("offline");
    expect(sockets[0]!.readyState).toBe(3);
    const before = sockets.length;
    listeners.online!();
    await vi.advanceTimersByTimeAsync(1);
    expect(sockets.length).toBe(before + 1);
    client.close();
  });
});

describe("incremental view (performance, §7.1)", () => {
  it("always equals the view rebuilt from scratch, through edits, broadcasts, gaps, ACKs and rejections", async () => {
    for (let seed = 1; seed <= 20; seed++) {
      const x = await joined();
      let s = seed;
      const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32);
      const lists: string[] = [x.client.addList("L0")];
      const cards: string[] = [];
      let version = 0;
      const held: { op: Op; version: number }[] = [];
      for (let step = 0; step < 120; step++) {
        const r = rnd();
        const pendingNow = [...x.client.pendingOps()]; // a copy: the live array changes under us
        if (r < 0.25) cards.push(x.client.addCard(lists[Math.floor(rnd() * lists.length)]!, `c${step}`));
        else if (r < 0.32) lists.push(x.client.addList(`L${step}`));
        else if (r < 0.45 && cards.length) x.client.moveCard(cards[Math.floor(rnd() * cards.length)]!, lists[Math.floor(rnd() * lists.length)]!, 0);
        else if (r < 0.52 && cards.length) x.client.deleteCard(cards[Math.floor(rnd() * cards.length)]!);
        else if (r < 0.65 && pendingNow.length) {
          // the server ACKs the head of our queue
          x.ws.push({ type: "OP_ACK", op_id: pendingNow[0]!.op_id, version: ++version });
          x.ws.push({ type: "OP_BROADCAST", op: pendingNow[0], version, user_id: ALICE });
        } else if (r < 0.7 && pendingNow.length) {
          x.ws.push({ type: "ERROR", code: "INVALID_OP", op_id: pendingNow[0]!.op_id, detail: "test" });
        } else if (r < 0.74 && pendingNow.length) {
          x.ws.push({ type: "ERROR", code: "CLOCK_SKEW", op_id: pendingNow.at(-1)!.op_id, server_time_ms: T0 });
        } else if (r < 0.85 && lists.length) {
          // a remote op, sometimes held back to make a gap
          const op = { op_id: crypto.randomUUID(), board_id: BOARD, hlc: H.encode({ physical_ms: T0 + step, counter: 0, replica_id: OTHER }), replica_id: OTHER,
            type: "ADD_CARD", card_id: crypto.randomUUID(), placement: { list_id: lists[0]!, position_key: "a" + String.fromCharCode(48 + (step % 10)) }, title: `r${step}` } as Op;
          held.push({ op, version: ++version });
          if (rnd() < 0.6) for (const h of held.splice(0).reverse()) x.ws.push({ type: "OP_BROADCAST", op: h.op, version: h.version, user_id: ALICE });
        } else if (held.length) {
          for (const h of held.splice(0)) x.ws.push({ type: "OP_BROADCAST", op: h.op, version: h.version, user_id: ALICE });
        }
        expect(JSON.stringify(x.client.view()), `seed ${seed} step ${step}`).toBe(JSON.stringify(x.client.rebuiltView()));
      }
      x.client.close();
    }
  });
});

describe("§7.2 order: durable append, then view, then send (amendment 21)", () => {
  /** MemoryStorage whose appends complete only when the test says so. */
  class SlowStorage extends MemoryStorage {
    gates: (() => void)[] = [];
    override append(op: Op, seq: number): Promise<void> {
      return new Promise((resolve) => this.gates.push(() => (super.append(op, seq), resolve())));
    }
  }

  async function slow() {
    const storage = new SlowStorage();
    const sockets: FakeSocket[] = [];
    const client = await SyncClient.open({
      urls: ["ws://g/ws"], boardId: BOARD, ticket: () => "t", storage, random: () => 0,
      connect: (url) => {
        const s = new FakeSocket(url);
        sockets.push(s);
        return s;
      },
    });
    await new Promise((r) => setTimeout(r, 5));
    const ws = sockets[0]!;
    ws.readyState = 1;
    ws.onopen?.({});
    ws.push({ type: "JOINED", snapshot: Board.empty().encode(), version: 0, server_time_ms: Date.now(), role: "editor" });
    return { client, storage, ws };
  }
  const flush = () => new Promise((r) => setTimeout(r, 0));

  it("an edit is neither shown nor sent until its append is durable", async () => {
    const { client, storage, ws } = await slow();
    const list = client.addList("L");
    expect(client.view().lists).toHaveLength(0);
    expect(client.pendingOps()).toHaveLength(0);
    expect(ws.ops()).toHaveLength(0);
    storage.gates.shift()!();
    await flush();
    expect(client.view().lists.map((l) => l.id)).toEqual([list]);
    expect(ws.ops().map((o) => o.type)).toEqual(["ADD_LIST"]);
    client.close();
  });

  it("quick edits keep their order, see each other (distinct keys), and are sent in order", async () => {
    const { client, storage, ws } = await slow();
    const list = client.addList("L");
    const cards = ["one", "two", "three"].map((t) => client.addCard(list, t));
    while (storage.gates.length) {
      storage.gates.shift()!();
      await flush();
    }
    await client.saved();
    const shown = client.view().lists[0]!.cards;
    expect(shown.map((c) => c.id)).toEqual(cards);
    expect(new Set(shown.map((c) => c.position_key)).size).toBe(3);
    expect(ws.ops().map((o) => o.type)).toEqual(["ADD_LIST", "ADD_CARD", "ADD_CARD", "ADD_CARD"]);
    client.close();
  });

  it("a reload merges the log after the last snapshot, and the clock never reuses a tick", async () => {
    const storage = new MemoryStorage();
    const a = await SyncClient.open({ urls: ["ws://g/ws"], boardId: BOARD, ticket: () => "t", storage, connect: (u) => new FakeSocket(u), random: () => 0 });
    const ops = [a.submit({ type: "ADD_LIST", list_id: crypto.randomUUID(), placement: { position_key: "a0" }, title: "x" })];
    ops.push(a.submit({ type: "ADD_LIST", list_id: crypto.randomUUID(), placement: { position_key: "a1" }, title: "y" }));
    // No snapshot was written (they are coalesced): the edits live only in the log.
    a.close = () => {}; // a crash: nothing flushed
    const b = await SyncClient.open({ urls: ["ws://g/ws"], boardId: BOARD, ticket: () => "t", storage, connect: (u) => new FakeSocket(u), random: () => 0 });
    expect(b.pendingOps().map((o) => o.op_id)).toEqual(ops.map((o) => o.op_id));
    expect(b.replica).toBe(a.replica);
    const next = b.submit({ type: "ADD_LIST", list_id: crypto.randomUUID(), placement: { position_key: "a2" }, title: "z" });
    for (const o of ops) expect(H.compare(next.hlc, o.hlc)).toBeGreaterThan(0);
    b.close();
  });
});
