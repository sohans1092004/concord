// Helpers for end-to-end tests and drills against the running compose stack
// (deploy/docker-compose.yml): seeding via devtools, docker control, psql, and clients.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { MemoryStorage, SyncClient, type ClientOptions } from "../../src/index.js";

const ROOT = fileURLToPath(new URL("../../../", import.meta.url));
export const COMPOSE = `${ROOT}deploy/docker-compose.yml`;
export const GATEWAYS = ["ws://localhost:8081/ws", "ws://localhost:8082/ws"];

export interface Seeded {
  board: string;
  users: { id: string; role: string; name: string; session: string }[];
}

/** One board with an owner, `editors` editors and `viewers` viewers (devtools, dev only). */
export function seed(editors: number, viewers = 0): Seeded {
  const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : "java";
  const out = execFileSync(java, ["-cp", `${ROOT}server/devtools/build/install/devtools/lib/*`, "dev.concord.devtools.Dev", "seed", String(editors), String(viewers)], {
    env: { ...process.env, CONCORD_ENV: "dev", CONCORD_DB_URL: "jdbc:postgresql://localhost:5432/concord", CONCORD_REDIS_URL: "redis://localhost:6379" },
    encoding: "utf8",
  });
  return JSON.parse(out) as Seeded;
}

export function devtools(...args: string[]): string {
  const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : "java";
  return execFileSync(java, ["-cp", `${ROOT}server/devtools/build/install/devtools/lib/*`, "dev.concord.devtools.Dev", ...args], {
    env: { ...process.env, CONCORD_ENV: "dev", CONCORD_DB_URL: "jdbc:postgresql://localhost:5432/concord", CONCORD_REDIS_URL: "redis://localhost:6379" },
    encoding: "utf8",
  });
}

export function compose(...args: string[]): string {
  return execFileSync("docker", ["compose", "-f", COMPOSE, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

export function docker(...args: string[]): string {
  return execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

/** Rows from psql -At inside the postgres container. */
export function psql(sql: string): string[] {
  return compose("exec", "-T", "postgres", "psql", "-U", "concord", "-d", "concord", "-At", "-c", sql).split("\n").filter((l) => l.length > 0);
}

export function redisCli(...args: string[]): string {
  return compose("exec", "-T", "redis", "redis-cli", ...args).trim();
}

/** Every op_id stored for the board. */
export function storedOpIds(board: string): Set<string> {
  return new Set(psql(`SELECT op_id FROM board_ops WHERE board_id = '${board}'`));
}

/** A single-use WebSocket ticket from the REST API of the gateway behind `wsUrl` (SPEC §9.4). */
export async function ticket(session: string, wsUrl: string = GATEWAYS[0]!): Promise<string> {
  const rest = wsUrl.replace(/^ws/, "http").replace(/\/ws$/, "/api/ws-ticket");
  const r = await fetch(rest, { method: "POST", headers: { Authorization: `Bearer ${session}`, "Content-Type": "application/json" }, body: "{}" });
  if (!r.ok) throw new Error(`ticket: ${r.status}`);
  return ((await r.json()) as { ticket: string }).ticket;
}

/** A client of `board` signed in as `session`, pinned to one gateway first; records every ACK. */
export async function client(board: string, session: string, gateway: number, extra: Partial<ClientOptions> = {}) {
  const acked = new Map<string, number>();
  const events: { event: string; data: Record<string, unknown>; at: number }[] = [];
  const urls = [...GATEWAYS.slice(gateway), ...GATEWAYS.slice(0, gateway)];
  let first = true;
  const c = await SyncClient.open({
    urls,
    boardId: board,
    ticket: (url) => ticket(session, url),
    storage: new MemoryStorage(),
    // The first connection goes to the chosen gateway; later ones rotate through the list.
    random: () => (first ? ((first = false), 0) : Math.random()),
    log: (event, data) => {
      events.push({ event, data, at: Date.now() });
      if (event === "acked") acked.set(data.op_id as string, data.version as number);
    },
    ...extra,
  });
  return { c, acked, events };
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
