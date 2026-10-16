# Concord: CRDT engine (Phase 1)

A hand-rolled CRDT engine for a collaborative kanban board, written twice, in TypeScript
(`crdt-ts/`, the client) and Java 21 (`crdt-java/`, the server), and proven to compute
byte-identical state. No networking, no storage: Phase 1 of [`docs/SPEC.md`](docs/SPEC.md).

```
crdt-ts/              TypeScript core + property tests + permutation fuzz (vitest, fast-check)
crdt-java/            Java core + the same tests (JUnit 5, jqwik) + a line-protocol CLI
conformance/vectors/  55 hand-written golden vectors, shared by both cores
conformance/tools/    build_vectors.py: expands the hand-written vectors and hashes them
conformance/runner/   differential fuzz: TypeScript generates, Java answers, any mismatch fails
docs/adr/             ADR-000, 000b, 001, 002, 003
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
| `cd crdt-ts && FUZZ_RUNS=100000 npx vitest run test/fuzz.test.ts` | a local run on 2026-10-16 was stopped by the host for low system memory; the 100,000-seed runs are done by the nightly workflow instead |

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
