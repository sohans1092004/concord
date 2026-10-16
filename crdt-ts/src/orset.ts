// Observed-remove set of entity IDs (SPEC §3.2). Encoding: SPEC §9.2.3.
// An ID is present while it has an add-tag that is not tombstoned. Tombstones are a
// flat set of tags and are kept forever, so a delete that arrives before its add still
// wins over exactly the tags it observed.

import { compareUtf8 } from "./canonical.js";
import { isUuid } from "./hlc.js";

export type OrSetJson = {
  readonly adds: { readonly [id: string]: readonly string[] };
  readonly tombstones: readonly string[];
};

function fail(why: string): never {
  throw new Error(`orset: ${why}`);
}

export class OrSet {
  private readonly addTags = new Map<string, Set<string>>();
  private readonly tombs = new Set<string>();

  add(id: string, tag: string): void {
    let tags = this.addTags.get(id);
    if (!tags) this.addTags.set(id, (tags = new Set()));
    tags.add(tag);
  }

  /** Tombstones exactly the observed tags. */
  remove(observed: Iterable<string>): void {
    for (const t of observed) this.tombs.add(t);
  }

  contains(id: string): boolean {
    const tags = this.addTags.get(id);
    if (!tags) return false;
    for (const t of tags) if (!this.tombs.has(t)) return true;
    return false;
  }

  /** The live (not tombstoned) tags of `id`: what a DELETE from this replica observes. */
  liveTags(id: string): string[] {
    return [...(this.addTags.get(id) ?? [])].filter((t) => !this.tombs.has(t)).sort(compareUtf8);
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
    for (const [id, tags] of other.addTags) for (const t of tags) this.add(id, t);
    for (const t of other.tombs) this.tombs.add(t);
  }

  clone(): OrSet {
    const out = new OrSet();
    out.mergeFrom(this);
    return out;
  }

  encode(): OrSetJson {
    const adds: Record<string, string[]> = {};
    for (const [id, tags] of this.addTags) adds[id] = [...tags].sort(compareUtf8);
    return { adds, tombstones: [...this.tombs].sort(compareUtf8) };
  }

  static decode(x: unknown): OrSet {
    if (typeof x !== "object" || x === null || Array.isArray(x)) fail("not an object");
    const o = x as Record<string, unknown>;
    if (Object.keys(o).length !== 2 || !("adds" in o) || !("tombstones" in o)) fail("members must be adds, tombstones");
    const out = new OrSet();
    const adds = o.adds;
    if (typeof adds !== "object" || adds === null || Array.isArray(adds)) fail("adds is not an object");
    for (const [id, tags] of Object.entries(adds as Record<string, unknown>)) {
      if (!isUuid(id)) fail(`bad id ${id}`);
      if (!Array.isArray(tags) || tags.length === 0) fail(`adds.${id} must be a non-empty array`);
      for (const t of tags as unknown[]) {
        if (!isUuid(t)) fail(`bad tag under ${id}`);
        out.add(id, t);
      }
    }
    if (!Array.isArray(o.tombstones)) fail("tombstones is not an array");
    for (const t of o.tombstones as unknown[]) {
      if (!isUuid(t)) fail("bad tombstone");
      out.tombs.add(t);
    }
    return out;
  }
}
