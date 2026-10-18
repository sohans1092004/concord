
## 9. Failure handling

> **All fifteen SPEC §8 failure scenarios are handled; the eight marked "drill" were induced on purpose, rerun on the current code, run in CI on every push, and written up, and none lost an acknowledged edit.**

### 9.1 The SPEC's scenarios

| # | Failure | Handling | Proof |
|---|---|---|---|
| 1 | Gateway crashes | clients reconnect elsewhere with backoff and resend | e2e "1: gateway crash" **[CI]**: client back on gateway 2 in 0.1 s; incident 001 |
| 2 | Owner crashes | lease expires; next instance claims, loads snapshot + tail | kill-owner drill; incident 002 |
| 3 | Two owners at once | Postgres fence rejects the stale owner's commit | pause-owner drills; incident 003 |
| 4 | Postgres down | no commits, no ACKs, `RETRY_LATER`; clients edit locally | stop-postgres drill; incident 004 |
| 5 | Redis down | owners keep serving; gateways poll owners each second; presence lost | stop-redis drill; incident 005 |
| 6 | Redis wiped | fence counters rebuild from `boards.fence`; leases re-taken | wipe-redis drill; incident 006 |
| 7 | Pub/Sub message lost | version gap or `BOARD_VERSION` → `SYNC_REQUEST` | e2e "7: lost broadcast" **[CI]**: 8 dropped, 8 syncs, converged |
| 8 | Client offline 24 h | snapshot or op range on reconnect; queue resent, paced | offline 24 h **[accelerated]**: 5,701 edits, 0 lost |
| 9 | Same op many times | LRU, then primary key; original ACK, never re-broadcast | e2e "9: same op many times"; `WritePathTest.dedupReturnsTheOriginalAckAndNeverRebroadcasts` |
| 10 | Client clock far ahead | `CLOCK_SKEW`; client corrects and re-stamps | e2e "10: client clock two minutes fast"; vector 010 |
| 11 | Access revoked while offline | ops rejected into Unsent edits, never dropped | `WritePathTest.viewersAndNonMembersCannotEditAndRevocationTakesEffectOnTheNextOp`; client unit tests |
| 12 | Malformed or malicious op | rejected before persist; logged with user and reason ([[actor#private void rejectOrAckStored]]) | e2e "12: malformed and malicious frames"; `validateShape` vectors 037–044, 055 |
| 13 | Snapshot corrupt | hash mismatch → previous snapshot + longer tail ([[store#ALERT snapshot checksum mismatch]]) | `SnapshotTest`; `RetentionTest` loads identically with the two newest snapshots corrupted |
| 14 | Reconnect storm | client jitter; no rate limit needed (amendment 30) | reconnect-storm drill; incident 007 |
| 15 | Rolling deploy | gateway sends `RECONNECT_ELSEWHERE` and drains; owners flush, snapshot, release | 3 live deploys under live editing; incident 008 |

### 9.2 The drills

Self-checking. Each fails unless every ACKed op is in `board_ops`, every client and a fresh one end on one SHA-256 state hash, and nothing is in Unsent edits ([[drills#run("kill-owner")("kill-owner"]]). Results from the rerun on current code, 2026-10-18 (`deploy/drills/results.txt`) **[laptop]**:

| Drill | Fault | Result | ACKed missing / Unsent / converged |
|---|---|---|---|
| kill-owner | `SIGKILL` the owner | new owner serving 1,565 ms after lease expiry | 0 / 0 / yes |
| pause-owner | `docker pause` the owner 20.5 s | 0 commits by the stale owner (its renewal saw the new lease) | 0 / 0 / yes |
| pause-owner-no-redis | pause, with the owner's Redis also cut | 1 Postgres fence rejection; probe op refused `NOT_OWNER`, never stored | 0 / 0 / yes |
| stop-postgres | Postgres down 10.6 s | 0 ACKs while down; first ACK 7.8 s after it returned | 0 / 0 / yes |
| stop-redis | Redis down 15.8 s | 268 ACKs while down; clients 3–13 versions behind (polling) | 0 / 0 / yes |
| wipe-redis | `FLUSHALL` | forced claim got `boards.fence + 1` with 0 retries | 0 / 0 / yes |
| reconnect-storm | gateway with 500 clients on 50 boards killed | all back in 3.5–16.2 s (12.4 s in CI); nothing else restarted | 0 / n/a / 50 of 50 boards |

### 9.3 Every postmortem, one lesson each

All in `production/incident-reports/`, in Reliant's format.

| # | What happened | Lesson |
|---|---|---|
| 001 | Clients could hang in "connecting" after a gateway restart | every state a client can wait in needs a timer out of it |
| 002 | Owner killed mid-load: held; found 012 | a drill must check the user's end state, not only the server's |
| 003 | The stale-owner drill never reached the Postgres fence | log the counter that proves the path under test ran |
| 004 | Postgres down: 0 ACKs, 0 lost | the honest answer during an outage is to promise nothing |
| 005 | Redis down: kept committing | the fence in Postgres makes losing Redis a speed problem, not a safety one |
| 006 | The wipe drill asserted a mechanism, not a property | assert the property; count retries, which can hide the bug they recover from |
| 007 | The storm drill's wait condition was wrong, twice | describe the clients the fault actually hit, and count them |
| 008 | Rolling deploys lost nothing; the deploy *process* had gaps | end every deploy by checking the change from outside |
| 009 | The spec's claim order could lose an ACKed op | the fence must be in the database before the new owner reads |
| 010 | No rule for a commit with an unknown outcome | every remote call has three outcomes; name the third |
| 011 | The gateway could reorder a client's ops | asynchronous code keeps no order unless something enforces it |
| 012 | Per-op retry timers made valid edits Unsent | independent timers for dependent work recreate reordering |
| 013 | Chromium's WebSocket never closed offline | a connection is alive only if something was heard recently |
| 014 | A stale owner could re-take its lease after a wipe | "safe because Postgres fences it" covered writes, not reads and routing |
| 015 | The fencing test could not fail | a test that has never failed has shown nothing; remove the guard and watch |
| 016 | The soak failed: heap grew by design | a soak's workload must have a flat correct heap; bound time, not just size |
| 017 | CI deadlocked on arm64 in a test helper | make a hang fail loudly with a thread dump before hunting it |
| 018 | A board deleted while loaded | "zero rows" needs a "why" before it raises an alarm |
| 019 | `/metrics` public; two deploys did not close it | pin the config files with the images; mount directories, not files |
| 020 | A telemetry report one item over the cap | check a cap against the largest single addition |
| 021 | A day's offline queue would take hours to sync | a limit on one side needs a matching pace on the other |
