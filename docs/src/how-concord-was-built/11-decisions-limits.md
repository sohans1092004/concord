
## 13. Decisions

> **Ten ADRs record the design choices and thirty-two dated amendments record every change to the spec; most make a rule precise enough for two implementations to agree, and the rest close gaps a test, drill, measurement or the live host found.**

### 13.1 Architecture decision records (`docs/adr/`)

| ADR | Decision | Alternatives rejected | Why | Consequence | Changed the spec? |
|---|---|---|---|---|---|
| 000 | Hand-roll the CRDTs | Automerge, Yjs, a library for primitives | their defaults are not this product's rules (delete-wins, Orphaned); no Java implementation | correctness is on us: the test suite is the product | no (decided in v3) |
| 000b | Java 21 server, TypeScript client, conformance suite between them | TypeScript everywhere; GraalJS; one core compiled to both | the server's concurrency story fits the JVM; the browser needs TS | every rule stated precisely enough for two implementations | no |
| 001 | String fractional keys; ties by `(key, entity_id)`; interleaving accepted | floats, pure fractions, RGA, random jitter | no precision limit, short appends, deterministic | equal keys are common; interleaving anomaly accepted | no |
| 002 | One atomic placement register | separate list and position registers; a sequence CRDT per list | no mixed-winner placement | a losing move is undone visibly ("moved by") | no |
| 003 | Delete wins over an edit; restore by a new tag; Orphaned list | add-wins, two-phase set, cascade, hiding cards | implementable and loses no edit | tombstones grow for the board's life | made precise (per-entity tombstones, amendment 4) |
| 004 | One actor per board, which is also its writer | stateless merge-on-read; v3's per-instance writer for all boards; platform threads | no locks in merge; one board's failure stalls only itself | commits never merge across boards (§11 many boards) | **yes**: v3's per-instance writer replaced (amendment 6 D) |
| 005 | ACK only after commit; 5 ms group-commit window | ACK on receipt; 0 ms; 15 ms; adaptive | an ACK must mean stored; 5 ms measured best | each edit pays ≤ 5 ms + one commit | **yes**: 15 ms → 5 ms (amendment 32) |
| 006 | Redis Pub/Sub, not Kafka, for fan-out | Kafka, LISTEN/NOTIFY, owner-push | Postgres already holds every op with its version; a lost message is a repairable gap | best-effort delivery plus version-gap repair | no |
| 007 | Fence in Postgres; owners keep serving while Redis is down | fence check in Redis; v3's lazy fence; stop when Redis is down | correctness must not depend on Redis | one extra small transaction per claim | **yes**: claim order (amendments 6 A, 8, 13) |
| 008 | Snapshot every 500 ops or 60 s, keep three | more or less often; one snapshot | recovery is dominated by the lease, not replay | retention bounded by the oldest kept snapshot | no (made precise by amendment 28) |

### 13.2 Every amendment (`docs/SPEC.md`, "Amendments log")

"Changed" means the amendment changed what the original v3 text required; "precise" means it pinned down a rule v3 left loose; "added" means v3 said nothing.

| # | Decision | Why | Consequence | Kind |
|---|---|---|---|---|
| 1 | HLC JSON has three fields; canonical encoding is integer-only RFC 8785 | equal states must give equal bytes in both languages | one SHA-256 per state | precise |
| 2 | Strict 60,000 ms skew boundary; LWW tie-break on canonical bytes, counted; exact Orphaned rules; Greenspan key format; op JSON and validation rules | two implementations must agree on every edge | 55 vectors pin them | precise |
| 3 | The key algorithm's licence is CC0-1.0, not MIT; reversed bounds throw | the attribution was wrong; silent swaps hide caller bugs | notice corrected | precise |
| 4 | Tombstones per entity; `INVALID_OP` goes to Unsent, not dropped; only the server parses client JSON | a flat tombstone set let a delete for X remove Y | vectors 051–054 | **changed** |
| 5 | U+0000 rejected everywhere; snapshot hash over the canonical re-encoding | Postgres JSONB cannot store U+0000 and does not keep bytes | vector 055 | precise |
| 6 | (A) claim order: fence to Postgres before load; (B) reconcile after an unknown commit; (D) one writer per actor; (E) server admission rules | v3's order could lose an ACKed op; v3 had no rule for "no reply" | incidents 009, 010 | **changed** |
| 7 | The rate limit lives in gateway memory | a connection lives on one gateway and must work with Redis down | `rl:` key removed | changed |
| 8 | Claim order confirmed with the lease before the Postgres fence; `membership` channel; shared secret on the internal socket | a claimer losing the lease race must not fence out the live owner | `CONCORD_INTERNAL_SECRET` | precise |
| 9 | Every queue bounded (mailbox 5,000; client 8 MB; per-connection 256; owner→gateway 64 MB; Redis 10,000) | an unbounded queue is a memory leak waiting for a slow peer | slow consumers are cut | added |
| 10 | Phase 2 client and gateway behaviour approved (head-of-queue `INVALID_OP`, in-order resend, 10 s JOIN timeout, fresh replica after skew) | incidents 001, 011, 012 | the client's rules | added |
| 11 | A drill where only the Postgres fence can stop the stale owner; claim retries counted | the plain drill never reached the fence; retries hid bugs | incidents 003, 006 | added |
| 12 | A stale owner may serve stale reads, bounded by the 5 s cache and its next renewal | writes are fenced, reads are not | documented limit | precise |
| 13 | A renewal re-takes a missing lease only while `boards.fence` is its own | a stale owner could steal routing back after a wipe | incident 014 | **changed** |
| 14 | Session cookies and argon2id, no JWTs; single-use 30 s WebSocket tickets | as in Reliant; tokens in URLs leak to logs | JWT code deleted | **changed** |
| 15 | Broadcasts carry the sender's `user_id`; JOINED carries the member map | "moved by {name}" needs a name | protocol fields added | added |
| 16 | Presence specified (`active`/`idle`, 10 s, Redis only, sweep) | v3 named presence without a protocol | `PresenceTest` | precise |
| 17 | One replica per tab with Web Locks; duplicated tabs make a new ID; adoption under a lock | duplicated tabs share `sessionStorage` | §8.3 | added |
| 18 | 15 s silence drops a connection; browser `offline`/`online` events used | Chromium leaves a dead WebSocket open | incident 013 | added |
| 19 | REST endpoints; members and history rules; in-app bug reports | v3 did not list them | `Api.java` | precise |
| 20 | The laptop is the measuring machine; CI runs looser regression bounds | shared CI runners are not a benchmark | render test: < 16 ms laptop, p95 < 25 ms CI | precise |
| 21 | Append each edit durably before showing it; coalesced snapshots | §7.2's order was not enforced | edit-to-screen got faster | precise |
| 22 | Presence per connection | two tabs of one user conflicted | two-tab test | changed |
| 23 | X-Forwarded-For believed only from the trusted proxy | anyone could forge it to dodge per-IP limits | guard `trusted-proxy-only` | added |
| 24 | Trial deployment: Caddy, explicit memory limits, production refuses dev values | Phase 4a | §12 | added |
| 25 | Verified backups; account deletion | a backup you cannot check is a hope | `Backup`, `Accounts` | added |
| 26 | Idle boards unload after 10 minutes | the soak's heap grew with every board ever loaded | incident 016 | added |
| 27 | A board deleted while loaded is dropped | the live host's smoke test found it | incident 018 | added |
| 28 | Op retention bounded by the oldest kept snapshot | the newest would break the corrupt-snapshot fallback | `RetentionTest` | precise |
| 29 | Observability as built; `/metrics` not served publicly | §9.5 | incident 019 | precise |
| 30 | No per-gateway connection-rate limit | the storm drill showed no cascade without one; the spec gave no number | "revisit above 500 connections per gateway" | **changed** |
| 31 | The client paces its sends to the gateway's limit | a day's offline queue would take hours to sync | incident 021 | **changed** |
| 32 | The group-commit window is 5 ms | measured: as much batching as 15 ms, 11 ms faster | ADR-005 | **changed** |

## 14. Known limits and open gaps

> **The limits are real and stated: one laptop's numbers, one small VM in one distant region, a render tail on the largest board, and a Postgres bottleneck at many boards.**

**Known limits**
- **What the numbers mean.** Every laptop number comes from one machine running the services, Postgres, Redis and the load generator together, with no network between them. Treat them as where the bottlenecks sit, not as capacity.
- **One region, far away.** From India the round trip to Sydney is about 300 ms, so the 200 ms target is missed end to end while the server takes about 17 ms.
- **Many boards.** One commit per board per batch; at 300 active boards, about 300 commits/s and p95 294 ms **[laptop]**. Commits merged across boards would help; ADR-004 rejected that for isolation.
- **The large-board render tail.** About 1 render in 300 at 2,000 cards takes 16–26 ms; not profiled.
- **Accelerated offline runs.** 8 h and 24 h were compressed with a clock offset; only the 1 h run is real time in a real browser.
- **One small VM.** No replica, no Postgres failover, nightly backups. A lost VM loses up to a day of edits. Point-in-time recovery would tighten that.
- **Fleet scale** (10,000 boards, 50,000 connections) is argued from the design, not measured.
- **Security scope.** No SSO, no two-factor sign-in, invite-only sign-up (SPEC §1.3). Server code coverage is not measured.

**Open gaps**
- **Profile the large board's slow renders,** and fix them or argue a looser bound with evidence. The test stays strict until then.
- **Record a restore of a live backup** after the `restore.sh` fix (section 12.3).
- **The manual offline hour** (`docs/manual-offline-run.md`) is for the project owner to do by hand.
- **Trial bug reports:** none received so far. Each one that reveals a bug gets a postmortem.

**Reference check.** `python docs/tools/refs.py check` verifies that every code link in this document and in the interview guide resolves at the current commit and still points at its anchor text, that every cited commit exists, and that every cited test method exists. Result at the commit that adds these documents: **288 file references (293 line numbers), 12 commit SHAs and 29 test names, all resolve.** Rebuild with `python docs/tools/refs.py build`, then `node docs/tools/render.mjs` for the diagrams and PDFs.
