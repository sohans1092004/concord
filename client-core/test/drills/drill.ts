// Drill harness (SPEC §8): run an editing load from several clients on both gateways, inject a
// fault, keep editing, then assert the two things that must hold whatever happened:
//   1. every op any client saw ACKed is in board_ops;
//   2. every client, and a fresh one, converge to the same state hash.
// Each drill prints one `DRILL {...}` line with its measurements.
import { expect } from "vitest";
import { client, compose, psql, redisCli, seed, sleep, storedOpIds, type Seeded } from "../e2e/stack.js";
import { prng, randomEdit } from "../e2e/workload.js";

export interface DrillContext {
  board: string;
  seeded: Seeded;
  clients: Awaited<ReturnType<typeof client>>[];
  /** ACKs seen so far, with the time each arrived. */
  acks(): { op_id: string; at: number }[];
  log(key: string, value: unknown): void;
}

export interface Owner {
  instance: string;
  address: string;
  fence: number;
}

export function ownerOf(board: string): Owner | null {
  const raw = redisCli("GET", `board:owner:${board}`);
  return raw ? (JSON.parse(raw) as Owner) : null;
}

/** A Session instance's /metrics (compose publishes session1..3 on 7001..7003). */
export async function metricsOf(instance: string): Promise<Record<string, number>> {
  const port = 7000 + Number(instance.replace("session", ""));
  return (await (await fetch(`http://localhost:${port}/metrics`)).json()) as Record<string, number>;
}

/** Cuts (or restores) one Session instance's route to Redis, via the compose Toxiproxy. */
export async function redisRoute(instance: string, enabled: boolean): Promise<void> {
  const r = await fetch(`http://localhost:8474/proxies/redis-${instance}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
  if (!r.ok) throw new Error(`toxiproxy: ${r.status} ${await r.text()}`);
}

export function boardVersion(board: string): number {
  return Number(psql(`SELECT version FROM boards WHERE id = '${board}'`)[0]);
}

/** Polls `f` every 100ms until it returns a value; returns it and when it appeared. */
export async function waitFor<T>(f: () => T | null | undefined, ms: number, what: string): Promise<{ value: T; at: number }> {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    try {
      const v = f();
      if (v !== null && v !== undefined && v !== false) return { value: v as T, at: Date.now() };
    } catch {
      // the thing being polled is down; keep polling
    }
    await sleep(100);
  }
  throw new Error(`drill: ${what} not seen within ${ms}ms`);
}

export async function drill(
  name: string,
  opts: { clients?: number; warmupMs?: number; afterMs?: number; seed?: number },
  fault: (ctx: DrillContext) => Promise<void>,
): Promise<Record<string, unknown>> {
  const n = opts.clients ?? 4;
  const seeded = seed(n - 1);
  const result: Record<string, unknown> = { drill: name, board: seeded.board, clients: n };
  const log = (k: string, v: unknown) => (result[k] = v);
  const clients: Awaited<ReturnType<typeof client>>[] = [];
  for (let i = 0; i < n; i++) clients.push(await client(seeded.board, seeded.users[i]!.session, i % 2));
  for (const x of clients) await x.c.until(() => x.c.status === "joined", 30_000);
  // Every client works from the same first list so they collide constantly.
  clients[0]!.c.addList("drill");
  await clients[0]!.c.settled(30_000);

  let editing = true;
  let submitted = 0;
  const loops = clients.map(async (x, i) => {
    const rnd = prng((opts.seed ?? Date.now()) + i);
    const deleted = new Set<string>();
    while (editing) {
      if (x.c.status !== "unauthorized") submitted += randomEdit(x.c, rnd, deleted);
      await sleep(150 + Math.floor(rnd() * 100));
    }
  });
  const ctx: DrillContext = {
    board: seeded.board,
    seeded,
    clients,
    acks: () => clients.flatMap((x) => x.events.filter((e) => e.event === "acked").map((e) => ({ op_id: e.data.op_id as string, at: e.at }))),
    log,
  };
  const started = Date.now();
  await sleep(opts.warmupMs ?? 5_000);
  await fault(ctx);
  await sleep(opts.afterMs ?? 5_000);
  editing = false;
  await Promise.all(loops);

  // Settle: every queue drained, every client at the board's version.
  for (const x of clients) await x.c.settled(180_000);
  const version = boardVersion(seeded.board);
  for (const x of clients) await x.c.until(() => x.c.confirmedVersion === version, 60_000);
  const hashes = await Promise.all(clients.map((x) => x.c.confirmedHash()));
  const fresh = await client(seeded.board, seeded.users[0]!.session, 0);
  await fresh.c.until(() => fresh.c.status === "joined" && fresh.c.confirmedVersion === version, 60_000);
  hashes.push(await fresh.c.confirmedHash());

  const stored = storedOpIds(seeded.board);
  const acked = ctx.acks().map((a) => a.op_id);
  const missing = acked.filter((id) => !stored.has(id));
  const unsent = clients.reduce((s, x) => s + x.c.unsentEdits().length, 0);
  Object.assign(result, {
    duration_s: Number(((Date.now() - started) / 1000).toFixed(1)),
    ops_submitted: submitted,
    acks: acked.length,
    board_version: version,
    rows_in_board_ops: stored.size,
    acked_missing_from_board_ops: missing.length,
    unsent_edits: unsent,
    unsent_detail: clients.flatMap((x) => x.c.unsentEdits().map((u) => `${u.op.type} ${u.code}: ${u.detail}`)),
    clients_converged: new Set(hashes).size === 1,
    state_sha256: hashes[0],
  });
  console.log(`DRILL ${JSON.stringify(result)}`);
  for (const x of [...clients, fresh]) x.c.close();

  expect(missing, "every ACKed op is in board_ops").toEqual([]);
  expect(new Set(hashes).size, "all clients and a fresh one converge").toBe(1);
  expect(stored.size).toBe(version);
  expect(unsent, "no edit ends in Unsent edits").toBe(0);
  void compose;
  return result;
}
