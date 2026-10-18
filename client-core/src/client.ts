// The headless sync client (SPEC §7). It keeps three things:
//
//   confirmed  state built only from server-confirmed ops, plus its board version
//   ahead      confirmed ops that arrived before an earlier version (a gap); applied once
//              the gap is filled, by later broadcasts or by a SYNC
//   pending    this client's own ops not yet ACKed, in creation order
//
// The view is confirmed + ahead + pending, merged. Merge is commutative and idempotent, so an
// op present in two of them is harmless, and nothing optimistic ever needs undoing by hand:
// when an op is rejected it leaves `pending` and the view is rebuilt (§7.1). For the same
// reason the view board is kept up to date by applying each op to it as it arrives, and is
// rebuilt from scratch only when an op leaves `pending` without being confirmed (a rejection,
// a re-stamp) or a snapshot replaces the confirmed replica.
//
// Saving (§7.1, §7.2, amendment 21): a new edit is appended to the storage's log, and only
// once that append is durable is it put on the pending queue, shown and sent ("staged" until
// then). Everything else (ACKs, rejections, broadcasts) is saved as a coalesced snapshot within
// a second, and on pagehide: losing one only means resending ops the server dedups, or
// catching up from an older version.
//
// Rejections (§7.4): CLOCK_SKEW re-stamps and resends under the same op_id; RETRY_LATER and
// UNAVAILABLE keep the op and resend with backoff; INVALID_OP and UNAUTHORIZED move it to
// Unsent edits. No user edit is ever silently discarded.
//
// Order matters, because the owner checks references (§4.2 step 4): an ADD_CARD is valid only
// once the ADD_LIST before it in this queue has committed. So retries always resend the whole
// queue in order, never one op on its own timer, and INVALID_OP is final only for the head of
// the queue: an op with an unacknowledged op of ours ahead of it may have been judged before
// its dependency committed (the owner died between them), so it is deferred and resent once
// everything ahead of it is ACKed. If it is still invalid then, it goes to Unsent edits.

import { Board, compareUtf8, hlc as H, keys, op as O, type Hlc, type Op, type Placement, type View } from "@concord/crdt";
import { MemoryStorage, type Saved, type Storage, type Unsent } from "./storage.js";

const NIL = O.NIL_UUID;
type Msg = Record<string, unknown>;
type Body = Record<string, unknown>;

export type Status = "connecting" | "joined" | "offline" | "unauthorized" | "closed";

/** A board member, from JOINED (§7.5). */
export interface Member {
  readonly name: string;
  readonly role: string;
}

/** Someone else's view of the board (§7.5 PRESENCE_UPDATE). */
export interface Presence {
  readonly cardId: string | null;
  readonly state: "active" | "idle";
}

/** A concurrent move of ours that lost (§3.5): shown briefly as "moved by {name}". */
export interface MovedBy {
  readonly userId: string | null;
  readonly at: number;
}

/** A deleted list or card, for the trash view; restoring it is an ordinary RESTORE_* op. */
export interface Trashed {
  readonly kind: "list" | "card";
  readonly id: string;
  readonly title: string;
  readonly listId: string | null;
}

/** How long a lost move stays marked, and how long after its ACK a move of ours can still lose. */
export const MOVED_BY_MS = 4_000;
export const MOVE_WATCH_MS = 30_000;
/** §5.2: presence entries older than 30s are swept, so a client refreshes its own every 10s. */
export const PRESENCE_EVERY_MS = 10_000;

/**
 * Labels as the user typed them, made valid for §4.2: trimmed, NFC, empty ones dropped,
 * duplicates removed, sorted by unsigned UTF-8 bytes. Throws if more than 20 remain or one is
 * longer than 50 code points, so the caller can say so instead of sending an invalid op.
 */
export function normalizeLabels(input: readonly string[]): string[] {
  const out = [...new Set(input.map((l) => l.trim().normalize("NFC")).filter((l) => l.length > 0))].sort(compareUtf8);
  if (out.length > O.MAX_LABELS) throw new Error(`at most ${O.MAX_LABELS} labels`);
  for (const l of out) if ([...l].length > O.MAX_LABEL) throw new Error(`a label is at most ${O.MAX_LABEL} characters: ${l}`);
  return out;
}

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
  /**
   * A fresh single-use WebSocket ticket for each connection attempt (SPEC §9.4: valid 30s,
   * fetched from the REST API with the signed-in session). Given the gateway URL about to be used.
   */
  readonly ticket: (url: string) => string | Promise<string>;
  readonly storage?: Storage;
  readonly connect?: (url: string) => Socket;
  readonly now?: () => number;
  readonly random?: () => number;
  readonly log?: (event: string, data: Record<string, unknown>) => void;
  /**
   * SPEC 9.5: what only a client can measure, handed over every 10s (at most 100 items) for the
   * app to POST to /api/telemetry. Sync latency: another client's op, from its HLC time until it
   * is applied here (both clocks corrected by the server's, so the error is the difference of
   * the two clients' one-way delays). Spans of the op's trace: client.send (made until sent) and
   * peer.render (received until applied). Off when not given.
   */
  readonly telemetry?: (report: Telemetry) => void;
}

export interface TelemetrySpan {
  readonly op_id: string;
  readonly name: "client.send" | "peer.render";
  readonly start_ms: number;
  readonly end_ms: number;
}

export interface Telemetry {
  readonly sync_ms: number[];
  readonly spans: TelemetrySpan[];
}

/** At most this many items per telemetry report (the gateway refuses more). */
export const TELEMETRY_MAX = 100;

/** A connection that has not reached JOINED by then is abandoned (a hung handshake or owner). */
export const JOIN_TIMEOUT_MS = 10_000;

/**
 * §7.3 (amendment 18): a joined connection that hears nothing for this long is dead. The owner
 * publishes BOARD_VERSION every 5s (§6.4), so silence means the network went away without
 * closing the socket (a dropped Wi-Fi, a laptop lid), which no WebSocket event reports.
 */
export const SILENCE_MS = 15_000;
/** The gateway's per-connection limit on OP frames (§9.4); the client paces itself to it. */
export const SEND_PER_SECOND = 20;
export const SEND_BURST = 50;

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
  private telemetryBuf: { sync_ms: number[]; spans: TelemetrySpan[] } = { sync_ms: [], spans: [] };
  private readonly madeAt = new Map<string, number>();
  private telemetryTimer: ReturnType<typeof setTimeout> | null = null;
  private clock: Hlc;
  private offsetMs = 0;
  private role: string | null = null;

  private status_: Status = "connecting";
  private ws: Socket | null = null;
  private urlIndex: number;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private joinTimer: ReturnType<typeof setTimeout> | null = null;
  private resendTimer: ReturnType<typeof setTimeout> | null = null;
  private resendAttempt = 0;
  private outbox: Op[] = []; // to send, in order, as the token bucket allows
  private readonly outboxIds = new Set<string>();
  private pumpTimer: ReturnType<typeof setTimeout> | null = null;
  private tokens = SEND_BURST;
  private tokensAt = 0;
  /** Ops refused as INVALID_OP while an earlier op of ours was unacknowledged. */
  private readonly deferred = new Set<string>();
  private syncInFlight = false;
  private syncTimer: ReturnType<typeof setTimeout> | null = null;
  private viewCache: Board | null = null;
  /** The confirmed replica's encoding as last saved; null once it has changed. */
  private confirmedJson: ReturnType<Board["encode"]> | null = null;
  private queueDirty = false;
  /** Edits made but not yet durably appended: in planning (keys, tags), not yet shown or sent. */
  private staged: Op[] = [];
  private appendSeq = 0;
  private durableUpTo = 0;
  private appending: Promise<void> | null = null;
  private planCache: Board | null = null;
  private readonly onPageHide = () => this.saveNow();
  private confirmedDirty = false;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private saveInFlight = false;
  private saveAgain = false;
  private readonly listeners = new Set<() => void>();
  private saving: Promise<void> = Promise.resolve();
  private members_ = new Map<string, Member>();
  /** Which user each replica belongs to, learnt from ops that carry user_id (§7.5). */
  private readonly replicaUser = new Map<string, string>();
  /** Our recent moves (§3.5): card → the placement we chose and when it was ACKed (null: pending). */
  private readonly localMoves = new Map<string, { op_id: string; placement: Placement; ackedAt: number | null }>();
  private readonly movedBy_ = new Map<string, MovedBy>();
  /** Presence per connection (amendment 22): connection ID → its user, card and state. */
  private readonly connections = new Map<string, { userId: string } & Presence>();
  /** presence() as last computed; reset when a connection changes, so renders reuse it. */
  private presenceView: Map<string, Presence> | null = null;
  private myPresence: { cardId: string | null; state: "active" | "idle" } | null = null;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;
  private lastHeard = 0;
  private silenceTimer: ReturnType<typeof setInterval> | null = null;
  /** Browser network events, when there is a browser: offline drops the socket, online reconnects now. */
  private readonly onOffline = () => this.dropConnection("browser_offline");
  private readonly onOnline = () => {
    if (this.status_ !== "offline" || !this.reconnectTimer) return;
    clearTimeout(this.reconnectTimer);
    this.attempt = 0;
    this.log("browser_online", {});
    void this.connect();
  };

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
    this.replicaId = this.storage.replicaId ?? this.replicaId;
    const g = globalThis as { addEventListener?: (t: string, f: () => void) => void };
    g.addEventListener?.("offline", this.onOffline);
    g.addEventListener?.("pagehide", this.onPageHide); // the snapshot, best effort; edits are already durable
    g.addEventListener?.("online", this.onOnline);
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

  /** This client's current replica ID (§7.1: one per tab). */
  get replica(): string {
    return this.replicaId;
  }

  get boardRole(): string | null {
    return this.role;
  }

  /** Board members by user ID, from the last JOINED. */
  members(): ReadonlyMap<string, Member> {
    return this.members_;
  }

  /** Cards whose concurrent move by this client lost in the last few seconds (§3.5). */
  movedBy(): ReadonlyMap<string, MovedBy> {
    const now = this.now();
    for (const [id, m] of this.movedBy_) if (now - m.at > MOVED_BY_MS) this.movedBy_.delete(id);
    return this.movedBy_;
  }

  /**
   * Who is on the board (§1.1): user ID → the card they have open (from any of their
   * connections; the most recently reported one with a card) and whether any connection of
   * theirs is active. A user stays present while any of their connections is.
   */
  presence(): ReadonlyMap<string, Presence> {
    if (this.presenceView) return this.presenceView;
    const out = new Map<string, Presence>();
    for (const c of this.connections.values()) {
      const p = out.get(c.userId);
      out.set(c.userId, {
        cardId: c.cardId ?? p?.cardId ?? null,
        state: c.state === "active" || p?.state === "active" ? "active" : "idle",
      });
    }
    return (this.presenceView = out);
  }

  /** Deleted lists and cards, newest state first by title; restore with restoreList/restoreCard. */
  trash(): Trashed[] {
    const s = this.viewBoard().encode();
    const out: Trashed[] = [];
    const reg = s.registers as Record<string, Record<string, { value: unknown }>>;
    for (const kind of ["list", "card"] as const) {
      const set = kind === "list" ? s.lists : s.cards;
      for (const [id, tags] of Object.entries(set.adds)) {
        const dead = new Set(set.tombstones[id] ?? []);
        if (tags.some((t) => !dead.has(t))) continue;
        const placement = reg[id]?.placement?.value as { list_id?: string } | undefined;
        out.push({ kind, id, title: String(reg[id]?.title?.value ?? ""), listId: placement?.list_id ?? null });
      }
    }
    return out.sort((a, b) => compareUtf8(a.title, b.title) || compareUtf8(a.id, b.id));
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
    const op = { op_id: O.newOpId(), board_id: this.opts.boardId, hlc: H.encode(this.clock), replica_id: this.replicaId, ...body } as Op;
    if (this.opts.telemetry && this.madeAt.size < 1_000) this.madeAt.set(op.op_id, this.physicalNow());
    const check = O.checkShape(op);
    if (!check.ok) throw new Error(`client-core: refusing to submit an invalid op: ${check.reason}`);
    // §7.2: 2. append it durably; 3. then update the view; 4. then send. In order, one at a time.
    const seq = ++this.appendSeq;
    this.staged.push(op);
    this.planCache?.apply(op);
    const durable = () => {
      this.staged.shift();
      this.durableUpTo = seq;
      this.pending.push(op);
      this.viewCache?.apply(op);
      this.queueDirty = true;
      this.changed();
      this.sendOp(op);
    };
    const failed = (e: unknown) => {
      // Storage refused (quota, a closed database): keep the edit in memory rather than drop it.
      this.log("append_failed", { op_id: op.op_id, error: String(e) });
      durable();
    };
    const write = () => {
      try {
        const r = this.storage.append(op, seq);
        return r ? r.then(durable, failed) : (durable(), undefined);
      } catch (e) {
        failed(e);
        return undefined;
      }
    };
    const chained: Promise<void> | undefined = this.appending ? this.appending.then(write) : write();
    if (chained) {
      const mine = (this.appending = chained.then(() => {
        if (this.appending === mine) this.appending = null;
      }));
    }
    return op;
  }

  /** Adds a list at `index` (default, or past the end: last). Returns its ID. */
  addList(title: string, index?: number): string {
    const lists = this.lists();
    const key = this.placeAt(lists, Math.min(index ?? lists.length, lists.length), (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { position_key: k } }));
    const id = O.newOpId();
    this.submit({ type: "ADD_LIST", list_id: id, placement: { position_key: key }, title });
    return id;
  }

  /** Adds a card to `listId` at `index` (default, or past the end: last). Returns its ID. */
  addCard(listId: string, title: string, index?: number): string {
    const cards = this.cardsOf(listId);
    const key = this.placeAt(cards, Math.min(index ?? cards.length, cards.length), (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { list_id: listId, position_key: k } }));
    const id = O.newOpId();
    this.submit({ type: "ADD_CARD", card_id: id, placement: { list_id: listId, position_key: key }, title });
    return id;
  }

  /** Moves a card to `index` of `listId` (also reorders within a list): one atomic placement (§3.5). */
  moveCard(cardId: string, listId: string, index: number): void {
    const cards = this.cardsOf(listId).filter((c) => c.id !== cardId);
    const key = this.placeAt(cards, Math.min(index, cards.length), (id, k) => ({ type: "SET_PLACEMENT", entity_id: id, placement: { list_id: listId, position_key: k } }));
    const op = this.submit({ type: "SET_PLACEMENT", entity_id: cardId, placement: { list_id: listId, position_key: key } });
    this.localMoves.set(cardId, { op_id: op.op_id, placement: { list_id: listId, position_key: key }, ackedAt: null });
    this.movedBy_.delete(cardId);
  }

  /**
   * What this client shows on the board (§1.1 presence): the open card, or null, and whether
   * the user is active. Sent now and every 10s while connected; never stored (§5.2).
   */
  setPresence(cardId: string | null, state: "active" | "idle" = "active"): void {
    const same = this.myPresence && this.myPresence.cardId === cardId && this.myPresence.state === state;
    this.myPresence = { cardId, state };
    if (!same) this.sendPresence();
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
    this.submit({ type: "DELETE_CARD", card_id: cardId, observed_tags: this.planBoard().liveTags("card", cardId) });
  }

  deleteList(listId: string): void {
    this.submit({ type: "DELETE_LIST", list_id: listId, observed_tags: this.planBoard().liveTags("list", listId) });
  }

  restoreCard(cardId: string): void {
    this.submit({ type: "RESTORE_CARD", card_id: cardId });
  }

  restoreList(listId: string): void {
    this.submit({ type: "RESTORE_LIST", list_id: listId });
  }

  /** Stops for good: no reconnects, no timers. Pending ops stay saved for the next open. */
  close(): void {
    this.flushTelemetry();
    if (this.queueDirty || this.confirmedDirty) this.saveNow();
    (globalThis as { removeEventListener?: (t: string, f: () => void) => void }).removeEventListener?.("pagehide", this.onPageHide);
    this.status_ = "closed";
    this.clearTimers();
    const g = globalThis as { removeEventListener?: (t: string, f: () => void) => void };
    g.removeEventListener?.("offline", this.onOffline);
    g.removeEventListener?.("online", this.onOnline);
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    this.presenceTimer = null;
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
    let ticket: string;
    try {
      ticket = await this.opts.ticket(url);
    } catch (e) {
      this.log("ticket_failed", { error: String(e) });
      this.disconnected();
      return;
    }
    if (this.status_ !== "connecting") return;
    let ws: Socket;
    try {
      ws = this.connectFn(`${url}${url.includes("?") ? "&" : "?"}ticket=${encodeURIComponent(ticket)}`);
    } catch (e) {
      this.log("connect_failed", { url, error: String(e) });
      this.disconnected();
      return;
    }
    this.ws = ws;
    // A socket can hang without ever opening or closing (a port accepted by a proxy whose
    // backend is not up yet). Without this the client would wait forever.
    this.joinTimer = setTimeout(() => {
      if (this.ws !== ws || this.status_ !== "connecting") return;
      this.log("join_timeout", { url, ms: JOIN_TIMEOUT_MS });
      this.ws = null; // ignore whatever this socket does from now on
      try {
        ws.close(4000, "join timeout");
      } catch {
        // never opened
      }
      this.disconnected();
    }, JOIN_TIMEOUT_MS);
    ws.onopen = () => this.send({ type: "JOIN", board_id: this.opts.boardId, since_version: this.version });
    ws.onmessage = (ev) => {
      if (this.ws !== ws) return;
      this.lastHeard = this.now();
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

  /** The socket is dead or about to be: stop using it and reconnect with backoff. */
  private dropConnection(why: string): void {
    const ws = this.ws;
    if (!ws) return;
    this.log("connection_dropped", { why });
    this.ws = null; // ignore whatever this socket does from now on
    try {
      ws.close(4001, why);
    } catch {
      // already closed
    }
    this.disconnected();
  }

  private disconnected(): void {
    this.ws = null;
    if (this.silenceTimer) clearInterval(this.silenceTimer);
    this.silenceTimer = null;
    if (this.joinTimer) clearTimeout(this.joinTimer);
    this.joinTimer = null;
    this.syncInFlight = false;
    if (this.resendTimer) clearTimeout(this.resendTimer);
    this.resendTimer = null;
    this.clearOutbox();
    this.deferred.clear(); // JOINED resends the whole queue in order
    if (this.presenceTimer) clearInterval(this.presenceTimer);
    this.presenceTimer = null;
    this.connections.clear(); // stale once we are gone; JOINED brings the current set
    this.presenceView = null;
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

  /**
   * Queues `op` to send, in call order, paced to the gateway's limit (§9.4: 20 ops/s, burst 50),
   * so a long offline queue drains at that rate instead of being refused frame by frame.
   */
  private sendOp(op: Op): void {
    if (this.status_ !== "joined") return;
    if (!this.outboxIds.has(op.op_id)) {
      this.outbox.push(op);
      this.outboxIds.add(op.op_id);
    }
    this.pump();
  }

  /** Sends what the token bucket allows now; wakes itself when the next token is due. */
  private pump(): void {
    if (this.pumpTimer) return;
    const now = performance.now(); // real time, as the gateway's bucket runs on
    this.tokens = Math.min(SEND_BURST, this.tokens + ((now - this.tokensAt) * SEND_PER_SECOND) / 1000);
    this.tokensAt = now;
    while (this.outbox.length > 0 && this.tokens >= 1 && this.status_ === "joined") {
      const op = this.outbox.shift()!;
      this.outboxIds.delete(op.op_id);
      this.tokens -= 1;
      this.transmit(op);
    }
    if (this.outbox.length > 0 && this.status_ === "joined") {
      const wait = Math.max(1, Math.ceil(((1 - this.tokens) * 1000) / SEND_PER_SECOND));
      this.pumpTimer = setTimeout(() => {
        this.pumpTimer = null;
        this.pump();
      }, wait);
    }
  }

  /** Drops whatever was waiting to be sent (the connection is gone, or the queue is resent). */
  private clearOutbox(): void {
    this.outbox = [];
    this.outboxIds.clear();
    if (this.pumpTimer) clearTimeout(this.pumpTimer);
    this.pumpTimer = null;
  }

  private transmit(op: Op): void {
    this.send({ type: "OP", op });
    const made = this.madeAt.get(op.op_id);
    if (made !== undefined) { // the first send only: the edit's root span
      this.madeAt.delete(op.op_id);
      this.record(null, { op_id: op.op_id, name: "client.send", start_ms: made, end_ms: this.physicalNow() });
    }
  }

  /** Adds to the next telemetry report; sends it within 10s, or at once when it is full. */
  private record(syncMs: number | null, span: TelemetrySpan | null): void {
    if (!this.opts.telemetry) return;
    const keep = syncMs !== null && syncMs >= 0 && syncMs <= 60_000;
    // Up to two items arrive at once: send first if they would not fit (the gateway refuses a
    // report over TELEMETRY_MAX, losing all of it).
    if (this.telemetryBuf.sync_ms.length + this.telemetryBuf.spans.length + (keep ? 1 : 0) + (span ? 1 : 0) > TELEMETRY_MAX) this.flushTelemetry();
    const b = this.telemetryBuf;
    if (keep) b.sync_ms.push(Math.round(syncMs));
    if (span) b.spans.push(span);
    if (b.sync_ms.length + b.spans.length >= TELEMETRY_MAX) this.flushTelemetry();
    else this.telemetryTimer ??= setTimeout(() => this.flushTelemetry(), 10_000);
  }

  /** Hands over what was measured since the last report (also on close). */
  flushTelemetry(): void {
    if (this.telemetryTimer) clearTimeout(this.telemetryTimer);
    this.telemetryTimer = null;
    const b = this.telemetryBuf;
    if (!this.opts.telemetry || b.sync_ms.length + b.spans.length === 0) return;
    this.telemetryBuf = { sync_ms: [], spans: [] };
    try {
      this.opts.telemetry(b);
    } catch {
      // a report is never worth an error
    }
  }

  // ---- server messages (§7.5) --------------------------------------------------------------

  private handle(m: Msg): void {
    switch (m.type) {
      case "JOINED": {
        this.offsetMs = (m.server_time_ms as number) - this.now();
        this.role = m.role as string;
        if (m.members && typeof m.members === "object") this.members_ = new Map(Object.entries(m.members as Record<string, Member>));
        this.catchUp(m);
        this.status_ = "joined";
        this.attempt = 0;
        if (this.joinTimer) clearTimeout(this.joinTimer);
        this.joinTimer = null;
        this.log("joined", { version: this.version, pending: this.pending.length });
        // step 5: resend the queue, paced; a new connection starts with the gateway's full bucket
        this.clearOutbox();
        this.tokens = SEND_BURST;
        this.tokensAt = performance.now();
        for (const op of this.pending) this.sendOp(op);
        this.sendPresence();
        if (this.silenceTimer) clearInterval(this.silenceTimer);
        this.silenceTimer = setInterval(() => {
          if (this.now() - this.lastHeard > SILENCE_MS) this.dropConnection("silent");
        }, SILENCE_MS / 3);
        if (this.presenceTimer) clearInterval(this.presenceTimer);
        this.presenceTimer = setInterval(() => this.sendPresence(), PRESENCE_EVERY_MS);
        break;
      }
      case "SYNC":
        this.syncInFlight = false;
        if (this.syncTimer) clearTimeout(this.syncTimer);
        this.catchUp(m);
        break;
      case "OP_BROADCAST": {
        const op = m.op as Op;
        const received = this.physicalNow();
        this.learnUser(op, m.user_id);
        this.confirmedOp(op, m.version as number, true);
        if (this.opts.telemetry && op.replica_id !== this.replicaId) { // another client's edit
          const shown = this.physicalNow();
          this.record(shown - op.hlc.physical_ms, { op_id: op.op_id, name: "peer.render", start_ms: received, end_ms: shown });
        }
        break;
      }
      case "PRESENCE_UPDATE": {
        // One entry per connection; "left" removes that connection only, and may arrive more
        // than once (several gateways sweep the same board).
        const conn = String(m.connection_id ?? m.user_id);
        this.presenceView = null;
        if (m.state === "left") this.connections.delete(conn);
        else this.connections.set(conn, { userId: m.user_id as string, cardId: (m.card_id as string | null) ?? null, state: m.state === "idle" ? "idle" : "active" });
        break;
      }
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
      this.viewCache = null;
      this.confirmedJson = null;
    } else {
      for (const e of m.ops as { op: Op; version: number; user_id?: string }[]) {
        this.learnUser(e.op, e.user_id);
        if (this.version === null || e.version > this.version) {
          this.applyConfirmed(e.op);
          this.observe(e.op);
        }
      }
    }
    this.version = m.version as number;
    this.confirmedDirty = true;
    for (const v of [...this.ahead.keys()]) if (v <= this.version) this.ahead.delete(v);
    this.drain();
  }

  private applyConfirmed(op: Op): void {
    this.confirmed.apply(op);
    this.viewCache?.apply(op); // already there if it was ours or ahead: applying twice is a no-op
    this.confirmedJson = null;
    this.confirmedDirty = true;
  }

  /** A confirmed op at `version`, from a broadcast or our own ACK. */
  private confirmedOp(op: Op, version: number, broadcast: boolean): void {
    this.observe(op);
    if (this.version !== null && version <= this.version) return;
    this.ahead.set(version, op);
    this.viewCache?.apply(op);
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
      this.applyConfirmed(next);
      this.version++;
    }
  }

  private acked(opId: string, version: number): void {
    const i = this.pending.findIndex((p) => p.op_id === opId);
    if (i < 0) return;
    const [op] = this.pending.splice(i, 1);
    this.queueDirty = true;
    this.deferred.delete(opId);
    this.resendAttempt = 0;
    this.log("acked", { op_id: opId, version });
    for (const mv of this.localMoves.values()) if (mv.op_id === opId) mv.ackedAt = this.now();
    this.confirmedOp(op!, version, false); // §7.3 step 6
    // Deferred ops now at the head have everything before them committed: judge them again.
    let k = 0;
    while (k < this.pending.length && this.deferred.has(this.pending[k]!.op_id)) {
      const d = this.pending[k++]!;
      this.deferred.delete(d.op_id);
      this.sendOp(d);
    }
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
        this.viewCache = null;
        this.queueDirty = true;
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
        this.viewCache = null;
        this.queueDirty = true;
        this.sendOp(restamped);
        break;
      }
      case "RETRY_LATER":
      case "UNAVAILABLE":
        this.scheduleQueueResend();
        break;
      case "INVALID_OP":
        if (i > 0) {
          // An earlier op of ours is still unacknowledged: this may only be invalid without it.
          this.deferred.add(op.op_id);
          break;
        }
      // falls through: at the head of the queue, INVALID_OP is final (a client bug, logged)
      default:
        // INVALID_OP and UNAUTHORIZED: out of the queue, into Unsent edits.
        this.pending.splice(i, 1);
        this.deferred.delete(op.op_id);
        this.unsent.push({ op, code, detail, at: this.now() });
        this.viewCache = null;
        this.queueDirty = true;
    }
  }

  /** Resends the whole queue, in order, after a backoff. The server dedups by op_id (§4.3). */
  private scheduleQueueResend(): void {
    if (this.resendTimer) return;
    this.resendTimer = setTimeout(() => {
      this.resendTimer = null;
      this.deferred.clear();
      this.clearOutbox();
      for (const op of this.pending) this.sendOp(op);
    }, backoffMs(this.resendAttempt++, this.random));
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

  /** What the next edit is computed against: the view plus edits still being appended. */
  private planBoard(): Board {
    if (this.staged.length === 0) {
      this.planCache = null;
      return this.viewBoard();
    }
    if (!this.planCache) {
      this.planCache = this.viewBoard().clone();
      for (const op of this.staged) this.planCache.apply(op);
    }
    return this.planCache;
  }

  private planView(): View {
    return this.staged.length === 0 ? this.view() : this.planBoard().view();
  }

  private lists(): { id: string; position_key: string }[] {
    return this.planView().lists.filter((l) => l.id !== NIL) as { id: string; position_key: string }[];
  }

  private cardsOf(listId: string): { id: string; position_key: string }[] {
    const l = this.planView().lists.find((x) => x.id === listId);
    return l ? [...l.cards] : [];
  }

  /** §3.7: re-key equal upper neighbours first (as ordinary SET_PLACEMENTs), then the key. */
  private placeAt(entries: { id: string; position_key: string }[], index: number, rekey: (id: string, key: string) => Body): string {
    const plan = keys.planInsert(entries.map((e) => e.position_key), index);
    for (const r of plan.rekeys) this.submit(rekey(entries[r.index]!.id, r.key));
    return plan.key;
  }

  /**
   * §3.5: a move of ours lost to a concurrent move when the card now shows a placement other
   * than the one we chose. Checked after every change, for our moves still pending or ACKed in
   * the last 30s. The winner's user comes from the winning register's replica.
   */
  private checkMoves(): void {
    if (this.localMoves.size === 0) return;
    const now = this.now();
    let state: ReturnType<Board["encode"]> | null = null;
    for (const [card, mv] of this.localMoves) {
      if (mv.ackedAt !== null && now - mv.ackedAt > MOVE_WATCH_MS) {
        this.localMoves.delete(card);
        continue;
      }
      state ??= this.viewBoard().encode();
      const reg = (state.registers as Record<string, Record<string, { value: Placement; hlc: Hlc }>>)[card]?.placement;
      if (!reg) continue;
      const shown = reg.value as { list_id?: string; position_key: string };
      const mine = mv.placement as { list_id?: string; position_key: string };
      if (shown.list_id === mine.list_id && shown.position_key === mine.position_key) continue;
      if (reg.hlc.replica_id === this.replicaId) continue; // a later move of our own
      this.localMoves.delete(card);
      this.movedBy_.set(card, { userId: this.replicaUser.get(reg.hlc.replica_id) ?? null, at: now });
      this.log("move_lost", { card_id: card });
    }
  }

  private learnUser(op: Op, userId: unknown): void {
    if (typeof userId === "string") this.replicaUser.set(op.replica_id, userId);
  }

  private sendPresence(): void {
    if (this.status_ !== "joined" || !this.myPresence) return;
    this.send({ type: "PRESENCE", board_id: this.opts.boardId, card_id: this.myPresence.cardId, state: this.myPresence.state });
  }

  private changed(): void {
    this.planCache = null; // the view under it may have changed
    this.checkMoves();
    if ((this.queueDirty || this.confirmedDirty) && !this.saveTimer) this.saveTimer = setTimeout(() => this.saveNow(), 1_000);
    this.emit();
  }

  /**
   * Writes the snapshot now; the confirmed encoding is reused if unchanged. One write at a time:
   * changes made while one is in flight are written together, as the latest state, the moment
   * it finishes. The snapshot covers the log up to `durableUpTo`, which storage then drops.
   */
  private saveNow(): void {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = null;
    if (this.saveInFlight) {
      this.saveAgain = true;
      return;
    }
    this.queueDirty = this.confirmedDirty = false;
    this.confirmedJson ??= this.confirmed.encode();
    const state: Saved = {
      replicaId: this.replicaId,
      hlc: this.clock,
      offsetMs: this.offsetMs,
      confirmed: this.confirmedJson,
      version: this.version,
      pending: [...this.pending],
      unsent: [...this.unsent],
      logUpTo: this.durableUpTo,
    };
    this.saveInFlight = true;
    this.saving = this.storage.save(state).catch((e) => this.log("save_failed", { error: String(e) })).then(() => {
      this.saveInFlight = false;
      if (this.saveAgain) {
        this.saveAgain = false;
        this.saveNow();
        return this.saving;
      }
    });
  }

  /** Resolves once every edit made so far is durable and shown (snapshots may still follow). */
  async saved(): Promise<void> {
    while (this.appending) await this.appending;
  }

  /** Resolves once everything, snapshot included, is in storage. */
  async flushed(): Promise<void> {
    await this.saved();
    this.saveNow();
    while (this.saveInFlight || this.saveAgain) await this.saving;
  }

  /** The view rebuilt from scratch: what the incrementally kept view must always equal. For tests. */
  rebuiltView(): View {
    const b = this.confirmed.clone();
    for (const op of this.ahead.values()) b.apply(op);
    for (const op of this.pending) b.apply(op);
    return b.view();
  }

  private emit(): void {
    for (const f of this.listeners) f();
  }

  private restore(s: Saved): void {
    this.replicaId = s.replicaId;
    this.clock = s.hlc;
    this.offsetMs = s.offsetMs;
    this.confirmed = Board.decode(s.confirmed);
    this.confirmedJson = s.confirmed;
    this.viewCache = null;
    this.version = s.version;
    this.pending = [...s.pending];
    this.unsent = [...s.unsent];
    this.appendSeq = this.durableUpTo = s.logUpTo ?? 0;
    // Edits appended after the snapshot carry later ticks than its HLC: never reuse one.
    for (const op of this.pending) if (op.replica_id === this.replicaId) this.observe(op);
  }

  private clearTimers(): void {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = null;
    if (this.silenceTimer) clearInterval(this.silenceTimer);
    this.silenceTimer = null;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.joinTimer) clearTimeout(this.joinTimer);
    if (this.syncTimer) clearTimeout(this.syncTimer);
    if (this.resendTimer) clearTimeout(this.resendTimer);
    this.resendTimer = null;
    this.clearOutbox();
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
