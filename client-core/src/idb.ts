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

import { Board } from "@concord/crdt";
import type { Saved, Storage } from "./storage.js";

const DB = "concord";
const STORE = "replicas";

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
  const r = indexedDB.open(DB, 1);
  r.onupgradeneeded = () => {
    const s = r.result.createObjectStore(STORE, { keyPath: "key" });
    s.createIndex("board", "boardId");
  };
  return req(r);
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
    const rows = (await req(this.db.transaction(STORE).objectStore(STORE).index("board").getAll(this.boardId))) as Row[];
    const own = rows.find((r) => r.tabReplicaId === this.replicaId);
    const orphans: { row: Row; lock: { release: () => void } }[] = [];
    for (const row of rows) {
      if (row.tabReplicaId === this.replicaId) continue;
      const lock = await tryLock(lockName(this.boardId, row.tabReplicaId));
      if (lock) orphans.push({ row, lock }); // its tab is gone: ours now, and nobody else's
    }
    try {
      if (orphans.length === 0) return own?.saved;
      // The adopted queues go first, oldest tab's ops first; their ops keep their op_id, HLC
      // and replica ID, so a resend of an op that already committed is answered by dedup (§4.3).
      orphans.sort((a, b) => firstPhysical(a.row.saved) - firstPhysical(b.row.saved));
      const base = own?.saved ?? newest(orphans.map((o) => o.row.saved));
      const merged: Saved = {
        ...base,
        replicaId: own?.saved.replicaId ?? this.replicaId,
        hlc: own?.saved.hlc ?? { physical_ms: 0, counter: 0, replica_id: this.replicaId },
        confirmed: base.confirmed,
        version: base.version,
        pending: [...orphans.flatMap((o) => o.row.saved.pending), ...(own?.saved.pending ?? [])],
        unsent: [...orphans.flatMap((o) => o.row.saved.unsent), ...(own?.saved.unsent ?? [])],
      };
      const tx = this.db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      store.put(this.row(merged));
      for (const o of orphans) store.delete(o.row.key);
      await done(tx);
      this.adopted = orphans.map((o) => ({ replicaId: o.row.tabReplicaId, pending: o.row.saved.pending.length, unsent: o.row.saved.unsent.length }));
      return merged;
    } finally {
      for (const o of orphans) o.lock.release();
    }
  }

  async save(state: Saved): Promise<void> {
    const tx = this.db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(this.row(state));
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
