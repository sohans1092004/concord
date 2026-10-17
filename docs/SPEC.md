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

REST endpoints (Phase 3; `server/gateway/.../Api.java`). Any member may read; only owners
manage members; a board always keeps at least one owner (changing the last owner's role or
removing them is refused, under a row lock); every membership change publishes on
`membership` (§5.2). A non-member gets 404 for a board, never 403, so a board's existence is
not revealed. The built web app is served by the gateway (`CONCORD_WEB_DIR`), same origin as
the API.

| Call | Does |
|---|---|
| `POST /api/auth/sign-up`, `sign-in`, `sign-out`, `password`; `GET /api/me` | §9.4 |
| `POST /api/ws-ticket` | A single-use WebSocket ticket, valid 30s (§9.4) |
| `GET /api/boards`, `POST /api/boards` | My boards with my role; create a board (its creator is its owner) |
| `GET/POST /api/boards/{id}/members`, `PATCH/DELETE …/members/{user}` | List; add an existing user by email with a role; change a role; remove (an owner, or a member leaving) |
| `GET /api/boards/{id}/history?before_version=&limit=` | `board_ops` of the last 30 days, newest first, 50 per page (200 at most), with the sender's name; the owner's rebalancing ops have no user |
| `POST /api/bug-reports` | The trial's in-app report: description and the diagnostics the tester left ticked, redacted (§9.4) |
| `DELETE /api/me` | With the password: deletes the account, the boards only that user belongs to and their bug reports; on shared boards they leave (the last owner's role passes to the longest-standing member) and their past ops stay, unnamed (amendment 25) |

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

### 2.5 Unloading idle boards

A board's state stays in its owner's memory while it is loaded, and grows with the board's
history (tombstones are kept, decision 9; the last 10,000 ACKs, §4.3). So an owner unloads a
board nobody has open (amendment 26):

- **Idle means both:** no request (submit, join, sync, poll, ensure) has reached the board's
  actor for `CONCORD_IDLE_UNLOAD_MS` (10 minutes), and `PUBSUB NUMSUB board:{id}` is 0. A
  gateway is subscribed while it has a client on the board. A gateway polling the owner because
  its Pub/Sub is down sends a request every second, so it keeps the board too.
- **Unloading is the graceful handover:** snapshot if anything changed since the last one,
  release the lease, close. The next request claims the board and loads it from that
  snapshot. A resend of an op ACKed before the unload gets its original ACK from the primary
  key on `board_ops` (§4.3 step 2).
- **Never:**
  - while reconciling an unknown commit outcome;
  - with ops in the mailbox or the group-commit batch;
  - when Redis cannot say how many subscribers there are.
- Checked at most every 30s once the idle time has passed. A request racing the close is
  answered `NOT_OWNER`, as in any handover, and is resent.

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
    email TEXT UNIQUE NOT NULL,          -- stored lowercased
    name TEXT NOT NULL,                  -- 1-80 code points, shown to other members
    password_hash TEXT NOT NULL,         -- argon2id, PHC string format
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sign-in sessions (SPEC §9.4): only the SHA-256 of the cookie's token is stored, so a leaked
-- copy of this table cannot be replayed as a sign-in.
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user ON sessions (user_id);

-- Single-use WebSocket tickets, valid 30s (SPEC §9.4). Using one deletes it.
CREATE TABLE ws_tickets (
    token_hash TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
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

-- Bug reports from the in-app form (Phase 3 trial). Diagnostics are what the tester chose to
-- attach, already passed through the token redactor.
CREATE TABLE bug_reports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    board_id UUID REFERENCES boards(id),
    description TEXT NOT NULL,
    diagnostics JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
| `presence:{id}` (sorted set) | One member per connection: `user:connection:card:state` (`-` for no card; `state` is `active` or `idle`), score = last seen. Written only by gateways, never Postgres (§7.5) | Swept every 10s by each gateway serving the board, entries older than 30s removed |

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
   wiped), it reads `boards.fence`: only if that still equals its own fence does it re-take
   the lease with the same value (`SET NX`, then the owner key); otherwise it drops the
   board. Without this check a stale owner could re-take the lease after a wipe, and the
   real owner's next renewal would find a foreign lease and drop the board. If another
   instance holds the lease, the owner drops the board.

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
- A stale owner that never writes is never fenced: only a commit checks the fence. Until it
  drops the board it may answer `JOIN`, `SYNC_REQUEST` and gateway polls from its own memory.
  Memory changes only after commit (§6.1 step 4), so what it serves is a prefix of the
  committed history: the client is behind, never wrong, and catches up on the next write or
  the new owner's `BOARD_VERSION` (§6.4). The window is bounded by the gateway's 5s owner
  cache: once a gateway's entry expires it re-reads `board:owner:{id}` (§2.3 step 4), so it
  stops routing to the stale owner within 5s of the new owner's announce (§6.2 step 6). If
  the stale owner can reach Redis, its next renewal (within 5s) also finds the other lease
  and drops the board (`StaleReadTest`, `RouterCacheTest`). The bound assumes the gateway
  can reach Redis: without it, a gateway keeps its last known owner (amendment 7).

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
- A tab keeps its replica ID in `sessionStorage`, so a reload resumes the same replica, and
  holds the Web Lock `concord:replica:{board}:{replica}` for as long as it lives. A duplicated
  tab inherits a copy of `sessionStorage`, so on start a tab takes the lock for its stored ID
  and, if another tab holds it, makes a new replica ID. Each replica's state is one IndexedDB
  record. Adopting a closed tab's record takes that record's lock first; its pending ops and
  Unsent edits go to the front of the adopting tab's queue, keeping their `op_id`, HLC and
  replica ID; writing the merged record and deleting the adopted one is one IndexedDB
  transaction. The app shell is cached by a service worker, so a reload while offline opens
  the board from IndexedDB.

### 7.2 Edit

1. Make the op: new `op_id`, `hlc.tick()`.
2. Append it to the pending queue in IndexedDB.
3. Update the view.
4. If connected, send it. If not, it waits.

Steps 2–4 run in this order for every edit, one edit at a time (amendment 21). Step 2 appends
only that op, to an append-only log store; it is shown and sent only once the append has
committed. Until then the op is staged: later edits are computed against it (keys, observed
tags) but it is not rendered. Everything else (the confirmed replica, ACKs, Unsent edits) is
saved as a coalesced snapshot at most about once a second and on `pagehide`; a snapshot
records the last log entry it includes and removes those entries in the same transaction. On
load, a replica is its snapshot plus the log entries after it, and the HLC is moved past every
logged op of this replica, so a tick is never reused. A tab that crashes the moment an edit is
on screen loses nothing (`offline.spec.ts`, guard check `append-before-view`).

### 7.3 Reconnect

1. Reconnect with backoff: 0.5s × 2, capped at 30s, full jitter. A joined connection that
   receives nothing for 15s is treated as lost and reconnected (the owner publishes
   `BOARD_VERSION` every 5s, §6.4): a dropped network often leaves a WebSocket open with no
   close event. In a browser the client also drops the socket on the `offline` event and
   reconnects at once, without waiting for backoff, on `online`.
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
| Server → client | `JOINED` | `ops` or `snapshot`, `version`, `server_time_ms`, `role`, `members` (`user_id` → `{name, role}`) |
| Server → client | `OP_ACK` | `op_id`, `version` |
| Server → client | `OP_BROADCAST` | `op`, `version`, `user_id` |
| Server → client | `BOARD_VERSION` | `version` |
| Server → client | `SYNC` | `ops` or `snapshot`, `version` |

Presence is handled by the gateway alone, in Redis: `PRESENCE` is never forwarded to the
owner and never stored in Postgres. A client sends `PRESENCE` after `JOINED`, whenever the card
it has open or its activity changes, and every 10s; `state` is `active`, or `idle` after 60s
without input. The gateway accepts it only for the connection's joined board, at most 5/s,
writes `presence:{id}` (removing that connection's previous member) and publishes
`PRESENCE_UPDATE` on `board:{id}`. After `JOINED` it sends the joining client the current set.
On disconnect, and when its 10s sweep removes entries older than 30s, it publishes
`state: "left"` for that one connection (amendment 22). A client keeps presence per
connection and shows a user while any of their connections is present. Several gateways may
sweep the same board, so a client treats `left` idempotently. If Redis is down, presence is
absent.

Each entry of `ops` in `JOINED` and `SYNC` is `{op, version, user_id}`: `user_id` is the user
who sent the op (`board_ops.user_id`; the nil UUID for the owner's own rebalancing ops), so a
client can name who made a change, for example "moved by {name}" (§3.5).
| Server → client | `PRESENCE_UPDATE` | `user_id`, `connection_id`, `card_id`, `state` (`active`, `idle` or `left`) |
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
| 3 | Two owners at once (Redis failover, or an owner paused past its lease) | The stale owner's commit updates zero rows; it drops the board and answers `NOT_OWNER`. It never broadcast anything uncommitted | Yes (pause an owner with `docker pause`, as in Reliant's incident 004; and a variant where the paused owner also cannot reach Redis, so only the Postgres fence stops it) |
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

- Sign-in, as in Reliant: no JWTs. Signing in or up creates a session: 32 random bytes sent
  as the `concord_session` cookie (`HttpOnly; SameSite=Strict; Secure; Path=/`, 30 days),
  of which `sessions` stores only the SHA-256. Scripts (tests, drills) send the same token as
  `Authorization: Bearer`. Changing the password deletes every other session of that user.
  Sign-in is limited per email (5 attempts, then 1 a minute) and per IP (30, then 1 every
  10s) in each gateway's memory. The IP is the request's socket address, except that a request
  whose socket address is the configured proxy (`CONCORD_TRUSTED_PROXY`, Caddy) is taken to be
  from the last `X-Forwarded-For` entry, which Caddy sets to the connecting client; the header
  from anyone else is ignored (amendment 23). The gateway adds the Content-Security-Policy; the
  proxy adds HSTS and the other security headers, and an unknown email is checked against a dummy hash so
  timing does not reveal it. Sign-up can require an invite code (`CONCORD_INVITE_CODE`; off
  when empty).
- Every state-changing REST call must be JSON and its `Origin`, when present, must be on the
  allowlist; a request carrying the session cookie with no `Origin` is refused. Board role
  is checked per op (§4.2), so removal takes effect on the next op, not the next connect.
- The WebSocket URL carries a single-use ticket, `?ticket=…` (browsers cannot set headers on
  a WebSocket): `POST /api/ws-ticket` returns 32 random bytes, `ws_tickets` stores their
  SHA-256 with a 30s expiry, and the gateway uses it with
  `DELETE … WHERE token_hash = ? AND expires_at > now() RETURNING user_id`, so it opens one
  connection, once, on any gateway, and a leaked URL is worthless. Tickets live in Postgres,
  not Redis, so connecting keeps working while Redis is down or after a wipe (§5). Each
  gateway deletes expired tickets and sessions every minute.
- No session token or ticket may appear in any log: Concord's code logs `user_id`, never a
  secret, and every log line is written by an encoder that redacts `ticket=`,
  `access_token=` and `concord_session=` values and `Bearer` credentials, which catches
  libraries that log request lines or headers at DEBUG (`GatewayLogTest`; client-core's log
  events are tested the same way). Bug-report text and diagnostics pass through the same
  redactor before they are stored.
- The WebSocket upgrade checks `Origin` against an allowlist, preventing cross-site
  WebSocket hijacking.
- The internal gateway ↔ owner WebSocket (§2.3 step 4) requires a shared secret, sent by the
  gateway in the `X-Concord-Internal` header on upgrade and compared in constant time.
  Outside dev it must be at least 32 bytes and not the dev value.
- Rate limit per connection: 20 ops/s, burst 50, as a token bucket in the gateway's memory
  (a connection lives on one gateway, and limiting must keep working while Redis is down).
  Frames over 64 KB are rejected.
- Passwords hashed with argon2id (19 MiB, 2 passes, 1 lane; PHC string format).

### 9.5 Observability

| Kind | What |
|---|---|
| Metrics | Sync latency (client-reported op created → seen elsewhere), commit latency and group-commit batch size, ops/s per board, duplicates, rejections by code, LWW ties actually decided, ownership claims and fence rejections, version gaps detected, connections per gateway, snapshot size and duration, recovery time |
| Logs | JSON lines with `board_id`, `op_id`, `user_id` where known: connections, rejections, claims, fence rejections, snapshot failures |
| Traces | OpenTelemetry: client send → gateway → owner → commit → broadcast → peer render |
| Alerts | Sync p95 > 200ms for 5 min; commit failures; any fence rejection; claim churn above normal; version-gap rate above normal; reconnect storm |

### 9.6 Trial deployment (Phase 4a)

One VM (`deploy/prod/compose.yaml`; operations in `docs/runbook.md`):

- **Services:** Caddy (the only published ports, 80 and 443, with a Let's Encrypt
  certificate), one gateway that also serves the web app, two Session instances so a board's
  owner can fail over, Postgres with a named volume, Redis without persistence, and a backup job.
- **Memory:** every JVM has an explicit heap and every container a memory limit.
- **Production refuses every dev value:**
  - the dev internal secret, or a short or dev database password;
  - a public origin that is not `https`;
  - an Origin allowlist other than exactly that origin;
  - a session cookie without `Secure`;
  - no invite code, or a per-IP sign-in limit above 30;
  - no trusted proxy.
- **`/healthz`** on every service. The gateway's also fails when Postgres does not answer or
  less than 10% of the disk is free, so an external uptime monitor on the public URL reports both.
- **Backups:** nightly. `pg_dump` and a manifest are taken from one exported Postgres snapshot,
  encrypted with `age` to a key that is not on the VM, and stored off-machine with `rclone`,
  14 days kept.
  - The manifest holds every table's row count and every board's version and state hash (a
    full replay of its ops).
  - A restore counts only once verified: row counts equal, and every board's state, loaded both
    as a new owner loads it (snapshot plus later ops) and by full replay, hashes to the
    manifest's value (`dev.concord.session.Backup`, `deploy/prod/restore.sh`).

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

All on one machine, reported like Reliant's: where the bottlenecks sit, not capacity. The
measuring machine is the development laptop; targets are judged there. CI's shared runners
(which also host the whole stack and the load generators) run the same tests as regression
guards with looser bounds and record their numbers: for "Large board", every update under
16ms on the laptop, p95 under 25ms in CI (`web/e2e/perf.spec.ts`).

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
| 10 | 2026-10-17 | §9.4 | The Phase 2 client choices (head-of-queue `INVALID_OP`, in-order queue resend, 10s JOIN timeout, fresh replica after a clock correction) and gateway choices (one board per connection, `access_token` query parameter, Origin optional but token required, `RETRY_LATER` when rate limited, 1s claim watchdog) approved. Access tokens never appear in logs (redacting encoder, tests); Phase 3 replaces the URL token with a single-use 30s ticket from REST. |
| 11 | 2026-10-17 | §6.2, §8 | Drill for row 3 with the stale owner cut off from Redis, so its lease renewal cannot save it and the Postgres fence must reject its commit (it must log the rejection and commit nothing). A claim whose drawn fence is not above `boards.fence` is counted (`claim_fence_retries`); the wipe-redis drill requires the rebuilt counter to give `boards.fence + 1` with no retry. |
| 12 | 2026-10-17 | §6.3 | A stale owner that never writes is never fenced and may answer `JOIN`/`SYNC_REQUEST` from stale memory (a prefix of the committed history); the window is bounded by the gateway's 5s owner cache, and by the stale owner's next renewal when it can reach Redis. |
| 13 | 2026-10-17 | §6.2 | A renewal that finds the lease gone re-takes it only if `boards.fence` still equals its fence; otherwise it drops the board (`FencingTest.staleOwnerDoesNotRetakeAWipedLease`, guard check `retake-fence-check`). |
| 14 | 2026-10-17 | §5.1, §9.4 | Sign-in as in Reliant, no JWTs: hashed 30-day session cookie (or Bearer for scripts), argon2id, per-email and per-IP sign-in limits, optional invite code; changing the password revokes other sessions; Origin checked on every state-changing REST call. The WebSocket URL carries a single-use 30s ticket stored hashed in Postgres; expired tickets and sessions are deleted every minute. `users.name`, `sessions`, `ws_tickets` and `bug_reports` added to §5.1. The JWT code, config and secret are deleted. |
| 15 | 2026-10-17 | §7.5 | `OP_BROADCAST` and each `ops` entry of `JOINED` and `SYNC` carry the sender's `user_id`; `JOINED` carries the board's member map, `user_id` → `{name, role}`. |
| 16 | 2026-10-17 | §5.2, §7.5 | Presence specified: `state` is `active` or `idle`; client sends on join, on change and every 10s; gateway-only, in Redis, never Postgres; snapshot after `JOINED`; `left` on disconnect and from each gateway's 10s sweep, idempotent on the client (`PresenceTest`, guard checks `presence-sweep`, `presence-joined-board`). |
| 17 | 2026-10-17 | §7.1 | Tab replicas: replica ID in `sessionStorage`, held by a Web Lock; a duplicated tab whose stored ID is locked makes a new one (`offline.spec.ts`, guard check `duplicate-tab-lock`). Adoption takes the record's lock first and is one IndexedDB transaction (guard check `adoption-lock`). A service worker caches the app shell for offline reloads. |
| 18 | 2026-10-17 | §7.3 | Liveness: 15s with no message drops a joined connection and reconnects; the browser's `offline` event drops it at once and `online` reconnects without backoff. Found by the Phase 3 offline test: Chromium's offline mode, like a dropped network, leaves the WebSocket open (guard checks `silence-drop`, `offline-event`). |
| 19 | 2026-10-17 | §2.1 | REST endpoints listed; approved defaults: members readable by any member, managed by owners, at least one owner kept, 404 for non-members; history 30 days, paged by version; in-app bug reports. The web app remembers who is signed in (no secret) in `localStorage`, so a new tab opens offline too. |
| 20 | 2026-10-17 | §10.1 | The development laptop is the measuring machine; targets are judged there. CI runs the same tests as regression guards with looser bounds and records the numbers: the 2,000-card render test requires every update under 16ms locally and p95 under 25ms in CI. |
| 21 | 2026-10-17 | §7.1, §7.2 | §7.2's order is enforced: each edit's op is appended to an append-only IndexedDB log and committed before it is shown or sent; snapshots are coalesced (about 1s, and on `pagehide`) and replace the log entries they include; load merges snapshot and log and moves the HLC past logged ops. Edit-to-screen p50 4.1–4.3ms after, 4.9–5.0ms before (`web/e2e/latency.spec.ts`). |
| 22 | 2026-10-17 | §5.2, §7.5 | Presence per connection: member `user:connection:card:state`, `PRESENCE_UPDATE` carries `connection_id`, `left` removes one connection; a user is shown while any of their connections is present (`board.spec.ts` two-tab test, guard check `presence-per-connection`). |
| 23 | 2026-10-17 | §9.4 | Revised in Phase 4a: the client IP for per-IP limits is the last `X-Forwarded-For` entry only when the request's socket address is the trusted proxy (`CONCORD_TRUSTED_PROXY`, Caddy, re-resolved every 30s); any other source uses its socket address (`ApiTest.forwardedForIsBelievedOnlyFromTheTrustedProxy`, guard check `trusted-proxy-only`). |
| 24 | 2026-10-17 | §9.6 (new), §9.4 | Trial deployment: Caddy with Let's Encrypt as the only open ports; 1 gateway, 2 Session instances, Postgres, Redis, backups; explicit heaps and memory limits; production refuses every dev value (`Config.checkPublic`); the gateway sets the CSP and `/healthz` checks Postgres and free disk. The server image uses the JDK (jcmd for memory checks). |
| 25 | 2026-10-17 | §2.1, §9.6 | Verified backups (`Backup dump`/`verify`, one exported snapshot for dump and manifest; guard check `backup-verify-replay`). Account deletion, `DELETE /api/me` and `Accounts delete <email>` (guard check `delete-user-heir`). |
| 26 | 2026-10-17 | §2.5 (new) | An owner unloads a board after 10 minutes with no request and no gateway subscribed to its channel, through the graceful handover path (snapshot, release the lease, close); never while reconciling, with ops in the mailbox or batch, or when Redis cannot count subscribers. Found by the Phase 4a soak: without it, a Session instance's heap grew with the history of every board it had loaded (`IdleUnloadTest`; guard checks `idle-unload-not-reconciling`, `idle-unload-subscribers`). |
