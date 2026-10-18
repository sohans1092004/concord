# Concord

A collaborative kanban board where several people edit the same board at once, keep working offline for a day, and **never lose an edit or get asked which version to keep**.

Under it is a CRDT engine written twice, in TypeScript for the browser and Java for the server, proven to produce byte-identical state. Around that is a small distributed system that never tells a client an edit is saved until it is in Postgres.

**Status:** built, measured, and running a small invite-only trial at `concord-serve.duckdns.org` (AWS, Sydney). Feature-frozen; only fixes from the trial from here on.

## What it promises, and the evidence

| Promise | Evidence |
|---|---|
| **No acknowledged edit is ever lost** | 0 ACKed edits missing in every drill, load test, soak and live deploy: owner killed, owner frozen, Postgres down, Redis down or wiped, 500-client reconnect storm, 7 rolling deploys under live editing |
| **Every replica converges** | 55 hand-written conformance vectors; a permutation fuzz (500 seeds per commit, 100,000 nightly) in both languages; a differential fuzz TypeScript against Java; every test ends with all clients on one SHA-256 |
| **Changes show up fast** | Edit on one client to visible on another: p95 **32ms** with 2 editors, **42ms** with 30 (laptop). From India to the Sydney host: p95 ~315–354ms, of which the server is about 17ms |
| **A day offline is fine** | 24 accelerated hours of offline edits (5,701) sync in 283s, 0 lost. A real-time hour in two Chromium browsers: 240 edits, converged 11.5s after reconnecting |
| **Losing a server is fine** | A crashed owner is replaced 1.6s after its lease expires; a claim takes under 350ms even at 2,000 cards |

Two targets are missed, and both are written down where they are measured:
- **The large board:** about 1 update in 300 on a 2,000-card board renders in 16–26ms, against a budget of every update under 16ms.
- **Sync latency from far away:** missed end to end because of network distance; the server's share is about 17ms.

Every number: [`production/load-tests/`](production/load-tests/README.md).

## How it works, in one paragraph

Each board has exactly one **owner**, a Session instance, chosen by a lease in Redis. A **fence** in Postgres keeps a stale owner from committing anything.
- The owner validates each edit and **commits it to Postgres before** applying it, acknowledging it or broadcasting it. Edits arriving within 5ms share one commit.
- Gateways hold the browsers' WebSockets and fan broadcasts out over Redis Pub/Sub. Every broadcast carries a version, so a client that misses one notices and catches up.
- Clients edit locally first: each edit is saved to IndexedDB, shown at once, and sent. Their queue survives reloads, crashes and a day offline.
- Merging commutes, so the order edits arrive in never matters.

The full walkthrough is [how Concord was built](docs/how-concord-was-built.md).

```
browser (React + client-core) ──WebSocket──▶ gateway ──▶ Session instance (one actor per board) ──▶ Postgres
        ▲                                      │                     │
        └────────── broadcast ◀── Redis Pub/Sub ◀──────── publish ───┘   (Redis: leases, presence)
```

## Try it locally

Needs Docker, JDK 21 and Node 22.

```sh
docker compose -f deploy/docker-compose.yml up -d --build   # 2 gateways, 3 Session instances, Postgres, Redis
cd web && npm ci && npm run dev                              # http://localhost:5173: sign up, make a board
```

Open the board in two windows; turn the network off in one, keep editing, turn it back on.

Add `--profile observability` to the compose command for Prometheus (`:9091`), Grafana (`:3003`, a provisioned dashboard) and Jaeger (`:16687`).

## Tests

```sh
cd crdt-ts && npm ci && npm test                     # TypeScript core: 109 tests, vectors, fuzz
cd crdt-java && ./gradlew test                       # Java core: the same 109
cd server && ./gradlew test                          # 61 tests on real Postgres, Redis, Toxiproxy
cd client-core && npm test                           # 35 client unit tests
cd client-core && npx vitest run test/e2e            # end to end against the compose stack
deploy/drills/run-all.sh                             # the 7 failure drills
cd web && npx playwright test                        # 19 browser tests in Chromium
cd server && python tools/guard_checks.py            # 42 remove-the-guard checks
```

Every safety guard has a **remove-the-guard check**: it deletes the guard, requires its test to fail, and restores it. CI runs everything above (except the guard checks) on every push, on amd64 and arm64.

## Where to read more

| | |
|---|---|
| [`docs/SPEC.md`](docs/SPEC.md) | The specification, with 32 dated amendments, each with its evidence |
| [`docs/how-concord-was-built.md`](docs/how-concord-was-built.md) | Requirements point by point, components, failure handling, performance, limits |
| [`docs/adr/`](docs/adr/) | Ten decisions: hand-rolled CRDTs, fractional keys, actor per board, fence in Postgres, the 5ms commit window… |
| [`production/incident-reports/`](production/incident-reports/README.md) | 21 postmortems: the eight failure drills and thirteen real bugs |
| [`production/load-tests/`](production/load-tests/README.md) | Every SPEC §10.1 measurement, with commands and raw output |
| [`docs/walkthrough-phase2.md`](docs/walkthrough-phase2.md), [`phase3`](docs/walkthrough-phase3.md) | One edit followed through the code, with file:line references |
| [`docs/runbook.md`](docs/runbook.md) | Running the trial: a fresh VM, deploys, backups, restores, what to do when it breaks |
| [`docs/phases.md`](docs/phases.md) | The phase-by-phase notes this README used to hold |

## Layout

```
crdt-ts/  crdt-java/   the CRDT engine, twice        conformance/   shared vectors and the differential fuzz
server/                gateway, session, common      client-core/   the sync client, e2e tests, drills, load tests
web/                   the React app, Playwright     deploy/        dev compose, drills, soak; prod/ for the trial
production/            postmortems, measurements     docs/          spec, ADRs, walkthroughs, runbook
```
