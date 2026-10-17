// IndexedDB storage for a browser tab (SPEC §7.1), behind the same Storage interface as
// MemoryStorage.
//
// One replica per tab. A tab's replica ID is kept in sessionStorage, so a reload resumes the
// same replica and its saved queue. Each tab holds a Web Lock named after its replica ID for as
// long as it lives; the browser releases it when the tab closes or crashes.
//
//   - Duplicated tab: browsers copy sessionStorage into the copy, so two live tabs would share
//     a replica ID. On load a tab takes the lock for its stored ID; if another tab holds it,
//     this tab makes a new replica ID (amendment 17).
//   - Closed tabs: on load, a tab looks for saved replicas of this board whose lock nobody
//     holds (their tab is gone) and takes each one's lock before adopting it, so two tabs never
//     adopt the same queue. Adopting moves that replica's pending ops and Unsent edits to the
//     front of this tab's queue; writing this tab's record and deleting the adopted ones is one
//     IndexedDB transaction, so a crash in between loses nothing and adopts nothing twice.
//   - Two stores (amendment 21): `replicas`, one coalesced snapshot per replica, and `log`, the
//     edits appended one by one before they are shown. A replica's state is its snapshot plus
//     its log entries after `logUpTo`; a snapshot write removes the entries it contains.

import { Board } from "@concord/crdt";
import type { Op } from "@concord/crdt";
import { merge, type Saved, type Storage } from "./storage.js";

const DB = "concord";
const STORE = "replicas";
const LOG = "log";

interface Row {
  readonly key: string; // `${boardId}:${tabReplicaId}`
  readonly boardId: string;
  readonly tabReplicaId: string;
  readonly saved: Saved;
}

/** Holds a Web Lock until `release()`; null if someone else holds it (ifAvailable). */
async function tryLock(name: string): Promise<{ release: () => void } | null> {
  let release!: () => void;
  const held = new Promise<void>((r) => (release = r));
  const got = await new Promise<boolean>((resolve) => {
    void navigator.locks.request(name, { ifAvailable: true }, async (lock) => {
      resolve(lock !== null);
      if (lock) await held;
    });
  });
  return got ? { release } : null;
}

function lockName(boardId: string, replicaId: string): string {
  return `concord:replica:${boardId}:${replicaId}`;
}

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("transaction aborted"));
  });
}

function openDb(): Promise<IDBDatabase> {
  // Version 2 adds the append-only log of edits (amendment 21).
  const r = indexedDB.open(DB, 2);
  r.onupgradeneeded = () => {
    const db = r.result;
    if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "key" }).createIndex("board", "boardId");
    if (!db.objectStoreNames.contains(LOG)) db.createObjectStore(LOG, { keyPath: ["owner", "seq"] }).createIndex("board", "boardId");
  };
  return req(r);
}

interface LogEntry {
  readonly owner: string; // the replica record's key
  readonly boardId: string;
  readonly tabReplicaId: string;
  readonly seq: number;
  readonly op: Op;
}

export class IndexedDbStorage implements Storage {
  /** What this tab adopted on load: for the UI and for tests. */
  adopted: { replicaId: string; pending: number; unsent: number }[] = [];

  private constructor(
    private readonly db: IDBDatabase,
    private readonly boardId: string,
    /** This tab's replica ID: the ID it started with, which also names its record and lock. */
    readonly replicaId: string,
    private readonly lock: { release: () => void },
  ) {}

  /**
   * Claims this tab's replica: the one sessionStorage remembers, if no other live tab holds its
   * lock, else a new one. Throws if the browser has no IndexedDB or Web Locks.
   */
  static async open(boardId: string, session: globalThis.Storage = sessionStorage): Promise<IndexedDbStorage> {
    const db = await openDb();
    const key = `concord:replica:${boardId}`;
    const remembered = session.getItem(key);
    let id = remembered;
    let lock = id ? await tryLock(lockName(boardId, id)) : null;
    if (!lock) {
      // No replica yet, or a duplicated tab whose original still holds the lock.
      id = crypto.randomUUID();
      lock = await tryLock(lockName(boardId, id));
      if (!lock) throw new Error("concord: a fresh replica ID is locked; this cannot happen");
    }
    session.setItem(key, id!);
    return new IndexedDbStorage(db, boardId, id!, lock);
  }

  async load(): Promise<Saved | undefined> {
    const tx0 = this.db.transaction([STORE, LOG]);
    const rows = (await req(tx0.objectStore(STORE).index("board").getAll(this.boardId))) as Row[];
    const logs = (await req(tx0.objectStore(LOG).index("board").getAll(this.boardId))) as LogEntry[];
    const logOf = (tab: string) => logs.filter((e) => e.tabReplicaId === tab);
    const rowOf = (tab: string) => rows.find((r) => r.tabReplicaId === tab);
    // A tab closed before its first snapshot has only log entries, so look in both.
    const tabs = new Set([...rows.map((r) => r.tabReplicaId), ...logs.map((e) => e.tabReplicaId)]);
    const own = merge(rowOf(this.replicaId)?.saved, logOf(this.replicaId));
    const orphans: { tab: string; state: Saved; lock: { release: () => void } }[] = [];
    for (const tab of tabs) {
      if (tab === this.replicaId) continue;
      const lock = await tryLock(lockName(this.boardId, tab));
      if (!lock) continue; // its tab is alive
      const state = merge(rowOf(tab)?.saved, logOf(tab)); // its tab is gone: ours now, and nobody else's
      if (state) orphans.push({ tab, state, lock });
      else lock.release();
    }
    try {
      if (orphans.length === 0) return own;
      // The adopted queues go first, oldest tab's ops first; their ops keep their op_id, HLC
      // and replica ID, so a resend of an op that already committed is answered by dedup (§4.3).
      orphans.sort((a, b) => firstPhysical(a.state) - firstPhysical(b.state));
      const base = own ?? newest(orphans.map((o) => o.state));
      const merged: Saved = {
        ...base,
        replicaId: own?.replicaId ?? this.replicaId,
        hlc: own?.hlc ?? { physical_ms: 0, counter: 0, replica_id: this.replicaId },
        offsetMs: base.offsetMs,
        pending: [...orphans.flatMap((o) => o.state.pending), ...(own?.pending ?? [])],
        unsent: [...orphans.flatMap((o) => o.state.unsent), ...(own?.unsent ?? [])],
        logUpTo: own?.logUpTo ?? 0,
      };
      // One transaction: our merged record in, the adopted records and logs out.
      const tx = this.db.transaction([STORE, LOG], "readwrite");
      tx.objectStore(STORE).put(this.row(merged));
      for (const o of orphans) {
        tx.objectStore(STORE).delete(`${this.boardId}:${o.tab}`);
        tx.objectStore(LOG).delete(IDBKeyRange.bound([`${this.boardId}:${o.tab}`, -Infinity], [`${this.boardId}:${o.tab}`, Infinity]));
      }
      await done(tx);
      this.adopted = orphans.map((o) => ({ replicaId: o.tab, pending: o.state.pending.length, unsent: o.state.unsent.length }));
      return merged;
    } finally {
      for (const o of orphans) o.lock.release();
    }
  }

  /** The snapshot, and the log entries it now contains removed, in one transaction. */
  async save(state: Saved): Promise<void> {
    const tx = this.db.transaction([STORE, LOG], "readwrite");
    tx.objectStore(STORE).put(this.row(state));
    const owner = `${this.boardId}:${this.replicaId}`;
    if (state.logUpTo) tx.objectStore(LOG).delete(IDBKeyRange.bound([owner, -Infinity], [owner, state.logUpTo]));
    await done(tx);
  }

  /** One edit, durable before it is shown or sent (§7.2 step 2). Small and append-only. */
  async append(op: Op, seq: number): Promise<void> {
    const tx = this.db.transaction(LOG, "readwrite");
    const entry: LogEntry = { owner: `${this.boardId}:${this.replicaId}`, boardId: this.boardId, tabReplicaId: this.replicaId, seq, op };
    tx.objectStore(LOG).put(entry);
    await done(tx);
  }

  /** Lets another tab adopt this replica (tests; a real tab just closes). */
  close(): void {
    this.lock.release();
    this.db.close();
  }

  private row(saved: Saved): Row {
    return { key: `${this.boardId}:${this.replicaId}`, boardId: this.boardId, tabReplicaId: this.replicaId, saved };
  }
}

function firstPhysical(s: Saved): number {
  return s.pending[0]?.hlc.physical_ms ?? Number.MAX_SAFE_INTEGER;
}

/** The saved state with the highest confirmed version (any one, if none has joined yet). */
function newest(states: Saved[]): Saved {
  let best = states[0]!;
  for (const s of states) if ((s.version ?? -1) > (best.version ?? -1)) best = s;
  // Validate before trusting it: a corrupt row must fail here, not deep inside the client.
  Board.decode(best.confirmed);
  return best;
}
