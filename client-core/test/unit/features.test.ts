// client-core's Phase 3 features against a scripted fake gateway: equal-key inserts (§3.7),
// "moved by" (§3.5), the trash view, presence (§7.5), labels (§4.2) and the member map.
import { Board, hlc as H, type Op } from "@concord/crdt";
import { describe, expect, it } from "vitest";
import { MemoryStorage, MOVED_BY_MS, normalizeLabels, SyncClient, type Socket } from "../../src/index.js";

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

  it("sends PRESENCE on join and change, tracks others, and treats 'left' idempotently", async () => {
    const { client, ws } = await joined();
    client.setPresence(null);
    client.setPresence("card-1");
    client.setPresence("card-1"); // unchanged: not resent
    expect(ws.sent.filter((m) => m.type === "PRESENCE")).toEqual([
      { type: "PRESENCE", board_id: BOARD, card_id: null, state: "active" },
      { type: "PRESENCE", board_id: BOARD, card_id: "card-1", state: "active" },
    ]);
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, card_id: "card-9", state: "active" });
    expect(client.presence().get(ALICE)).toEqual({ cardId: "card-9", state: "active" });
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, card_id: null, state: "left" });
    ws.push({ type: "PRESENCE_UPDATE", user_id: ALICE, card_id: null, state: "left" }); // a second gateway's sweep
    expect(client.presence().size).toBe(0);
  });

  it("labels are trimmed, NFC, unique and sorted by UTF-8 bytes; too many or too long throws", () => {
    expect(normalizeLabels([" b ", "a", "b", "", "é", "Z"])).toEqual(["Z", "a", "b", "é"]);
    expect(() => normalizeLabels(Array.from({ length: 21 }, (_, i) => `l${i}`))).toThrow(/20/);
    expect(() => normalizeLabels(["x".repeat(51)])).toThrow(/50/);
    expect(normalizeLabels(["😀".repeat(50)])).toHaveLength(1); // 50 code points, 100 UTF-16 units
  });
});
