// The SPEC §10.1 measurements, against the dev compose stack on this machine. One per run:
//   cd client-core && LOAD=<name> npx vitest run test/load
// baseline | editors | batching | many-boards | inflight | offline (OFFLINE_HOURS=8 or 24).
// Each prints `LOAD {...}` lines; production/load-tests/README.md records them.
//
// Sync latency here is exact: every client is in this one Node process, so the time an op is
// submitted and the time another client applies it (its peer.render span, taken back to this
// process's clock) are read from one clock. No clock offset enters it.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { describe, expect, it } from "vitest";
import { MemoryStorage, SyncClient, type Socket, type Telemetry } from "../../src/index.js";
import { client, compose, GATEWAYS, psql, seed, type Seeded, sleep, storedOpIds, ticket } from "../e2e/stack.js";
import { prng, randomEdit } from "../e2e/workload.js";

const MODE = process.env.LOAD;
const run = (name: string) => (MODE === name ? it : it.skip);
const exec = promisify(execFile);
type Client = SyncClient;

/** The client's clock offset (test-only access): physical time = Date.now() + offset. */
const offsetOf = (c: Client) => (c as unknown as { offsetMs: number }).offsetMs;
const setOffset = (c: Client, ms: number) => ((c as unknown as { offsetMs: number }).offsetMs = ms);

/** Submit times (this process's clock) and apply times on every other client, per op. */
class Latency {
  readonly sent = new Map<string, number>();
  samples: number[] = [];
  /** A telemetry sink for client `c`: each peer.render span is one op applied there. */
  sink(get: () => Client) {
    return (t: Telemetry) => {
      const off = offsetOf(get());
      for (const s of t.spans) {
        if (s.name !== "peer.render") continue;
        const at = this.sent.get(s.op_id);
        if (at !== undefined) this.samples.push(s.end_ms - off - at);
      }
    };
  }
  take(): number[] {
    const s = this.samples;
    this.samples = [];
    return s;
  }
}

function stats(xs: number[]) {
  const s = [...xs].sort((a, b) => a - b);
  const q = (p: number) => (s.length ? Math.round(s[Math.min(s.length - 1, Math.floor(p * s.length))]!) : null);
  return { samples: s.length, p50: q(0.5), p95: q(0.95), p99: q(0.99), max: s.length ? Math.round(s[s.length - 1]!) : null };
}

/** Summed counters from every Session instance's Prometheus text. */
async function sessionCounters(): Promise<{ commits: number; ops: number }> {
  let commits = 0, ops = 0;
  for (const port of [7001, 7002, 7003]) {
    const text = await (await fetch(`http://localhost:${port}/metrics/prometheus`)).text();
    for (const line of text.split("\n")) {
      if (line.startsWith("concord_session_commit_ms_count ")) commits += Number(line.split(" ")[1]);
      if (line.startsWith("concord_session_committed_ops_total ")) ops += Number(line.split(" ")[1]);
    }
  }
  return { commits, ops };
}

/** A board whose owner made one list and `cards` cards; every user joined, with latency sinks. */
async function board(lat: Latency, seeded: Seeded, cards: number) {
  const clients: Client[] = [];
  for (let i = 0; i < seeded.users.length; i++) {
    let me: Client | undefined;
    const x = await client(seeded.board, seeded.users[i]!.session, i % 2, { telemetry: lat.sink(() => me!) });
    me = x.c;
    clients.push(x.c);
  }
  for (const c of clients) await c.until(() => c.status === "joined", 60_000);
  const owner = clients[0]!;
  const list = owner.addList("load");
  for (let i = 0; i < cards; i++) owner.addCard(list, `card ${i}`);
  await owner.settled(60_000);
  for (const c of clients) await c.until(() => (c.view().lists[0]?.cards.length ?? 0) === cards, 60_000);
  return { clients, cardIds: owner.view().lists[0]!.cards.map((c) => c.id) };
}

/** Editor i renames its own card once a second until stopped (§10.1: 1 op/s each). */
function editing(lat: Latency, editors: Client[], cardIds: string[]) {
  let on = true;
  let submitted = 0;
  const loops = editors.map(async (c, i) => {
    await sleep(Math.floor((i * 1000) / editors.length)); // spread them over the second
    for (let n = 0; on; n++) {
      if (c.status === "joined") {
        c.setField(cardIds[i % cardIds.length]!, "title", `editor ${i} edit ${n}`);
        lat.sent.set(c.pendingOps().at(-1)!.op_id, Date.now());
        submitted++;
      }
      await sleep(1000);
    }
  });
  return { stop: async () => ((on = false), await Promise.all(loops)), submitted: () => submitted };
}

/** Runs the load for `seconds` and reports latency and commit rates for that window only. */
async function window(lat: Latency, clients: Client[], seconds: number) {
  for (const c of clients) c.flushTelemetry(); // what was applied before the window is not in it
  lat.take();
  const before = await sessionCounters();
  const started = Date.now();
  await sleep(seconds * 1000);
  const after = await sessionCounters();
  const elapsed = (Date.now() - started) / 1000;
  for (const c of clients) c.flushTelemetry();
  const s = stats(lat.take());
  const commits = after.commits - before.commits, ops = after.ops - before.ops;
  return { seconds: Math.round(elapsed), ...s, committed_ops_per_s: +(ops / elapsed).toFixed(1), commits_per_s: +(commits / elapsed).toFixed(1), ops_per_commit: commits ? +(ops / commits).toFixed(2) : null };
}

/** Everyone settled, on the board's version and one state; every ACKed op stored. */
async function checkConverged(boardId: string, clients: Client[], acked: Set<string>) {
  for (const c of clients) await c.settled(120_000);
  const version = Number(psql(`SELECT version FROM boards WHERE id = '${boardId}'`)[0]);
  for (const c of clients) await c.until(() => c.confirmedVersion === version, 60_000);
  const hashes = new Set(await Promise.all(clients.map((c) => c.confirmedHash())));
  const stored = storedOpIds(boardId);
  const missing = [...acked].filter((id) => !stored.has(id)).length;
  const unsent = clients.reduce((n, c) => n + c.unsentEdits().length, 0);
  return { board_version: version, converged: hashes.size === 1, acked_missing: missing, unsent };
}

function ackTracker() {
  const acked = new Set<string>();
  return { acked, log: (event: string, data: Record<string, unknown>) => { if (event === "acked") acked.add(data.op_id as string); } };
}

/** Seeds boards in parallel (devtools is one JVM start per board). */
async function seedMany(n: number, editors: number, viewers: (i: number) => number): Promise<Seeded[]> {
  const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : "java";
  const root = new URL("../../../", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
  const out: Seeded[] = [];
  for (let i = 0; i < n; i += 8) {
    const batch = Array.from({ length: Math.min(8, n - i) }, (_, k) => exec(java, ["-cp", `${root}server/devtools/build/install/devtools/lib/*`, "dev.concord.devtools.Dev", "seed", String(editors), String(viewers(i + k))], {
      env: { ...process.env, CONCORD_ENV: "dev", CONCORD_DB_URL: "jdbc:postgresql://localhost:5432/concord", CONCORD_REDIS_URL: "redis://localhost:6379" },
    }));
    for (const r of await Promise.all(batch)) out.push(JSON.parse(r.stdout) as Seeded);
  }
  return out;
}

/** docker stats every 10s for the Concord containers: peak and mean CPU %, peak memory. */
function containerStats() {
  const cpu = new Map<string, number[]>(), mem = new Map<string, number>();
  let on = true;
  const loop = (async () => {
    while (on) {
      try {
        const { stdout } = await exec("docker", ["stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}} {{.MemUsage}}"]);
        for (const line of stdout.trim().split("\n")) {
          const [name, c, m] = line.split(" ");
          if (!name?.startsWith("deploy-")) continue;
          const key = name.replace(/^deploy-|-1$/g, "");
          cpu.set(key, [...(cpu.get(key) ?? []), parseFloat(c!)]);
          const mib = m!.endsWith("GiB") ? parseFloat(m!) * 1024 : parseFloat(m!);
          mem.set(key, Math.max(mem.get(key) ?? 0, mib));
        }
      } catch {
        // a missed sample
      }
      await sleep(10_000);
    }
  })();
  return {
    stop: async () => {
      on = false;
      await loop;
      return Object.fromEntries([...cpu.keys()].sort().map((k) => {
        const xs = cpu.get(k)!;
        return [k, { cpu_mean_pct: +(xs.reduce((a, b) => a + b, 0) / xs.length).toFixed(1), cpu_peak_pct: Math.max(...xs), mem_peak_mib: Math.round(mem.get(k)!) }];
      }));
    },
  };
}

describe("§10.1 measurements", () => {
  /** 2 clients, 1 board, 1 op/s each; p95 < 200ms. */
  run("baseline")("baseline", async () => {
    const lat = new Latency();
    const seeded = seed(1);
    const { clients, cardIds } = await board(lat, seeded, 2);
    const load = editing(lat, clients, cardIds);
    await sleep(5_000); // warm up
    const r = await window(lat, clients, Number(process.env.LOAD_SECONDS ?? 120));
    await load.stop();
    console.log(`LOAD ${JSON.stringify({ test: "baseline", clients: 2, ...r })}`);
    for (const c of clients) c.close();
  }, 600_000);

  /** Ramp 2 → 30 editors on one board, 1 op/s each: p95 at each step; correct state at 30. */
  run("editors")("editors", async () => {
    const lat = new Latency();
    const steps = [2, 5, 10, 15, 20, 25, 30];
    const seeded = seed(29);
    const t = ackTracker();
    const all: Client[] = [];
    // Join one at a time as the ramp grows (each with its own sink and ACK log).
    const join = async (i: number) => {
      let me: Client | undefined;
      const x = await client(seeded.board, seeded.users[i]!.session, i % 2, { telemetry: lat.sink(() => me!), log: t.log });
      me = x.c;
      await x.c.until(() => x.c.status === "joined", 60_000);
      all.push(x.c);
    };
    await join(0);
    const owner = all[0]!;
    const list = owner.addList("load");
    for (let i = 0; i < 30; i++) owner.addCard(list, `card ${i}`);
    await owner.settled(60_000);
    const cardIds = owner.view().lists[0]!.cards.map((c) => c.id);
    let load: ReturnType<typeof editing> | null = null;
    for (const n of steps) {
      await load?.stop();
      while (all.length < n) await join(all.length);
      for (const c of all) await c.until(() => (c.view().lists[0]?.cards.length ?? 0) === 30, 60_000);
      load = editing(lat, all, cardIds);
      await sleep(5_000);
      const r = await window(lat, all, Number(process.env.LOAD_SECONDS ?? 30));
      console.log(`LOAD ${JSON.stringify({ test: "editors", editors: n, ...r })}`);
    }
    await load!.stop();
    const check = await checkConverged(seeded.board, all, t.acked);
    console.log(`LOAD ${JSON.stringify({ test: "editors", at: 30, acked: t.acked.size, ...check })}`);
    for (const c of all) c.close();
    expect(check.converged && check.acked_missing === 0 && check.unsent === 0).toBe(true);
  }, 1_800_000);

  /** The 30-editor load against one window (the Session instances run with CONCORD_BATCH_MS). */
  run("batching")("batching", async () => {
    const lat = new Latency();
    const seeded = seed(29);
    const { clients, cardIds } = await board(lat, seeded, 30);
    const load = editing(lat, clients, cardIds);
    await sleep(10_000);
    const r = await window(lat, clients, Number(process.env.LOAD_SECONDS ?? 60));
    await load.stop();
    console.log(`LOAD ${JSON.stringify({ test: "batching", batch_ms: Number(process.env.CONCORD_BATCH_MS ?? "15"), editors: 30, ...r })}`);
    for (const c of clients) c.close();
  }, 900_000);

  /**
   * 1,000 connections across 300 boards, 3 editors each (§10.1): 300 × 3 = 900 editing
   * connections, so 100 of the boards also have a viewer to make 1,000. 1 op/s per editor.
   */
  run("many-boards")("many-boards", async () => {
    const lat = new Latency();
    const seeded = await seedMany(300, 2, (i) => (i < 100 ? 1 : 0));
    const clients: Client[] = [];
    const editors: { c: Client; card: string }[] = [];
    for (const s of seeded) {
      const mine: Client[] = [];
      for (let i = 0; i < s.users.length; i++) {
        let me: Client | undefined;
        const x = await client(s.board, s.users[i]!.session, clients.length % 2, { telemetry: lat.sink(() => me!) });
        me = x.c;
        mine.push(x.c);
        clients.push(x.c);
      }
      await Promise.all(mine.map((c) => c.until(() => c.status === "joined", 120_000)));
      const list = mine[0]!.addList("load");
      const cards = [0, 1, 2].map((k) => mine[0]!.addCard(list, `card ${k}`));
      mine.slice(0, 3).forEach((c, k) => editors.push({ c, card: cards[k]! }));
    }
    for (const { c } of editors) await c.settled(120_000);
    for (const { c } of editors) await c.until(() => (c.view().lists[0]?.cards.length ?? 0) === 3, 120_000);
    const cpu0 = process.cpuUsage();
    const docker = containerStats();
    let on = true;
    const loops = editors.map(async ({ c, card }, i) => {
      await sleep(Math.floor((i * 1000) / editors.length));
      for (let n = 0; on; n++) {
        if (c.status === "joined") {
          c.setField(card, "title", `edit ${n}`);
          lat.sent.set(c.pendingOps().at(-1)!.op_id, Date.now());
        }
        await sleep(1000);
      }
    });
    await sleep(10_000);
    const r = await window(lat, clients, Number(process.env.LOAD_SECONDS ?? 120));
    on = false;
    await Promise.all(loops);
    const per = await docker.stop();
    const used = process.cpuUsage(cpu0);
    const generatorCores = +((used.user + used.system) / 1e6 / (r.seconds + 10)).toFixed(2);
    const joined = clients.filter((c) => c.status === "joined").length;
    console.log(`LOAD ${JSON.stringify({ test: "many-boards", boards: 300, connections: clients.length, editors: editors.length, joined_at_end: joined, offered_ops_per_s: editors.length, ...r, generator_cores: generatorCores, containers: per })}`);
    for (const c of clients) c.close();
  }, 3_600_000);

  /** One connection, one op in flight: submit, wait for its ACK, repeat. */
  run("inflight")("inflight", async () => {
    const seeded = seed(0);
    const x = await client(seeded.board, seeded.users[0]!.session, 0);
    const c = x.c;
    await c.until(() => c.status === "joined", 60_000);
    const list = c.addList("load");
    const card = c.addCard(list, "card");
    await c.settled(60_000);
    const rtt: number[] = [];
    const seconds = Number(process.env.LOAD_SECONDS ?? 30);
    const before = await sessionCounters();
    const end = Date.now() + seconds * 1000;
    for (let n = 0; Date.now() < end; n++) {
      const t0 = performance.now();
      c.setField(card, "title", `edit ${n}`);
      await c.settled(30_000);
      rtt.push(performance.now() - t0);
    }
    const after = await sessionCounters();
    console.log(`LOAD ${JSON.stringify({ test: "inflight", seconds, ops: rtt.length, ops_per_s: +(rtt.length / seconds).toFixed(1), ack_ms: stats(rtt), commits: after.commits - before.commits })}`);
    c.close();
  }, 600_000);

  /**
   * Offline merge, ACCELERATED: B is offline for OFFLINE_HOURS of simulated time and makes one
   * random edit every 15 simulated seconds while A, online, makes one every 30 (the rates of the
   * real-time scripted hour, docs/offline-hour-scripted.md). The hours pass in about a minute.
   *
   * Clock: the server's clock cannot be fast-forwarded, so both clients' clocks are offset
   * instead (test-only, through the client's offset): the board's whole timeline, setup
   * included, runs from OFFLINE_HOURS ago to now, so every op is stamped in the past as it would
   * have been, and none in the future (which the server would refuse, §3.1). At reconnect B's
   * JOIN restores its real offset. What this does not reproduce: hours of real time on the
   * connection, the browser, or storage.
   */
  run("offline")("offline", async () => {
    const hours = Number(process.env.OFFLINE_HOURS ?? 8);
    const span = hours * 3_600_000;
    const seeded = seed(1);
    const [a0, b0] = seeded.users as [Seeded["users"][0], Seeded["users"][0]];
    const ta = ackTracker(), tb = ackTracker();
    const A = (await client(seeded.board, a0.session, 0, { log: ta.log })).c;
    // B's network: a switch in front of every connection it makes.
    let online = true;
    let live: Socket | null = null;
    const events: { event: string; at: number }[] = [];
    const B = await SyncClient.open({
      urls: GATEWAYS, boardId: seeded.board, storage: new MemoryStorage(), ticket: (url) => ticket(b0.session, url),
      log: (event, data) => (events.push({ event, at: Date.now() }), tb.log(event, data)),
      connect: (url) => {
        if (online) return (live = new WebSocket(url) as unknown as Socket);
        const dead: Socket = { readyState: 3, send() {}, close() {}, onopen: null, onmessage: null, onclose: null, onerror: null };
        setTimeout(() => dead.onclose?.({}), 0);
        return dead;
      },
    });
    for (const c of [A, B]) await c.until(() => c.status === "joined", 60_000);
    const realStart = Date.now();
    const at = (simMs: number, c: Client) => setOffset(c, realStart - span + simMs - Date.now());
    at(0, A);
    at(0, B);
    const list = A.addList("offline");
    for (let i = 0; i < 20; i++) A.addCard(list, `card ${i}`);
    await A.settled(60_000);
    await B.until(() => (B.view().lists[0]?.cards.length ?? 0) === 20, 60_000);

    online = false;
    (live as Socket | null)?.close(); // assigned inside connect, which TypeScript cannot see
    await B.until(() => B.status !== "joined", 30_000);
    const rndA = prng(1), rndB = prng(2), delA = new Set<string>(), delB = new Set<string>();
    let editsA = 0, editsB = 0;
    for (let sim = 15_000; sim <= span; sim += 15_000) {
      at(sim, B);
      editsB += randomEdit(B, rndB, delB);
      if (sim % 30_000 === 0) {
        at(sim, A);
        editsA += randomEdit(A, rndA, delA);
        if (sim % 600_000 === 0) await A.settled(120_000); // keep A's queue short, as a person would
      }
      if (sim % 300_000 === 0) await sleep(1);
    }
    at(span, A);
    await A.settled(300_000);
    const pendingB = B.pendingOps().length;
    const stampsB = B.pendingOps().map((o) => o.hlc.physical_ms);

    online = true;
    const onlineAt = Date.now();
    const joinedBack = await (async () => {
      await B.until(() => B.status === "joined", 120_000);
      return events.filter((e) => e.event === "joined").at(-1)!.at;
    })();
    await B.settled(600_000);
    const convergedAt = Date.now();
    const check = await checkConverged(seeded.board, [A, B], new Set([...ta.acked, ...tb.acked]));
    console.log(`LOAD ${JSON.stringify({
      test: "offline", accelerated: true, simulated_hours: hours, real_seconds: Math.round((onlineAt - realStart) / 1000),
      edits_offline_B: editsB, pending_B_at_reconnect: pendingB, edits_online_A: editsA,
      B_stamps_from: new Date(Math.min(...stampsB)).toISOString(), B_stamps_to: new Date(Math.max(...stampsB)).toISOString(),
      online_to_joined_ms: joinedBack - onlineAt, joined_to_all_acked_ms: convergedAt - joinedBack, ...check,
    })}`);
    A.close();
    B.close();
    expect(check.converged && check.acked_missing === 0 && check.unsent === 0).toBe(true);
  }, 3_600_000);

  void compose;
});
