// Observed-remove set of entity IDs (SPEC §3.2). Encoding: SPEC §9.2.3.
// An ID is present while adds[id] minus tombstones[id] is non-empty. Tombstones are
// per entity, so a delete naming X only ever tombstones (X, tag) pairs, and are kept
// forever, so a delete that arrives before its add still wins over the tags it observed.

import { compareUtf8 } from "./canonical.js";
import { isUuid } from "./hlc.js";

export type OrSetJson = {
  readonly adds: { readonly [id: string]: readonly string[] };
  readonly tombstones: { readonly [id: string]: readonly string[] };
};

function fail(why: string): never {
  throw new Error(`orset: ${why}`);
}

export class OrSet {
  private readonly addTags = new Map<string, Set<string>>();
  private readonly tombs = new Map<string, Set<string>>();

  add(id: string, tag: string): void {
    put(this.addTags, id, tag);
  }

  /** Tombstones exactly the (id, tag) pairs observed. */
  remove(id: string, observed: Iterable<string>): void {
    for (const t of observed) put(this.tombs, id, t);
  }

  contains(id: string): boolean {
    return this.liveTags(id).length > 0;
  }

  /** The live (not tombstoned) tags of `id`: what a DELETE from this replica observes. */
  liveTags(id: string): string[] {
    const dead = this.tombs.get(id);
    return [...(this.addTags.get(id) ?? [])].filter((t) => !dead?.has(t)).sort(compareUtf8);
  }

  /** Has `id` at least one add-tag, live or not? */
  everAdded(id: string): boolean {
    return this.addTags.has(id);
  }

  /** Every ID with at least one add-tag, live or not. */
  ids(): IterableIterator<string> {
    return this.addTags.keys();
  }

  static merge(a: OrSet, b: OrSet): OrSet {
    const out = a.clone();
    out.mergeFrom(b);
    return out;
  }

  /** In-place union with `other`. */
  mergeFrom(other: OrSet): void {
    for (const [id, tags] of other.addTags) for (const t of tags) put(this.addTags, id, t);
    for (const [id, tags] of other.tombs) for (const t of tags) put(this.tombs, id, t);
  }

  clone(): OrSet {
    const out = new OrSet();
    out.mergeFrom(this);
    return out;
  }

  encode(): OrSetJson {
    return { adds: encodeTags(this.addTags), tombstones: encodeTags(this.tombs) };
  }

  static decode(x: unknown): OrSet {
    if (typeof x !== "object" || x === null || Array.isArray(x)) fail("not an object");
    const o = x as Record<string, unknown>;
    if (Object.keys(o).length !== 2 || !("adds" in o) || !("tombstones" in o)) fail("members must be adds, tombstones");
    const out = new OrSet();
    decodeTags(o.adds, "adds", out.addTags);
    decodeTags(o.tombstones, "tombstones", out.tombs);
    return out;
  }
}

function put(m: Map<string, Set<string>>, id: string, tag: string): void {
  let tags = m.get(id);
  if (!tags) m.set(id, (tags = new Set()));
  tags.add(tag);
}

/** {id: [tags]} with sorted tags; an entity with no tags has no entry. */
function encodeTags(m: Map<string, Set<string>>): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const [id, tags] of m) out[id] = [...tags].sort(compareUtf8);
  return out;
}

function decodeTags(x: unknown, name: string, into: Map<string, Set<string>>): void {
  if (typeof x !== "object" || x === null || Array.isArray(x)) fail(`${name} is not an object`);
  for (const [id, tags] of Object.entries(x as Record<string, unknown>)) {
    if (!isUuid(id)) fail(`bad id ${id}`);
    if (!Array.isArray(tags) || tags.length === 0) fail(`${name}.${id} must be a non-empty array`);
    for (const t of tags as unknown[]) {
      if (!isUuid(t)) fail(`bad tag under ${name}.${id}`);
      put(into, id, t);
    }
  }
}
