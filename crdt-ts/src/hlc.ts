// Hybrid logical clock (SPEC §3.1, encoding §9.2.1). Kulkarni et al., 2014.
// Pure: the caller passes physical time in, so the module never reads a clock.

import { compareUtf8 } from "./canonical.js";

export interface Hlc {
  readonly physical_ms: number;
  readonly counter: number;
  readonly replica_id: string;
}

export const MAX_PHYSICAL_MS = Number.MAX_SAFE_INTEGER;
export const MAX_COUNTER = 2 ** 31 - 1;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function isUuid(x: unknown): x is string {
  return typeof x === "string" && UUID.test(x);
}

export function zero(replicaId: string): Hlc {
  return { physical_ms: 0, counter: 0, replica_id: replicaId };
}

export function compare(a: Hlc, b: Hlc): number {
  if (a.physical_ms !== b.physical_ms) return a.physical_ms < b.physical_ms ? -1 : 1;
  if (a.counter !== b.counter) return a.counter < b.counter ? -1 : 1;
  return compareUtf8(a.replica_id, b.replica_id);
}

function make(physical_ms: number, counter: number, replica_id: string): Hlc {
  if (counter > MAX_COUNTER) throw new RangeError("hlc: counter overflow");
  return { physical_ms, counter, replica_id };
}

/** Next timestamp for a local event. Strictly greater than `last`. */
export function tick(last: Hlc, nowMs: number): Hlc {
  const pt = Math.max(last.physical_ms, nowMs);
  return make(pt, pt === last.physical_ms ? last.counter + 1 : 0, last.replica_id);
}

/** Next timestamp after seeing `remote`. Strictly greater than both. */
export function receive(last: Hlc, remote: Hlc, nowMs: number): Hlc {
  const pt = Math.max(last.physical_ms, remote.physical_ms, nowMs);
  let counter: number;
  if (pt === last.physical_ms && pt === remote.physical_ms) counter = Math.max(last.counter, remote.counter) + 1;
  else if (pt === last.physical_ms) counter = last.counter + 1;
  else if (pt === remote.physical_ms) counter = remote.counter + 1;
  else counter = 0;
  return make(pt, counter, last.replica_id);
}

export function encode(h: Hlc): { counter: number; physical_ms: number; replica_id: string } {
  return { counter: h.counter, physical_ms: h.physical_ms, replica_id: h.replica_id };
}

/** Parses the §9.2.1 object; returns null if it is not exactly that shape. */
export function decode(x: unknown): Hlc | null {
  if (typeof x !== "object" || x === null || Array.isArray(x)) return null;
  const o = x as Record<string, unknown>;
  if (Object.keys(o).length !== 3) return null;
  const { physical_ms, counter, replica_id } = o;
  if (!Number.isSafeInteger(physical_ms) || (physical_ms as number) < 0) return null;
  if (!Number.isSafeInteger(counter) || (counter as number) < 0 || (counter as number) > MAX_COUNTER) return null;
  if (!isUuid(replica_id)) return null;
  return { physical_ms: physical_ms as number, counter: counter as number, replica_id };
}
