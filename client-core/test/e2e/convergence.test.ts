// Phase 2 exit criterion (SPEC §10): two client-core instances on DIFFERENT gateways converge
// to the same state hash across a randomized session of at least 1,000 ops, including
// concurrent moves, deletes and restores. Needs the compose stack (deploy/docker-compose.yml).
import { describe, expect, it } from "vitest";
import { client, compose, GATEWAYS, psql, seed, sleep, storedOpIds } from "./stack.js";
import { prng, randomEdit } from "./workload.js";

const OPS = Number(process.env.E2E_OPS ?? 1_000);
const SEED = Number(process.env.E2E_SEED ?? Date.now() % 1_000_000);

async function converged(clients: { c: { confirmedHash(): Promise<string>; confirmedVersion: number | null } }[], board: string) {
  const version = Number(psql(`SELECT version FROM boards WHERE id = '${board}'`)[0]);
  for (const { c } of clients) await (c as never as { until(t: () => boolean, ms: number): Promise<void> }).until(() => c.confirmedVersion === version, 30_000);
  return { version, hashes: await Promise.all(clients.map(({ c }) => c.confirmedHash())) };
}

describe("convergence across gateways", () => {
  it(`two clients on different gateways converge over ${OPS}+ random ops (seed ${SEED})`, async () => {
    const s = seed(1);
    const a = await client(s.board, s.users[0]!.session, 0);
    const b = await client(s.board, s.users[1]!.session, 1);
    await a.c.until(() => a.c.status === "joined");
    await b.c.until(() => b.c.status === "joined");
    expect(new Set([a.events.find((e) => e.event === "joined"), b.events.find((e) => e.event === "joined")]).size).toBe(2);

    const ra = prng(SEED), rb = prng(SEED + 1);
    const da = new Set<string>(), db = new Set<string>();
    let submitted = 0;
    const started = Date.now();
    while (submitted < OPS) {
      // Both edit from their own views at once, so most ops are concurrent.
      submitted += randomEdit(a.c, ra, da) + randomEdit(b.c, rb, db);
      await sleep(40 + Math.floor(ra() * 40)); // under the 20 ops/s limit per connection
    }
    await a.c.settled(120_000);
    await b.c.settled(120_000);
    const { version, hashes } = await converged([a, b], s.board);
    const fresh = await client(s.board, s.users[0]!.session, 1); // a third replica, from scratch
    await fresh.c.until(() => fresh.c.status === "joined" && fresh.c.confirmedVersion === version);
    const freshHash = await fresh.c.confirmedHash();

    const stored = storedOpIds(s.board);
    const acked = [...a.acked.keys(), ...b.acked.keys()];
    const types = psql(`SELECT type, count(*) FROM board_ops WHERE board_id = '${s.board}' GROUP BY type ORDER BY type`);
    console.log(
      `convergence: seed ${SEED}, ${submitted} ops submitted, ${acked.length} ACKed, board version ${version}, ` +
        `${((Date.now() - started) / 1000).toFixed(1)}s, unsent ${a.c.unsentEdits().length + b.c.unsentEdits().length}, ` +
        `gateways ${GATEWAYS.join(" & ")}, hash ${hashes[0]}\n  by type: ${types.join(", ")}`,
    );
    expect(submitted).toBeGreaterThanOrEqual(OPS);
    expect(hashes[0]).toBe(hashes[1]);
    expect(freshHash).toBe(hashes[0]);
    expect(a.c.unsentEdits()).toEqual([]);
    expect(b.c.unsentEdits()).toEqual([]);
    for (const id of acked) expect(stored.has(id), `ACKed ${id} missing from board_ops`).toBe(true);
    expect(stored.size).toBe(version);
    for (const t of ["SET_PLACEMENT", "DELETE_CARD", "RESTORE_CARD"]) expect(types.some((r) => r.startsWith(`${t}|`))).toBe(true);
    for (const x of [a, b, fresh]) x.c.close();
    void compose;
  });
});
