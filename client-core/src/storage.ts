// Where a client keeps what must survive a reload (SPEC §7.1): the confirmed replica and its
// version, the pending queue, Unsent edits, the replica ID, the HLC and the clock offset.
// IndexedDbStorage (idb.ts) is the browser implementation.
//
// Two kinds of write (§7.2, amendment 21):
//   append(op, seq)  one new edit, durable before it is shown or sent. Append-only, small.
//   save(state)      a snapshot of everything, coalesced (at most about once a second). It
//                    replaces the log entries up to state.logUpTo, which it already contains.
// load() returns the snapshot with every later log entry merged into its pending queue.

import { Board, type BoardJson, type Hlc, type Op } from "@concord/crdt";

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
  /** The highest append sequence number this state includes. */
  readonly logUpTo?: number;
}

export interface Storage {
  /** The replica ID a fresh client should use (IndexedDbStorage: this tab's). Default: a new one. */
  readonly replicaId?: string;
  load(): Promise<Saved | undefined>;
  save(state: Saved): Promise<void>;
  /** Durably appends one edit; the client shows and sends it only after this completes. */
  append(op: Op, seq: number): Promise<void> | void;
}

/** A snapshot merged with the log entries after it (shared by both implementations). */
export function merge(snapshot: Saved | undefined, log: readonly { seq: number; op: Op }[]): Saved | undefined {
  const after = log.filter((e) => e.seq > (snapshot?.logUpTo ?? 0)).sort((a, b) => a.seq - b.seq);
  if (!snapshot && after.length === 0) return undefined;
  const base: Saved = snapshot ?? {
    replicaId: after[0]!.op.replica_id,
    hlc: { physical_ms: 0, counter: 0, replica_id: after[0]!.op.replica_id },
    offsetMs: 0,
    confirmed: Board.empty().encode(),
    version: null,
    pending: [],
    unsent: [],
  };
  const known = new Set([...base.pending, ...base.unsent.map((u) => u.op)].map((o) => o.op_id));
  const maxSeq = Math.max(base.logUpTo ?? 0, ...log.map((e) => e.seq));
  return { ...base, pending: [...base.pending, ...after.map((e) => e.op).filter((o) => !known.has(o.op_id))], logUpTo: maxSeq };
}

/** Keeps the last saved state in memory: a "reload" is a new client on the same instance. */
export class MemoryStorage implements Storage {
  private state: Saved | undefined;
  private log: { seq: number; op: Op }[] = [];

  async load(): Promise<Saved | undefined> {
    const m = merge(this.state, this.log);
    return m && structuredClone(m);
  }

  async save(state: Saved): Promise<void> {
    this.state = structuredClone(state);
    this.log = this.log.filter((e) => e.seq > (state.logUpTo ?? 0));
  }

  /** Synchronous: memory is "durable" at once, so headless clients stay synchronous. */
  append(op: Op, seq: number): void {
    this.log.push({ seq, op: structuredClone(op) });
  }
}
