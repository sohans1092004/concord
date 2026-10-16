// The headless sync client (SPEC §7). It keeps three things:
//
//   confirmed  state built only from server-confirmed ops, plus its board version
//   ahead      confirmed ops that arrived before an earlier version (a gap); applied once
//              the gap is filled, by later broadcasts or by a SYNC
//   pending    this client's own ops not yet ACKed, in creation order
//
// The view is confirmed + ahead + pending, merged. Merge is commutative and idempotent, so an
// op present in two of them is harmless, and nothing optimistic ever needs undoing by hand:
// when an op is rejected it leaves `pending` and the view is rebuilt (§7.1).
//
// Rejections (§7.4): CLOCK_SKEW re-stamps and resends under the same op_id; RETRY_LATER and
// UNAVAILABLE keep the op and resend with backoff; INVALID_OP and UNAUTHORIZED move it to
// Unsent edits. No user edit is ever silently discarded.

import { Board, hlc as H, keys, op as O, type Hlc, type Op, type View } from "@concord/crdt";
import { MemoryStorage, type Saved, type Storage, type Unsent } from "./storage.js";

const NIL = O.NIL_UUID;
type Msg = Record<string, unknown>;
type Body = Record<string, unknown>;

export type Status = "connecting" | "joined" | "offline" | "unauthorized" | "closed";

/** The part of the WebSocket API the client uses, so tests and Phase 3 can supply their own. */
export interface Socket {
  readonly readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: unknown) => void) | null;
  onerror: ((ev: unknown) => void) | null;
}

export interface ClientOptions {
  /** Gateway WebSocket URLs (ws://host:port/ws). A failed connection moves to the next. */
  readonly urls: readonly string[];
  readonly boardId: string;
  /** A fresh JWT for each connection attempt (they live 15 minutes, SPEC §9.4). */
  readonly token: () => string | Promise<string>;
  readonly storage?: Storage;
  readonly connect?: (url: string) => Socket;
  readonly now?: () => number;
  readonly random?: () => number;
  readonly log?: (event: string, data: Record<string, unknown>) => void;
}

/** SPEC §7.3 step 1: 0.5s × 2^attempt, capped at 30s, full jitter. */
export function backoffMs(attempt: number, random: () => number): number {
  return random() * Math.min(30_000, 500 * 2 ** attempt);
}

export class SyncClient {
  private confirmed = Board.empty();
  private version: number | null = null;
  private readonly ahead = new Map<number, Op>();
  private pending: Op[] = [];
  private unsent: Unsent[] = [];
  private replicaId = O.newOpId();
  private clock: Hlc;
  private offsetMs = 0;
  private role: string | null = null;

  private status_: Status = "connecting";
  private ws: Socket | null = null;
  private urlIndex: number;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly resendTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly resendAttempts = new Map<string, number>();
  private syncInFlight = false;
  private syncTimer: ReturnType<typeof setTimeout> | null = null;
  private viewCache: Board | null = null;
  private readonly listeners = new Set<() => void>();
  private saving: Promise<void> = Promise.resolve();

  private readonly storage: Storage;
  private readonly connectFn: (url: string) => Socket;
  private readonly now: () => number;
  private readonly random: () => number;
  private readonly logFn: (event: string, data: Record<string, unknown>) => void;

  private constructor(private readonly opts: ClientOptions) {
    this.storage = opts.storage ?? new MemoryStorage();
    this.connectFn = opts.connect ?? ((url) => new (globalThis as unknown as { WebSocket: new (u: string) => Socket }).WebSocket(url));
    this.now = opts.now ?? Date.now;
    this.random = opts.random ?? Math.random;
    this.logFn = opts.log ?? (() => {});
    this.urlIndex = Math.floor(this.random() * opts.urls.length);
    this.clock = H.zero(this.replicaId);
  }

  /** Loads saved state (a reload resumes where it left off, §7.1) and starts connecting. */
  static async open(opts: ClientOptions): Promise<SyncClient> {
    const c = new SyncClient(opts);
    const saved = await c.storage.load();
    if (saved) c.restore(saved);
    void c.connect();
    return c;
  }

  // ---- reading -----------------------------------------------------------------------------

  get status(): Status {
    return this.status_;
  }

  /** The confirmed version, or null before the first JOINED. */
  get confirmedVersion(): number | null {
    return this.version;
  }

  get boardRole(): string | null {
    return this.role;
  }

  pendingOps(): readonly Op[] {
    return this.pending;
  }

  unsentEdits(): readonly Unsent[] {
    return this.unsent;
  }

  /** What the user sees: confirmed + ahead + pending (§7.1). */
  view(): View {
    return this.viewBoard().view();
  }

  /** Canonical encoding of the confirmed replica; equal on every converged client. */
  confirmedState(): string {
    return this.confirmed.canonical();
  }

  /** SHA-256 of the confirmed state (§9.2.2), as 64 lowercase hex characters. */
  async confirmedHash(): Promise<string> {
    const bytes = new TextEncoder().encode(this.confirmedState());
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  /** Calls `f` after every change to the view, status or queues. Returns an unsubscribe. */
  onChange(f: () => void): () => void {
    this.listeners.add(f);
    return () => this.listeners.delete(f);
  }

  // ---- editing (§7.2) ----------------------------------------------------------------------

  /**
   * Makes one op: new op_id, hlc.tick(), appended to the pending queue and saved, applied to
   * the view, and sent if connected. Throws if the op is malformed: that is a caller bug.
   */
  submit(body: Body): Op {
    this.clock = H.tick(this.clock, this.physicalNow());
    const op = { op_id: O.newOpId(), board_id: this.opts.boardId, hlc: H.encode(this.clock), replica_id: this.replicaId, ...body };
    const check = O.checkShape(op);
    if (!check.ok) throw new Error(`client-core: refusing to submit an invalid op: ${check.reason}`);
    this.pending.push(op as Op);
    this.changed();
    this.sendOp(op as Op);
    return op as Op;
  }

  /** Adds a list at `index` (default: last). Returns its ID. */
  addList(title: string, index?: number): string {
    const lists = this.lists();
    const key = this.placeAt(lists, index ?? lists.length, (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { position_key: k } }));
    const id = O.newOpId();
    this.submit({ type: "ADD_LIST", list_id: id, placement: { position_key: key }, title });
    return id;
  }

  /** Adds a card to `listId` at `index` (default: last). Returns its ID. */
  addCard(listId: string, title: string, index?: number): string {
    const cards = this.cardsOf(listId);
    const key = this.placeAt(cards, index ?? cards.length, (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { list_id: listId, position_key: k } }));
    const id = O.newOpId();
    this.submit({ type: "ADD_CARD", card_id: id, placement: { list_id: listId, position_key: key }, title });
    return id;
  }

  /** Moves a card to `index` of `listId` (also reorders within a list): one atomic placement (§3.5). */
  moveCard(cardId: string, listId: string, index: number): void {
    const cards = this.cardsOf(listId).filter((c) => c.id !== cardId);
    const key = this.placeAt(cards, Math.min(index, cards.length), (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { list_id: listId, position_key: k } }));
    this.submit({ type: "SET_PLACEMENT", entity_id: cardId, placement: { list_id: listId, position_key: key } });
  }

  moveList(listId: string, index: number): void {
    const lists = this.lists().filter((l) => l.id !== listId);
    const key = this.placeAt(lists, Math.min(index, lists.length), (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { position_key: k } }));
    this.submit({ type: "SET_PLACEMENT", entity_id: listId, placement: { position_key: key } });
  }

  setField(entityId: string, field: "title" | "description" | "assignee" | "labels", value: string | null | readonly string[]): void {
    this.submit({ type: "SET_FIELD", entity_id: entityId, field, value });
  }

  /** Deletes with the tags this replica has observed (§3.2). */
  deleteCard(cardId: string): void {
    this.submit({ type: "DELETE_CARD", card_id: cardId, observed_tags: this.viewBoard().liveTags("card", cardId) });
  }

  deleteList(listId: string): void {
    this.submit({ type: "DELETE_LIST", list_id: listId, observed_tags: this.viewBoard().liveTags("list", listId) });
  }

  restoreCard(cardId: string): void {
    this.submit({ type: "RESTORE_CARD", card_id: cardId });
  }

  restoreList(listId: string): void {
    this.submit({ type: "RESTORE_LIST", list_id: listId });
  }

  /** Stops for good: no reconnects, no timers. Pending ops stay saved for the next open. */
  close(): void {
    this.status_ = "closed";
    this.clearTimers();
    const ws = this.ws;
    this.ws = null;
    ws?.close(1000, "client closed");
    this.emit();
  }

  // ---- connection (§7.3) -------------------------------------------------------------------

  private async connect(): Promise<void> {
    this.reconnectTimer = null;
    if (this.status_ === "closed" || this.status_ === "unauthorized") return;
    this.status_ = "connecting";
    const url = this.opts.urls[this.urlIndex % this.opts.urls.length]!;
    let token: string;
    try {
      token = await this.opts.token();
    } catch (e) {
      this.log("token_failed", { error: String(e) });
      this.disconnected();
      return;
    }
    if (this.status_ !== "connecting") return;
    let ws: Socket;
    try {
      ws = this.connectFn(`${url}${url.includes("?") ? "&" : "?"}access_token=${encodeURIComponent(token)}`);
    } catch (e) {
      this.log("connect_failed", { url, error: String(e) });
      this.disconnected();
      return;
    }
    this.ws = ws;
    ws.onopen = () => this.send({ type: "JOIN", board_id: this.opts.boardId, since_version: this.version });
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return;
      try {
        this.handle(JSON.parse(String(ev.data)) as Msg);
      } catch (e) {
        this.log("bad_message", { error: String(e) });
      }
    };
    ws.onclose = () => {
      if (this.ws === ws) this.disconnected();
    };
    ws.onerror = () => {};
  }

  private disconnected(): void {
    this.ws = null;
    this.syncInFlight = false;
    for (const t of this.resendTimers.values()) clearTimeout(t);
    this.resendTimers.clear();
    if (this.status_ === "closed" || this.status_ === "unauthorized") return;
    this.status_ = "offline";
    this.urlIndex++;
    const delay = backoffMs(this.attempt++, this.random);
    this.log("reconnect_scheduled", { delay_ms: Math.round(delay), attempt: this.attempt });
    this.reconnectTimer = setTimeout(() => void this.connect(), delay);
    this.emit();
  }

  private send(m: Msg): void {
    const ws = this.ws;
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(m));
  }

  private sendOp(op: Op): void {
    if (this.status_ === "joined") this.send({ type: "OP", op });
  }

  // ---- server messages (§7.5) --------------------------------------------------------------

  private handle(m: Msg): void {
    switch (m.type) {
      case "JOINED": {
        this.offsetMs = (m.server_time_ms as number) - this.now();
        this.role = m.role as string;
        this.catchUp(m);
        this.status_ = "joined";
        this.attempt = 0;
        this.log("joined", { version: this.version, pending: this.pending.length });
        for (const op of this.pending) this.send({ type: "OP", op }); // step 5: resend the queue
        break;
      }
      case "SYNC":
        this.syncInFlight = false;
        if (this.syncTimer) clearTimeout(this.syncTimer);
        this.catchUp(m);
        break;
      case "OP_BROADCAST":
        this.confirmedOp(m.op as Op, m.version as number, true);
        break;
      case "OP_ACK":
        this.acked(m.op_id as string, m.version as number);
        break;
      case "BOARD_VERSION":
        // §6.4: a quiet client that is behind notices within 5s.
        if (this.version !== null && (m.version as number) > this.version) this.requestSync();
        break;
      case "ERROR":
        this.error(m);
        break;
      case "RECONNECT_ELSEWHERE":
        this.attempt = 0;
        this.ws?.close(1000, "reconnect elsewhere");
        break;
    }
    this.changed();
  }

  /** JOINED or SYNC: a snapshot replaces the confirmed replica; ops are merged in (§7.3 step 4). */
  private catchUp(m: Msg): void {
    if (m.snapshot !== undefined) {
      this.confirmed = Board.decode(m.snapshot);
    } else {
      for (const e of m.ops as { op: Op; version: number }[]) {
        if (this.version === null || e.version > this.version) {
          this.confirmed.apply(e.op);
          this.observe(e.op);
        }
      }
    }
    this.version = m.version as number;
    for (const v of [...this.ahead.keys()]) if (v <= this.version) this.ahead.delete(v);
    this.drain();
  }

  /** A confirmed op at `version`, from a broadcast or our own ACK. */
  private confirmedOp(op: Op, version: number, broadcast: boolean): void {
    this.observe(op);
    if (this.version !== null && version <= this.version) return;
    this.ahead.set(version, op);
    if (this.version === null) return; // before JOINED: hold it
    // §6.4: version v arrived while we last had v-2 or lower: ask before applying anything newer.
    const gap = version > this.version + 1;
    this.drain();
    if (broadcast && gap && version > this.version) this.requestSync();
  }

  private drain(): void {
    if (this.version === null) return;
    for (let next = this.ahead.get(this.version + 1); next; next = this.ahead.get(this.version + 1)) {
      this.ahead.delete(this.version + 1);
      this.confirmed.apply(next);
      this.version++;
    }
  }

  private acked(opId: string, version: number): void {
    const i = this.pending.findIndex((p) => p.op_id === opId);
    if (i < 0) return;
    const [op] = this.pending.splice(i, 1);
    this.clearResend(opId);
    this.confirmedOp(op!, version, false); // §7.3 step 6
  }

  private requestSync(): void {
    if (this.syncInFlight || this.status_ !== "joined" || this.version === null) return;
    this.syncInFlight = true;
    this.log("sync_request", { since_version: this.version });
    this.send({ type: "SYNC_REQUEST", board_id: this.opts.boardId, since_version: this.version });
    this.syncTimer = setTimeout(() => {
      this.syncInFlight = false; // no answer: allow the next trigger to ask again
    }, 10_000);
  }

  private error(m: Msg): void {
    const code = m.code as string;
    const opId = (m.op_id as string | null) ?? null;
    const detail = (m.detail as string | null) ?? null;
    this.log("error", { code, op_id: opId, detail });
    const i = opId === null ? -1 : this.pending.findIndex((p) => p.op_id === opId);
    if (i < 0) {
      if (opId !== null) return; // an error for an op we no longer hold (already ACKed)
      if (code === "UNAUTHORIZED") {
        // Access revoked (or never granted): nothing queued can ever be sent (§7.4).
        this.status_ = "unauthorized";
        for (const op of this.pending) this.unsent.push({ op, code, detail, at: this.now() });
        this.pending = [];
        this.ws?.close(1000, "unauthorized");
      } else if (this.syncInFlight) {
        this.syncInFlight = false;
      } else {
        this.ws?.close(1000, `join failed: ${code}`); // JOIN refused for now: back off and retry
      }
      return;
    }
    const op = this.pending[i]!;
    switch (code) {
      case "CLOCK_SKEW": {
        // §3.1: recompute the offset from the server time, re-stamp, resend under the same op_id.
        this.offsetMs = (m.server_time_ms as number) - this.now();
        if (this.clock.physical_ms > this.physicalNow()) {
          // Our HLC already ran ahead on the bad clock and HLCs never go back, so continue as a
          // fresh replica: its sequence can never repeat a tick of the old one (§3.4).
          this.replicaId = O.newOpId();
          this.clock = H.zero(this.replicaId);
        }
        this.clock = H.tick(this.clock, this.physicalNow());
        const restamped = { ...op, hlc: H.encode(this.clock), replica_id: this.replicaId } as Op;
        this.pending[i] = restamped;
        this.sendOp(restamped);
        break;
      }
      case "RETRY_LATER":
      case "UNAVAILABLE":
        this.scheduleResend(op.op_id);
        break;
      default:
        // INVALID_OP (a client bug, logged) and UNAUTHORIZED: out of the queue, into Unsent edits.
        this.pending.splice(i, 1);
        this.clearResend(op.op_id);
        this.unsent.push({ op, code, detail, at: this.now() });
    }
  }

  private scheduleResend(opId: string): void {
    const n = (this.resendAttempts.get(opId) ?? 0) + 1;
    this.resendAttempts.set(opId, n);
    const old = this.resendTimers.get(opId);
    if (old) clearTimeout(old);
    this.resendTimers.set(opId, setTimeout(() => {
      this.resendTimers.delete(opId);
      const op = this.pending.find((p) => p.op_id === opId);
      if (op) this.sendOp(op);
    }, backoffMs(n, this.random)));
  }

  private clearResend(opId: string): void {
    const t = this.resendTimers.get(opId);
    if (t) clearTimeout(t);
    this.resendTimers.delete(opId);
    this.resendAttempts.delete(opId);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private physicalNow(): number {
    return this.now() + this.offsetMs;
  }

  /** Keeps our HLC ahead of every op we have seen, so our next edit wins over them. */
  private observe(op: Op): void {
    try {
      this.clock = H.receive(this.clock, op.hlc, this.physicalNow());
    } catch {
      // a remote counter at its maximum: keep our clock; tick() will move it on
    }
  }

  private viewBoard(): Board {
    if (!this.viewCache) {
      const b = this.confirmed.clone();
      for (const op of this.ahead.values()) b.apply(op);
      for (const op of this.pending) b.apply(op);
      this.viewCache = b;
    }
    return this.viewCache;
  }

  private lists(): { id: string; position_key: string }[] {
    return this.view().lists.filter((l) => l.id !== NIL) as { id: string; position_key: string }[];
  }

  private cardsOf(listId: string): { id: string; position_key: string }[] {
    const l = this.view().lists.find((x) => x.id === listId);
    return l ? [...l.cards] : [];
  }

  /** §3.7: re-key equal upper neighbours first (as ordinary SET_PLACEMENTs), then the key. */
  private placeAt(entries: { id: string; position_key: string }[], index: number, rekey: (id: string, key: string) => Body): string {
    const plan = keys.planInsert(entries.map((e) => e.position_key), index);
    for (const r of plan.rekeys) this.submit(rekey(entries[r.index]!.id, r.key));
    return plan.key;
  }

  private changed(): void {
    this.viewCache = null;
    const state: Saved = {
      replicaId: this.replicaId,
      hlc: this.clock,
      offsetMs: this.offsetMs,
      confirmed: this.confirmed.encode(),
      version: this.version,
      pending: this.pending,
      unsent: this.unsent,
    };
    this.saving = this.saving.then(() => this.storage.save(state)).catch((e) => this.log("save_failed", { error: String(e) }));
    this.emit();
  }

  private emit(): void {
    for (const f of this.listeners) f();
  }

  private restore(s: Saved): void {
    this.replicaId = s.replicaId;
    this.clock = s.hlc;
    this.offsetMs = s.offsetMs;
    this.confirmed = Board.decode(s.confirmed);
    this.version = s.version;
    this.pending = [...s.pending];
    this.unsent = [...s.unsent];
  }

  private clearTimers(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.syncTimer) clearTimeout(this.syncTimer);
    for (const t of this.resendTimers.values()) clearTimeout(t);
    this.resendTimers.clear();
  }

  private log(event: string, data: Record<string, unknown>): void {
    this.logFn(event, { board_id: this.opts.boardId, ...data });
  }

  /** Resolves when `test()` holds, re-checking after every change; rejects after `ms`. */
  until(test: () => boolean, ms = 30_000): Promise<void> {
    return new Promise((resolve, reject) => {
      if (test()) return resolve();
      const timer = setTimeout(() => {
        off();
        reject(new Error(`client-core: condition not met within ${ms}ms (status ${this.status_}, version ${this.version}, pending ${this.pending.length})`));
      }, ms);
      const off = this.onChange(() => {
        if (test()) {
          clearTimeout(timer);
          off();
          resolve();
        }
      });
    });
  }

  /** Waits until joined with nothing pending. */
  settled(ms?: number): Promise<void> {
    return this.until(() => this.status_ === "joined" && this.pending.length === 0, ms);
  }
}
