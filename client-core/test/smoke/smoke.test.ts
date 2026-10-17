// The smoke test (Phase 4a step 10), against a live deployment over the public internet:
//   cd client-core && SMOKE_URL=https://concord-<me>.duckdns.org SMOKE_INVITE=<code> npx vitest run test/smoke
// Signs up two users with the invite code, one creates a board and adds the other, two headless
// clients edit it at once over wss:// and must converge on the same state hash; the security
// headers and /healthz are checked; then both accounts are deleted (which deletes the board).
//
// SMOKE_LIVE_SECONDS=N adds the deploy-under-load check: three clients edit one board for N
// seconds while you run deploy/prod/deploy.sh (or restart anything); afterwards every op any
// client saw ACKed must be in the board's history, all converge, and nothing is Unsent.
import { describe, expect, it } from "vitest";
import { MemoryStorage, SyncClient } from "../../src/index.js";
import { prng, randomEdit } from "../e2e/workload.js";

const URL_ = process.env.SMOKE_URL?.replace(/\/$/, "");
const INVITE = process.env.SMOKE_INVITE ?? "";
const PASSWORD = "smoke test password, deleted at the end";

interface Account {
  id: string;
  email: string;
  session: string;
}

async function api(path: string, init: { method?: string; body?: unknown; session?: string } = {}) {
  const r = await fetch(`${URL_}${path}`, {
    method: init.method ?? "GET",
    headers: {
      Origin: URL_!,
      ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(init.session ? { Authorization: `Bearer ${init.session}` } : {}),
    },
    ...(init.body !== undefined ? { body: JSON.stringify(init.body) } : {}),
  });
  const text = await r.text();
  return { status: r.status, headers: r.headers, json: text ? (JSON.parse(text) as Record<string, unknown>) : {} };
}

async function signUp(name: string): Promise<Account> {
  const email = `smoke-${crypto.randomUUID().slice(0, 8)}@smoke.invalid`;
  const r = await fetch(`${URL_}/api/auth/sign-up`, {
    method: "POST", headers: { Origin: URL_!, "Content-Type": "application/json" },
    body: JSON.stringify({ email, name, password: PASSWORD, invite_code: INVITE }),
  });
  expect(r.status, await r.clone().text()).toBe(201);
  const session = /concord_session=([^;]+)/.exec(r.headers.getSetCookie().join("\n"))![1]!;
  return { id: ((await r.json()) as { id: string }).id, email, session };
}

function client(board: string, who: Account, log?: (event: string, data: Record<string, unknown>) => void): Promise<SyncClient> {
  const ws = `${URL_!.replace(/^http/, "ws")}/ws`;
  return SyncClient.open({
    urls: [ws], boardId: board, storage: new MemoryStorage(),
    ticket: async () => ((await api("/api/ws-ticket", { method: "POST", body: {}, session: who.session })).json.ticket as string),
    ...(log ? { log } : {}),
  });
}

/** Every op_id in the board's history (the gateway's view of board_ops). */
async function historyOpIds(board: string, who: Account): Promise<string[]> {
  const ids: string[] = [];
  let before: unknown = null;
  for (;;) {
    const q = before === null ? "" : `&before_version=${before}`;
    const page = (await api(`/api/boards/${board}/history?limit=200${q}`, { session: who.session })).json;
    for (const e of page.entries as { op_id: string }[]) ids.push(e.op_id);
    before = page.next_before_version;
    if (before === null || before === undefined) return ids;
  }
}

describe.skipIf(!URL_)("smoke test against a live deployment", () => {
  it(`works end to end at ${URL_}`, async () => {
    const started = Date.now();
    // Up, and the headers the deployment promises.
    const health = await fetch(`${URL_}/healthz`);
    expect(health.status).toBe(200);
    const page = await fetch(`${URL_}/`);
    expect(page.status).toBe(200);
    expect(page.headers.get("content-security-policy")).toContain("default-src 'self'");
    expect(page.headers.get("strict-transport-security")).toContain("max-age=");
    expect(page.headers.get("x-content-type-options")).toBe("nosniff");
    expect(await page.text()).toContain('<div id="root">');
    // Without the invite code, sign-up is refused.
    const noInvite = await fetch(`${URL_}/api/auth/sign-up`, {
      method: "POST", headers: { Origin: URL_!, "Content-Type": "application/json" },
      body: JSON.stringify({ email: `smoke-x-${Date.now()}@smoke.invalid`, name: "x", password: PASSWORD }),
    });
    expect(noInvite.status).toBe(403);

    const a = await signUp("Smoke A");
    const b = await signUp("Smoke B");
    let clients: SyncClient[] = [];
    try {
      const board = (await api("/api/boards", { method: "POST", body: { title: "Smoke test" }, session: a.session })).json.id as string;
      expect((await api(`/api/boards/${board}/members`, { method: "POST", body: { email: b.email, role: "editor" }, session: a.session })).status).toBe(201);
      clients = [await client(board, a), await client(board, b)];
      await Promise.all(clients.map((c) => c.until(() => c.status === "joined", 60_000)));
      const list = clients[0]!.addList("Smoke list");
      await clients[1]!.until(() => clients[1]!.view().lists.some((l) => l.id === list), 30_000);
      // Both at once: 25 edits each on the same list, then converge.
      await Promise.all(clients.map(async (c, i) => {
        for (let k = 0; k < 25; k++) {
          const cards = c.view().lists.find((l) => l.id === list)?.cards ?? [];
          if (k % 5 === 4 && cards.length) c.moveCard(cards[k % cards.length]!.id, list, 0);
          else if (k % 7 === 6 && cards.length) c.setField(cards[0]!.id, "title", `renamed by ${i} at ${k}`);
          else c.addCard(list, `card ${i}-${k}`);
          await new Promise((r) => setTimeout(r, 100)); // under the 20 ops/s limit
        }
      }));
      await Promise.all(clients.map((c) => c.settled(120_000)));
      await clients[0]!.until(() => clients[0]!.confirmedVersion === clients[1]!.confirmedVersion, 30_000);
      await clients[1]!.until(() => clients[0]!.confirmedVersion === clients[1]!.confirmedVersion, 30_000);
      const [ha, hb] = await Promise.all(clients.map((c) => c.confirmedHash()));
      expect(ha).toBe(hb);
      expect(clients[0]!.unsentEdits()).toHaveLength(0);
      expect(clients[1]!.unsentEdits()).toHaveLength(0);
      console.log(`smoke: ${URL_}, board version ${clients[0]!.confirmedVersion}, both on ${ha!.slice(0, 12)}…, ${Date.now() - started}ms`);
    } finally {
      for (const c of clients) c.close();
      // Clean up: B leaves, then A's deletion removes the now single-member board.
      // SMOKE_KEEP=1 keeps them (the rehearsal uses it to have data to back up).
      if (!process.env.SMOKE_KEEP) {
        for (const who of [b, a]) expect((await api("/api/me", { method: "DELETE", body: { password: PASSWORD }, session: who.session })).status).toBe(200);
        expect((await api("/api/me", { session: a.session })).status).toBe(401);
      }
    }
  }, 300_000);
});

const LIVE_S = Number(process.env.SMOKE_LIVE_SECONDS ?? 0);
describe.skipIf(!URL_ || !LIVE_S)("editing through a deploy", () => {
  it(`loses nothing over ${LIVE_S}s of live editing`, async () => {
    const a = await signUp("Live A");
    const board = (await api("/api/boards", { method: "POST", body: { title: "Live editing" }, session: a.session })).json.id as string;
    const acked = new Set<string>();
    let joins = 0;
    const clients = await Promise.all([0, 1, 2].map(() => client(board, a, (event, data) => {
      if (event === "acked") acked.add(data.op_id as string);
      if (event === "joined") joins++;
    })));
    try {
      await Promise.all(clients.map((c) => c.until(() => c.status === "joined", 60_000)));
      clients[0]!.addList("live");
      await clients[0]!.settled(60_000);
      const end = Date.now() + LIVE_S * 1000;
      let submitted = 0;
      await Promise.all(clients.map(async (c, i) => {
        const rnd = prng(Date.now() + i);
        const deleted = new Set<string>();
        while (Date.now() < end) {
          submitted += randomEdit(c, rnd, deleted);
          await new Promise((r) => setTimeout(r, 250 + Math.floor(rnd() * 250))); // ~3 ops/s each, under the per-user limit
        }
      }));
      await Promise.all(clients.map((c) => c.settled(300_000)));
      const history = await historyOpIds(board, a);
      const version = Math.max(...clients.map((c) => c.confirmedVersion ?? 0));
      await Promise.all(clients.map((c) => c.until(() => c.confirmedVersion === version, 60_000)));
      const fresh = await client(board, a);
      await fresh.until(() => fresh.status === "joined" && fresh.confirmedVersion === version, 60_000);
      const hashes = new Set(await Promise.all([...clients, fresh].map((c) => c.confirmedHash())));
      fresh.close();
      const stored = new Set(history);
      const missing = [...acked].filter((id) => !stored.has(id));
      const unsent = clients.flatMap((c) => c.unsentEdits().map((u) => `${u.op.type} ${u.code}: ${u.detail}`));
      console.log(`LIVE ${JSON.stringify({ url: URL_, seconds: LIVE_S, submitted, acked: acked.size, board_version: version, history: history.length, acked_missing: missing.length, unsent: unsent.length, unsent_detail: unsent, rejoins: joins - clients.length, converged: hashes.size === 1 })}`);
      expect(missing, "every ACKed op is in the board's history").toEqual([]);
      expect(history.length).toBe(version);
      expect(unsent, "no edit ends in Unsent edits").toEqual([]);
      expect(hashes.size, "all clients and a fresh one converge").toBe(1);
    } finally {
      for (const c of clients) c.close();
      if (!process.env.SMOKE_KEEP) expect((await api("/api/me", { method: "DELETE", body: { password: PASSWORD }, session: a.session })).status).toBe(200);
    }
  }, LIVE_S * 1000 + 600_000);
});
