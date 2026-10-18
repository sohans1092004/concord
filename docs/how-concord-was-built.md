# Concord: How the Spec Was Built

2026-10-18 · Sohan

## Overview

Concord is built and running. Every functional requirement in the Concord Engineering Specification (v3, with 32 amendments recorded in `docs/SPEC.md`) is built. Every measured target is recorded on one development laptop (§10.1, [`production/load-tests/`](../production/load-tests/README.md)). It runs for a small trial on a real host: an AWS t4g.small in Sydney, at `concord-serve.duckdns.org`.

Two targets are not fully met, and both are stated where they are measured:

- **Large board:** every update must render in under 16ms. About 1 update in 300 does not (max 19.9 and 26.3ms over two runs; p99 11.8–13.8ms).
- **Sync latency from far away:** met by the server (p95 32ms on the laptop, about 17ms of server time on the real host), missed end to end from India to Sydney (p95 315–354ms) because of network distance.

Concord is a collaborative kanban board. People on different devices edit the same board at once, see each other's changes within 200ms, keep working offline for up to a day, and never lose an edit or get asked to choose between versions.

The spec's core promise is **no acknowledged edit is ever lost, and every replica converges**. An `OP_ACK` means the edit is committed to Postgres. Two replicas that have seen the same edits, in any order, hold byte-identical state.

Two terms used throughout:
- An **op** is one edit (add a card, move it, rename a list), with a unique `op_id` and a hybrid logical clock (HLC) timestamp.
- A board's **owner** is the one Session instance that orders, validates and commits its ops at any moment.

**What exists today**

- **A CRDT engine written twice**, in TypeScript (`crdt-ts/`) and Java (`crdt-java/`): an observed-remove set for what exists, last-writer-wins registers for everything else, an atomic placement register, and fractional keys for order.
  - Proven identical by 55 hand-written conformance vectors and a differential fuzz between the two (ADR-000, ADR-000b).
- **A Java 21 server** (`server/`), on Javalin, Postgres 16 and Redis 7:
  - gateways hold the WebSockets and the REST API;
  - Session instances run one actor per board, which validates, group-commits, applies and broadcasts.
- **A TypeScript sync client** (`client-core/`): an offline queue in IndexedDB, in-order resend, clock correction, presence and telemetry.
- **A React web app** (`web/`) on top of it.
- **Tests:** 109 + 109 CRDT tests (property tests, vectors, a 500-seed permutation fuzz per commit and 100,000 nightly), 61 server tests against real Postgres, Redis and Toxiproxy, 35 client unit tests, 7 end-to-end tests, 7 failure drills, 19 browser tests in Chromium, and 42 remove-the-guard checks.
  - GitHub Actions runs them on every push, on amd64 and arm64.
- **Observability, locally:** Prometheus metrics on every service, client-reported sync latency, OpenTelemetry traces keyed by `op_id`, a provisioned Grafana dashboard and the §9.5 alert rules (`docker compose --profile observability`).
- **Production on one VM:** Caddy with automatic HTTPS; encrypted nightly backups to Backblaze B2 with a verified restore; `deploy.sh <sha>` for rolling deploys with no lost edits; a daily summary.
- **21 postmortems** ([`production/incident-reports/`](../production/incident-reports/README.md)): the eight §8 drills and thirteen real bugs.

Code: github.com/sohans1092004/concord.

## Architecture

Postgres is the system's memory, and the board's owner is its only writer. An edit is committed before anyone is told about it, and Redis carries only what can be lost.

```
 browser / client-core ── WebSocket ──▶ gateway ──── internal WebSocket ────▶ Session instance
   IndexedDB queue                     (2 locally,                           (3 locally, 2 on the VM)
   optimistic local merge               1 on the VM)                          one BoardActor per board
        ▲                                 │  ▲                                    │        │
        │ OP_BROADCAST                    │  │ Pub/Sub board:{id}                 │commit  │publish
        └─────────────────────────────────┘  └──────────── Redis ◀───────────────┘        │
                                         REST API            leases, owner keys,     ┌──────▼──────┐
                                         (sign-in, boards,   fence counters,         │  Postgres   │
                                          history, tickets)  presence, Pub/Sub       │ board_ops,  │
                                                                                      │ snapshots,  │
                                                                                      │ boards.fence│
                                                                                      └─────────────┘
```

### The life of one edit

1. **Local first.** The client stamps the op with its HLC, appends it to an append-only IndexedDB log and commits that, then shows it, then sends it (§7.2). A closed tab or a crash after step 1 loses nothing.
2. **Gateway.** The gateway checks the frame (64 KB cap, rate limit 20 ops/s per connection), finds the board's owner (a 5s cache, then `board:owner:{id}` in Redis, then rendezvous hashing) and forwards it. One connection's requests go one at a time, in arrival order (incident 011).
3. **Admission.** The owner's actor deduplicates (an LRU of the last 10,000 op_ids, then the primary key), validates the op's shape, role, clock and references, and adds it to the current batch.
4. **Commit, then everything else.** After the 5ms group-commit window (ADR-005), one transaction does three things:
   - drops op_ids already stored;
   - checks the fence: `UPDATE boards SET version = version + n WHERE fence = :fence`;
   - inserts the ops.

   Only after `COMMIT` does the actor apply them to memory, ACK them, and `PUBLISH` them on `board:{id}`. A commit with an unknown outcome stops the board until it has reconciled from Postgres (incident 010).
5. **Fan-out.** Every gateway with a client on the board receives the broadcast and forwards it. Each carries the board version, so a client that misses one sees the gap and asks for a sync. A 5s `BOARD_VERSION` heartbeat catches a quiet loss (ADR-006).
6. **Merge.** Every client applies the op with the same merge as the server. Merge commutes, so arrival order does not matter. The sender drops the op from its queue on the ACK.

### Ownership

Exactly one Session instance owns a board at a time (ADR-004, ADR-007).

- **Claim:** draw a fence (a Redis counter that never goes below `boards.fence + 1`), take the lease (`SET NX`, 15s, renewed every 5s), write the fence to Postgres, **then** load the newest valid snapshot and replay the tail (incident 009).
- **Every commit checks** `fence = :fence`, so a stale owner, paused or cut off from Redis, commits nothing (drill 003).
- **A graceful handover** (deploy, idle board) flushes, snapshots and releases the lease. A crash waits for the lease to expire.

## Requirements, point by point

### Functional (SPEC §1.1)

| Requirement | How Concord does it | Proof |
|---|---|---|
| Create boards; invite members as owner, editor or viewer | REST: `POST /api/boards`; `POST/PATCH/DELETE /api/boards/{id}/members` (owners only). The owner checks the role of every op through a 30s membership cache, refreshed at once over the `membership` channel | `ApiTest`, `WritePathTest` (revocation); Playwright `board.spec.ts` |
| Ordered lists of ordered cards | Fractional string keys, ties broken by entity ID (ADR-001); one placement register per card (ADR-002) | Property tests in both cores; vectors 005–046 |
| Concurrent create, edit, reorder, move, delete of cards and lists | OR-Set existence with per-entity tombstones; delete wins over a concurrent edit; restore adds a new tag; cards of a deleted list shown in Orphaned (ADR-003) | 55 conformance vectors; permutation fuzz (500 seeds per commit, 100,000 nightly); differential fuzz TS against Java |
| Offline edits merge on reconnect, no conflict dialog | The queue is resent in order, paced to the gateway's limit (amendment 31); merge is deterministic; a lost move shows "moved by {name}" | Scripted offline hour (240 edits, converged 11.5s after reconnect); 8h and 24h accelerated (§10.1) |
| History: who changed what, when, for 30+ days | `GET /api/boards/{id}/history` over `board_ops` (each op stores its `user_id`); op retention keeps 30 days and never deletes above the oldest kept snapshot (amendment 28) | `ApiTest`, `RetentionTest`, guard checks `retention-*` |
| Presence: who is viewing, which card is open | Gateway-only, in Redis: `active`/`idle`, the card open; snapshot after JOIN, `left` on disconnect and from a 10s sweep (amendment 16) | `PresenceTest`; guard checks `presence-*` |

### Non-functional (SPEC §1.2), measured on the development laptop

| Target | Spec | Measured | Verdict |
|---|---|---|---|
| Convergence | Identical state, any order | Byte-identical canonical state across both cores in every vector and fuzz run; every drill, load test and live deploy check ends with all clients on one SHA-256 | **Met** |
| Durability | No ACKed op lost | 0 ACKed ops missing in every drill, load test, soak and live deploy (7 deploys under live editing, 1,834–2,797 ops each) | **Met** |
| Sync latency | p95 < 200ms, p99 < 500ms at 2–10 editors | p95 32–45ms, p99 33–94ms (laptop); from India to Sydney, p95 315–354ms, of which the server is about 17ms | **Met on the server; missed end to end** from a distant region |
| Editors per board | 30, correct; latency recorded | p95 42ms at 30; 3,756 ops, all converged, 0 missing | **Met** |
| Cards per board | 2,000, render < 16ms per update | p50 4.8ms, p99 ≤13.8ms; max 19.9 / 26.3ms (2 of ~600 per run) | **Not met** (≈0.3% of updates) |
| Offline window | 24 hours | 5,701 edits from 24 accelerated hours synced in 283s, 0 lost | **Met** (accelerated, after amendment 31) |
| Owner crash recovery | Serving ≤5s after lease expiry | 1,565ms; claims 29–342ms even at 2,000 cards with a 499-op tail | **Met** |
| Concurrent connections | ~1,000 on one machine | 1,000 on 300 boards, 890 of 900 ops/s committed, p95 294ms | **Met for connections; latency above target** |
| Fleet scale | 10,000 boards, ~50,000 connections | Design target only: owners spread by rendezvous hashing, boards unload when idle, nothing global on the hot path | Argued, not measured |

## Components

### The CRDT core (`crdt-ts/`, `crdt-java/`, spec §3–4)

| Module | What it decides | Key choices |
|---|---|---|
| `hlc` | Op timestamps | 64-bit physical ms + counter + replica ID; ops more than 60s ahead of the server are refused (`CLOCK_SKEW`), and the client corrects its offset |
| `orset` | Which lists and cards exist | Tags are the `op_id`s of adds and restores; tombstones per entity, so a delete can only touch the entity it names (vectors 051–054) |
| `lww` | Every attribute, including placement | HLC order; an exact tie is broken on canonical bytes and counted |
| `keys` | Order within a list | Greenspan fractional indexing, credited port; equal keys re-keyed; rebalance past 40 characters |
| `board` | The composition, the view, Orphaned | Canonical JSON encoding (RFC 8785, integers only), so equal states have equal SHA-256 |

### Server processes (`server/`)

| Process | Job | How it does it |
|---|---|---|
| gateway (`:8080`) | WebSockets, REST API, fan-out, presence | Single-use 30s tickets for the WebSocket URL; per-connection token bucket; forwards to owners over an internal WebSocket with a shared secret; Pub/Sub fan-out with a 1s polling fallback; a 1s watchdog asks the preferred instance to claim any board with no owner; the daily op retention job |
| Session instance (`:7000`) | Board owners | One `BoardActor` per board: a mailbox drained by one virtual thread; dedup, admission, 5ms group commit, apply-after-commit, reconcile, snapshots every 500 ops or 60s (ADR-008); lease renewal by Lua compare-and-extend; unload after 10 minutes idle; drop a deleted board at once |
| migrate | Schema | Forward-only versions; the deploy runs it before replacing anything |
| backup | Nightly encrypted backup | `pg_dump`, a manifest of every board's state hash, `age` encryption, upload to B2 at 03:00 UTC, keep 14 days; restore verifies each board by replay against the manifest |

### Client (`client-core/`, `web/`)

| Part | Job |
|---|---|
| `SyncClient` | The connection and the queue: JOIN with `since_version`, in-order paced resend, head-of-queue `INVALID_OP`, 10s JOIN timeout, 15s silence drop, backoff with full jitter, clock correction with a fresh replica after a skew, version-gap sync, presence, telemetry |
| `IndexedDbStorage` | One replica per tab; an append-only op log committed before an edit is shown; snapshots coalesced about once a second and on `pagehide`; a Web Lock adopts the queue of a closed tab |
| `web/` | React board, drag and drop, "moved by", trash and restore, Unsent edits, history, members, in-app bug reports with diagnostics attached (secrets redacted) |

## Data model

Postgres holds everything durable in eight tables. Redis holds only state that can be lost.

| Table | Holds | Notes |
|---|---|---|
| `users`, `sessions`, `ws_tickets` | Accounts (argon2id passwords), session and ticket hashes | Only SHA-256 hashes of tokens are stored |
| `boards` | Title, `version`, `fence` | `fence` is the Postgres side of ownership: every commit checks it |
| `board_members` | Role per user per board | Changes are announced on the `membership` channel |
| `board_ops` | Every committed op with its version and `user_id` | The history and the source of every recovery; retention trims what is at or below the oldest kept snapshot and older than 30 days |
| `board_snapshots` | The canonical state at a version, with its SHA-256 | Three kept per board; a hash mismatch falls back to the one before |
| `bug_reports` | Trial bug reports with client diagnostics | Tokens and tickets are redacted before storage |

**Redis keys:** `board:lease:{id}`, `board:owner:{id}` and `board:fence:{id}`; presence sets; and the `board:{id}`, `membership` and `board-deleted` channels. If Redis is wiped, fence counters rebuild from `boards.fence` and leases are reclaimed; nothing durable is lost (drill 006).

## Failure handling

All 15 failure scenarios in §8 are handled. The eight marked "drill" were induced on purpose and written up; every drill was rerun on the current code on 2026-10-18 and runs in CI on every push.

| # | Failure | Handling | Verified by |
|---|---|---|---|
| 1 | Gateway crashes | Clients reconnect to another gateway and resend | e2e test; [001](../production/incident-reports/001-gateway-crash.md) |
| 2 | Owner crashes | Lease expires; next instance claims within 5s of expiry | kill-owner drill: 1,565ms; [002](../production/incident-reports/002-owner-crash.md) |
| 3 | Two owners at once | Postgres fence rejects the stale owner's commits | pause-owner drills: 0 stale commits; [003](../production/incident-reports/003-stale-owner.md) |
| 4 | Postgres down | No ACKs; `RETRY_LATER`; clients keep editing locally | 0 ACKs while down, 0 lost; [004](../production/incident-reports/004-postgres-down.md) |
| 5 | Redis down | Owners keep serving; gateways poll owners | 268 ACKs during 15.8s down; [005](../production/incident-reports/005-redis-down.md) |
| 6 | Redis wiped | Fence counters rebuild from Postgres | forced claim gets `boards.fence + 1`, no retry; [006](../production/incident-reports/006-redis-wiped.md) |
| 7 | Pub/Sub message lost | Version gap → `SYNC_REQUEST` | e2e test: 8 dropped, 8 syncs, converged |
| 8 | Client offline 24 hours | Snapshot or op range on reconnect; queue resent, paced | 24h accelerated: 5,701 edits, 0 lost |
| 9 | Same op sent many times | LRU or primary-key dedup; original ACK returned | e2e and server tests |
| 10 | Client clock far ahead | `CLOCK_SKEW`; client corrects and re-stamps | e2e test |
| 11 | Access revoked while offline | Ops rejected into Unsent edits, never silently dropped | server and client tests |
| 12 | Malformed or malicious op | Rejected before persist; logged with user and reason | e2e and server tests |
| 13 | Snapshot corrupt | Hash mismatch → previous snapshot + longer tail | `SnapshotTest`; `RetentionTest` with two snapshots corrupted |
| 14 | Reconnect storm | Client jitter (no rate limit needed: amendment 30) | 500 clients back in 3.5–16.2s; [007](../production/incident-reports/007-reconnect-storm.md) |
| 15 | Rolling deploy | Gateway drains with `RECONNECT_ELSEWHERE`; owners hand over | 7 deploys under live editing, 0 lost; [008](../production/incident-reports/008-rolling-deploy.md) |

### Real bugs, and how each was found

| # | Bug | Found by |
|---|---|---|
| [009](../production/incident-reports/009-claim-order-lost-acked-ops.md) | The spec's claim order could lose an ACKed op | Reading the spec |
| [010](../production/incident-reports/010-commit-with-unknown-outcome.md) | No rule for a commit with an unknown outcome | Reading the spec |
| [011](../production/incident-reports/011-gateway-reordered-ops.md) | The gateway's `CompletableFuture`s could reorder a client's ops | Reading the code |
| [012](../production/incident-reports/012-retries-out-of-order-after-owner-crash.md) | Per-op retry timers sent edits out of order after an owner crash | kill-owner drill |
| [013](../production/incident-reports/013-websocket-never-closed-offline.md) | Chromium's WebSocket never closed when the network went away | Browser offline test |
| [014](../production/incident-reports/014-stale-owner-retakes-lease-after-redis-wipe.md) | A stale owner could re-take its lease after a Redis wipe | Writing up §6.3 |
| [015](../production/incident-reports/015-the-fencing-test-could-not-fail.md) | The fencing test could not fail | A remove-the-guard check |
| [016](../production/incident-reports/016-soak-heap-growth-by-design.md) | Heap grew with every board ever loaded | 2-hour memory soak |
| [017](../production/incident-reports/017-arm64-ci-deadlock.md) | CI deadlocked on arm64 in a test helper | CI thread dump |
| [018](../production/incident-reports/018-board-deleted-while-loaded.md) | A board deleted while loaded | The live host's logs |
| [019](../production/incident-reports/019-metrics-public-on-the-trial-host.md) | `/metrics` public on the live host; two deploys did not close it | Building observability; a deploy check |
| [020](../production/incident-reports/020-telemetry-report-over-the-cap.md) | A telemetry report one item over the gateway's cap | Unit test |
| [021](../production/incident-reports/021-offline-queue-drained-slowly.md) | A day's offline queue took hours to sync | The §10.1 offline measurement |

**The remove-the-guard checks.** Every safety guard has a check that removes it, runs the test that protects it, and requires that test to fail, then restores it and requires it to pass (`server/tools/guard_checks.py`, results in `guard_checks.out`).
- There are 42, from the fence condition in the commit to the client's send pacing.
- Two tests could not fail the first time they were checked ([015](../production/incident-reports/015-the-fencing-test-could-not-fail.md)), and the check is what found it.

## Performance

Every §10.1 row is recorded with its command and raw output ([`production/load-tests/README.md`](../production/load-tests/README.md)).

**Sync latency** is measured exactly: every client runs in one process, so submit and apply times share one clock.
- 2 editors: p95 32ms.
- 30 editors on one board: p95 42ms. It never crosses 200ms from 2 to 30.

Group commit absorbs the growth: commits per second level off around 13–16 while ops per commit rise.

**The group-commit window** (ADR-005):

| Window | p50 | Ops per commit |
|---|---|---|
| 0ms | 18ms | 1.00 |
| **5ms** (chosen) | **24ms** | **2.55** |
| 15ms | 35ms | 2.53 |
| 30ms | 49ms | 1.80 |

**Where it bends:** 1,000 connections on 300 boards.
- Throughput held: 890 of 900 ops/s committed.
- p95 rose to 294ms.
- Postgres was the busiest process, peaking at 6.8 cores for about 300 commits/s, one per board per second: group commit merges ops within a board, never across boards.
- The Session instances stayed under a core each on average.

**Recovery** (ADR-008):
- A claim takes 29–342ms, even for a 2,000-card board (a 942 KB snapshot) with a full 499-op tail.
- A user waits 13–20s after an owner dies: that is the 15s lease plus backoff.

**Offline** (amendment 31): a day of edits (5,701, accelerated) syncs in 283s, at the gateway's 20 ops/s. Before the fix it did not finish in 10 minutes.

**The large board:** a 2,000-card board renders a typical update in under 5ms, and 99% under 14ms. About 1 in 300 takes 16–26ms, with no long task behind it. That misses the "every update" target.

## Running it

- **Laptop:** `docker compose -f deploy/docker-compose.yml up -d --build` runs 2 gateways, 3 Session instances, Postgres, Redis and Toxiproxy (each Session reaches Redis through its own proxy, so a drill can cut one). `cd web && npm run dev` serves the app.
- **Observability:** `docker compose -f deploy/docker-compose.yml --profile observability up -d` adds:
  - Prometheus, with the 7 §9.5 alert rules;
  - Grafana, with the provisioned 12-panel "Concord overview" dashboard;
  - Jaeger, with traces when `CONCORD_OTLP_ENDPOINT` is set.
- **Production** (`deploy/prod/`):
  - **Host:** an AWS t4g.small (2 GB) in ap-southeast-2. Caddy terminates TLS (Let's Encrypt) and never serves `/metrics`.
  - **Services:** 1 gateway, 2 Session instances, Postgres, Redis and the backup job, with memory limits summing to 1,336 MiB.
  - **Images:** CI builds them for amd64 and arm64 and publishes them to GHCR tagged by commit; the VM only pulls.
  - **`deploy.sh <full sha>`** refuses unless the checkout is at that SHA. It then:
    1. takes a backup;
    2. pulls the images;
    3. migrates;
    4. replaces session1, session2, then the gateway, each after the previous one is healthy;
    5. reloads Caddy;
    6. checks `/healthz` and that `/metrics` is closed;
    7. prints the rollback command.
  - **`summary.sh`:** the daily view of errors, fence rejections, sync latency, commit latency and backups.

### Observability (spec §9.5)

- **Metrics:** Prometheus text on `/metrics/prometheus` of every service:
  - commit latency, batch size, ops/s per board, rejections by code, duplicates, LWW ties, claims, fence rejections, version gaps, connections, snapshot size and time, recovery time, boards loaded;
  - client-reported sync latency through `POST /api/telemetry` (signed in, one report per 10s per user, at most 100 items).
- **Traces:** OpenTelemetry, one trace per edit, trace ID = the op's `op_id`, so nothing new travels on the wire. Seven spans: `client.send` → `gateway.forward` → `owner.admit` → `owner.commit` → `owner.broadcast` → `gateway.deliver` → `peer.render`. An end-to-end test asserts all seven in Jaeger.
- **Logs:** one JSON object per line, with `board_id`, `op_id` and `user_id` where known. Access tokens, tickets and session cookies never appear (redacting encoder, tests, guard check).
- **Alerts:** service down; sync p95 > 200ms for 5 minutes; commit failures; any fence rejection; claim churn; version-gap rate; reconnect storm.

### Tests and CI

- **CRDT cores:**
  - 109 TypeScript tests (Vitest and fast-check) and 109 Java (JUnit and jqwik): merge laws per module, the 55 conformance vectors, and a permutation fuzz of 500 seeds per commit.
  - A nightly job runs 100,000 seeds per core and 5,000 differential scenarios.
- **Server:** 61 tests against real Postgres 16, Redis 7 and Toxiproxy (Testcontainers): the write path, fencing, reconcile after a lost COMMIT reply, snapshots, idle unload, deleted boards, retention, backup and verify, the REST API, presence, telemetry, log redaction.
- **Client:** 35 unit tests against a scripted fake gateway; 7 end-to-end tests against the compose stack (1,000-op convergence across gateways, §8 rows 1, 7, 9, 10 and 12, traces); 7 drills.
- **Browser:** 19 Playwright tests in Chromium: board editing, drag and drop, "moved by", offline and reload, two tabs, bug reports, the CSP, edit-to-screen latency, and the 2,000-card render budget (strict on the laptop, a looser regression bound in CI, amendment 20).
- **Remove-the-guard checks:** 42, recorded in `server/tools/guard_checks.out`.
- **GitHub Actions on every push:** both cores (Node 20 and 22, Java 21), the vectors check, differential fuzz, the server suite, client unit tests, the end-to-end tests and every drill against the compose stack, Playwright, and the server suite plus production images natively on arm64. Images are published only after all of it passes.

## Decisions, deviations and what was left out

Ten ADRs in `docs/adr/`. Thirty-two amendments to the spec are recorded with their date, section and evidence in `docs/SPEC.md`. None reverses the spec's goals; most make a rule precise enough for two implementations to agree, or close a gap that a test, drill or measurement found.

| ADR | Decision | Matches spec? |
|---|---|---|
| 000 | Hand-roll the CRDTs; no Automerge or Yjs | Yes |
| 000b | Java 21 server, TypeScript client, a conformance suite between them | Yes |
| 001 | String fractional keys, ties by `(key, entity_id)`, interleaving accepted | Yes |
| 002 | One atomic placement register, not separate list and position fields | Yes |
| 003 | Delete wins over a concurrent edit; restore by a new tag; Orphaned list | Yes, made precise (per-entity tombstones) |
| 004 | One actor per board, which is also its writer | Changed: v3's per-instance writer for all boards was rejected for isolation |
| 005 | ACK only after commit; 5ms group-commit window | Yes; the value is measured (amendment 32) |
| 006 | Redis Pub/Sub, not Kafka, for fan-out | Yes |
| 007 | Fence in Postgres; owners keep serving while Redis is down | Changed: v3's claim order could lose an ACKed op (amendment 6) |
| 008 | Snapshot every 500 ops or 60s, keep three | Yes; retention bounded by the oldest kept snapshot (amendment 28) |

### Spec gaps closed during the build (from the amendments log)

- **Ownership:**
  - the claim order (6, 8);
  - unknown commit outcomes (6);
  - a stale owner's reads (12) and its lease re-take after a wipe (13);
  - every queue bounded (9).
- **Security:**
  - single-use WebSocket tickets instead of tokens in URLs (14);
  - session cookies instead of JWTs;
  - Origin checks; redacted logs.
- **Client behaviour:**
  - in-order resend and head-of-queue `INVALID_OP` (10);
  - the 15s liveness drop (18);
  - append-first saving (21);
  - paced resend (31).
- **Operations:**
  - idle unload (26) and deleted boards (27);
  - op retention (28) and observability (29);
  - no connection-rate limit (30) and the 5ms window (32).

### Smaller simplifications

- **One image for all server processes**, with the process chosen by command. The VM never builds; it pulls the image CI tested.
- **Presence lives only in Redis.** It is lost on a Redis outage, by design (§8 row 5).
- **No per-instance writer across boards (ADR-004).** The 1,000-connection measurement shows where this costs: one commit per board per second.

## Known limits

- **What the numbers mean.** Every measured number comes from one laptop running the services, Postgres, Redis and the load generator together, with no network between them. Treat them as where the bottlenecks sit, not as capacity. The real host adds the network: about 300ms of round trip from India to Sydney.
- **Many boards.** Postgres commits one transaction per board per batch. At 300 active boards that is about 300 commits/s, and p95 latency was 294ms on the laptop. More boards per instance would need commits merged across boards, which ADR-004 rejected.
- **The large board.** About 0.3% of updates on a 2,000-card board take 16–26ms to render on the laptop. What those renders do is not yet profiled.
- **Accelerated offline runs.** The 8h and 24h runs compress time with a clock offset. They do not exercise hours of real time on the connection, the browser or IndexedDB; only the 1-hour run does that.
- **One small host.** The trial runs everything on one 2 GB VM: no replica, no failover for Postgres, backups nightly. A lost VM is restored from the last backup, which loses up to a day of edits. A recovery-point objective tighter than that needs point-in-time recovery.
- **Fleet scale is argued, not measured** (§1.2): 10,000 boards and 50,000 connections cannot be shown honestly on one machine.

## Open gaps

- **Profile the large board's slow renders** (about 1 in 300 over 16ms) and fix them, or argue a looser bound with evidence. The test stays strict until then.
- **The trial's bug reports:** none have arrived. Each one that reveals a bug gets a postmortem in `production/incident-reports/`.
- **The manual offline hour** (`docs/manual-offline-run.md`) is for the project owner to do by hand; only the scripted hour is recorded.
