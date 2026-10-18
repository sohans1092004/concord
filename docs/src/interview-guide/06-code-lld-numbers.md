
## 7. Code tour

> **Fifteen files, read in this order, cover the whole system; know the key function in each well enough to explain it line by line.**

| # | File | What it holds | Key functions |
|---|---|---|---|
| 1 | `crdt-ts/src/hlc.ts` | the clock | `tick` [[hlc#export function tick(]], `receive` [[hlc#export function receive(]], `compare` [[hlc#export function compare(]] |
| 2 | `crdt-ts/src/orset.ts` | existence | `remove` [[orset#remove(id: string, observed: Iterable<string>)]], `liveTags` [[orset#liveTags(id: string): string[]]] |
| 3 | `crdt-ts/src/lww.ts` | attributes and the tie-break | `pick` [[lww#export function pick(a: Register, b: Register)]] |
| 4 | `crdt-ts/src/keys.ts` | order | `keyBetween` [[keys#export function keyBetween(]], `planInsert` [[keys#export function planInsert(]] |
| 5 | `crdt-ts/src/board.ts` | composition, view, Orphaned | `apply` [[board#apply(op: Op): number]], `view` [[board#view(): View]] |
| 6 | `crdt-ts/src/op.ts` | validation and the skew guard | `checkShape` [[op#export function checkShape(x: unknown)]], `checkClock` [[op#export function checkClock(]] |
| 7 | `server/.../session/BoardActor.java` | the owner: admit, commit, apply, reconcile, unload | `admit` [[actor#private void admit(Submit s)]], `flush` [[actor#private void flush()]], `applyCommitted` [[actor#private void applyCommitted]], `reconcile` [[actor#private void reconcile() throws]] |
| 8 | `server/.../session/Store.java` | every SQL statement of the write path | `commit` [[store#public Committed commit(String board, long fence, List<Pending> ops, long deadlineMs)]], `raiseFence` [[store#public boolean raiseFence]], `load` [[store#public Loaded load(String board)]] |
| 9 | `server/.../session/Ownership.java` | claim, renew, release | `claim` [[ownership#public long claim(String board)]], `renew` [[ownership#public long renew(String board, long fence)]] |
| 10 | `server/.../session/Boards.java` | actors per instance, lease renewal | `dispatch` [[boards#public void dispatch(String board, BoardActor.Item item)]], `renewAll` [[boards#private void renewAll()]] |
| 11 | `server/.../gateway/ClientConn.java` | one client socket, in order | `onMessage` [[clientconn#void onMessage(String text)]], `drain` [[clientconn#private void drain()]] |
| 12 | `server/.../gateway/Router.java`, `Fanout.java` | owner lookup, delivery, watchdog | `owner` [[router#String owner(String board)]], `tick` [[fanout#private void tick()]] |
| 13 | `server/.../gateway/Api.java` | REST, auth, tickets | `sameOrigin` [[api#private void sameOrigin(Context ctx)]], `consumeTicket` [[api#static String consumeTicket]] |
| 14 | `client-core/src/client.ts` | the sync client | `submit` [[client#submit(body: Body): Op]], `pump` [[client#private pump()]], `error` [[client#private error(m: Msg)]], `confirmedOp` [[client#private confirmedOp(op: Op, version: number, broadcast: boolean)]] |
| 15 | `client-core/src/idb.ts` | tab replicas, log, adoption | `open` [[idb#static async open(boardId: string]], `load` [[idb#async load(): Promise<Saved | undefined>]] |

Read `docs/walkthrough-phase2.md` and `docs/walkthrough-phase3.md` alongside: each follows one edit through the code.

## 8. Low-level design and DSA angles

> **The design maps onto standard LLD and DSA questions; name the pattern, then show the Concord code.**

### 8.1 Low-level design

| Typical question | Concord's answer | Code |
|---|---|---|
| Design a component that serialises work per key with no locks | **Actor**: a mailbox per board drained by one virtual thread; others only `offer` | [[actor#public Offered offer(Item item)]] |
| Model an object with a lifecycle | **State machine**: CLAIMING → SERVING ⇄ RECONCILING → CLOSED, every mailbox item handled per state | [[actor#private void serveOnce()]], [[actor#private void whileReconciling(Item item)]] |
| Make storage swappable for tests | **Strategy / interface**: `Storage` with `MemoryStorage` and `IndexedDbStorage`, sharing one `merge` | [[storage#export interface Storage {]] |
| Handle errors from a remote service | **Classified responses**: each code maps to retry, re-stamp, defer or surface; nothing silently dropped | [[client#private error(m: Msg)]] |
| Bound memory for a slow consumer | **Bounded buffer with backpressure**: count bytes; over the limit, tell it to go and close | [[outbox#public boolean send(String text)]] |
| Find a resource with a cache and fallbacks | **Cache-aside with retry**: 5 s cache → Redis → hash; invalidate on `NOT_OWNER` | [[router#private CompletableFuture<Map<String, Object>> attempt(]] |
| Test failure handling | **Seams**: `Store.beforeCommit()` lets a test pause or fail a commit before `COMMIT` | [[store#protected void beforeCommit() throws SQLException {}]] |

### 8.2 DSA

| Topic | Where | The idea, in one line |
|---|---|---|
| **String midpoint / fractional indexing** | [[keys#export function keyBetween(]] | base-62 digits; strip the common prefix, then take the middle digit; carry into the integer part |
| **LRU cache** | [[actor#protected boolean removeEldestEntry(Map.Entry<String, Long> e)]] | `LinkedHashMap` in access order with `removeEldestEntry`: O(1) get and evict |
| **Rendezvous (highest-random-weight) hashing** | [[rendezvous#public static List<Instance> order(]] | each node's weight = SHA-256(board ‖ node); the highest wins; removing a node moves only its keys |
| **Token bucket** | [[bucket#synchronized boolean take(long nowMs)]] | refill `rate × elapsed` up to the burst; take 1 or refuse. The same shape on the client ([[client#private pump()]]) |
| **Logical clocks** | [[hlc#export function receive(]] | Lamport's max-plus-one, keeping the physical part |
| **Join-semilattice** | [[orset#mergeFrom(other: OrSet): void]] | merge is set union, a least upper bound: commutative, associative, idempotent |
| **Delta debugging** | [[sim#export function minimize(]] | shrink a failing op set by removing chunks while it still fails |
| **Exponential backoff with full jitter** | [[client#export function backoffMs(]] | `random × min(30 s, 0.5 s × 2^n)`, so retries spread out |
| **Gap detection** | [[client#private confirmedOp(op: Op, version: number, broadcast: boolean)]] | hold out-of-order versions in a map; drain while `version + 1` is present |

## 9. Numbers cheat sheet

> **The numbers worth remembering, each with where it was measured; never quote a laptop number as user-facing.**

| Number | Meaning | Tag | Source |
|---|---|---|---|
| 32 ms | sync p95, 2 editors | [laptop] | `production/load-tests/README.md` §1 |
| 42 ms | sync p95, 30 editors | [laptop] | §2 |
| 315–354 ms | sync p95, India → Sydney | [host] | `deploy/prod/rehearsal-results.txt` |
| ≈17 ms | the server's share of that | [host] | same, "On the real host" |
| 294 ms | sync p95, 1,000 connections on 300 boards | [laptop] | §4 |
| 890 / 900 | ops/s committed / offered at 1,000 connections | [laptop] | §4 |
| 6.8 cores | Postgres peak at 1,000 connections | [laptop] | §4 |
| 5 ms | group-commit window (was 15) | [laptop] | ADR-005, amendment 32 |
| 2.55 | ops per commit at 5 ms, 30 editors | [laptop] | §3 |
| 1,565 ms | owner crash: serving after lease expiry (target ≤ 5 s) | [laptop] | `deploy/drills/results.txt` |
| 29–342 ms | claim time, up to 2,000 cards and a 499-op tail | [laptop] | §9 |
| 15 s / 5 s | lease length / renewal interval | code | [[rediskeys#public static final long LEASE_MS = 15_000;]] |
| 60 s | clock-skew limit | code | [[op#export const MAX_SKEW_MS = 60_000;]] |
| 10,000 | dedup LRU size | code | [[actor#static final int LRU_SIZE = 10_000;]] |
| 5,000 | actor mailbox bound | code | [[actor#static final int MAILBOX_LIMIT]] |
| 20 /s, 50 | per-connection rate limit, burst | code | [[clientconn#static final double OPS_PER_SECOND = 20, BURST = 50;]] |
| 2 s | commit budget | code | [[actor#static final long COMMIT_TIMEOUT_MS]] |
| 500 ops / 60 s, keep 3 | snapshot policy | code | [[actor#static final int SNAPSHOT_EVERY_OPS]] |
| 35 days | op retention (history shows 30) | code | [[retention#static final int DAYS = 35;]] |
| 15 s / 10 s | client silence drop / JOIN timeout | code | [[client#export const SILENCE_MS = 15_000;]] |
| 3.5–16.2 s | 500 clients back after their gateway died (CI 12.4 s) | [laptop] [CI] | `deploy/drills/results.txt` |
| 92 s / 283 s | 8 h / 24 h offline queues synced | [accelerated] | §6 |
| 11.5 s | real 1-hour offline: reconnect to converged | [laptop] | `docs/offline-hour-scripted.md` |
| 56.6 → 4.7 ms | large-board render p50, before → after the fixes | [laptop] | `docs/phases.md:241-242` |
| 19.9 / 26.3 ms | large-board render max on today's reruns (target < 16) | [laptop] | §5 |
| 55, 109 + 109, 61, 35, 7, 7, 19, 42 | vectors; TS + Java core tests; server; client unit; e2e; drills; Playwright; guard checks | count | section 10 of the main document |
| 94.62% / 87.37% | branch coverage, TS core / Java core | [laptop] | main document §10.3 |
| 1,336 MiB | sum of memory limits on the 2 GB host | config | [[prodcompose#= 1,336 MiB]] |
| 0 | ACKed ops lost, in every drill, load test, soak and live deploy | all | everywhere |
