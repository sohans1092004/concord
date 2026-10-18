
## 10. Testing strategy

> **Each test layer exists to prove one kind of claim, and the remove-the-guard checks prove the tests themselves: every safety guard was deleted and its test was seen to fail.**

### 10.1 The layers, and what each proves

| Layer | Proves | How | Where |
|---|---|---|---|
| Property tests | the algebraic laws: merge is **commutative**, **associative** and **idempotent**; apply equals merging single-op states; HLC `tick` strictly increases; `keyBetween` lands strictly between | fast-check (TS) and jqwik (Java) over random states | e.g. [[crdt-ts/test/orset.test.ts#merge is commutative, associative and idempotent]], [[crdt-ts/test/board.test.ts#apply agrees with merge]] |
| Permutation fuzz | convergence under realistic concurrency | 3–5 simulated replicas with skewed clocks, 50–500 steps each, delivered in 8 random orders with about 20% duplicates; every replica must end byte-identical, with no LWW tie-break on valid ops | [[fuzz#const RUNS = Number(process.env.FUZZ_RUNS ?? 500);]], [[sim#export function scenarioArb(]] |
| Conformance vectors | the two cores agree with a *hand-written* expectation | 55 JSON cases with expected view, state and hash; the hashes come from a third implementation (Python), never from either core | `conformance/vectors/` |
| Differential fuzz | the two cores agree with *each other* | the TS fuzzer drives the Java core through a line-protocol CLI and compares state hashes, view hashes, verdicts and bytes | `conformance/runner/differential.test.ts` |
| Integration (Testcontainers) | the server's promises against real Postgres 16, Redis 7 and Toxiproxy | write path, fencing, reconcile after a lost COMMIT reply, snapshots, idle unload, deletion, retention, backup, REST, presence, telemetry, log redaction: 61 tests | `server/*/src/test` |
| Client unit | queue order, rejections, liveness, pacing, telemetry | a scripted fake gateway: 35 tests | `client-core/test/unit` |
| End to end | the whole stack | 1,000 random ops across two gateways converge; §8 rows 1, 7, 9, 10, 12; traces | `client-core/test/e2e` |
| Drills | the §8 failure promises | kill, pause, stop, wipe and storm against the compose stack | `deploy/drills/run-all.sh` |
| Browser (Playwright) | the product, in Chromium | editing, drag and drop, "moved by", offline and reload, duplicated tabs, bug reports, CSP, latency, the 2,000-card render budget: 19 tests | `web/e2e` |
| Soak | memory stays flat for 2 hours | heap after a full GC sampled every 5 minutes; the verdict needs a flat second hour | `deploy/soak/` (incident 016) |
| Load (§10.1) | the measured targets | `production/load-tests/run-all.sh` | section 11 |

**The permutation fuzz in detail.**
- **Op mix:** each step picks an op kind out of 100 ([[sim#if (kind < 6) {..} else {]]):

  | Kind | Share |
  |---|---|
  | add list | 6 |
  | add card | 20 |
  | move card | 20 |
  | reorder card | 10 |
  | set a field | 12 |
  | delete card | 10 |
  | restore card | 8 |
  | delete list | 5 |
  | restore list | 4 |
  | reorder list | 3 |
  | rename list | 2 |

- **Leaning on the hard cases:** one "hot" card takes most moves, edits, deletes and restores, and inserts favour the end of a list so replicas pick equal keys. Clock skews range from −120 s to +59 s per replica.
- **A second property forces same-gap inserts** until keys pass the 40-character rebalance mark.
- **On failure:** fast-check shrinks the scenario, delta debugging cuts it to a minimal op set ([[sim#export function minimize(]]), and the result is written to `.fuzz-failures/`, a candidate for a new vector.
- **Counts:** CI runs 500 seeds per commit; the nightly workflow runs 100,000 per core plus 5,000 differential scenarios (`.github/workflows/nightly.yml`). The last recorded nightly took 56 min (TS) and 39 min (Java) and was green (`docs/phases.md:94`).
- **Testing the fuzzer:** three deliberately injected bugs (arrival-order LWW, a delete that forgets unseen tags, an HLC that ignores its counter) were each caught within 200 runs (ADR-000).
- **Covering the hard cases:** the generator's coverage is asserted, so a test fails if it stops producing them (`crdt-ts/test/coverage.test.ts`).

### 10.2 The remove-the-guard checks

For each safety guard, `server/tools/guard_checks.py`:
1. replaces the guarded code with code that compiles and runs without the guard;
2. runs the named test and **requires it to fail**;
3. restores the file from git and **requires the test to pass**.

Drill-level checks rebuild the server image with the guard removed. Results are recorded in `server/tools/guard_checks.out`. All 42:

| Guard check | What was removed | Test that must fail | How it failed |
|---|---|---|---|
| `fence-check` | the fence condition in the commit `UPDATE` | `FencingTest.staleOwnerCannotCommitAfterANewOwnerClaims` | `stale owner ACKed: {type=OP_ACK …}` |
| `old-claim-order` | claim step 3, restoring v3's `fence <= :fence` | same | `stale owner ACKed` |
| `apply-after-commit` | apply-after-commit (applied before the transaction) | `WritePathTest.noAckBeforeCommit` | `memory must not change` |
| `dedup-backstop` | commit step 3.1 (drop stored op_ids) | `WritePathTest.dedupReturnsTheOriginalAckAndNeverRebroadcasts` | `RETRY_LATER … commit failed` instead of the ACK |
| `dedup-lru` | the LRU lookup | `WritePathTest.lruAnswersResendsWithoutTheDatabase` | `RETRY_LATER … storage unavailable` instead of the ACK |
| `retake-fence-check` | the `boards.fence` check before re-taking a lease | `FencingTest.staleOwnerDoesNotRetakeAWipedLease` | `a stale owner must not re-take the lease: expected 0 but was 2` |
| `fence-counter-rebuild` | the `boards.fence` floor in the fence Lua script | drill wipe-redis | `without a retry: expected 1 to be +0` |
| `fence-check-e2e` | the fence condition, end to end | drill pause-owner-no-redis | `the stale owner sent no ACK for the probe: expected 'OP_ACK' to be 'ERROR'` |
| `ticket-single-use` | deleting the ticket as it is read | `ApiTest.aTicketWorksOnceAndOnlyWithin30Seconds` | `a used ticket is refused`: nothing thrown |
| `ticket-expiry` | the 30 s expiry check | same | `a ticket older than 30s is refused`: nothing thrown |
| `rest-origin` | Origin checks on state-changing REST calls | `ApiTest.stateChangingCallsRequireAnAllowedOrigin` | expected 403, was 201 |
| `password-revokes-sessions` | ending other sessions on a password change | `ApiTest.changingThePasswordEndsTheUsersOtherSessions` | expected 401, was 200 |
| `members-owner-only` | the owner check on adding a member | `ApiTest.boardsMembersAndRoles` | expected 403, was 404 |
| `last-owner` | "a board keeps at least one owner" | same | `the last owner stays`: expected 409, was 200 |
| `history-members-only` | the membership check on history | same | expected 404, was 200 |
| `bug-report-redaction` | redacting secrets from bug reports | `ApiTest.bugReportsAreStoredWithSecretsRedacted` | the session token appeared in storage |
| `log-redaction-ticket` | redacting `ticket=` and session values in logs | `GatewayLogTest.tokensNeverReachTheLogs` | `secret in encoded output` |
| `trusted-proxy-only` | believing X-Forwarded-For only from Caddy | `ApiTest.forwardedForIsBelievedOnlyFromTheTrustedProxy` | `a forged X-Forwarded-For … must not dodge the per-IP limit` |
| `backup-verify-replay` | checking each board's replay against the manifest | `BackupTest.verifyPassesOnTheSameDataAndCatchesDamage` | `expected: <true> but was: <false>` (no problem reported) |
| `delete-user-heir` | promoting a member when the last owner is deleted | `ApiTest.deletingAnAccountRemovesItsDataAndKeepsSharedBoards` | expected 200, was 503 |
| `idle-unload-not-reconciling` | never unloading while reconciling | `IdleUnloadTest.neverUnloadedWhileReconcilingAnUnknownCommitOutcome` | the actor was gone (null) |
| `idle-unload-subscribers` | keeping a subscribed board loaded | `IdleUnloadTest.anIdleBoardIsUnloadedAndARejoinConverges` | `a board with a subscriber stays loaded` |
| `board-deleted-commit` | telling deleted from fenced on zero rows | `BoardDeletedTest.anEditCommittingAfterTheDeletionIsRefusedNotAFenceAlert` | `NOT_OWNER … fenced out` instead of `UNAUTHORIZED` |
| `board-deleted-reconcile` | ending reconcile on a deleted board | `BoardDeletedTest.reconcilingOnADeletedBoardEndsInsteadOfRetryingForever` | not dropped within 15 s |
| `board-deleted-announced` | dropping a board on the announcement | `BoardDeletedTest.anAnnouncedDeletionDropsTheBoardAtOnceWithNoSnapshot` | not dropped within 15 s |
| `retention-below-snapshot` | retention's bound by a snapshot | `RetentionTest.deletesOnlyOldOpsAtOrBelowTheOldestKeptSnapshot` | deleted 1,500 ops, expected 500 |
| `retention-oldest-snapshot` | bounding by the *oldest* kept snapshot | same | `only ops 1..500: the oldest kept snapshot, not the newest` |
| `presence-sweep` | removing presence entries older than 30 s | `PresenceTest.sweepRemovesStaleEntriesAndAnnouncesEachConnectionThatWent` | the stale entry was still in the set |
| `presence-joined-board` | PRESENCE must name the joined board | `PresenceTest.presenceFlowsWithTheGatewaysPostgresCutOffAndStaysOnItsBoard` | `no matching message within 10s` |
| `telemetry-size` | the 100-item cap | `ApiTest.telemetryIsBoundedAndRateLimited` | `{accepted=101}`: expected 413, was 200 |
| `telemetry-rate` | one report per 10 s | same | `the third report within 10s`: expected 429, was 200 |
| `telemetry-client-cap` | the client's pre-flush before two items | `client-core/test/unit/features.test.ts` | `expected false to be true` (a report over 100 items) |
| `client-send-pacing` | the client's send pacing | `client-core/test/unit/client.test.ts` | `the burst goes out at once, no more: expected 200 to be 50` |
| `trace-op-id` | keying the commit span by `op_id` | `client-core/test/e2e/trace.test.ts` | the trace had 6 span names instead of 7 |
| `equal-key-rekey` | re-keying equal neighbours before an insert | `client-core/test/unit/features.test.ts` | `expected [ADD_CARD] to equal [SET_PLACEMENT, ADD_CARD]` |
| `silence-drop` | the 15 s silence drop | same file | `expected 1 to be 3` (no reconnect) |
| `offline-event` | reacting to the browser's `offline` event | same file | `listeners.offline is not a function` |
| `moved-by` | detecting a lost concurrent move | `web/e2e/moved.spec.ts` | `expect(received).toEqual(expected)` |
| `append-before-view` | appending before showing | `web/e2e/offline.spec.ts` | `expect(locator).toBeVisible() failed` |
| `presence-per-connection` | presence keyed by connection | `web/e2e/board.spec.ts` | `expect(locator).toHaveText(expected) failed` |
| `duplicate-tab-lock` | the Web Lock on the remembered replica ID | `web/e2e/offline.spec.ts` | `expect(received).not.toBe(expected)` (the two tabs' replica IDs) |
| `adoption-lock` | taking a lock before adopting a queue | `web/e2e/offline.spec.ts` | `expect(received).toEqual(expected)` |

**Where a guard check exposed a test that could not fail.**
- **`old-claim-order`, first run:** `FencingTest` passed with the old order, because the new owner committed before the stale one woke (incident 015, `d48cfa2`).
- **`dedup-lru`, first run:** nothing failed, because the primary key still caught every duplicate. A new test cuts Postgres off, so only the LRU can answer.
- **The pause-owner drill:** it never exercised the fence (`stale_owner_fence_rejections: 0`), because the lease renewal stopped the stale owner first. The no-Redis variant and its probe op were added (`2cfb00c`, `c092c96`; incident 003).
- **The wipe-redis drill:** a retry made a broken counter rebuild look correct, so the drill now requires zero retries (amendment 11; incident 006).

The blog draft `docs/blog/remove-the-guard.md` tells this story.

### 10.3 Coverage

Measured on 2026-10-18 at `1230291` **[laptop]**:

| Core | Branches | Lines |
|---|---|---|
| crdt-ts `src/` (`npm run coverage`) | **94.62%** (475/502) | 98.94% (658/665) |
| crdt-java core (`./gradlew test jacocoTestReport`, excluding the differential-test CLI) | **87.37%** (595/681) | 98.30% (637/648) |

The server's coverage has **not been measured**: JaCoCo is configured only for `crdt-java`. Its evidence is the integration tests plus the 42 guard checks.

### 10.4 CI: every job, and what it gates

On every push (`.github/workflows/ci.yml`):

| Job | Runs | Gates |
|---|---|---|
| vectors are up to date | regenerates the vectors from their hand-written source and diffs | stale vectors |
| crdt-ts (node 20, node 22) | typecheck, unit and property tests, 55 vectors, 500 fuzz seeds, coverage | the TS core |
| crdt-java | the same in Java, JaCoCo | the Java core |
| differential fuzz (ts vs java) | TS drives the Java CLI | any disagreement between cores |
| server (Testcontainers) | 61 tests | the server |
| arm64 | the server suite and the production images on `ubuntu-24.04-arm` | the architecture the trial host runs |
| client-core (unit) | 35 tests | the client |
| end to end and drills | the compose stack, e2e tests, then all 7 drills | §8 promises |
| web (Playwright) | 19 browser tests; the render test's CI bound is p95 < 25 ms (amendment 20) | the product |
| publish images (amd64, arm64, manifest) | builds and pushes `ghcr.io/sohans1092004/concord-server` and `concord-backup`, tagged by SHA | **needs every job above** ([[ci#needs: [vectors, crdt-ts, crdt-java, differential, arm64, server, client-core, e2e, web]]]) |

A commit's images exist only if all of its tests passed, and the trial host only pulls images by SHA (section 12). Nightly: 100,000 fuzz seeds per core and 5,000 differential scenarios.
