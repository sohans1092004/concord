# SPEC §10.1 measurements

Every row of the §10.1 table, measured, with its command and raw output in [`raw/`](raw/).

**Environment.** One laptop: Intel i7-12700H (20 logical cores), 15.6 GB RAM, Windows 11, Docker Desktop (20 CPUs and 7.6 GiB for its VM), Node 22.17.

- The dev compose stack runs 2 gateways, 3 Session instances, Postgres 16 and Redis 7, with tracing off and the observability containers stopped.
- The load generator runs in the same machine, as one Node process holding every client, so the absolute numbers are lower bounds. What carries over is where the bottlenecks sit (as in Reliant's results).
- Every run was made with nothing else running. Four first attempts overlapped other work of mine and were rerun; they are kept in [`raw/disturbed/`](raw/disturbed/) with the reason.

**Method.**
- **Sync latency** is exact. Every client is in one process, so the time an op is submitted and the time another client applies it (its `peer.render` span, taken back to that process's clock) come from one clock, with no clock offset in it. "Sync latency" means edit on one client until another client has applied it.
- **Commit rates** come from the Session instances' own Prometheus counters, read before and after each window.
- **Harness:** `client-core/test/load/load.test.ts`; runner: `production/load-tests/run-all.sh [test ...]`.

## Summary

| Test | Setup | Pass criterion | Result | Verdict |
|---|---|---|---|---|
| Baseline sync latency | 2 clients, 1 board, 1 op/s each, 120s | p95 < 200ms | p50 27ms, **p95 32ms** | **Met** (laptop) |
| …from the real host | Laptop in India → AWS Sydney, smoke test, n=30 | p95 < 200ms | p50 312–317ms, **p95 315–354ms**; server ≈17ms of it | **Met server-side, missed end to end** (network distance) |
| Editors per board | 2 → 30 editors, 1 op/s each, 30s per step | Correct at 30; p95 at each step; name the 200ms crossing | p95 33–45ms at every step; correct at 30 (3,756 ops, 0 missing) | **Met**; p95 never passes 200ms up to 30 |
| Commit batching | 30 editors, window 0 / 5 / 15 / 30ms | Recorded; chosen value in ADR-005 | p50 18 / 24 / 35 / 49ms; ops per commit 1.0 / 2.55 / 2.53 / 1.8 | Recorded; [ADR-005](../../docs/adr/ADR-005-ack-after-commit-group-commit-window.md) |
| Many boards | 1,000 connections, 300 boards, 900 editors at 1 op/s | Throughput, CPU, memory per Session instance | 890 ops/s committed of 900 offered; p95 294ms; Session CPU ~0.7 cores mean, heap peak ≤266 MiB | Recorded; p95 above 200ms on the laptop |
| Large board | 2,000 cards, 10 editors | Every update renders < 16ms | p50 4.8ms, p99 11.8–13.8ms; **max 19.9 and 26.3ms (2 of ~600 per run)** | **Not met** (reproducible, ~0.3% of updates) |
| Offline merge, 1h | Real time, real browsers | Converged, nothing lost, reconnect time | 240 edits; converged 11.5s after reconnecting; 0 unsent | **Met** ([scripted hour](../../docs/offline-hour-scripted.md)) |
| Offline merge, 8h | **Accelerated** | same | 1,885 edits synced in 92s; 0 lost, 0 unsent | **Met** (after amendment 31) |
| Offline merge, 24h | **Accelerated** | same | 5,701 edits synced in 283s; 0 lost, 0 unsent | **Met** (after amendment 31; **failed before it**) |
| Owner crash | Kill the owner mid-load | Serving ≤5s after lease expiry; every ACKed op stored | 1,565ms after expiry; 0 missing | **Met** ([drills](../../deploy/drills/results.txt)) |
| Stale owner | Pause the owner past its lease | Zero stale commits; clients recover | 0 stale commits; fence rejection when Redis is cut too | **Met** ([drills](../../deploy/drills/results.txt)) |
| Reconnect storm | Gateway with 500 connections killed | No cascade; time to all reconnected | 3.5–16.2s (laptop, 2 runs), 12.4s (CI); nothing else restarted | **Met** ([drills](../../deploy/drills/results.txt), amendment 30) |
| Per-connection throughput | One connection, one op in flight | (asked in Phase 4b) | 21.6 ops/s, ACK p50 47ms | Recorded: the 20 ops/s rate limit is the cap |
| Recovery cost | Snapshot + 0 or 499-op tail, owner killed | (for ADR-008) | claim 29–342ms at 2,000 cards and a 942 KB snapshot | Recorded; [ADR-008](../../docs/adr/ADR-008-snapshot-every-500-ops-or-60s.md) |

## Bugs found while measuring

- **A long offline queue drained far too slowly** (amendment 31, [incident 021](../incident-reports/021-offline-queue-drained-slowly.md)).
  - The client sent its whole queue at once, and every refusal re-sent all of it.
  - 8h queue: 487s before the fix, 92s after. 24h: unfinished after 10 minutes before, 283s after.
  - The runs from before the fix are in [`raw/before-amendment-31/`](raw/before-amendment-31/).
- **Two of my own harness flaws**, fixed before the numbers above were taken:
  - telemetry from the warm-up leaked into the measuring window;
  - the first recovery run read Postgres on every rename, so the 60s snapshot timer fired before the 499-op tail was complete. That run is in [`raw/superseded/`](raw/superseded/).

## 1. Baseline sync latency

`LOAD=baseline` → [`raw/baseline.txt`](raw/baseline.txt): 239 samples in 120s, **p50 27ms, p95 32ms, p99 33ms, max 60ms**; 1 op per commit.

- **Reading:** with two editors there is never a second op to share a commit, so each op pays the 15ms window (the default then) plus one commit and one Pub/Sub hop.

**On the real host** (`deploy/prod/rehearsal-results.txt`), the smoke test's latency, from a laptop in India to the AWS host in Sydney, n=30:
- p50 317ms, p95 354ms, max 359ms (2026-10-18, first run);
- p50 312ms, p95 315ms, max 317ms (during the 41b6b9a deploy).

TCP connect from the laptop to the VM takes 298–312ms. An edit needs about one round trip (half to the server, half from it to the other client), so **the server accounts for about 17ms of it**.

- **Reading:** the 200ms target is met by the server and missed end to end, entirely because of the distance to the Sydney region. A region near the users would bring it under target; nothing in the server would change.

## 2. Editors per board, 2 → 30

`LOAD=editors` → [`raw/editors.txt`](raw/editors.txt), 30s per step:

| Editors | Samples | p50 | p95 | p99 | Committed ops/s | Commits/s | Ops per commit |
|---|---|---|---|---|---|---|---|
| 2 | 60 | 29 | 33 | 37 | 2.0 | 2.0 | 1.00 |
| 5 | 596 | 29 | 41 | 46 | 5.0 | 5.0 | 1.00 |
| 10 | 2,682 | 29 | 45 | 94 | 9.9 | 9.9 | 1.00 |
| 15 | 6,272 | 29 | 35 | 38 | 14.8 | 13.0 | 1.14 |
| 20 | 11,324 | 29 | 36 | 39 | 19.8 | 14.5 | 1.37 |
| 25 | 17,904 | 31 | 42 | 47 | 24.8 | 13.5 | 1.84 |
| 30 | 25,926 | 33 | 42 | 44 | 29.7 | 15.6 | 1.90 |

**At 30 editors:** 3,756 ops ACKed, board version 3,756, every client on one state hash, 0 ACKed ops missing, 0 unsent.

- **Reading:** p95 does not cross 200ms anywhere from 2 to 30 editors, so there is no crossing to name on this machine.
  - Group commit absorbs the growth: commits per second level off around 13–16 while ops per commit rise, so the board's single writer (ADR-004) is far from busy at 30 ops/s.
  - The 10-editor step's p99 of 94ms (max 264ms) is one burst in that 30s window; the steps either side do not show it.

## 3. Commit batching

`LOAD=batching`, 30 editors at 1 op/s on one board, 60s per window. The Session instances were restarted with `CONCORD_BATCH_MS` for each. → [`raw/batching-*.txt`](raw/)

| Window | Samples | p50 | p95 | p99 | max | Commits/s | Ops per commit |
|---|---|---|---|---|---|---|---|
| 0ms | 51,823 | 18 | 36 | 47 | 119 | 29.7 | 1.00 |
| 5ms | 51,794 | 24 | 34 | 39 | 49 | 11.6 | 2.55 |
| 15ms (default until now) | 51,765 | 35 | 46 | 53 | 88 | 11.8 | 2.53 |
| 30ms | 51,881 | 49 | 58 | 64 | 76 | 16.5 | 1.80 |

- **Reading:** any window above 0 cuts commits by more than half at this load. 5ms gets as much batching as 15ms (2.55 vs 2.53 ops per commit) at 11ms lower median latency, and 30ms adds latency without batching more.
  - The ops-per-commit figure is also bounded by the load: 30 ops/s spread evenly over a second leaves few ops to share any window.
  - [ADR-005](../../docs/adr/ADR-005-ack-after-commit-group-commit-window.md) records the choice.

## 4. Many boards

`LOAD=many-boards` → [`raw/many-boards.txt`](raw/many-boards.txt).
- **Setup:** 300 boards with 3 editors each make 900 connections, so 100 of the boards also have a viewer, to make §10.1's 1,000. Every editor makes 1 op/s, on both gateways. 120s measured after 10s of warm-up.

| | |
|---|---|
| Connections joined at the end | 1,000 of 1,000 |
| Committed ops/s | **890.5** of 900 offered |
| Commits/s, ops per commit | 299.5, 2.97 |
| Sync latency | p50 **89ms**, p95 **294ms**, p99 608ms, max 1,596ms (251,509 samples) |
| Load generator | 1.24 cores |

Containers (`docker stats` every 10s):

| | CPU mean | CPU peak | Memory peak |
|---|---|---|---|
| session1 / session2 / session3 | 69% / 67% / 71% | 160% / 166% / 128% | 266 / 246 / 265 MiB |
| gateway1 / gateway2 | 118% / 101% | 168% / 152% | 524 / 555 MiB |
| postgres | 158% | **679%** | 231 MiB |
| redis | 25% | 29% | 8 MiB |

- **Reading:** throughput held (99% of the offered load committed), but latency rose to a p95 of 294ms, above the 200ms target.
  - **Postgres** is the busiest process: it peaked at 6.8 cores for about 300 commits/s, one per board per second, which group commit cannot merge because each board commits alone (ADR-004).
  - **The Session instances** used under one core each on average.
  - **The gateways** use the most memory: 1,000 WebSockets with their send buffers.
  - The next bottleneck at this scale is Postgres commits per second across many boards, the same one Reliant reached ("each event is its own Postgres commit"). A per-instance writer batching several boards was rejected in ADR-004 for isolation; this is the measurement that would reopen it.

## 5. Large board

`cd web && npx playwright test e2e/perf.spec.ts` (`web/e2e/perf.spec.ts`), twice. → [`raw/large-board.txt`](raw/large-board.txt), [`raw/large-board-run2.txt`](raw/large-board-run2.txt)
- **Setup:** a 2,000-card board in Chromium, 10 headless editors making one edit a second each for 60s. Every update is timed in the page, from client-core's change to React's DOM commit.

| Run | Updates | p50 | p95 | p99 | max | At or over 16ms | Long tasks |
|---|---|---|---|---|---|---|---|
| 1 | 601 | 4.8 | 9.4 | 11.8 | **19.9** | 2 | 0 |
| 2 | 600 | 4.9 | 9.1 | 13.8 | **26.3** | 2 | 0 |

- **Reading: not met.** The criterion is *every* update under 16ms, and about 1 update in 300 misses it on this laptop, in both runs, with nothing else running.
  - The typical update is a third of the budget.
  - No long task (>50ms) occurred, so the outliers are single slow renders, not blocking work.
  - An earlier run on a busy machine (2026-10-17) had max 16.4ms, 1 of 599 over.
  - The test was not loosened. Finding what the slow renders do is listed as an open gap in `docs/how-concord-was-built.md`.

## 6. Offline merge

- **1 hour, real time** ([`docs/offline-hour-scripted.md`](../../docs/offline-hour-scripted.md)):
  - two real Chromium browsers, B offline for 60 minutes making one edit every 15s (with a reload and a tab reopen), A online editing every 30s;
  - 240 offline edits;
  - **converged 11,457ms after reconnecting**, 0 unsent.
- **8 and 24 hours, ACCELERATED** (`LOAD=offline OFFLINE_HOURS=8|24`; [`raw/offline-8h-accelerated.txt`](raw/offline-8h-accelerated.txt), [`raw/offline-24h-accelerated.txt`](raw/offline-24h-accelerated.txt)):
  - **Same rates as the real hour:** B edits every 15 simulated seconds while offline, A every 30 while online.
  - **The clock:** the server's clock cannot be fast-forwarded, so each client's clock offset was set (test-only) so that the board's whole timeline, setup included, runs from N hours ago to now. Every op is stamped in the past as it would have been, never in the future, which the server refuses (§3.1).
  - **Not reproduced:** hours of real time on the connection, the browser or IndexedDB.
  - **Cross-check:** the same harness at 1 hour gave 224 edits converged in 10.6s, against the real hour's 240 in 11.5s.

| | 8h | 24h |
|---|---|---|
| Real time taken | 46s | 143s |
| Edits made offline (B) / online meanwhile (A) | 1,885 / 939 | 5,701 / 2,852 |
| B's timestamps | 02:39 → 10:39 UTC | 10:41 the day before → 10:41 UTC |
| Back online → JOINED | 9.6s (waiting out its backoff) | 1.2s |
| JOINED → every offline edit ACKed | **91.8s** | **282.7s** |
| Converged / ACKed missing / unsent | yes / 0 / 0 | yes / 0 / 0 |

- **Reading:** both drain at about 20 ops/s, the gateway's per-connection limit, which the client now paces itself to (amendment 31).
  - **Before the fix**, the same runs took 487s for 8h, and 24h still had 5,243 edits pending after 10 minutes.
  - A day's work offline now takes under 5 minutes to sync on reconnect, during which the user keeps editing and sees everyone else's edits.

## 7. Owner crash, stale owner, reconnect storm

These are the drills, rerun on the current code on 2026-10-18 ([`deploy/drills/results.txt`](../../deploy/drills/results.txt); write-ups in [`production/incident-reports/`](../incident-reports/)):

- **Owner crash:** new owner serving **1,565ms after lease expiry** (target ≤5,000); first ACK 13,979ms after the kill; 0 of 347 ACKed ops missing.
- **Stale owner:**
  - paused 20.5s: 0 commits by the stale owner;
  - with its Redis also cut: 1 fence rejection, rows 99 → 99, the probe op refused with `NOT_OWNER`.
- **Reconnect storm:** 500 clients on 50 boards, gateway killed. All rejoined in **3.5–16.2s** on the laptop (two runs; the spread is how many clients first retried the dead gateway: 0 vs 128 failed attempts) and **12.4s** on CI; 0 ACKed ops lost; 50/50 boards converged; no other service restarted. No rate limit was needed (amendment 30).

## 8. Per-connection throughput, one op in flight

`LOAD=inflight` → [`raw/inflight.txt`](raw/inflight.txt): one connection submits an op, waits for its ACK, repeats, for 30s.
- **649 ops in 30s, 21.6 ops/s**; ACK p50 47ms, p95 65ms, p99 68ms; 649 commits.
- **Reading:** this is the ceiling the gateway sets, not the round trip.
  - The client paces itself to the gateway's limit, 20 ops/s after a burst of 50 (amendment 31): 50 + 20 × 30 = 650 ops, and 649 were sent.
  - The ACK time includes waiting for the next token. Without the limit, one op in flight would allow about one op per round trip.
  - A person makes a few edits a second at most, so the cap only matters to a reconnecting client with a backlog (section 6).

## 9. Recovery cost (for ADR-008)

`LOAD=recovery` → [`raw/recovery.txt`](raw/recovery.txt).
- **Setup:** a board padded to a snapshot boundary (snapshots every 500 ops), then 0 or 499 more ops (the most a tail can hold before the next snapshot), then its owner killed. `claim_ms` is the new owner's own measurement of the claim: fence, lease, Postgres fence, load the snapshot and replay the tail.

| Cards | Snapshot (JSON) | Tail | Claim | Kill → first ACK |
|---|---|---|---|---|
| 10 | 5 KB | 0 | 61ms | 19.8s |
| 10 | 5 KB | 499 | 29ms | 13.3s |
| 2,000 | 942 KB | 0 | 77ms | 20.3s |
| 2,000 | 942 KB | 499 | 342ms* | 15.7s |

\* The mean of 3 claims the new owner made in that window: the killed instance also held boards from the earlier rows.

- **Reading:** a claim costs well under a second even for the largest board §1.2 asks for with a full tail. What a user waits after an owner dies is the 15s lease plus client backoff, not the claim.
