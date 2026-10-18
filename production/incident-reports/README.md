# Incident reports

Postmortems in Reliant's format: what broke, impact, detection, root cause, fix, verification, and what I learned. Facts only; each names its commits and the files that hold its evidence.

**Induced failures (SPEC §8 drills).** All seven drills were rerun on the current code on 2026-10-18 (`deploy/drills/results.txt`) and run on every CI push.

| # | Failure | Outcome |
|---|---|---|
| [001](001-gateway-crash.md) | Gateway crash (§8 row 1) | Held. Found clients stuck in "connecting" after a restart (`7a98605`) |
| [002](002-owner-crash.md) | Owner killed mid-load (row 2) | Held: serving 1.6s after lease expiry, 0 ACKed ops missing. Found [012](012-retries-out-of-order-after-owner-crash.md) |
| [003](003-stale-owner.md) | Two owners at once (row 3) | Held: 0 stale commits. The drill was fixed twice to reach the Postgres fence (`2cfb00c`, `c092c96`) |
| [004](004-postgres-down.md) | Postgres down (row 4) | Held: 0 ACKs while down, 0 missing |
| [005](005-redis-down.md) | Redis down (row 5) | Held: 268 ACKs during a 15.8s outage |
| [006](006-redis-wiped.md) | Redis wiped (row 6) | Held. The drill asserted the wrong thing once (`ade7f57`) |
| [007](007-reconnect-storm.md) | Reconnect storm, 500 clients (row 14) | Held without a rate limit (amendment 30). The drill's first check was wrong (`3a3284c`) |
| [008](008-rolling-deploy.md) | Rolling deploy under live editing (row 15) | Held in 7 recorded deploys: 0 missing, 0 unsent |

**Bugs.**

| # | Bug | Found by | Fix |
|---|---|---|---|
| [009](009-claim-order-lost-acked-ops.md) | The spec's claim order could lose an ACKed op | Reading the spec | Amendment 6 (A), `e5ce262` |
| [010](010-commit-with-unknown-outcome.md) | No rule for a commit with an unknown outcome | Reading the spec | Amendment 6 (B), `e5ce262` |
| [011](011-gateway-reordered-ops.md) | The gateway's `CompletableFuture` chains could reorder a client's ops | Reading the code | `a916b65` |
| [012](012-retries-out-of-order-after-owner-crash.md) | Per-op retry timers sent edits out of order after an owner crash | kill-owner drill | `5a2a495` |
| [013](013-websocket-never-closed-offline.md) | Chromium's WebSocket never closed when the network went away | Phase 3 offline browser test | Amendment 18, `d0ebd87` |
| [014](014-stale-owner-retakes-lease-after-redis-wipe.md) | A stale owner could re-take its lease after a Redis wipe | Writing up §6.3 | Amendment 13, `4b20d57` |
| [015](015-the-fencing-test-could-not-fail.md) | The fencing test (and the LRU's) could not fail | Remove-the-guard checks | `d48cfa2` |
| [016](016-soak-heap-growth-by-design.md) | The memory soak failed: heap growth by design, no idle unload | Phase 4a soak | Amendment 26, `00bea31`, `7207093` |
| [017](017-arm64-ci-deadlock.md) | CI hung on arm64 in the log-capture test helper | CI thread dump | `d940470` |
| [018](018-board-deleted-while-loaded.md) | A board deleted while its owner had it loaded | Live trial host logs | Amendment 27, `f21aac5` |
| [019](019-metrics-public-on-the-trial-host.md) | `/metrics` public on the trial host; two deploys did not close it | Building observability; `deploy.sh`'s new check | `eca3941`, `41b6b9a`, `e274eba` |
| [020](020-telemetry-report-over-the-cap.md) | The client could send a 101-item telemetry report | Unit test, before deploy | `eca3941` |
| [021](021-offline-queue-drained-slowly.md) | A long offline queue took far too long to sync (8h: 487s; 24h: unfinished after 10 min) | §10.1 offline measurement | Amendment 31, `816037b` |

**Trial bug reports:** none received so far. The in-app reports land in `bug_reports`, and the daily `summary.sh` counts them; each one that reveals a bug will get a report here.
