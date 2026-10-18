
## 3. Requirements, point by point

> **All six functional requirements are met; of the nine non-functional targets, six are met, one is met with a stated caveat (offline, accelerated), and two miss (the large-board render tail, and latency from far away or at 1,000 connections).**

### Functional (SPEC §1.1)

| Requirement | How Concord meets it | Proof | Verdict |
|---|---|---|---|
| Create boards; invite members as owner, editor or viewer | `POST /api/boards` creates the board and makes the caller its owner in one transaction ([[api#private void createBoard(Context ctx)]]). Owners add members by email, change roles and remove them; a board always keeps one owner, checked under a row lock ([[api#private void changeMember(]]). Roles are checked on every op through a 30 s cache, cleared at once over the `membership` channel ([[membership#public String role(String board, String user)]]) | `ApiTest.boardsMembersAndRoles`; guard checks `members-owner-only`, `last-owner` | **Met** |
| Ordered lists of ordered cards | Fractional string keys, ties broken by entity ID (ADR-001); one atomic placement per card (ADR-002) | property tests in both cores; conformance vectors 005–046 | **Met** |
| Concurrent create, edit, reorder, move, delete of cards and lists | OR-Set existence with per-entity tombstones; delete wins over a concurrent edit; restore adds a fresh tag; a deleted list's cards appear in Orphaned (ADR-003, section 4) | 55 vectors; permutation fuzz, 500 seeds per commit and 100,000 nightly; differential fuzz TypeScript against Java | **Met** |
| Offline edits merge on reconnect, no conflict dialog | The queue survives reloads in IndexedDB and is resent in order on reconnect; merge is deterministic; a lost move is shown as "moved by {name}", never asked about | scripted offline hour (`docs/offline-hour-scripted.md`); `web/e2e/offline.spec.ts`; 8 h and 24 h **[accelerated]** | **Met** |
| History: who changed what, when, 30+ days | `GET /api/boards/{id}/history`, newest first, over `board_ops`, each op stored with its `user_id` ([[api#private void history(Context ctx)]]). Retention keeps 35 days and never deletes above the oldest kept snapshot ([[retention#static final int DAYS = 35;]]) | `ApiTest.boardsMembersAndRoles` (history is members-only, guard `history-members-only`); `RetentionTest` | **Met** |
| Presence: who is viewing, which card is open | Gateway-only, in a Redis sorted set per board, one entry per connection; a 10 s sweep removes entries older than 30 s and announces "left" ([[presence#void sweep(Iterable<String> boards)]]) | `PresenceTest`; Playwright two-tab test (`web/e2e/board.spec.ts`) | **Met** |

### Non-functional (SPEC §1.2)

| Target | Spec | Measured | Where | Verdict |
|---|---|---|---|---|
| Convergence | identical state on every replica given the same ops in any order | byte-identical canonical state in every vector, fuzz scenario, drill, load test and live check | [laptop] [CI] [host] | **Met** |
| Durability | no ACKed op ever lost | 0 ACKed ops missing in every drill (7), load test, soak (3 runs) and live deploy (3 under live editing: 2,315, 2,306 and 2,351 ops) | [laptop] [CI] [host] | **Met** |
| Sync latency | p95 < 200 ms, p99 < 500 ms at 2–10 editors | 2 editors p95 32 ms, p99 33 ms; 10 editors p95 45 ms, p99 94 ms | [laptop] | **Met** |
| …from a distant client | (same target) | India → Sydney: p95 315–354 ms; the server is about 17 ms of it | [host] | **Missed end to end; met by the server** |
| Editors per board | 30, correct; latency recorded | p95 42 ms at 30; 3,756 ops, all converged, 0 missing | [laptop] | **Met** |
| Cards per board | 2,000, render < 16 ms per update | p50 4.8 ms, p99 11.8–13.8 ms; max 19.9 and 26.3 ms, 2 of about 600 per run over 16 ms | [laptop] | **Missed** (about 0.3% of renders) |
| Offline window | 24 hours of local edits | 5,701 edits from 24 h synced in 283 s, 0 lost | [accelerated] | **Met, accelerated**: hours of real time on the browser and IndexedDB were not exercised |
| Owner crash recovery | serving within 5 s of lease expiry | 1,565 ms after expiry; a claim takes 29–342 ms even at 2,000 cards with a 499-op tail | [laptop] [CI] | **Met** |
| Concurrent connections | ~1,000 on one machine | 1,000 connections on 300 boards: 890 of 900 ops/s committed, p95 294 ms | [laptop] | **Met for connections; latency above 200 ms** |
| Fleet scale | 10,000 boards, ~50,000 connections | not measured: owners spread by rendezvous hashing, idle boards unload, nothing global on the write path | [design] | **Argued, not measured** (the SPEC marks it a design target) |

**The three misses, plainly.**
1. **Distance.** From India to a Sydney host, the network round trip is about 300 ms (TCP connect 298–312 ms), and an edit needs about one round trip, half to the server and half to the other client. The server's share is about 17 ms. A host near the users would meet the target with the same code (`deploy/prod/rehearsal-results.txt`, "On the real host").
2. **The large-board render tail.** One render in about 300 takes 16–26 ms, with no long task behind it. It is not profiled. The test was not loosened ([[perfspec#every update must render in under 16ms]]).
3. **1,000 connections.** Postgres was the busiest process, peaking at 6.8 cores for about 300 commits/s, one per board per second. Group commit merges ops within a board, never across boards (section 11).
