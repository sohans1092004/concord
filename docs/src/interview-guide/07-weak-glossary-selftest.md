
## 10. Weak spots, and honest answers

> **Name the weakness before the interviewer does, give the number, say why it happens, and say what you would change.**

| Weak spot | What to say |
|---|---|
| **Sydney latency** | "From India the p95 is 315–354 ms, against a 200 ms target. The server takes about 17 ms; the round trip to Sydney is about 300 ms. The fix is a region near the users. I measured it rather than assume it." |
| **Postgres at 1,000 connections** | "At 300 busy boards Postgres hit 6.8 cores and p95 went to 294 ms, because each board commits alone. Group commit helps within a board, not across boards. Next step: batch commits per instance across boards, accepting less isolation, or shard Postgres by board." |
| **The large-board render tail** | "About 1 render in 300 at 2,000 cards takes 16–26 ms; p99 is under 14 ms. I fixed the big causes (56.6 → 4.7 ms p50) but haven't profiled this tail. I kept the test strict rather than loosen it." |
| **Accelerated offline runs** | "The 8 and 24 h runs compress time with a clock offset; they prove the merge and the resend, not hours of a real browser and IndexedDB. The 1 h run is real time in real browsers." |
| **Laptop-only measurements** | "One laptop ran the services, databases and load generator together. Those numbers show where bottlenecks sit, not capacity. Only latency and deploys were measured on the real host." |
| **One region, one VM** | "No replica and no Postgres failover; nightly backups mean a lost VM loses up to a day. For a trial that's a deliberate trade; next would be point-in-time recovery and a standby." |
| **No SSO, no 2FA** | "Out of scope by the spec (§1.3); invite-only sign-up, argon2id, hashed sessions and rate limits are what's there." |
| **AI wrote the code** | "Yes. I owned the spec, the decisions and the evidence. Here's a decision I'd defend line by line: the claim order (section 3, Q18)." |
| **Server coverage unmeasured** | "True. I rely on 61 integration tests on real infrastructure and 42 guard checks that prove the tests catch their bugs." |
| **Small user base** | "The trial has had no bug reports so far; the live host has been exercised by deploy checks and smoke tests, not by real load." |

## 11. Glossary

| Term | Meaning | In Concord |
|---|---|---|
| ACK | the server's promise that an op is stored | `OP_ACK {op_id, version}`, after `COMMIT` |
| Actor | an object with a private mailbox processed by one thread | `BoardActor`, one per board |
| Backoff, full jitter | wait a random time up to a growing cap before retrying | `random × min(30 s, 0.5 s × 2^n)` |
| Canonical encoding | one byte string per state | RFC 8785 JSON, integers only |
| Claim | taking ownership of a board | fence, lease, Postgres fence, load, announce |
| Convergence | replicas with the same ops end identical | the fuzzers' assertion |
| CRDT | a data type whose replicas merge without coordination | two OR-Sets plus an LWW map |
| CSP | Content-Security-Policy: what a page may load | same-origin scripts, `wss://` to the host |
| Delta debugging | shrinking a failing input by removing parts | `minimize` in the fuzzer |
| Drill | a failure induced on purpose, with checks | `deploy/drills/` |
| Fence (fencing token) | a growing number storage checks on every write | `boards.fence`, checked by every commit |
| Fan-out | delivering one message to many subscribers | Redis Pub/Sub → gateways → clients |
| Group commit | committing several waiting ops in one transaction | 5 ms window |
| Guard check | removing a safety guard to prove its test fails | 42 in `guard_checks.py` |
| HLC | hybrid logical clock: physical time + counter + replica | op timestamps |
| Idempotent | doing it twice equals doing it once | resends, merge |
| Lease | a time-limited claim that must be renewed | `board:lease:{id}`, 15 s |
| LWW register | last-writer-wins: the highest timestamp's value survives | titles, labels, placements |
| Op | one edit | `ADD_CARD`, `SET_FIELD`, … |
| OR-Set | observed-remove set: tags on add, tombstones on delete | lists and cards |
| Orphaned | a derived list of live cards whose list isn't there | nil UUID, shown last |
| Owner | the one instance allowed to commit a board's ops | holds lease and current fence |
| Pub/Sub | publish/subscribe messaging, fire-and-forget | `board:{id}` channels |
| Reconcile | reading back the truth after an unknown commit outcome | `SELECT … FOR UPDATE`, replay |
| Rendezvous hashing | each key goes to the node with the highest hash weight | preferred owner |
| Replica | a copy of the board that applies ops | each tab, and the owner |
| Snapshot | a board's state at a version, hashed | every 500 ops or 60 s, keep 3 |
| Tombstone | a record that a tag was deleted, kept forever | per entity |
| Token bucket | a rate limiter that refills over time | 20/s, burst 50 |
| Version | the board's commit counter | gap detection, history |
| Virtual thread | a cheap JVM thread that can block | actors, connections |
| Web Lock | a browser lock held for a tab's lifetime | one replica per tab |

## 12. Self-test

> **Answer these out loud, then check the key below; anything you miss, reread its section.**

1. What are the two halves of Concord's core promise?
2. Which two CRDTs make up the board, and what does each hold?
3. What does an HLC's `receive` guarantee about the next local stamp?
4. What happens to an op stamped 61 s ahead of server time?
5. Why does a client sometimes switch to a fresh replica ID?
6. In the OR-Set, what is a tag, and what does a delete carry?
7. What was the cross-card delete bug, and how was it fixed?
8. Why does delete win over a concurrent edit, and what happens to the edit?
9. Why is placement one register rather than two?
10. When is a card shown in Orphaned?
11. `keyBetween("a0", "a1")` returns what? Two concurrent appends get which keys, and how are they ordered?
12. What three things happen in Concord's commit transaction?
13. Why must apply, ACK and publish wait for `COMMIT`?
14. Describe the claim order, step by step.
15. What exact interleaving did v3's claim order allow?
16. Why is the lease taken before the fence is written to Postgres?
17. What does the owner do after a commit times out?
18. What are the two layers of dedup, and why both?
19. How does a client detect a missed broadcast?
20. Why do clients use a 15 s silence rule?
21. What is the duplicated-tab bug?
22. What happens to an `INVALID_OP` that arrives behind an unacknowledged op?
23. What bug did the 24-hour offline measurement find, and how was it fixed?
24. Why did the gateway forward one request at a time per connection?
25. What is a remove-the-guard check, and which two tests could not fail at first?
26. Why did the commit window change, from what to what?
27. What limits throughput at 1,000 connections on 300 boards?
28. What made `/metrics` stay public after the first fix?
29. What is the measured sync latency from India, and why?
30. Name three things Concord deliberately does not promise.

---

### Answer key

1. **Convergence:** replicas with the same ops are byte-identical. **Durability:** an ACKed op is in Postgres and never lost.
2. **Two OR-Sets** (which lists and cards exist) and **an LWW map** (every field, including placement).
3. It is strictly greater than both the last local stamp and the received one.
4. Rejected with `CLOCK_SKEW`; the client recomputes its offset from the server's time, re-stamps, and resends under the same `op_id`.
5. Its HLC already ran ahead on a bad clock, and an HLC can't go backwards, so it continues under a new ID that can't repeat old ticks.
6. A tag is the `op_id` of an add or restore; a delete carries the tags its sender had observed.
7. A flat tombstone set let a delete for X list Y's tag and remove Y. Tombstones became per entity (amendment 4).
8. An edit adds no tag, so it can't revive a card. The edit is kept and shows if the card is restored.
9. Separate list and position registers could mix two concurrent moves into a placement nobody chose.
10. When it's live but its list is deleted, unknown, or missing from its placement.
11. `a0V`. Both get `a1`; ties render by entity ID, the same everywhere.
12. Drop already-stored op_ids; `UPDATE boards … WHERE fence = :f RETURNING version`; insert the ops with consecutive versions.
13. So memory, ACKs and broadcasts never contain an op that isn't stored.
14. Fence `INCR` with a floor; lease `SET NX`; `UPDATE boards SET fence … WHERE fence < :f`; load snapshot and tail; announce.
15. A pauses with op X; B claims and loads; A commits X under `fence <= 1` and ACKs it; B's state lacks X forever.
16. So a claimer that loses the lease race never fences out the live owner.
17. Treats the outcome as unknown: `RETRY_LATER`, no snapshots, lock the board row, replay what was committed, then serve.
18. The in-memory LRU (fast, works with Postgres down) and the primary key (true after a failover). A rejected resend is checked against the table first.
19. A version gap (v after v−2) or `BOARD_VERSION` ahead of it; it sends `SYNC_REQUEST`.
20. Browsers often keep a dead WebSocket open; the owner's 5 s `BOARD_VERSION` makes 15 s of silence a reliable death signal.
21. A duplicated tab copies `sessionStorage`, so two tabs shared one replica ID. A Web Lock on the ID makes the copy pick a new one.
22. It's deferred and judged again once everything ahead of it is ACKed, since its dependency may not have committed yet.
23. The client sent its whole queue at once and resent all of it on each refusal; it now paces itself at 20/s, burst 50 (amendment 31).
24. Chained `CompletableFuture`s and `NOT_OWNER` retries could reorder a client's ops, and the owner validates references in order.
25. Delete the guard, require its test to fail, restore, require a pass. `FencingTest` and the LRU test.
26. Measurements: 5 ms batched as well as 15 ms with 11 ms lower median latency. 15 → 5 ms.
27. Postgres commits: one per board per batch, about 300/s, Postgres at 6.8 cores.
28. The VM's checkout was stale (no `git pull`), then Caddy read the old file through a single-file bind mount.
29. p95 315–354 ms; the round trip to Sydney is about 300 ms, and the server's share is about 17 ms.
30. Any three of: character-level text merging, caret cursors, unbounded undo, moving cards between boards, SSO or 2FA, that your own concurrent move always wins.
