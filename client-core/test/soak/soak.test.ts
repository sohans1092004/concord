// Memory soak (Phase 4a step 1), against a running compose stack. Hours long, so it runs only
// with SOAK_MINUTES set:
//   cd client-core && SOAK_MINUTES=120 npx vitest run test/soak                      # the dev stack
//   SOAK_TARGET=prod SOAK_ORIGIN=https://<CONCORD_DOMAIN> SOAK_INVITE=<code> SOAK_MINUTES=125 \
//     npx vitest run test/soak   # deploy/prod/compose.yaml + deploy/soak/compose.soak.yaml (Caddy on 127.0.0.1:8088)
// SOAK_WORKLOAD=fixed (the default): each board gets 3 lists and 40 cards, then only renames,
// descriptions, labels and moves of those cards, so board state stays the same size and a heap
// that keeps growing is a leak. Editors are fast enough (SOAK_EDIT_MS, 500) to fill each board's
// 10,000-op ACK cache (SPEC 4.3) in the first hour. SOAK_WORKLOAD=growing is run 1's workload
// (adds and deletes: state grows by design, amendment 26).
// Load, for the whole run:
//   - 4 boards, 2 editors each;
//   - per board, one connection at a time opening, sending presence, maybe editing, closing
//     after 3-8s (a fresh ticket each time), so connections open and close every few seconds;
//   - every 2 minutes a slow consumer: a raw WebSocket that asks for full catch-ups and never
//     reads, which the gateway must cut (amendment 9).
// Every 5 minutes, each JVM gets a full GC (jcmd GC.run) and reports its used heap
// (GC.heap_info) and the container's memory (docker stats). Samples go to
// deploy/soak/<start>.jsonl; the verdict is computed by deploy/soak/verdict.py.
import { mkdirSync, appendFileSync } from "node:fs";
import { connect, type Socket as NetSocket } from "node:net";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { MemoryStorage, SyncClient } from "../../src/index.js";
import { client, docker, GATEWAYS, seed, sleep, ticket } from "../e2e/stack.js";

const MINUTES = Number(process.env.SOAK_MINUTES ?? 0);
const PROD = process.env.SOAK_TARGET === "prod";
const BASE = process.env.SOAK_BASE ?? "http://127.0.0.1:8088";
const ORIGIN = process.env.SOAK_ORIGIN ?? BASE;
const PROJECT = process.env.SOAK_PROJECT ?? "concord-soak";
const FIXED = (process.env.SOAK_WORKLOAD ?? "fixed") === "fixed";
const EDIT_MS = Number(process.env.SOAK_EDIT_MS ?? (FIXED ? 500 : 3_000));
const JVMS = PROD
  ? [`${PROJECT}-gateway-1`, `${PROJECT}-session1-1`, `${PROJECT}-session2-1`]
  : ["deploy-gateway1-1", "deploy-gateway2-1", "deploy-session1-1", "deploy-session2-1", "deploy-session3-1"];
const WS = PROD ? [`${BASE.replace(/^http/, "ws")}/ws`] : GATEWAYS;
const OUT = fileURLToPath(new URL("../../../deploy/soak/", import.meta.url));

interface Board { board: string; users: { session: string }[] }

async function api(path: string, body: unknown, session?: string): Promise<{ status: number; json: Record<string, unknown>; cookie: string }> {
  const r = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { Origin: ORIGIN, "Content-Type": "application/json", ...(session ? { Authorization: `Bearer ${session}` } : {}) },
    body: JSON.stringify(body),
  });
  const text = await r.text();
  return { status: r.status, json: text ? (JSON.parse(text) as Record<string, unknown>) : {}, cookie: r.headers.getSetCookie().join("\n") };
}

/** Production has no devtools: sign up through the API with the invite code, then create and share. */
async function seedBoard(editors: number): Promise<Board> {
  if (!PROD) return seed(editors);
  const users: { session: string; email: string }[] = [];
  for (let i = 0; i < editors; i++) {
    const email = `soak-${crypto.randomUUID().slice(0, 8)}@soak.invalid`;
    const r = await api("/api/auth/sign-up", { email, name: `Soak ${i}`, password: "soak password, local only", invite_code: process.env.SOAK_INVITE ?? "" });
    if (r.status !== 201) throw new Error(`sign-up: ${r.status} ${JSON.stringify(r.json)}`);
    users.push({ email, session: /concord_session=([^;]+)/.exec(r.cookie)![1]! });
  }
  const board = (await api("/api/boards", { title: "Soak" }, users[0]!.session)).json.id as string;
  for (const u of users.slice(1)) {
    const r = await api(`/api/boards/${board}/members`, { email: u.email, role: "editor" }, users[0]!.session);
    if (r.status !== 201) throw new Error(`add member: ${r.status}`);
  }
  return { board, users };
}

async function ticketFor(session: string, gateway: number): Promise<string> {
  if (!PROD) return ticket(session, GATEWAYS[gateway]);
  const r = await api("/api/ws-ticket", {}, session);
  if (r.status !== 200) throw new Error(`ticket: ${r.status}`);
  return r.json.ticket as string;
}

/** `events` collects the client's own log (connects, drops, ticket failures) for error reports. */
async function open(board: string, session: string, gateway: number, events: string[] = []): Promise<SyncClient> {
  const log = (event: string, data: Record<string, unknown>) => {
    const { board_id: _, ...rest } = data;
    events.push(`${new Date().toISOString().slice(11, 23)} ${event} ${JSON.stringify(rest)}`.slice(0, 200));
    if (events.length > 12) events.shift();
  };
  if (!PROD) return (await client(board, session, gateway, { log })).c;
  return SyncClient.open({ urls: WS, boardId: board, storage: new MemoryStorage(), ticket: () => ticketFor(session, 0), log });
}

/**
 * Used heap after a full GC, in KB, from `jcmd 1 GC.heap_info`. G1 prints one line ("garbage-first
 * heap total ..., used Y"); Serial, which production's small containers get, prints the young and
 * the old generation ("def new generation ... used Y", "tenured generation ... used Z"): summed.
 */
function heapAfterFullGc(container: string): number {
  docker("exec", container, "jcmd", "1", "GC.run");
  const info = docker("exec", container, "jcmd", "1", "GC.heap_info");
  const used = [...info.matchAll(/(garbage-first heap|def new generation|tenured generation)\s+total (?:reserved \d+K, committed )?\d+K, used (\d+)K/g)];
  if (!used.length) throw new Error(`cannot read heap_info of ${container}: ${info.slice(0, 200)}`);
  return used.reduce((kb, m) => kb + Number(m[2]), 0);
}

function memUsage(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of docker("stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}").split("\n")) {
    const [name, used] = line.split(" ");
    if (name && used) out[name] = used;
  }
  return out;
}

/** A WebSocket that completes the handshake, asks for full catch-ups and never reads a byte. */
async function slowConsumer(board: string, session: string, gateway: number): Promise<void> {
  const url = new URL(WS[gateway % WS.length]!);
  const t = await ticketFor(session, gateway);
  const s: NetSocket = connect({ host: url.hostname, port: Number(url.port) });
  await new Promise<void>((resolve, reject) => (s.once("connect", resolve), s.once("error", reject)));
  s.write(`GET /ws?ticket=${t} HTTP/1.1\r\nHost: ${url.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
    `Sec-WebSocket-Key: ${randomBytes(16).toString("base64")}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
  await new Promise((r) => s.once("data", r)); // the 101; from here on, nothing is read
  s.pause();
  const frame = (text: string) => {
    const p = Buffer.from(text);
    const head = p.length < 126 ? Buffer.from([0x81, 0x80 | p.length]) : Buffer.from([0x81, 0x80 | 126, p.length >> 8, p.length & 0xff]);
    const mask = randomBytes(4);
    const body = Buffer.alloc(p.length);
    for (let i = 0; i < p.length; i++) body[i] = p[i]! ^ mask[i % 4]!;
    return Buffer.concat([head, mask, body]);
  };
  s.on("error", () => {}); // the gateway cutting us is the expected end
  s.write(frame(JSON.stringify({ type: "JOIN", board_id: board, since_version: 0 })));
  for (let i = 0; i < 40 && !s.destroyed; i++) {
    s.write(frame(JSON.stringify({ type: "SYNC_REQUEST", board_id: board, since_version: 0 })));
    await sleep(500);
  }
  s.destroy();
}

describe.skipIf(!MINUTES)("memory soak", () => {
  it(`runs ${MINUTES} minutes and samples every JVM's heap after a full GC`, async () => {
    mkdirSync(OUT, { recursive: true });
    const file = `${OUT}${new Date().toISOString().replace(/[:.]/g, "-")}.jsonl`;
    const until = Date.now() + MINUTES * 60_000;
    const boards: Board[] = [];
    for (let i = 0; i < 4; i++) boards.push(await seedBoard(2));
    const counts = { edits: 0, connections: 0, slow: 0, errors: 0 };
    const rnd = (n: number) => Math.floor(Math.random() * n);

    const editors: SyncClient[] = [];
    for (const [i, b] of boards.entries()) {
      for (let k = 0; k < 2; k++) editors.push(await open(b.board, b.users[k]!.session, (i + k) % 2));
    }
    await Promise.all(editors.map((e) => e.until(() => e.status === "joined", 60_000)));
    if (FIXED) {
      // The fixed set: 3 lists and 40 cards per board, made once by each board's first editor.
      for (let i = 0; i < editors.length; i += 2) {
        const e = editors[i]!;
        const lists = [e.addList("To do"), e.addList("Doing"), e.addList("Done")];
        for (let k = 0; k < 40; k++) {
          e.addCard(lists[k % 3]!, `card ${k}`);
          if (k % 10 === 9) await e.settled(60_000); // stay under the per-user op rate
        }
        await e.settled(60_000);
      }
      for (const e of editors) await e.until(() => e.view().lists.flatMap((l) => l.cards).length === 40, 60_000);
    }

    const editFixed = (c: SyncClient) => {
      const lists = c.view().lists.filter((l) => l.id !== "00000000-0000-0000-0000-000000000000");
      const cards = lists.flatMap((l) => l.cards);
      if (!cards.length) return;
      const card = cards[rnd(cards.length)]!.id, r = Math.random();
      if (r < 0.35) c.setField(card, "title", `t ${rnd(1000)}`);
      else if (r < 0.6) c.setField(card, "description", `d ${rnd(1000)}`);
      else if (r < 0.7) c.setField(card, "labels", [`l${rnd(5)}`]);
      else {
        const list = lists[rnd(lists.length)]!;
        c.moveCard(card, list.id, rnd(list.cards.length + 1));
      }
      counts.edits++;
    };

    const edit = (c: SyncClient) => {
      if (FIXED) return editFixed(c);
      const lists = c.view().lists.filter((l) => l.id !== "00000000-0000-0000-0000-000000000000");
      if (lists.length < 3) return c.addList(`list ${Date.now()}`);
      const list = lists[rnd(lists.length)]!;
      const cards = lists.flatMap((l) => l.cards);
      const r = Math.random();
      if (cards.length > 60) c.deleteCard(cards[rnd(cards.length)]!.id);
      else if (r < 0.4 || cards.length === 0) c.addCard(list.id, `card ${Date.now()}`);
      else if (r < 0.7) c.setField(cards[rnd(cards.length)]!.id, "title", `t ${Date.now()}`);
      else c.moveCard(cards[rnd(cards.length)]!.id, list.id, rnd(list.cards.length + 1));
      counts.edits++;
    };

    const loops: Promise<void>[] = [];
    // Steady editors.
    for (const e of editors) loops.push((async () => {
      while (Date.now() < until) {
        try { edit(e); } catch (err) { counts.errors++; console.log(`soak error (edit): ${String(err)}`); }
        await sleep(EDIT_MS);
      }
    })());
    // Connection churn, one at a time per board.
    for (const [i, b] of boards.entries()) loops.push((async () => {
      while (Date.now() < until) {
        const events: string[] = [];
        let opened: SyncClient | undefined;
        try {
          const c = (opened = await open(b.board, b.users[rnd(b.users.length)]!.session, rnd(WS.length), events));
          counts.connections++;
          await c.until(() => c.status === "joined", 30_000);
          const cards = c.view().lists.flatMap((l) => l.cards);
          c.setPresence(cards.length ? cards[rnd(cards.length)]!.id : null);
          if (Math.random() < 0.5) edit(c);
          await sleep(3_000 + rnd(5_000));
          await c.saved();
        } catch (err) {
          counts.errors++;
          console.log(`soak error (churn) at ${new Date().toISOString()}: ${String(err)}; its last events: ${events.join(" | ")}`);
          await sleep(1_000);
        } finally {
          opened?.close(); // a client that failed to join must not keep retrying in the background
        }
        void i;
      }
    })());
    // Slow consumers.
    loops.push((async () => {
      while (Date.now() < until) {
        const b = boards[rnd(boards.length)]!;
        try { await slowConsumer(b.board, b.users[0]!.session, rnd(WS.length)); counts.slow++; } catch (err) { counts.errors++; console.log(`soak error (slow consumer): ${String(err)}`); }
        await sleep(100_000);
      }
    })());
    // Sampler.
    loops.push((async () => {
      const start = Date.now();
      while (true) {
        const sample: Record<string, unknown> = { at: new Date().toISOString(), minute: Math.round((Date.now() - start) / 60_000), target: PROD ? "prod" : "dev", workload: FIXED ? "fixed" : "growing", ...counts };
        for (const j of JVMS) sample[j] = heapAfterFullGc(j);
        sample.mem = memUsage();
        appendFileSync(file, `${JSON.stringify(sample)}\n`);
        console.log(JSON.stringify(sample));
        if (Date.now() >= until) break;
        await sleep(Math.min(300_000, Math.max(0, until - Date.now())));
      }
    })());
    await Promise.all(loops);
    for (const e of editors) e.close();
    console.log(`soak: ${MINUTES} min, ${JSON.stringify(counts)}, samples in ${file}`);
    expect(counts.connections).toBeGreaterThan(MINUTES * 4 * 6); // at least ~6 per minute per board
  }, (MINUTES + 15) * 60_000);
});
