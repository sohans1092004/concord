# Concord, phase by phase

The project README until Phase 4b: what each phase built and measured, how to run each suite, and
what the fuzz suite does and does not prove. The current overview is the top-level
[README](../README.md); the full account is [how Concord was built](how-concord-was-built.md).

A collaborative kanban board built on a hand-rolled CRDT engine, written twice, in
TypeScript (`crdt-ts/`, the client) and Java 21 (`crdt-java/`, the server), proven to compute
byte-identical state (Phase 1), running inside an actor-per-board distributed system that
never acknowledges an edit it has not durably stored (Phase 2). The spec is
[`docs/SPEC.md`](SPEC.md); Phase 2 is described [below](#phase-2-the-distributed-system).

```
crdt-ts/              TypeScript core + property tests + permutation fuzz (vitest, fast-check)
crdt-java/            Java core + the same tests (JUnit 5, jqwik) + a line-protocol CLI
conformance/vectors/  55 hand-written golden vectors, shared by both cores
conformance/tools/    build_vectors.py: expands the hand-written vectors and hashes them
conformance/runner/   differential fuzz: TypeScript generates, Java answers, any mismatch fails
server/               Phase 2, Java: common, session (board actors), gateway, devtools (dev only)
client-core/          Phase 2–3, TypeScript: the headless sync client (and IndexedDB storage), e2e tests and drills
web/                  Phase 3, React + Vite: the board UI over client-core; Playwright tests in web/e2e
deploy/               docker-compose.yml (2 gateways, 3 Session instances, Postgres, Redis), drills, soak/
deploy/prod/          Phase 4a: the trial deployment (Caddy, 1 gateway, 2 Session instances, backups), scripts
docs/adr/             ADR-000, 000b, 001–004, 006, 007
docs/walkthrough-phase2.md   one op from a client's send to every other client's view
docs/walkthrough-phase3.md   one click, from the screen to IndexedDB to the wire to another browser
docs/trial-guide.md          one page for the people trying it; docs/manual-offline-run.md, the by-hand hour
docs/runbook.md              Phase 4a: from a fresh VM to the trial, backups, restores, deploys, what to do when it dies
```

## What it guarantees

Given the same set of ops, delivered in any order and any number of times, every replica
reaches the same canonical state, byte for byte, and therefore the same view. Concretely:

- **Existence** is an OR-Set of add-tags with per-entity tombstones (SPEC §3.2). A delete
  naming X tombstones exactly the (X, tag) pairs it observed, so it can never remove another
  entity; a restore adds a fresh tag, so a restore concurrent with a delete survives.
- **Delete wins over a concurrent edit** (§3.3). Edits are kept and reappear on restore.
- **Attributes** are per-field LWW registers ordered by a hybrid logical clock
  `(physical_ms, counter, replica_id)` (§3.1, §3.4). Placement (`list_id` + `position_key`)
  is one atomic register, so concurrent moves never mix one writer's list with another's
  position (§3.5, ADR-002).
- **Cards of a deleted or not-yet-seen list** appear in a derived Orphaned list (§3.6).
- **Order** is `(position_key, entity_id)` with Greenspan fractional keys (§3.7, ADR-001).
- **Equal HLCs with different values** (only possible from a buggy client) resolve
  deterministically by unsigned canonical bytes and are reported as a collision count.
- `validateShape` and `checkClock` (the 60s skew guard) are pure and agree across languages.

API, identical in both languages: `hlc` (tick, receive, compare, encode), `op`
(validateShape, checkClock, newOpId), `orset`, `lww`, `keys` (keyBetween, keysBetween,
rebalance, planInsert), `board` (apply, merge, view, encode/decode, liveTags).

## How to run the tests

Requirements: Node 20+, JDK 21, Python 3 (vectors only).

```sh
# TypeScript: typecheck, unit + property tests, 55 vectors, 500 fuzz seeds
cd crdt-ts && npm ci && npm run typecheck && npm test
npm run coverage                                     # the same, with V8 branch coverage

# Java: the same, via Gradle (wrapper included)
cd crdt-java && ./gradlew test
./gradlew test jacocoTestReport                      # the same, with JaCoCo branch coverage

# Differential fuzz: build the jar, then let the TypeScript side drive it
cd crdt-java && ./gradlew jar
cd conformance/runner && npm ci && npm test          # needs crdt-ts/node_modules too

# Vectors: regenerate from the hand-written source, or check they are current
python conformance/tools/build_vectors.py [--check]
```

Knobs: `FUZZ_RUNS` (seeds, default 500) and `FUZZ_SEED` (replay) for both fuzzers;
`DIFF_RUNS`, `DIFF_SEED` for the differential run. CI (`.github/workflows/ci.yml`) runs all
of the above on every push; `nightly.yml` runs 100,000 seeds per core.

Counts from the last full local run (2026-10-16):

| Suite | Tests | Of which |
|---|---|---|
| crdt-ts (`npm test`) | 109 | hlc 6, canonical 7, op 10, keys 10, orset 6, lww 5, board 6, vectors 56 (55 + dir check), fuzz 2, generator coverage 1 |
| crdt-java (`./gradlew test`) | 109 | Hlc 7, Canonical 8, Op 11, Keys 8, merge laws 15 (orset 6, lww 4, board 5), vectors 57 (55 + dir + boundary check), fuzz 2, generator coverage 1 |
| differential (`conformance/runner`) | 4 | 500 scenarios / 174,834 op deliveries; 20,000 hostile ops; 20,000 Unicode values |

Fuzz runs recorded:

| Command | Result |
|---|---|
| `cd crdt-ts && npm test` (500 seeds + 50 rebalance seeds) | green: `fuzz: 500 scenarios, seed 1040129639, 128801 steps, 17.8s` |
| `cd crdt-java && ./gradlew test` (500 seeds + 50 rebalance seeds) | green: `fuzz: 500 seeds from 1, 139315 ops, 13.0s` |
| `cd conformance/runner && npm test` (`DIFF_RUNS=500`) | green: all state/view hashes, verdicts and bytes equal |
| nightly workflow (`.github/workflows/nightly.yml`), run [38936460363](https://github.com/sohans1092004/concord/actions/runs/38936460363) at `66237dd`, 2026-10-16 | green: crdt-ts 100,000 seeds (56m08s), crdt-java 100,000 seeds (38m48s), differential 5,000 scenarios (4m14s) |

Branch coverage from the last local run (2026-10-16; CI prints the same tables in each
job's summary):

| Core | Command | Branches | Lines |
|---|---|---|---|
| crdt-ts `src/` | `cd crdt-ts && npm run coverage` | 94.60% (473/500) | 99.24% (652/657) |
| crdt-java core | `cd crdt-java && ./gradlew test jacocoTestReport` | 87.59% (593/677) | 98.45% (636/646) |

The Java figure excludes the CLI (`cli/Main.java`), which only the differential run drives.
Lowest per file: `Json.java` at 74.80% branches (92/123), mostly error paths of the parser.

## What the fuzz suite proves, and what it does not

**Proves (to the extent of what it generated):**

- *Order independence and idempotence of apply.* 3–5 replicas each make ops from their own
  partial view, so most ops are concurrent; then every replica gets the missing ops in its
  own random order with ~20% duplicates, and two fresh replicas get everything. All end
  byte-identical, and equal to the merge of the pre-delivery states.
- *That apply and merge agree,* and the merge laws (commutative, associative, idempotent)
  for orset, lww and board over random states (property tests).
- *Cross-language identity.* Same state hash, same view hash, same validation verdict and
  same canonical bytes in TypeScript and Java on every generated input.
- *That the tests can fail.* Three injected engine bugs (arrival-order LWW; a delete that
  forgets tags it has not seen; an HLC that ignores its counter) were each caught within
  200 seeds and cut down to 2-op counterexamples. Two injected cross-language bugs
  (Orphaned rendered first; DEL escaped) were caught by the differential run.

**Does not prove:**

- *Anything it never generates.* Coverage is measured, not assumed, and a test fails if any
  hard case drops below 50 occurrences per 500 scenarios. Concurrent op pairs counted
  (`test/coverage.test.ts`, `sampleScenarios(500, 42)`; `CoverageTest`, Java seeds 1–500):

  | Hard case | TS pairs (scenarios) | Java pairs (scenarios) |
  |---|---|---|
  | concurrent moves of the same card | 32,518 (390/500) | 31,426 (377/500) |
  | delete vs concurrent edit | 5,451 (357/500) | 4,836 (330/500) |
  | delete vs concurrent restore | 3,558 (338/500) | 3,336 (334/500) |
  | concurrent equal-key inserts | 31,903 (500/500) | 33,134 (500/500) |

  The ~25% of scenarios that only insert into one gap account for the rest. Ordinary
  scenarios never pushed a key past 40 characters, so a separate same-gap property exists
  to exercise rebalancing.
- *Malformed deletes.* The fuzzers only emit deletes with the named entity's own tags; a
  delete naming X with Y's tags is covered by a property test in each core and vectors
  051–052.
- *Intent.* Convergence is not "the result the users wanted": the documented interleaving
  anomaly (vector 046) converges perfectly and still surprises a user.
- *Behaviour on invalid ops.* The fuzzers feed only ops that pass `validateShape`; hostile
  ops are covered by the validation differential and the vectors, not by convergence fuzz.
- *Anything about Phase 2:* durability, fencing, delivery and the network are out of scope.
- *Exhaustiveness.* Random testing finds bugs with probability, not certainty. The vectors
  pin the rules; the fuzzers search the space around them.

## Publishing `crdt-ts`

The package is ready to publish but has not been. `npm pack` copies the vectors in
(`prepack`), so the packed tarball runs its whole suite standalone; this was checked by
packing, extracting outside the repo, `npm install` and `npx vitest run` (108 passed, 2026-10-16).

## Phase 2: the distributed system

One Session instance owns each board at a time and is the only place its merge runs
(`server/session`, ADR-004); gateways are stateless and forward to it (`server/gateway`);
Postgres is the only source of truth; Redis carries leases, owner lookup and fan-out and
can be wiped (ADR-006, ADR-007). Start with [`docs/walkthrough-phase2.md`](walkthrough-phase2.md),
which follows one op through the code with file:line references.

```sh
docker compose -f deploy/docker-compose.yml up -d --build   # the whole stack
(cd server && ./gradlew test)                               # unit + integration (Testcontainers)
(cd client-core && npm ci && npm test)                      # client unit tests
(cd server && ./gradlew :devtools:installDist)              # then, with the stack up:
(cd client-core && npx vitest run test/e2e)                 # convergence + §8 scenarios
deploy/drills/run-all.sh                                    # every drill, one command
```

`devtools` (seed users, boards and members with session tokens; list bug reports) is
dev-only: it refuses to run unless `CONCORD_ENV=dev`, and it needs direct database access.
Seeded users have no password, so they cannot sign in through the API.

### Libraries beyond those the brief named

- **PostgreSQL JDBC driver** (`org.postgresql:postgresql`): plain JDBC needs a driver.
- **Logback** (`logback-classic`): the SLF4J backend; its built-in `JsonEncoder` writes the JSON log lines, so no extra encoder library.
- **Testcontainers modules `postgresql` and `toxiproxy`**: typed Postgres container, and Toxiproxy to cut the Postgres network after COMMIT (amendment 6 B test) and Redis for one gateway (polling test).
- **JUnit 5**: already the Phase 1 test framework.
- **BouncyCastle** (`bcprov-jdk18on`, Phase 3): argon2id password hashing in pure Java; the JDK has no argon2, and the alternative (`argon2-jvm`) needs a native library in the image.
- Gateway → owner links and test clients use the JDK's `java.net.http.WebSocket`; client-core uses the platform `WebSocket` (Node 22, browsers). No WebSocket library.

### Exit criteria (SPEC §10, Phase 2), measured

| Criterion | Command | Result |
|---|---|---|
| Two clients on different gateways converge over ≥ 1,000 random ops (moves, deletes, restores) | `cd client-core && npx vitest run test/e2e/convergence.test.ts` (2026-10-16T17:49:29Z, seed 972694) | 1,000 ops submitted, 1,000 ACKed, board version 1,000; both clients and a fresh third replica on state hash `2e478538…f193`; 245 SET_PLACEMENT, 112 DELETE_CARD, 108 RESTORE_CARD, 25 DELETE_LIST; 0 Unsent edits |
| Killing an owner mid-load: new owner serving within 5s of lease expiry; every ACKed op in `board_ops` | `deploy/drills/kill-owner.sh` | serving 210, 1,474, 417, 287 and 1,221 ms after lease expiry in five runs (2026-10-16; the last from `run-all.sh`); 0 ACKed ops missing in all five. The first two runs failed the drill's no-Unsent-edits check (the client retry-order bug, fixed in `5a2a495`); the last three passed |
| The fencing test fails when the fence check is removed | `cd server && python tools/guard_checks.py` | fails without it (`stale owner ACKed: {type=OP_ACK, … version=3}`), passes with it; end to end, the pause-owner-no-redis drill fails without it (the stale owner ACKs the probe); the wipe-redis drill fails without the fence-counter floor; output in `server/tools/guard_checks.out` |
| `docker compose up` gives the full stack; one command runs every drill | `deploy/drills/run-all.sh` (2026-10-16T18:04:16Z–18:08:05Z, commit `21ba709`) | all five drills passed; output in `deploy/drills/results.txt` |

Drills (`deploy/drills/run-all.sh`, the run above). Each asserts that every ACKed op is in
`board_ops`, that 4 clients on both gateways and a fresh one converge to one state hash, and
that no edit ends in Unsent edits:

| Drill | Fault | Measured |
|---|---|---|
| kill-owner | `docker compose kill -s SIGKILL` the owner mid-load | lease had 10,587 ms left; new owner serving 1,221 ms after expiry; first ACK 11.9 s after the kill; 297 ops, 0 missing |
| pause-owner | `docker compose pause` the owner 20.5 s, then unpause | new owner after 16.2 s; 0 commits by the stale owner after the pause (it dropped the board when its lease renewal found the new owner); 684 ops, 0 missing |
| pause-owner-no-redis | cut the owner's Redis route (Toxiproxy), pause it past its lease, unpause it still without Redis; a probe op is delivered only to it | its lease renewal cannot save it: it serves the probe, the Postgres fence rejects the commit, it answers `NOT_OWNER` (no ACK), logs `ALERT fenced out by a newer owner`, and stores nothing (run of 2026-10-16T22:10Z, `deploy/drills/results.txt`) |
| stop-postgres | `docker compose stop postgres` for 10.6 s | 0 ACKs while down; up to 50 pending ops per client; first ACK 8.0 s after Postgres started; 622 ops, 0 missing |
| stop-redis | `docker compose stop redis` for 15.8 s | owners kept serving: 260 ACKs and 284 versions committed while Redis was down; clients 11–16 versions behind at the end of the outage (gateways polling owners); 569 ops, 0 missing |
| wipe-redis | `redis-cli FLUSHALL`, then kill the owner to force a claim | owner key back after 2.6 s (lease re-taken, fence unchanged); next claim got fence 2 = `boards.fence` + 1 from the rebuilt counter; 453 ops, 0 missing |

These are single runs on one laptop (Docker Desktop, 20 CPUs, 8 GB VM), not capacity
numbers; Phase 4 measures latency and throughput properly.

## Phase 3: the product

Sign-in and boards over REST (in the gateway process), the board UI in React on top of
client-core, IndexedDB storage with one replica per tab, offline editing, Unsent edits,
history, presence, and in-app bug reports for the trial. Start with
[`docs/walkthrough-phase3.md`](walkthrough-phase3.md).

```sh
docker compose -f deploy/docker-compose.yml up -d --build   # the stack (the gateways serve /api too)
(cd web && npm ci && npx playwright install chromium)
(cd web && npm run dev)                                     # the app on http://localhost:5173 (proxied to gateway1)
(cd web && npx playwright test)                             # builds, serves on :4173, runs every browser test
OFFLINE_HOUR=1 npx playwright test e2e/offline-hour.spec.ts # (in web/) the scripted real-time hour
CONCORD_ENV=dev java -cp 'server/devtools/build/install/devtools/lib/*' dev.concord.devtools.Dev bug-reports   # testers' reports
```

### Libraries added in Phase 3

- **React 18, Vite 6, `@vitejs/plugin-react`**: named in the brief.
- **`@dnd-kit/core`, `@dnd-kit/sortable`, `@dnd-kit/utilities`**: named in the brief; drag
  and drop with keyboard support.
- **Playwright**: named in the brief.
- **BouncyCastle** (server): argon2id, see Phase 2's list.

### Measured (2026-10-17, this laptop, Chromium 140 headless)

| What | Command | Result |
|---|---|---|
| Two browsers, one offline for 200+ edits with a reload, converge, nothing unsent | `cd web && npx playwright test e2e/offline.spec.ts` | 212 offline edits (2 through the UI, the rest through the same client-core calls the UI makes), including 24 card deletes, 29 moves and 14 restores before the reload; converged at board version 302, hash `7ab60ae6…f7a6f3`, 0 unsent on both |
| 2,000 cards, 10 editors at 1 edit/s each for 60s: render per update (client-core change → React DOM commit, `concord:update` measure) | `cd web && npx playwright test e2e/perf.spec.ts` | three runs: p50 4.7 / 4.8 / 4.7 ms, p95 8.2 / 8.3 / 8.2 ms, p99 10.4 / 10.7 / 11.4 ms, max 12.5 / 12.0 / 13.4 ms; 0 of 611, 612, 610 updates at or over 16ms; long tasks (>50ms) 0, 0, 1 (53ms) |
| Same, before the Phase 3 performance fixes (for comparison) | same, 20s | p50 56.6 ms, p95 83.9 ms, max 121.1 ms; 200 of 200 over 16ms |
| Same, in CI (GitHub runner, sharing its CPUs with the whole stack): a regression guard, p95 must stay under 25ms (amendment 20) | the `web` job | p50 7.7 ms, p95 15.0 ms, p99 17.2 ms, max 23.6 ms; 14 of 608 over 16ms (run on `6fe904b`) |
| Edit-to-screen latency (client-core call → text in the DOM), 100 title edits, 200-card board | `cd web && npx playwright test e2e/latency.spec.ts` | before amendment 21 (edit shown, then saved): p50 5.0 / 4.9 / 5.0 ms, p95 7.7 / 7.1 / 6.9 ms; after (appended to IndexedDB, then shown): p50 4.3 / 4.1 / 4.1 ms, p95 5.7 / 6.7 / 5.7 ms. Faster, because an edit used to trigger a full snapshot write and now writes one small log entry |
