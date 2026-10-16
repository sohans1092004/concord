// Last-writer-wins registers, one per (entity, field) (SPEC §3.4). Encoding: §9.2.3.

import { canonicalize, compareBytes, utf8, type Json } from "./canonical.js";
import { compare as compareHlc, decode as decodeHlc, encode as encodeHlc, isUuid, type Hlc } from "./hlc.js";

export interface Register {
  readonly value: Json;
  readonly hlc: Hlc;
}

export type LwwJson = {
  readonly [entity: string]: { readonly [field: string]: { readonly hlc: ReturnType<typeof encodeHlc>; readonly value: Json } };
};

function fail(why: string): never {
  throw new Error(`lww: ${why}`);
}

/**
 * Which register survives, and whether the defensive tie-break fired: equal HLCs with
 * different values, resolved by the greater canonical encoding as unsigned UTF-8 bytes.
 */
export function pick(a: Register, b: Register): { winner: Register; collision: boolean } {
  const c = compareHlc(a.hlc, b.hlc);
  if (c !== 0) return { winner: c > 0 ? a : b, collision: false };
  if (a.value === b.value) return { winner: a, collision: false };
  const ea = utf8(canonicalize(a.value));
  const eb = utf8(canonicalize(b.value));
  const v = compareBytes(ea, eb);
  return { winner: v >= 0 ? a : b, collision: v !== 0 };
}

export class Lww {
  private readonly regs = new Map<string, Map<string, Register>>();

  get(entity: string, field: string): Register | undefined {
    return this.regs.get(entity)?.get(field);
  }

  fields(entity: string): ReadonlyMap<string, Register> | undefined {
    return this.regs.get(entity);
  }

  /** Writes if `hlc` wins. Returns 1 if the tie-break fired, else 0. */
  set(entity: string, field: string, value: Json, hlc: Hlc): number {
    return this.put(entity, field, { value, hlc });
  }

  private put(entity: string, field: string, r: Register): number {
    let fields = this.regs.get(entity);
    if (!fields) this.regs.set(entity, (fields = new Map()));
    const cur = fields.get(field);
    if (!cur) {
      fields.set(field, r);
      return 0;
    }
    const { winner, collision } = pick(cur, r);
    if (winner !== cur) fields.set(field, winner);
    return collision ? 1 : 0;
  }

  /** Merged copy plus the number of registers whose tie-break fired. */
  static merge(a: Lww, b: Lww): { lww: Lww; collisions: number } {
    const lww = a.clone();
    return { lww, collisions: lww.mergeFrom(b) };
  }

  /** In-place merge; returns the collision count. */
  mergeFrom(other: Lww): number {
    let collisions = 0;
    for (const [entity, fields] of other.regs) for (const [field, r] of fields) collisions += this.put(entity, field, r);
    return collisions;
  }

  clone(): Lww {
    const out = new Lww();
    for (const [entity, fields] of this.regs) out.regs.set(entity, new Map(fields)); // registers are immutable
    return out;
  }

  encode(): LwwJson {
    const out: Record<string, Record<string, { hlc: ReturnType<typeof encodeHlc>; value: Json }>> = {};
    for (const [entity, fields] of this.regs) {
      const f: Record<string, { hlc: ReturnType<typeof encodeHlc>; value: Json }> = {};
      for (const [field, r] of fields) f[field] = { hlc: encodeHlc(r.hlc), value: r.value };
      out[entity] = f;
    }
    return out;
  }

  /** Structural decode. Field-specific value checks belong to the caller (board). */
  static decode(x: unknown): Lww {
    if (typeof x !== "object" || x === null || Array.isArray(x)) fail("not an object");
    const out = new Lww();
    for (const [entity, fields] of Object.entries(x as Record<string, unknown>)) {
      if (!isUuid(entity)) fail(`bad entity ${entity}`);
      if (typeof fields !== "object" || fields === null || Array.isArray(fields)) fail(`${entity} is not an object`);
      const entries = Object.entries(fields as Record<string, unknown>);
      if (entries.length === 0) fail(`${entity} has no fields`);
      for (const [field, reg] of entries) {
        if (typeof reg !== "object" || reg === null || Array.isArray(reg)) fail(`${entity}.${field} is not an object`);
        const r = reg as Record<string, unknown>;
        if (Object.keys(r).length !== 2 || !("value" in r)) fail(`${entity}.${field} must have exactly hlc and value`);
        const hlc = decodeHlc(r.hlc);
        if (!hlc) fail(`${entity}.${field}.hlc is invalid`);
        canonicalize(r.value as Json); // throws on non-integers, lone surrogates
        out.put(entity, field, { value: r.value as Json, hlc });
      }
    }
    return out;
  }
}
