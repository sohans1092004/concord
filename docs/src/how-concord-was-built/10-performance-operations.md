
## 11. Performance

> **Every §10.1 measurement is recorded with its command and raw output; the server is fast where it was designed to be, and the measurements found one real bug and chose one parameter.**

All in `production/load-tests/README.md`, raw output in `production/load-tests/raw/`, harness `client-core/test/load/load.test.ts` **[laptop]** unless tagged. Sync latency there is **exact**: every client runs in one process, so the submit time and the apply time on another client come from one clock ([[load#class Latency {]]).

| Test | Setup | Result | Verdict |
|---|---|---|---|
| Baseline sync latency | 2 clients, 1 op/s each, 120 s | p50 27 ms, **p95 32 ms**, p99 33 ms | met |
| From India to Sydney **[host]** | smoke test, n = 30 | p50 312–318 ms, p95 315–354 ms; server ≈17 ms | missed end to end |
| Editors per board | 2 → 30 editors, 30 s per step | p95 33–45 ms at every step; at 30: 3,756 ops, 0 missing | met; never crosses 200 ms |
| Commit window | 30 editors; 0 / 5 / 15 / 30 ms | p50 18 / 24 / 35 / 49 ms; ops per commit 1.0 / 2.55 / 2.53 / 1.8 | 5 ms chosen (ADR-005) |
| Many boards | 1,000 connections, 300 boards, 900 editors | 890 of 900 ops/s committed; p95 294 ms; Postgres peak 6.8 cores | latency above target |
| Large board | 2,000 cards, 10 editors, Chromium | p99 11.8–13.8 ms; max 19.9 / 26.3 ms | missed (≈0.3%) |
| Offline 1 h | real browsers, real time | 240 edits, converged 11.5 s after reconnecting | met |
| Offline 8 h / 24 h **[accelerated]** | the 1 h rates, compressed | 1,885 / 5,701 edits synced in 92 / 283 s | met after amendment 31 |
| Owner crash, stale owner, storm | the drills | 1.6 s after expiry; 0 stale commits; 500 back in 3.5–16.2 s | met |
| One op in flight | 1 connection, submit, wait, repeat | 21.6 ops/s, ACK p50 47 ms | the 20 ops/s rate limit is the cap |
| Recovery cost | snapshot + 0 or 499-op tail, owner killed | claim 29–342 ms at 2,000 cards (942 KB snapshot) | recorded (ADR-008) |

### 11.1 The large-board profiling story: 56.6 ms → 4.7 ms

The first 2,000-card measurement, timing each update in the page from client-core's change to React's DOM commit (`concord:update`), gave **p50 56.6 ms, p95 83.9 ms, max 121.1 ms, with 200 of 200 updates over 16 ms** (`docs/phases.md:242`). Three causes, three fixes (`2dfb84c`):

| Cause | Fix | Where |
|---|---|---|
| The drag-and-drop context re-rendered on every data update, re-rendering every sortable card | the context sits in a memoised component that does not depend on board data | [[boardpage#const DndArea = memo(]] |
| Unchanged list and card objects were rebuilt on every update, so memoised components re-rendered anyway | `useStableView` reuses the previous object for anything unchanged | [[boardpage#function useStableView(next: View)]] |
| The store subscription was recreated on render | a stable subscription through `useSyncExternalStore` | [[useboard#return useSyncExternalStore(subscribe, () => tick.n);]] |

After the fixes: **p50 4.7 / 4.8 / 4.7 ms, max 12.0–13.4 ms over three runs, 0 updates over 16 ms** (`docs/phases.md:241`) **[laptop]**. The rerun on the current code on an idle laptop has a tail again: 2 of about 600 updates at 16–26 ms, with no long task. That tail is not profiled. In CI the test is a regression guard at p95 < 25 ms (amendment 20).

### 11.2 The commit-window experiment (ADR-005)

The same 30-editor load, 60 s per window, with the Session instances restarted with `CONCORD_BATCH_MS` each time:

| Window | p50 | p95 | Ops per commit | Commits/s |
|---|---|---|---|---|
| 0 ms | 18 ms | 36 ms | 1.00 | 29.7 |
| **5 ms** | **24 ms** | **34 ms** | **2.55** | **11.6** |
| 15 ms (the SPEC's first value) | 35 ms | 46 ms | 2.53 | 11.8 |
| 30 ms | 49 ms | 58 ms | 1.80 | 16.5 |

**Reading:** any window above 0 cuts commits by more than half, and 5 ms batches as much as 15 ms for 11 ms less median latency. The window became 5 ms (amendment 32; [[config#e.getOrDefault("CONCORD_BATCH_MS", "5")]]).

### 11.3 The send-pacing fix (amendment 31)

The 8 h and 24 h offline measurements found a client bug. On reconnect, the client sent its whole queue at once, and the gateway refused everything past its burst of 50. Every refusal then made the client resend the **whole** queue: thousands of frames per cycle for a handful accepted, with the work per cycle growing with the queue.

| | Before | After |
|---|---|---|
| 8 h: 1,885 edits | 487 s | **92 s** |
| 24 h: 5,701 edits | 5,243 still pending after 10 min (the run failed) | **283 s** |

The fix is the client token bucket of section 8.5; unit test and guard check `client-send-pacing` (incident 021, `816037b`). The runs from before the fix are kept in `production/load-tests/raw/before-amendment-31/`.

## 12. Operations

> **One small VM runs the trial: images are pulled by commit SHA and never built there, every deploy is rolling and checked from outside, backups are encrypted and verifiable, and the monitoring is free.**

### 12.1 The live deployment

| Piece | Choice | Source |
|---|---|---|
| Host | AWS EC2 **t4g.small** (2 vCPU arm64, 2 GB; MemTotal 1,834 MiB) in **ap-southeast-2 (Sydney)**, Ubuntu Server 26.04 | `docs/runbook.md` |
| Name | `concord-serve.duckdns.org` on an Elastic IP (DuckDNS, free) | `docs/runbook.md` |
| TLS and the only open ports | **Caddy** with Let's Encrypt on 80 and 443; HSTS and security headers; `/metrics*` answers 404 ([[caddyfile#@internal path /metrics /metrics/*]]) | incident 019 |
| Services | 1 gateway, 2 Session instances (so a board's owner can fail over), Postgres 16, Redis 7, the backup job | `deploy/prod/compose.yaml` |
| Hardening | production refuses every dev value: secrets of at least 32 bytes, https origin only, Secure cookie, invite code, trusted proxy ([[config#public Config checkPublic()]]); the gateway sets the CSP ([[gatewaymain#String csp =]]) | amendment 24 |

**Memory budget** ([[prodcompose#= 1,336 MiB]]):

| Container | gateway | session ×2 | postgres | redis | caddy | backup | total |
|---|---|---|---|---|---|---|---|
| Limit (MiB) | 320 | 288 each | 224 | 40 | 48 | 128 | **1,336** (72.8% of 1,834) |

Every limit is at least 23% above its measured peak (soak run 3). The JVMs use the Serial collector with capped heap, metaspace, code cache and direct memory, and `MALLOC_ARENA_MAX=2` stops glibc growing a native arena per thread. **On the host after the fifth deploy [host]:** gateway 161.7/320 MiB, session1 134.2/288, session2 111.9/288, postgres 32.2/224; 720 MiB available.

### 12.2 Deploys and rollback

`deploy/prod/deploy.sh <full commit SHA>` ([[deploysh#set -euo pipefail]]):
1. Refuses unless the VM's checkout is at that SHA, so the config files match the images ([[deploysh#[ "$head" = "$tag" ]]]).
2. Takes a backup with the running version.
3. Pulls the images, which CI published only after every test passed. If they don't exist, it stops before changing anything.
4. Migrates, forward-only: a migration must keep the previous code working.
5. Replaces session1, then session2, then the gateway, each after the previous is healthy. An owner on SIGTERM flushes, snapshots and releases its leases; the gateway sends `RECONNECT_ELSEWHERE` ([[gatewaymain#public void close() {]]).
6. Reloads Caddy's config, then checks from outside that `/healthz` answers and `/metrics/prometheus` gives 404.
7. Prints the rollback: the same command with the previous SHA.

**Record [host]:** five deploys on 2026-10-18. Three ran under live editing: 2,315, 2,306 and 2,351 ops through the deploy, 0 missing, 0 unsent (`deploy/prod/rehearsal-results.txt`). Locally, a deploy and its rollback were rehearsed under live editing first (1,834 and 1,854 ops, 0 missing).

### 12.3 Backups and the verified restore

- **Nightly at 03:00 UTC, kept 14 days** (`deploy/prod/compose.yaml`, `BACKUP_HOUR_UTC`, `BACKUP_KEEP_DAYS`).
- **Encrypted with `age`** to a public key. The private key is never on the server (`docs/runbook.md` §5).
- **Uploaded to Backblaze B2** with rclone.
- **Checkable:** `Backup dump` takes `pg_dump` and a manifest of every board's state hash from **one exported snapshot**, so both describe the same moment while editing continues. `verify` restores into a throwaway Postgres and replays every board ([[backup#public final class Backup]]).
- **Restore result:** a rehearsal restore printed `RESTORE VERIFIED` in 19.9 s **[laptop]**.
- **On the host:** the first restore check from the laptop failed before touching data, because the key file was unreadable. `restore.sh` was fixed to pass the key on stdin, and that fix was verified on the rehearsal stack. **A restore of a live backup after the fix is not recorded** (`deploy/prod/rehearsal-results.txt`, "On the real host").

### 12.4 Monitoring

- **`deploy/prod/summary.sh`, daily:** containers, memory and heaps, errors and warnings, fence rejections, commit failures, metrics read inside the network, users and ops, and the last backup. Confirmed on the host (`deploy/prod/rehearsal-results.txt`, 12:47 UTC) **[host]**.
- **`/healthz`:** Postgres reachable and the disk under 90% full ([[gatewaymain#app.get("/healthz"]]).
- **External checks:** the runbook prescribes UptimeRobot on `/healthz` (5 minutes) and a healthchecks.io ping per backup (`docs/runbook.md` §9). Whether those accounts were set up is **not recorded in the repo**.
- **Prometheus, Grafana and Jaeger** run locally only (`docker compose --profile observability`); the 2 GB host has no room for them.

### 12.5 The /metrics exposure (incident 019)

The gateway's `/metrics` was reachable through Caddy. It exposed operational counters, including board IDs; no tokens, user data or board contents.
1. The Caddy rule (`eca3941`) did not take effect: the VM's checkout was stale, because the command I gave skipped `git pull`.
2. The next deploy's new outside check caught that Caddy was still reading the old file: a single-file bind mount pins the original inode. Fixed by mounting the directory (`e274eba`).
3. `deploy.sh` now refuses a stale checkout and checks `/metrics` from outside.

### 12.6 Costs and credits

The account is on AWS's free plan, spending sign-up credits ($100 here); "about $20 a month at this size" is **an estimate in the runbook, not a measured bill** ([[runbook#The credits are finite]]). DuckDNS, Let's Encrypt, Backblaze B2 (10 GB free), UptimeRobot and healthchecks.io are free tiers. A zero-spend budget emails if anything ever costs real money.
