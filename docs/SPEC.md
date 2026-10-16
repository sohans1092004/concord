# Concord — Engineering Specification v3

Oct 16, 2026 · @Sohan

> Transcribed from `Concord — Engineering Specification v3.pdf`. Tables were rebuilt
> from the PDF layout; the architecture diagram is described in words. Later
> changes are recorded in the Amendments log at the end of this document.

## Status and what changed

v3 replaces v1 and v2 completely: build from this document only. It fixes eleven
correctness gaps found in review, locks the open scope decisions, and cuts Phase 4 to
what adds new signal beyond Reliant.

Concord is a collaborative structured workspace (boards → lists → cards, like Trello's
board view), not a rich-text editor. Its core claim: a hand-rolled CRDT engine proven
convergent by property-based and permutation fuzz tests, running inside an
actor-per-board distributed system that never acknowledges an edit it has not durably stored.

### Locked decisions

- CRDTs are hand-rolled. No Automerge or Yjs (ADR-000).
- Server: Java 21. Client: TypeScript + React. The CRDT core is written twice and proven
  identical by shared golden vectors and cross-language fuzzing (§9). TypeScript-everywhere
  is the fallback if time runs short (ADR-000b).
- Snapshots: JSON for v1, compact binary later.
- Presence: last item in Phase 3.

### Changes from v2

| # | Change | Why |
|---|---|---|
| 1 | `board_ops` is unpartitioned in v1; dedup moved to its own key | Postgres requires primary keys and unique constraints on a partitioned table to include the partition key. v2's keys did not, so the table would not compile. |
| 2 | Server rejects ops whose HLC physical time is more than 60s ahead of server time | A client with a fast clock would otherwise win every LWW contest and drag every later HLC forward. |
| 3 | Delete wins over a concurrent edit, stated as a rule | v1 said "add wins", but an edit never adds an OR-Set tag, so the claim was contradictory. |
| 4 | Cards in a deleted list move to a built-in Orphaned list | v2 left them invisible, which broke "no lost edits". |
| 5 | Write path is persist, then apply | v2 applied to memory first, so a failed append left an unpersisted op in memory and in the next snapshot. |
| 6 | Fencing token comes from Redis `INCR` and is checked inside the Postgres append transaction | `SET NX` gives no monotonic token, and a Redis failover can briefly create two owners. |
| 7 | Every broadcast carries a board version; clients detect gaps | Redis Pub/Sub is fire-and-forget; "fixed by the next sync" was undefined. |
| 8 | Ordering ties break on `(position_key, entity_id)`; no key suffix | The `-replica_id` suffix sat outside the alphabet and complicated every later `keyBetween`. |
| 9 | Tombstones are kept for the life of the board | GC would break clients offline up to 24 hours; at 2,000 cards the cost is IDs only. |
| 10 | Ops rejected for lost access are returned to the user, not dropped | Revocation while offline otherwise silently loses edits. |
| 11 | Gateway-to-owner routing is specified | v2 named it but never said how a gateway finds the owner or what happens during a handover. |
| 12 | Phase 4 drops Terraform and EKS | Reliant already proves them; the time goes to correctness and outside validation. |
| 13 | Scale targets split into design targets and measured targets | 50,000 WebSockets cannot be measured meaningfully on one laptop. |

## 1. Requirements

Users on different devices edit the same board at once, see each other's changes within
200ms, keep working offline for up to 24 hours, and never lose an edit or get asked to pick
a version.

### 1.1 Functional

- Create boards; invite members as owner, editor or viewer.
- A board holds ordered lists; a list holds ordered cards.
- Concurrently, from any session: create, edit (title, description, assignee, labels),
  reorder, move between lists and delete cards; create, rename, reorder and delete lists.
- Offline edits merge on reconnect with no conflict dialog.
- History: who changed what, when, for at least 30 days.
- Presence: who is viewing the board, and which card they have open.

### 1.2 Non-functional

Targets marked measured must be demonstrated with recorded numbers. Design targets
shape the architecture but are argued, not claimed as measured, because one laptop
cannot exercise them honestly.

| Target | Value | Kind |
|---|---|---|
| Convergence | Identical state on every replica given the same op set, in any order | Measured (fuzz, §9) |
| Durability | No ACKed op is ever lost | Measured (chaos drills) |
| Sync latency, op created → visible on another online client | p95 < 200ms, p99 < 500ms at 2–10 editors | Measured |
| Editors per board | 30 with correctness; latency recorded | Measured |
| Cards per board | 2,000 with no client lag (render < 16ms per update) | Measured |
| Offline window | 24 hours of local edits | Measured |
| Owner crash recovery | New owner serving within 5s of lease expiry | Measured |
| Concurrent connections | ~1,000 on one machine | Measured |
| Fleet scale | 10,000 open boards, ~50,000 connections | Design |

### 1.3 Non-goals

- Character-level collaborative text. Description fields are last-writer-wins as a whole.
- Caret-level cursors.
- Moving cards between boards.
- Unbounded undo. History is a 30-day audit view.
- SSO, two-factor sign-in, email invites. A sign-in with roles is enough.

## 2. Architecture

Exactly one Session Service instance owns each board at a time and is the only place its
merge runs; gateways are stateless and forward to that owner. Postgres is the only source
of truth; Redis carries leases, fan-out and presence, and can be wiped without losing
anything but presence.

*Architecture diagram (PDF figure): clients, gateways, board owners, Redis, Postgres.*
One edit's path: client to gateway to the board's owner, commit in Postgres, then publish
through Redis to every gateway serving that board.

### 2.1 Services

| Service | Does | Never does |
|---|---|---|
| Gateway (Java) | WebSocket lifecycle, JWT check on upgrade, frame parsing, owner lookup, forwarding ops, delivering broadcasts to its clients | Holds CRDT state |
| Session Service (Java) | Owns leased boards: validate, dedup, persist, apply, ACK, broadcast; snapshots; recovery | Talks to clients directly |
| REST API (Java, may share the gateway process) | Sign-in, boards, membership, cold snapshot, history | Live op traffic |
| Client (TypeScript, React) | Local replica, optimistic apply, IndexedDB queue, offline mode, rendering | Trusts its own clock beyond §3.1 |

### 2.2 Board actor and threads

Each board is an actor: one mailbox, drained by one thread at a time, so merge code takes
no locks. Use one virtual thread per active board reading from a `BlockingQueue`. Each
board's actor is also its writer: it batches that board's appends into group commits
(§6.1 step 3) over a connection pool shared by the instance's boards. Board actors never
block on each other; a slow board cannot stall another. Time spent waiting for a pool
connection counts toward the 2s commit timeout (§6.1 step 5); pool sizing is measured in
Phase 4.

Every queue is bounded, so no slow peer can grow a process's memory without limit:

| Queue | Bound | When full |
|---|---|---|
| Board actor mailbox | 5,000 requests (`Drop` and `Release` always accepted) | the request gets `RETRY_LATER` |
| Gateway → client, unsent bytes per connection | 8 MB (`CONCORD_CLIENT_OUTBOX_BYTES`) | the gateway sends `RECONNECT_ELSEWHERE`, closes, and cuts the connection after 1s; the client reconnects and catches up by version (§7.3) |
| Gateway, requests waiting per connection | 256 | the request gets `RETRY_LATER` |
| Owner → gateway, unsent bytes per gateway | 64 MB | the owner cuts that gateway's socket; its pending requests fail with `UNAVAILABLE` and clients retry |
| Redis commands queued in a process | 10,000 | the command fails; a lost publish is a version gap (§6.4) |

### 2.3 Assignment and routing

1. Live Session instances register in Redis: `instances` sorted set, score = last heartbeat,
   refreshed every 2s, entries older than 6s ignored.
2. A board's preferred owner is the top instance in rendezvous hashing of `board_id` over
   live instances. If it is down, the next in that order.
3. The chosen instance claims the board (§6.3) and writes `board:owner:{id}` →
   `{instance, address, fence}`.
4. A gateway caches the owner per board for 5s and forwards ops over one internal
   WebSocket per instance pair.
5. An instance that does not own the board answers `NOT_OWNER`. The gateway drops its
   cache entry, looks the owner up again and resends. Resending is safe because every op
   carries an `op_id` (§4.3).
6. The owner publishes each committed op on Redis channel `board:{id}`. A gateway
   subscribes to a board's channel while it has at least one client on that board.

### 2.4 Handover

On graceful shutdown an owner stops taking ops, flushes the writer, writes a snapshot,
then deletes its lease so the next instance claims at once. On a crash the lease expires
after 15s and the next instance claims it. In both cases ops in flight to the old owner fail
and are resent by the client's pending queue.

## 3. CRDT design

Two CRDTs cover everything: an OR-Set decides which lists and cards exist, and a
last-writer-wins map holds every attribute, including placement. Every tie breaks on a hybrid
logical clock. Boards themselves are metadata created over REST, not CRDT state.

### 3.1 Hybrid logical clock

- An HLC is `(physical_ms, counter, replica_id)`, compared in that order. It is a total
  order consistent with causality. It does not detect concurrency, and nothing in this
  design relies on it to.
- `tick()` and `receive(remote)` follow the standard HLC algorithm (Kulkarni et al., 2014).
- `JOINED` carries server time; the client keeps `offset = server_time - local_time`
  and uses `local_time + offset` as its physical clock.
- **Skew guard.** The owner rejects any op whose `physical_ms` is more than 60s ahead of
  server time, with `ERROR CLOCK_SKEW` and the server time. The client recomputes its
  offset, re-stamps the op with a new HLC and resends it under the same `op_id`.
  Without this guard, one fast clock wins every contest it joins.
- The guard is the pure function `op.checkClock(op, server_time_ms)`. It rejects if and
  only if `op.hlc.physical_ms > server_time_ms + 60000` (strict): an op exactly 60,000ms
  ahead is accepted, 60,001ms is rejected.
- Ops older than the clock are always accepted; age is never a reason to reject.

### 3.2 Existence: OR-Set

- Tombstones are per entity: the set holds `adds[id]` and `tombstones[id]`, each a set of
  tags. An entity exists while `adds[id]` minus `tombstones[id]` is non-empty. A tag is the
  `op_id` of the op that added it.
- `DELETE_*` naming X carries the tags the deleting replica had observed and tombstones
  exactly the pairs (X, tag). A delete naming X never affects any other entity, even if
  it lists another entity's tag (vectors 051, 052).
- A delete may arrive before the add it cancels: `tombstones[X]` then exists with no
  `adds[X]`, and the add is cancelled on arrival (vectors 012, 053, 054).
- `RESTORE_*` adds a new tag. That is why this is an OR-Set and not a two-phase set: a
  restore concurrent with a delete survives, because the delete never observed the
  restore's tag.
- Tombstones are kept for the life of the board. At 2,000 cards that is a few hundred KB
  of IDs.

### 3.3 Rule: delete wins over a concurrent edit

An edit never adds a tag, so it cannot keep a deleted entity alive. The edit is still merged
into the attribute map; the entity is just not rendered. If the entity is later restored, it
shows every edit, including those made after the delete. No edit is discarded, only hidden
while the entity is deleted.

### 3.4 Attributes: LWW map

- Per entity, each key is a register `{value, hlc}`. Merge keeps the higher HLC. Two
  different values with the same HLC cannot exist: the HLC includes the replica, and a
  replica never reuses a tick.
- Defensive tie-break, for when that invariant is broken anyway (a buggy client, a
  corrupt snapshot): if two registers have equal HLCs and different values, keep the
  value whose canonical encoding (§9.2.2) is greater, comparing UTF-8 bytes as
  **unsigned** bytes (`Arrays.compareUnsigned` in Java, a byte loop over `Uint8Array`
  in TypeScript; never Java `String.compareTo`, whose UTF-16 order differs outside the
  BMP). This keeps merge a true join on every state, valid or not.
- When the tie-break fires, `lww.set`, `lww.merge`, `board.apply` and `board.merge`
  report it as a collision count in their result, so the server can export it as a
  metric. The modules stay pure: they never log.
- Card keys: `title`, `description`, `assignee`, `labels` (the whole set as one value),
  `placement`.
- List keys: `title`, `placement`.

### 3.5 Placement register

- Card: `placement = {list_id, position_key}`. List: `placement = {position_key}`.
- Create, move between lists and reorder all write a complete placement in one op with
  one HLC. No merge can mix one writer's list with another writer's position.
- Concurrent moves of the same card to different lists: the higher HLC wins whole. The
  losing client shows a brief "moved by {name}" transition, not a silent jump. This is
  tested (§9).

### 3.6 Rule: cards in a deleted list

A live card whose `placement.list_id` names a deleted list is shown in a built-in
Orphaned list. This is a derived view, not a write, so every replica computes the same
result. Restoring the list returns its cards. Moving a card out of Orphaned is an ordinary
placement write.

Exact rules (the view is a pure function of the state):

- An entity is *rendered* when it is live (§3.2) and has a `placement` register. An
  entity with no placement register can only exist before its `ADD_*` has arrived; it is
  not shown.
- A rendered card is shown in the list its `placement.list_id` names if that list is
  rendered. Otherwise — the list is deleted, *or has never been seen by this replica*
  (ops delivered out of order), or the placement has no `list_id` — it is shown in
  Orphaned.
- When a never-seen list's `ADD_LIST` arrives, its cards return to it with no write.
- A deleted card in a deleted list is not shown anywhere.
- Orphaned has the reserved ID `00000000-0000-0000-0000-000000000000` (the nil UUID).
  It is shown after every other list, and only when it has at least one card. Its cards
  are sorted by `(position_key, entity_id)` like any list.
- No op may target the nil UUID (§4.2), so Orphaned can never be created, deleted or
  moved into; moving a card out of it is an ordinary `SET_PLACEMENT`.

### 3.7 Ordering keys

- Keys use base62 in ASCII order: `0–9`, `A–Z`, `a–z`. Plain byte comparison sorts them in
  Java and TypeScript alike.
- `keyBetween(a, b)` is a credited port of Greenspan's fractional-indexing algorithm as
  implemented in `rocicorp/fractional-indexing` (CC0-1.0, public domain; see ADR-001). `null` means the open start or end. A key is an *integer part*
  whose length is fixed by its head character (`a`–`z`: 2–27 characters; `A`–`Z`: 27–2)
  followed by a *fractional part*. **The fractional part never ends in `0`**, so there is
  always room before a key. The first key is `a0` (integer part only).
- `keyBetween(a, b)` throws unless `a < b` (upstream silently swaps reversed bounds; we
  do not). It is deterministic: no randomness.
- Render order is `(position_key, entity_id)`. Equal keys from concurrent inserts sort
  by ID, deterministically, with no suffix. Equal keys are common, not rare: the midpoint
  is deterministic, so two replicas appending to the same list at once pick the same key.
- Inserting between two neighbours with equal keys: first re-key the upper neighbour
  with an ordinary `SET_PLACEMENT`, repeating for each further neighbour that shares the
  key, then insert between the now-distinct keys. `keys.planInsert` computes these writes.
- Known anomaly: two users inserting several cards into the same gap at once can see
  them interleave. Acceptable for a board; documented in ADR-001.
- Rebalance: when a key passes 40 characters, the owner issues ordinary
  `SET_PLACEMENT` ops with fresh keys under its own replica ID. Because they are normal
  ops, they converge like any other.

## 4. Operations

Eight op types, each carrying a client-made `op_id`, an HLC and a replica ID. Every op is
validated before it reaches merge, and a repeated `op_id` gets the original ACK back
without being applied twice.

### 4.1 Op set

Every op has `op_id` (UUID v4), `board_id`, `hlc`, `replica_id`, `type`, plus:

| Type | Fields | Effect |
|---|---|---|
| `ADD_LIST` | `list_id`, `placement`, `title` | New add-tag + initial registers |
| `ADD_CARD` | `card_id`, `placement`, `title` | New add-tag + initial registers |
| `SET_FIELD` | `entity_id`, `field`, `value` | One register write. Placement is not allowed here. |
| `SET_PLACEMENT` | `entity_id`, `placement` | Atomic placement write: move or reorder |
| `DELETE_LIST` | `list_id`, `observed_tags` | Tombstones those tags of `list_id` |
| `DELETE_CARD` | `card_id`, `observed_tags` | Tombstones those tags of `card_id` |
| `RESTORE_LIST` | `list_id` | New add-tag |
| `RESTORE_CARD` | `card_id` | New add-tag |

An `ADD_*` for an ID that already exists is invalid. Entity IDs are UUIDs made once by the
creating client.

JSON encoding. Every op is one object with exactly these members; any other member is
invalid:

| Type | Members besides `op_id`, `board_id`, `hlc`, `replica_id`, `type` |
|---|---|
| `ADD_LIST` | `list_id`, `placement: {position_key}`, `title` |
| `ADD_CARD` | `card_id`, `placement: {list_id, position_key}`, `title` |
| `SET_FIELD` | `entity_id`, `field` (`title`, `description`, `assignee` or `labels`), `value` |
| `SET_PLACEMENT` | `entity_id`, `placement` (card form `{list_id, position_key}` or list form `{position_key}`) |
| `DELETE_LIST` | `list_id`, `observed_tags: [op_id, ...]` |
| `DELETE_CARD` | `card_id`, `observed_tags: [op_id, ...]` |
| `RESTORE_LIST` | `list_id` |
| `RESTORE_CARD` | `card_id` |

`hlc` is the §9.2.1 object and `hlc.replica_id` must equal `replica_id`. Field values:
`title` a string, `description` a string, `assignee` a user ID or `null`, `labels` an
array of strings. `ADD_*` writes the `title` and `placement` registers with the op's HLC
and adds the tag `op_id`; `RESTORE_*` adds the tag `op_id`; `DELETE_*` tombstones
`observed_tags` under the entity it names; `SET_*` writes one register.

### 4.2 Validation (owner, before persisting)

1. Schema: known type, required fields, types, title ≤ 200 code points, description
   ≤ 10,000 code points, ≤ 20 labels.
2. Authorization: the sender's role on this board, re-read per op from a 30s membership
   cache that is cleared when membership changes. Viewers can submit no ops.
3. Clock: §3.1 skew guard.
4. References: `SET_PLACEMENT` and `ADD_CARD` must name a list that has existed on this
   board (live or deleted, per §3.6). Also, each rejected as `INVALID_OP`:
   - `ADD_LIST` and `ADD_CARD` must name an ID never added before, as a list or a card.
   - `SET_FIELD`, `SET_PLACEMENT`, `DELETE_*` and `RESTORE_*` must name an entity of the
     matching kind (list or card) that has existed on this board.
   - A list takes only `SET_FIELD title`, and the list form of placement; a card takes the
     card form.
   - "Has existed" includes ops admitted to the board's current, uncommitted batch, so an
     `ADD_LIST` and the `ADD_CARD` into it may share one group commit.

   These are server admission rules only. The engine's merge still accepts any op in any
   order, as the fuzzer requires; a client applying broadcast ops never checks them.
   Dedup comes first (§6.1 step 2): before rejecting an op for any reason but
   `RETRY_LATER`, the owner looks its `op_id` up in `board_ops`, and if it is stored (an op
   committed before a failover, resent to a new owner whose LRU is empty) returns its
   original ACK instead.
5. Key sanity: a `position_key` is 1–64 base62 characters and passes Greenspan's full
   key check: the head character is `a`–`z` or `A`–`Z`, the integer part has the length
   that head requires, the fractional part does not end in `0`, and the key is not the
   reserved minimum `A` followed by 26 `0`s.

Steps 1 and 5 are `op.validateShape(op)`, a pure function shared by client and server.
Its exact rules, beyond step 1:

- Every ID (`op_id`, `board_id`, `replica_id`, entity IDs, `list_id`, `assignee`,
  `observed_tags` entries) is a lowercase canonical UUID: `8-4-4-4-12` lowercase hex
  digits. Uppercase, braces or missing hyphens are rejected.
- No ID may be the nil UUID `00000000-0000-0000-0000-000000000000`, which is reserved for
  Orphaned (§3.6). In particular `ADD_CARD` and `SET_PLACEMENT` cannot target it.
- Lengths are counted in Unicode code points, so Java and TypeScript agree. Strings must
  be well-formed Unicode (no lone surrogates) and must not contain U+0000, which Postgres
  JSONB cannot store (§5.1; vector 055).
- `labels`: at most 20; each at most 50 code points and already in Unicode NFC form;
  sorted strictly ascending by unsigned UTF-8 bytes (which also makes them unique).
  Anything else is rejected, not normalised.
  Known limitation: the NFC check uses each runtime's Unicode tables (Java 21's
  `java.text.Normalizer`, the browser's or Node's ICU). For characters assigned in a
  Unicode version one side has and the other lacks, client and server can disagree on
  whether a label is NFC. This affects validation only, never convergence: the server's
  verdict is final, and a rejected op goes to Unsent edits (§7.4).
- Integers are the §9.2.1 ranges; any non-integer number is rejected.
- Step 3 is `op.checkClock(op, server_time_ms)` (§3.1). Steps 2 and 4 need server state
  and live in the Session Service.
- The server is the only parser of client-supplied JSON, and it rejects any object with
  a duplicate member name as `INVALID_OP`. Clients parse only server-produced JSON, which
  never contains duplicates, so the TypeScript core may use `JSON.parse` (which keeps the
  last duplicate) without the two sides ever reading the same text differently.

A failed check returns `ERROR {code, op_id}`. Nothing is stored or broadcast. Rejected
ops are logged with the user and reason.

### 4.3 Idempotency

1. Each board actor keeps an LRU of its last 10,000 `op_id` → ACK. A hit returns the
   stored ACK.
2. The backstop is the primary key `(board_id, op_id)` on `board_ops`, appended with
   `ON CONFLICT DO NOTHING`. On conflict the owner reads the stored version and returns
   the original ACK.
3. Duplicates are never re-broadcast. A metric counts them.

### 4.4 CRDT module layout

Same layout in `crdt-java/` and `crdt-ts/`, with no networking or storage code:

| Module | Exposes |
|---|---|
| `hlc` | `tick`, `receive`, `compare`, `encode` |
| `op` | op types, `validateShape`, `newOpId` |
| `orset` | `add`, `remove(observed)`, `contains`, `merge` |
| `lww` | `set`, `get`, `merge` |
| `keys` | `keyBetween`, `rebalance` |
| `board` | `apply(op)`, `merge(state, state)`, `view()` (lists, cards in order, Orphaned), `encode` / `decode` |

## 5. Data

Postgres holds every op, snapshot and membership; Redis holds only what can be lost. If
Redis is wiped, everything except presence is rebuilt from Postgres.

### 5.1 Postgres schema

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,         -- scrypt or argon2id
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE boards (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0,   -- last committed op version
    fence BIGINT NOT NULL DEFAULT 0,     -- highest owner fence seen
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE board_members (
    board_id UUID REFERENCES boards(id),
    user_id UUID REFERENCES users(id),
    role TEXT NOT NULL CHECK (role IN ('owner','editor','viewer')),
    PRIMARY KEY (board_id, user_id)
);

-- Not partitioned in v1. If volume ever needs it: PARTITION BY HASH (board_id),
-- which keeps both keys legal because both include board_id.
CREATE TABLE board_ops (
    board_id UUID NOT NULL REFERENCES boards(id),
    op_id UUID NOT NULL,
    version BIGINT NOT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    hlc_physical_ms BIGINT NOT NULL,
    hlc_counter INT NOT NULL,
    replica_id UUID NOT NULL,
    user_id UUID NOT NULL,
    fence BIGINT NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, op_id),
    UNIQUE (board_id, version)
);
CREATE INDEX board_ops_history ON board_ops (board_id, committed_at DESC);

CREATE TABLE board_snapshots (
    board_id UUID NOT NULL REFERENCES boards(id),
    version BIGINT NOT NULL,             -- last op version included
    state JSONB NOT NULL,
    sha256 TEXT NOT NULL,                -- of the canonical encoding (§9.2.2); checked on load
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (board_id, version)
);
```

JSONB does not keep the bytes it was given (it reorders members and drops whitespace), so
a snapshot's `sha256` is the SHA-256 of the canonical encoding (§9.2.2) of its state, and
on load the state read back from JSONB is decoded, re-encoded canonically and hashed
again before it is compared.

Version is a per-board counter assigned by the owner at commit. It orders broadcasts
and lets clients detect gaps (§6.4). It is not used for merge; the HLC is.

Snapshots are written every 500 ops or 60s of activity, whichever comes first. The last
three per board are kept.

Op retention: ops older than 35 days and at or below the latest snapshot's version are
deleted daily. History covers 30 days. A client behind the oldest kept op gets a full
snapshot (§7).

### 5.2 Redis

| Key | Holds | Lifetime |
|---|---|---|
| `instances` (sorted set) | Live Session instances, score = heartbeat time | Entries ignored after 6s |
| `board:lease:{id}` | `{instance}:{fence}`, set with `NX PX 15000` | 15s, renewed every 5s |
| `board:fence:{id}` | Counter, `INCR` on every claim | Permanent; rebuilt from `boards.fence` if lost |
| `board:owner:{id}` | `{instance, address, fence}` for gateway lookup | Same as the lease |
| `board:{id}` (channel) | Committed ops for fan-out | Ephemeral |
| `membership` (channel) | A board ID whose membership changed; every owner clears that board from its 30s cache (§4.2 step 2) | Ephemeral; a lost message only delays revocation to the 30s TTL |
| `presence:{id}` (sorted set) | Member = `user:card:state`, score = last seen | Swept every 10s, entries older than 30s removed |

## 6. Write path and ownership

An op is accepted only when its Postgres transaction commits, and that transaction also
checks the owner's fence, so a stale owner can neither store nor broadcast anything.
Memory is updated only after the commit.

### 6.1 Write path

1. The op enters the board actor's mailbox.
2. Dedup (§4.3), then validate (§4.2). On failure, reply and stop.
3. The actor's writer collects ops for up to 15ms (tunable), then runs one transaction for
   this board:
   1. Drop any `op_id` already in `board_ops` for this board. This catches duplicates the
      LRU missed; their stored ACKs are returned after commit.
   2. `UPDATE boards SET version = version + :n WHERE id = :board AND fence = :fence RETURNING version`,
      where `:n` counts the remaining ops. Equality, not `<=`: the claim already wrote this
      owner's fence (§6.2 step 3), so only the current owner may commit. Zero rows means a
      newer owner exists (§6.3).
   3. Insert the ops with consecutive versions ending at the returned version.
4. After commit: apply each op to memory, store its ACK in the LRU, send
   `OP_ACK {op_id, version}` to the sender, publish `{op, version}` on `board:{id}`.
5. If the transaction fails or takes over 2s: apply nothing, ACK nothing, reply
   `ERROR RETRY_LATER`. The client keeps the op queued (§7). Other clients see no new ops on
   this board until Postgres returns. That is the deliberate price of never acknowledging
   an unsaved edit (ADR-005).

   A failure or timeout leaves the outcome unknown: the COMMIT may have been applied with
   its reply lost. So the actor then *reconciles* before serving again: it locks the board
   row (`SELECT … FOR UPDATE`, which waits out any of its own transactions still holding
   it), reads every op with a version above its own, applies them and publishes them. Until
   that succeeds it answers every request with `RETRY_LATER`, takes no snapshots, and
   retries the reload with backoff. A client's resend of such an op then gets the original
   ACK from the LRU, so it is applied, published and ACKed exactly once. If the board's
   fence has changed meanwhile, the actor drops the board instead (§6.3).

Applying after commit costs nothing in correctness: merge is commutative, so the order
of apply does not matter. Applying first, as v2 did, would leave an unsaved op in memory
and in the next snapshot.

### 6.2 Claiming a board

1. Read `boards.fence` from Postgres. In one Lua script: `INCR board:fence:{id}`, and if
   the result is not greater than `boards.fence`, set the counter to `boards.fence + 1` and
   use that. Atomic, so two claimers never get the same fence; survives a Redis wipe.
2. `SET board:lease:{id} {instance}:{fence} NX PX 15000`. If it fails, someone else
   owns the board; return `NOT_OWNER`.
3. `UPDATE boards SET fence = :fence WHERE id = :board AND fence < :fence`. Zero rows: a
   higher fence reached Postgres first; release the lease and retry from step 1. This comes
   after the lease, so a claimer that loses the lease race never fences out the live owner,
   and before the load, so from this commit on no older fence can commit (§6.1 step 3.2)
   and the replay below sees every op an older owner ever committed.
4. Load the latest snapshot. If its SHA-256 does not match, use the previous one and
   alert.
5. Replay ops with a higher version, in version order.
6. Write `board:owner:{id}`, start renewing the lease every 5s with a compare-and-extend
   Lua script, and start serving. If renewal finds the lease gone (expired, or Redis
   wiped), it re-takes it with the same value; Postgres still fences this owner, so this
   is safe. If another instance holds it, the owner drops the board.

Why step 3 exists: without it the new owner's fence reaches Postgres only with its first
commit. An old owner paused past its lease could still commit with `fence <= :fence`
after the new owner had loaded, and the new owner's memory and snapshots would then skip
an acknowledged op. `FencingTest` replays exactly that interleaving.

Target: serving within 5s of the claim for a board of 2,000 cards with a 500-op tail.

### 6.3 Fencing

- Safety rests on Postgres, not Redis. A Redis failover can lose a lease and briefly let two
  instances both believe they own a board. Only the one with the higher fence can
  commit (§6.1 step 3.2). The other gets zero rows, drops the board at once and answers
  `NOT_OWNER` from then on.
- Because broadcast happens only after commit, a fenced-out owner can never
  broadcast either.
- If lease renewal fails because Redis is unreachable, the owner keeps serving. Postgres
  still fences it. Boards with no owner cannot be claimed until Redis returns; their clients
  get `UNAVAILABLE` and retry.

### 6.4 Versions and gap detection

- Every broadcast carries its board version. A client that receives version `v` when it last
  had `v - 2` or lower sends `SYNC_REQUEST {since_version}` before applying anything
  newer.
- Every 5s the owner publishes `BOARD_VERSION {version}`. A quiet client that is behind
  notices within 5s.
- If Redis Pub/Sub is down, each gateway polls the owner once a second for ops newer
  than the last version it delivered, for each board it serves. Fan-out keeps working, just
  slower.

## 7. Client and offline sync

The client applies every edit at once, stores it in IndexedDB until the server ACKs it, and
never discards an unacknowledged edit, whatever the server sends back.

### 7.1 Local state

- Confirmed replica: state built only from server-confirmed ops, plus its board version.
- Pending queue: the client's own ops not yet ACKed, in creation order.
- View = confirmed replica with the pending ops applied on top, updated incrementally.
  When an op is rejected, it is removed from the queue and the view is rebuilt. Nothing
  optimistic ever needs undoing by hand.
- IndexedDB stores the confirmed replica, its version, the pending queue, the replica ID
  and the HLC. A reload resumes exactly where the tab left off.
- Each tab is its own replica with its own ID, so two tabs never share an HLC sequence.
  On start, a tab takes over the queues of closed tabs, using the Web Locks API so only
  one tab adopts each queue.

### 7.2 Edit

1. Make the op: new `op_id`, `hlc.tick()`.
2. Append it to the pending queue in IndexedDB.
3. Update the view.
4. If connected, send it. If not, it waits.

### 7.3 Reconnect

1. Reconnect with backoff: 0.5s × 2, capped at 30s, full jitter.
2. Send `JOIN {board_id, since_version}`.
3. The server replies with ops after that version, or with a full snapshot if the client is
   more than 2,000 ops behind or behind the oldest kept op.
4. Merge them into the confirmed replica. A snapshot replaces the confirmed replica; the
   pending queue is untouched.
5. Resend the whole pending queue. The server dedups by `op_id`.
6. Remove each op from the queue on its ACK.

Ops arrive from the server in version order, and the owner checks references before
committing, so a client never sees a move before the add it refers to.

### 7.4 Rejections

| Error | Client does |
|---|---|
| `CLOCK_SKEW` | Recompute the clock offset from the server time in the error, re-stamp the op, resend under the same `op_id` |
| `RETRY_LATER`, `UNAVAILABLE` | Keep the op; retry with backoff |
| `INVALID_OP` | Remove it from the send queue, log it (a client bug) and move it to the Unsent edits panel. No user edit is ever silently discarded. |
| `UNAUTHORIZED` (access revoked while offline) | Move the op to an Unsent edits panel the user can read and export as JSON. It is never silently dropped. |

### 7.5 WebSocket protocol

JSON frames for v1.

| Direction | Message | Fields |
|---|---|---|
| Client → server | `JOIN` | `board_id`, `since_version` (null on first open) |
| Client → server | `OP` | `op` |
| Client → server | `SYNC_REQUEST` | `board_id`, `since_version` |
| Client → server | `PRESENCE` | `board_id`, `card_id` or null, `state` |
| Server → client | `JOINED` | `ops` or `snapshot`, `version`, `server_time_ms`, `role` |
| Server → client | `OP_ACK` | `op_id`, `version` |
| Server → client | `OP_BROADCAST` | `op`, `version` |
| Server → client | `BOARD_VERSION` | `version` |
| Server → client | `SYNC` | `ops` or `snapshot`, `version` |
| Server → client | `PRESENCE_UPDATE` | `user_id`, `card_id`, `state` |
| Server → client | `RECONNECT_ELSEWHERE` | sent before a gateway drains |
| Server → client | `ERROR` | `code`, `op_id`, `detail`, and `server_time_ms` for `CLOCK_SKEW` |

## 8. Failure scenarios

Every scenario either keeps edits flowing or pauses them; none loses an acknowledged
edit. Those marked drill are induced on purpose in Phase 4 and written up as
postmortems, the way Reliant's were.

| # | Failure | Handling | Drill |
|---|---|---|---|
| 1 | Gateway crashes | Clients reconnect with backoff to another gateway and resend their pending queues | Yes |
| 2 | Owner crashes | Lease expires within 15s; the next instance claims, loads snapshot + tail, serves within 5s; clients resend | Yes |
| 3 | Two owners at once (Redis failover, or an owner paused past its lease) | The stale owner's commit updates zero rows; it drops the board and answers `NOT_OWNER`. It never broadcast anything uncommitted | Yes (pause an owner with `docker pause`, as in Reliant's incident 004) |
| 4 | Postgres down | Owners commit nothing, ACK nothing, return `RETRY_LATER`; clients keep editing locally; cross-client sync for new ops pauses until Postgres returns | Yes |
| 5 | Redis down | Owners keep serving (Postgres fences them); gateways switch to polling owners; presence is lost; unowned boards wait | Yes |
| 6 | Redis wiped and restarted empty | Fence counters rebuild from `boards.fence`; leases are reclaimed; nothing durable is lost | Yes |
| 7 | Pub/Sub message lost | Client sees a version gap or a `BOARD_VERSION` ahead of it and sends `SYNC_REQUEST` | Fault injection in tests |
| 8 | Client offline 24 hours | Snapshot or op range on reconnect; pending queue merged on top and resent | Load test |
| 9 | Same op sent many times | LRU or primary key dedup; original ACK returned; no re-broadcast | Tests |
| 10 | Client clock far ahead | `CLOCK_SKEW`; client corrects its offset and re-stamps | Tests |
| 11 | Access revoked while offline | Ops rejected and shown in Unsent edits, never silently dropped | Tests |
| 12 | Malformed or malicious op | Rejected before persist; logged with user and reason | Tests |
| 13 | Snapshot corrupt | Checksum mismatch; previous snapshot + longer tail; alert | Tests |
| 14 | Reconnect storm (gateway with 500 clients restarts) | Client jitter; per-gateway connection-rate limit; snapshot reads served from memory | Yes |
| 15 | Rolling deploy | Gateway sends `RECONNECT_ELSEWHERE` and drains; owner flushes, snapshots and releases its lease (§2.4) | Yes |

## 9. Testing, security and observability

The convergence fuzz suite and the cross-language conformance suite are the project's
main correctness claim, and both gate every merge in CI.

### 9.1 Property tests (jqwik in Java, fast-check in TypeScript)

- `orset`, `lww`, `board`: `merge(A, B) = merge(B, A)`, `merge(merge(A, B), C) = merge(A, merge(B, C))`,
  `merge(A, A) = A`, over random states.
- `keys`: for random `a < b`, `a < keyBetween(a, b) < b`; no generated key's fractional
  part ends in `0` (every generated key passes the §4.2 step 5 check); `null` bounds work.
- `hlc`: tick is strictly increasing; receive exceeds both inputs.
- Permutation fuzz: generate 50–500 random ops across 3–5 simulated replicas,
  deliberately heavy on concurrent moves of the same card, delete versus edit, delete
  versus restore, and inserts into the same gap. Deliver them to each replica in a
  different random order, with random duplicates. Assert byte-identical canonical state.
- CI runs 500 seeds per commit; a nightly job runs 100,000. Every failing seed is shrunk
  and saved as a permanent regression test.

### 9.2 Cross-language conformance

- Canonical state encoding: JSON with sorted keys (RFC 8785), so equal states give
  equal bytes and an equal SHA-256.

#### 9.2.1 HLC encoding

An HLC is a JSON object with exactly three fields:

```json
{"counter": 0, "physical_ms": 1760000000000, "replica_id": "a0000000-0000-4000-8000-000000000001"}
```

- `physical_ms`: integer, 0 ≤ n ≤ 2^53 − 1 (milliseconds since the Unix epoch).
- `counter`: integer, 0 ≤ n ≤ 2^31 − 1 (fits `board_ops.hlc_counter INT`).
- `replica_id`: lowercase canonical UUID string (`8-4-4-4-12` hex digits).
- Order: `physical_ms`, then `counter`, then `replica_id` compared as unsigned UTF-8
  bytes. Never compare `java.util.UUID` values: `UUID.compareTo` uses signed longs and
  disagrees with byte order.

#### 9.2.2 Canonical JSON

The canonical encoding is RFC 8785 (JCS) restricted to integers:

- No whitespace. Object members sorted by key, comparing UTF-16 code units (RFC 8785
  §3.2.3). Every key Concord itself writes is ASCII, where this equals byte order.
- Numbers are integers in the safe range ±(2^53 − 1), written in plain decimal. Any
  other number is an encoding error.
- Strings: escape only `"` → `\"`, `\` → `\\`, and characters below U+0020: `\b`, `\f`,
  `\n`, `\r`, `\t` where they apply, otherwise `\u00xx` with lowercase hex. Every other
  character is written as itself, encoded as UTF-8. A string that is not well-formed
  Unicode (a lone surrogate) is an encoding error.
- Hashes are SHA-256 over the UTF-8 bytes of the canonical encoding, written as 64
  lowercase hex characters.

#### 9.2.3 Canonical state

```json
{
  "cards":     {"adds": {"<card_id>": ["<tag>", ...]}, "tombstones": {"<card_id>": ["<tag>", ...]}},
  "lists":     {"adds": {"<list_id>": ["<tag>", ...]}, "tombstones": {"<list_id>": ["<tag>", ...]}},
  "registers": {"<entity_id>": {"<field>": {"hlc": <HLC>, "value": <value>}}}
}
```

- Every tag array is sorted ascending by unsigned UTF-8 bytes, with no duplicates.
- An ID appears under `adds` or `tombstones` only with at least one tag (a delete with
  empty `observed_tags` leaves no entry, vector 044), and under `registers` only with at
  least one field. An ID may be under `tombstones` and not `adds` (vector 053). The empty
  state is
  `{"cards":{"adds":{},"tombstones":{}},"lists":{"adds":{},"tombstones":{}},"registers":{}}`.
- Decoding a state checks its shape, not just its JSON: IDs and tags are lowercase
  canonical UUIDs, and every `placement` register holds a §3.5 value (an object with a
  string `position_key` and optionally a UUID `list_id`, nothing else), because the view
  reads placements. Anything else is rejected.
- The state holds CRDT data only: no replica clock, no board ID, no op log. Two replicas
  that have applied the same op set therefore encode to the same bytes.

#### 9.2.4 View

`board.view()` returns, in canonical JSON:

```json
{"lists": [
  {"id": "<list_id>", "position_key": "a0", "title": "To Do", "cards": [
    {"id": "<card_id>", "position_key": "a0", "title": "...", "description": "...",
     "assignee": "<user_id>" | null, "labels": ["..."]}
  ]},
  {"id": "00000000-0000-0000-0000-000000000000", "cards": [ ... ]}
]}
```

Lists and cards appear per §3.6, each sorted by `(position_key, id)` as unsigned bytes.
An entity's `title`, `description`, `assignee` and `labels` members are present exactly
when that register exists. Orphaned, when present, is last and has only `id` and `cards`.

#### 9.2.5 Vector format

```json
{
  "name": "002-delete-wins-over-concurrent-edit",
  "rule": "§3.3",
  "description": "...",
  "initial_state": <canonical state>,
  "server_time_ms": 1760000000000,
  "ops": [<op>, ...],
  "expected_rejected": {"<op_id>": "INVALID_OP" | "CLOCK_SKEW"},
  "expected_collisions": 0,
  "expected_view": <view>,
  "expected_state": <canonical state>,
  "expected_state_sha256": "<64 hex>"
}
```

A runner decodes `initial_state`, then takes `ops` in order: an op failing
`validateShape` is `INVALID_OP`; if `server_time_ms` is present, an op failing
`checkClock` is `CLOCK_SKEW`. The rejected set must equal `expected_rejected` exactly
(`server_time_ms` and `expected_rejected` are optional). Accepted ops are applied in
order; their summed collision counts must equal `expected_collisions`. Then the view must
encode to the same bytes as `expected_view`, the state to the same bytes as
`expected_state`, and the state's SHA-256 must equal `expected_state_sha256`. Finally the
accepted ops are applied again, in reverse order, to a fresh decode of `initial_state`,
and must give the same state bytes.

Expected values are written by hand. `conformance/tools/build_vectors.py` only expands
readable aliases into UUIDs, sorts tag arrays and computes each hash from the
hand-written `expected_state` with Python's JSON encoder, a third implementation
independent of both cores.
- `conformance/vectors/*.json`: an initial state, an op list and the expected view and
  state hash. At least 50 hand-written vectors, one per rule in §3, plus every shrunk fuzz
  failure.
- Differential fuzz: the TypeScript fuzzer feeds the same op sequence to the Java core
  through a small CLI and compares state hashes. Any difference fails CI.

### 9.3 Integration and end-to-end tests

- No ACK before commit: inject a commit failure and assert no ACK, no broadcast and no
  memory change.
- Fencing: two owners, the stale one's write rejected. The test must fail with the fence
  check removed.
- Dedup, gap detection, clock-skew rejection, revocation into Unsent edits, Orphaned
  list, snapshot checksum fallback.
- Board isolation: no op, presence or history from board X ever reaches a client of board
  Y.
- Playwright: two browser contexts editing one board, one taken offline, both converging
  after reconnect.

### 9.4 Security

- JWTs live 15 minutes and are scoped to the user; board role is checked per op (§4.2), so
  removal takes effect on the next op, not the next connect.
- The WebSocket upgrade checks `Origin` against an allowlist, preventing cross-site
  WebSocket hijacking.
- The internal gateway ↔ owner WebSocket (§2.3 step 4) requires a shared secret, sent by the
  gateway in the `X-Concord-Internal` header on upgrade and compared in constant time.
  Outside dev it must be at least 32 bytes and not the dev value.
- Rate limit per connection: 20 ops/s, burst 50, as a token bucket in the gateway's memory
  (a connection lives on one gateway, and limiting must keep working while Redis is down).
  Frames over 64 KB are rejected.
- Passwords hashed with argon2id. Sessions stored hashed, as in Reliant.

### 9.5 Observability

| Kind | What |
|---|---|
| Metrics | Sync latency (client-reported op created → seen elsewhere), commit latency and group-commit batch size, ops/s per board, duplicates, rejections by code, LWW ties actually decided, ownership claims and fence rejections, version gaps detected, connections per gateway, snapshot size and duration, recovery time |
| Logs | JSON lines with `board_id`, `op_id`, `user_id` where known: connections, rejections, claims, fence rejections, snapshot failures |
| Traces | OpenTelemetry: client send → gateway → owner → commit → broadcast → peer render |
| Alerts | Sync p95 > 200ms for 5 min; commit failures; any fence rejection; claim churn above normal; version-gap rate above normal; reconnect storm |

## 10. Phases

Four phases, each ending in a demonstrable artifact. Phase 1 has no networking at all: the
engine is proven correct before any distributed code exists. Phases 1–3 carry almost all of
the new signal; Phase 4 deliberately reuses what Reliant already proved.

1. **CRDT engine, both languages.** `hlc`, `op`, `orset`, `lww`, `keys`, `board` in Java and
   TypeScript; canonical encoding; property tests; permutation fuzz; ≥ 50 conformance
   vectors; differential fuzz. ADR-000, 000b, 001, 002 written.
   *Exit:* 500-seed fuzz and differential fuzz green in CI; 100,000-seed nightly run green;
   `crdt-ts` publishable as a standalone package with its tests.
2. **Distributed system.** Gateway, Session Service with board actors, write path (§6.1),
   claiming and fencing (§6.2–6.3), versions and gap detection, snapshots, recovery. Runs
   in docker compose: 2 gateways, 3 Session instances, Postgres, Redis.
   *Exit:* two clients on different gateways converge; killing an owner recovers within 5s
   with every ACKed op present in `board_ops`; the fencing test fails when the fence
   check is removed.
3. **Product.** React board UI, IndexedDB and offline mode (§7), sign-in and roles, history
   view, Unsent edits, Orphaned list, then presence.
   *Exit:* two browsers edit one board, one goes offline for an hour, both converge with
   no devtools involved. Then at least one real group (a club, a class project, friends'
   side project) uses it for two weeks, and the bugs they hit are logged.
4. **Operations, reusing Reliant's patterns.** Docker images; multi-instance docker
   compose; the §9 suites as CI gates; metrics, logs, traces and alerts; the measurements
   below; the drills from §8 written up as postmortems; ADRs complete; one public write-up.
   *Exit:* measurements recorded in `production/load-tests/`; every drill has a
   postmortem in `production/incident-reports/`; a single-VM public demo, as in
   Reliant's `deploy/`.

Not in scope for Phase 4: Terraform, EKS, managed AWS services. Reliant shows them
already; say so in interviews rather than build them twice.

### 10.1 Measurements

All on one machine, reported like Reliant's: where the bottlenecks sit, not capacity.

| Test | Setup | Pass |
|---|---|---|
| Baseline sync latency | 2 clients, 1 board, 1 op/s each | p95 < 200ms |
| Editors per board | Ramp 2 → 30 editors on one board, 1 op/s each | Correct state at 30; p95 recorded at each step, and the point where it passes 200ms named |
| Commit batching | Same load, group-commit window 0, 5, 15, 30ms | Latency and commits/s per setting recorded; chosen value justified in ADR-005 |
| Many boards | 1,000 connections across 300 boards, 3 editors each | Throughput, CPU and memory per Session instance recorded |
| Large board | 2,000 cards, 10 editors | Client render < 16ms per update |
| Offline merge | Client offline 1h, 8h and 24h at realistic edit rates | Converged, nothing lost, reconnect time recorded |
| Owner crash | Kill the owner mid-load | Serving within 5s of lease expiry; every ACKed op in `board_ops` |
| Stale owner | Pause the owner past its lease, then resume | Zero commits from the stale owner; its clients recover |
| Reconnect storm | Restart a gateway holding 500 connections | No cascading failure; time until all reconnected recorded |

## 11. Decisions

Ten ADRs, each one page in `docs/adr/` (context, decision, alternatives, consequences).
Four are decided in this spec; the rest record choices made or measured during the build.

| ADR | Decision | State |
|---|---|---|
| 000 | Hand-roll the CRDTs; no Automerge or Yjs | Decided |
| 000b | Java 21 server, TypeScript client, conformance suite between them; TypeScript everywhere as fallback | Decided; confirm before Phase 1 |
| 001 | String fractional keys, ties broken by `(key, entity_id)`, interleaving accepted | Decided |
| 002 | One atomic placement register, not separate list and position fields | Decided |
| 003 | Delete wins over concurrent edits; restore via new tag; cards of a deleted list shown in Orphaned | Write during Phase 1 |
| 004 | Actor per board instead of stateless merge-on-read | Write during Phase 2 |
| 005 | ACK only after commit; group-commit window chosen from measurements | Write after the batching test |
| 006 | Redis Pub/Sub, not Kafka, for fan-out: low latency, no durability needed because Postgres and versions cover gaps (contrast with Reliant) | Write during Phase 2 |
| 007 | Fence in Postgres, not Redis; owners keep serving while Redis is down | Write during Phase 2 |
| 008 | Snapshot every 500 ops or 60s, keep three | Write after the recovery test |

### 11.1 Open before Phase 1 (resolved)

- ~~Confirm ADR-000b.~~ Confirmed: Java 21 and TypeScript, with the full §9.2 suite.
- ~~Pick the HLC encoding for JSON.~~ Three fields, §9.2.1. Canonical encoding §9.2.2–9.2.3.
- Write the first ten conformance vectors by hand before writing either implementation.

### 11.2 Open before Phase 2

- Check the 10,000-entry LRU against measured ops per board per minute.
- Decide where the public demo will run (a small VM, as with Reliant).

## Amendments log

Changes approved after v3 was issued. Each entry names the section it changes; the
section text itself is updated in the same commit.

| # | Date | Sections | Change |
|---|---|---|---|
| 1 | 2026-10-16 | §9.2.1–9.2.3, §11.1 | HLC JSON is three fields; canonical encoding is integer-only RFC 8785 with fixed escapes; canonical state shape defined; ADR-000b confirmed. |
| 2 | 2026-10-16 | §3.1, §3.4, §3.6, §3.7, §4.1, §4.2, §9.1, §9.2.4–9.2.5 | `checkClock` with a strict 60,000ms boundary; defensive LWW tie-break on unsigned canonical bytes, reported as a collision count; exact Orphaned rules with the nil-UUID reserved ID; Greenspan key format ("fractional part never ends in `0`"), `keyBetween` throws unless `a < b`, re-key rule for equal neighbours; op JSON encoding; `validateShape` rules (lowercase canonical UUIDs, no nil UUID, code-point lengths, NFC sorted labels ≤ 50 code points); view and vector formats. |
| 3 | 2026-10-16 | §3.7 | Upstream license is CC0-1.0, not MIT as first approved; attribution kept, notice corrected. Reversed bounds throw, unlike upstream. |
| 4 | 2026-10-16 | §3.2, §4.1, §4.2, §7.4, §9.2.3 | Tombstones are per entity, `{entity_id: [tags]}`, so a delete naming X only tombstones (X, tag) pairs (closes the cross-card delete found in Phase 1; vectors 051–054). Approved: `hlc.replica_id` must equal `replica_id`; lone surrogates rejected; state decode checks placement shape. NFC table mismatch documented as a known limitation. `INVALID_OP` moves the op to Unsent edits instead of dropping it. The server is the only parser of client JSON and rejects duplicate member names. |
| 5 | 2026-10-16 | §4.2, §5.1 | `validateShape` rejects U+0000 in every string (Postgres JSONB cannot store it), in both cores; vector 050 now uses U+0001 and vector 055 pins the rejection. Snapshot `sha256` is over the canonical re-encoding of the loaded state, since JSONB does not keep bytes. |
| 6 | 2026-10-16 | §2.2, §4.2, §6.1, §6.2 | (A) Claim order: atomic floor-and-INCR, lease, then `UPDATE boards SET fence … WHERE fence < :fence` before loading; commits require `fence = :fence`. (B) After a failed or timed-out commit the actor reconciles from `board_ops` under the board row lock before serving, answering `RETRY_LATER` and taking no snapshots meanwhile. (D) One writer per board actor over a shared pool; pool wait counts toward the 2s timeout. (E) Admission rules: ADD of an existing ID, references of the right kind, list fields and placement form; server-only, merge still accepts any order; dedup by `board_ops` before any rejection. |
| 7 | 2026-10-16 | §5.2, §9.4 | (F) The per-connection token bucket lives in the gateway's memory; the `rl:{connection}` Redis key is removed. |
| 8 | 2026-10-17 | §5.2, §6.2, §9.4 | Claim order confirmed: floor-and-INCR → `SET NX` lease → `UPDATE boards SET fence` → load → serve (already the §6.2 text). The `membership` invalidation channel added to §5.2. The internal gateway ↔ owner socket requires a shared-secret header (`CONCORD_INTERNAL_SECRET`). |
| 9 | 2026-10-17 | §2.2 | Every queue is bounded: actor mailbox (5,000; then `RETRY_LATER`), gateway → client unsent bytes (8 MB; then `RECONNECT_ELSEWHERE` and close), gateway requests per connection (256), owner → gateway unsent bytes (64 MB; then the socket is cut), Redis command queue (10,000). |
