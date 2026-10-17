// Memory soak (Phase 4a step 1), against a running compose stack. Hours long, so it runs only
// with SOAK_MINUTES set:
//   cd client-core && SOAK_MINUTES=120 npx vitest run test/soak
// Load, for the whole run:
//   - 4 boards, 2 editors each, one edit every 3s (adds, renames, moves; deletes keep it bounded);
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
import type { SyncClient } from "../../src/index.js";
import { client, docker, GATEWAYS, seed, sleep, ticket } from "../e2e/stack.js";

const MINUTES = Number(process.env.SOAK_MINUTES ?? 0);
const JVMS = ["deploy-gateway1-1", "deploy-gateway2-1", "deploy-session1-1", "deploy-session2-1", "deploy-session3-1"];
const OUT = fileURLToPath(new URL("../../../deploy/soak/", import.meta.url));

/** Used heap after a full GC, in KB, from `jcmd 1 GC.heap_info` ("garbage-first heap total X, used Y"). */
function heapAfterFullGc(container: string): number {
  docker("exec", container, "jcmd", "1", "GC.run");
  const info = docker("exec", container, "jcmd", "1", "GC.heap_info");
  const m = /garbage-first heap\s+total (?:reserved \d+K, committed )?(\d+)K, used (\d+)K/.exec(info) ?? /total (\d+)K, used (\d+)K/.exec(info);
  if (!m) throw new Error(`cannot read heap_info of ${container}: ${info.slice(0, 200)}`);
  return Number(m[2]);
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
  const url = new URL(GATEWAYS[gateway]!);
  const t = await ticket(session, GATEWAYS[gateway]);
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
    const boards = Array.from({ length: 4 }, () => seed(2));
    const counts = { edits: 0, connections: 0, slow: 0, errors: 0 };
    const rnd = (n: number) => Math.floor(Math.random() * n);

    const editors: SyncClient[] = [];
    for (const [i, b] of boards.entries()) {
      for (let k = 0; k < 2; k++) editors.push((await client(b.board, b.users[k]!.session, (i + k) % 2)).c);
    }
    await Promise.all(editors.map((e) => e.until(() => e.status === "joined", 60_000)));

    const edit = (c: SyncClient) => {
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
        await sleep(3_000);
      }
    })());
    // Connection churn, one at a time per board.
    for (const [i, b] of boards.entries()) loops.push((async () => {
      while (Date.now() < until) {
        try {
          const { c } = await client(b.board, b.users[rnd(b.users.length)]!.session, rnd(2));
          counts.connections++;
          await c.until(() => c.status === "joined", 30_000);
          const cards = c.view().lists.flatMap((l) => l.cards);
          c.setPresence(cards.length ? cards[rnd(cards.length)]!.id : null);
          if (Math.random() < 0.5) edit(c);
          await sleep(3_000 + rnd(5_000));
          await c.saved();
          c.close();
        } catch (err) {
          counts.errors++;
          console.log(`soak error (churn): ${String(err)}`);
          await sleep(1_000);
        }
        void i;
      }
    })());
    // Slow consumers.
    loops.push((async () => {
      while (Date.now() < until) {
        const b = boards[rnd(boards.length)]!;
        try { await slowConsumer(b.board, b.users[0]!.session, rnd(2)); counts.slow++; } catch (err) { counts.errors++; console.log(`soak error (slow consumer): ${String(err)}`); }
        await sleep(100_000);
      }
    })());
    // Sampler.
    loops.push((async () => {
      const start = Date.now();
      while (true) {
        const sample: Record<string, unknown> = { at: new Date().toISOString(), minute: Math.round((Date.now() - start) / 60_000), ...counts };
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
