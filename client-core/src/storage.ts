// Where a client keeps what must survive a reload (SPEC §7.1): the confirmed replica and its
// version, the pending queue, Unsent edits, the replica ID, the HLC and the clock offset.
// Phase 3 adds an IndexedDB implementation behind this same interface.

import type { BoardJson, Hlc, Op } from "@concord/crdt";

/** An edit the server refused for good (INVALID_OP or UNAUTHORIZED), kept for the user. */
export interface Unsent {
  readonly op: Op;
  readonly code: string;
  readonly detail: string | null;
  readonly at: number;
}

export interface Saved {
  readonly replicaId: string;
  readonly hlc: Hlc;
  readonly offsetMs: number;
  readonly confirmed: BoardJson;
  readonly version: number | null;
  readonly pending: readonly Op[];
  readonly unsent: readonly Unsent[];
}

export interface Storage {
  load(): Promise<Saved | undefined>;
  save(state: Saved): Promise<void>;
}

/** Keeps the last saved state in memory: a "reload" is a new client on the same instance. */
export class MemoryStorage implements Storage {
  private state: Saved | undefined;

  async load(): Promise<Saved | undefined> {
    return this.state && structuredClone(this.state);
  }

  async save(state: Saved): Promise<void> {
    this.state = structuredClone(state);
  }
}
