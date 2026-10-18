
## 4. The CRDT design, rule by rule

> **The board is two observed-remove sets (which lists and cards exist) and one map of last-writer-wins registers (everything else), ordered by fractional keys; every rule exists to make merge order-independent while keeping what users meant.**

A **CRDT** (conflict-free replicated data type) is a data structure whose replicas can apply the same operations in any order, any number of times, and still end identical, with no coordination. Concord's board is one, built from two simpler ones ([[board#export class Board {]]):

| Part | CRDT | Holds |
|---|---|---|
| `lists`, `cards` | OR-Set (observed-remove set) | which entities exist |
| `registers` | LWW map: one last-writer-wins register per (entity, field) | titles, descriptions, assignees, labels, placements |

Merge is the union of the OR-Sets plus a per-register maximum, which is **commutative** (A merge B = B merge A), **associative** and **idempotent** (merging twice changes nothing). The property tests check these three laws for every module (`docs/SPEC.md` §9.1).

**Every example below was computed by running the real `crdt-ts` code** (`docs/tools/examples.ts`; run it with `cd crdt-ts && npx vite-node ../docs/tools/examples.ts`). In each, two replicas receive the same ops in different orders and end with byte-identical canonical state.

### 4.1 Hybrid logical clock (HLC)

**Problem.** Last-writer-wins needs "later". Wall clocks disagree between devices and can jump backwards. A plain counter (a Lamport clock) orders events but loses the link to real time, so "the later edit wins" would not mean later to a person.

**Rule.** An HLC is `(physical_ms, counter, replica_id)`, compared in that order ([[hlc#export function compare(]]):
- **`tick`** for a local edit takes `max(last.physical, now)`. If that equals the last physical time, the counter goes up; otherwise it resets to 0 ([[hlc#export function tick(]]).
- **`receive`** of a remote stamp takes the maximum of all three times, so the next local stamp is **strictly greater than anything seen** ([[hlc#export function receive(]]).
- The replica ID breaks the last tie, so no two replicas can ever produce equal stamps.

**Worked example** (`examples.ts`):

| Step | Replica | Clock before | Event | Clock after |
|---|---|---|---|---|
| 1 | A | 1000 / 0 / A | local edit at wall time 1000 | **1000 / 1 / A** (same ms, counter +1) |
| 2 | B | 990 / 0 / B | receives A's stamp 1005 / 2 at wall time 995 (B's clock is behind) | **1005 / 3 / B**: B's next edit outranks A's although B's wall clock says 995 |
| 3 | n/a | n/a | compare 1000 / 1 / A against 1000 / 1 / B | A < B: the replica ID decides |

**Rejected:** wall time alone (a fast clock wins every contest); vector clocks (exact concurrency detection, but they grow with the number of replicas, and every browser tab is one). Vectors 023 (counter beats replica ID) and 024 (physical time beats counter) pin the order.

### 4.2 The clock-skew guard

**Problem.** An HLC never goes backwards, so a client whose clock is a day fast would win every LWW contest, and every replica that saw its ops would drag its own clock forward (v3 change 2).

**Rule.** The owner rejects an op whose `physical_ms` is **more than 60,000 ms ahead** of server time ([[op#export function checkClock(]], [[op#export const MAX_SKEW_MS = 60_000;]]). The error carries the server's time. The client recomputes its clock offset, re-stamps the op and resends it under the same `op_id`. If its own HLC had already run ahead on the bad clock, it continues as a **fresh replica ID**, because an HLC can never go backwards ([[client#if (this.clock.physical_ms > this.physicalNow())]]).

**Example.** Server time 1,760,000,000,000. An op stamped `+60,000` is accepted; one stamped `+60,001` gets `CLOCK_SKEW` (vector 010, `010-clock-skew-guard-boundary`). The end-to-end test "10: client clock two minutes fast" (`client-core/test/e2e/failures.test.ts`) shows the correction.

**Rejected:** trusting client clocks (above); server-assigned timestamps (the client could no longer order its own offline edits).

### 4.3 OR-Set with per-entity tombstones

**Problem.** "Does card X exist?" must merge. A plain set cannot tell "deleted" from "not added yet". A two-phase set cannot bring anything back.

**Rule.** Each add (`ADD_*` or `RESTORE_*`) adds a **tag**, the op's own `op_id`, under the entity. A delete carries the tags its sender had seen (`observed_tags`), and **tombstones exactly those (entity, tag) pairs** ([[orset#remove(id: string, observed: Iterable<string>)]]). An entity is live while it has a tag that is not tombstoned ([[orset#liveTags(id: string): string[]]]). Merge is the union of both maps ([[orset#mergeFrom(other: OrSet): void]]). Tombstones are kept for the board's life (v3 change 9), so a delete that arrives before its add still wins.

**Why per entity (amendment 4).** The first Phase 1 shape kept one flat set of tombstoned tags. A buggy or malicious `DELETE_CARD` naming card X but listing a tag of card Y deleted Y. Convergence still held, so no fuzzer could catch it; it was found by reading the shape. Now a delete naming X can only touch X (vectors 051–054).

**Worked example: a delete that arrives before its add** (vector 012):

| Order | after op 1 | after op 2 | Card X |
|---|---|---|---|
| R1: ADD_CARD X (tag t2), then DELETE_CARD X {t2} | adds {t2} | tombstones {t2} | **absent** |
| R2: DELETE_CARD X {t2}, then ADD_CARD X (tag t2) | tombstones {t2}, no adds | adds {t2} | **absent** |

Both replicas end with adds {t2} and tombstones {t2}. Same state, same view.

### 4.4 Delete wins over a concurrent edit; restore by a new tag

**Problem.** v1 said "add wins", but an edit adds no tag, so the rule could not be implemented (v3 change 3). What should happen when one person deletes a card while another retitles it?

**Rule.** An edit writes its register but adds no tag, so it cannot revive a deleted entity. **The delete wins; the edit is kept**, and shows again if the card is restored ([[board#apply(op: Op): number]]). A restore adds a **fresh** tag, so a restore concurrent with a delete survives: the delete never observed that tag.

**Worked example: delete against a concurrent edit** (vectors 002, 003):

| Order | Ops applied | Card X shown? | `title` register |
|---|---|---|---|
| R1 | ADD_CARD X "Pay rent"; DELETE_CARD X {t2} (B); SET_FIELD title "Pay rent today" (A) | **no** | "Pay rent today" |
| R2 | ADD_CARD X; SET_FIELD (A); DELETE_CARD (B) | **no** | "Pay rent today" |

**Worked example: a restore concurrent with a delete** (vectors 004, 020):

| Order | Ops applied | Tags | Card X shown? |
|---|---|---|---|
| R1 | ADD (t2); DELETE {t2} (B); RESTORE (A, tag t4) | adds {t2, t4}, tombstones {t2} | **yes** |
| R2 | ADD (t2); RESTORE (t4); DELETE {t2} | same | **yes** |

A delete that *did* observe the restore's tag wins (vector 011).

**Rejected:** add-wins (an edit would need a tag, so deleting a card someone is typing in could never stick); a two-phase set (nothing could be restored); cascading list deletes to their cards (ADR-003).

### 4.5 The LWW map and the collision tie-break

**Problem.** Two people set the same field at once; everyone must agree on the result.

**Rule.** One register per (entity, field); the higher HLC wins ([[lww#export function pick(a: Register, b: Register)]]). Two registers with **equal HLCs and different values** can only come from a buggy client, since a correct one never reuses a stamp. They are resolved **by the larger canonical encoding as unsigned UTF-8 bytes**, and counted as a "collision" metric (amendment 2).

**Worked example** (`examples.ts`): the same stamp 1000/0/A with values "x" and "y" gives **"y"** (larger bytes), with `collision: true`, in either order (vectors 025, 026). Different fields never interfere (vector 022).

**Rejected:** arrival order (the fuzzers were tested by injecting exactly this bug, and caught it within 200 runs: ADR-000).

### 4.6 The atomic placement register

**Problem.** v1 stored a card's list and its position as two LWW fields. Two concurrent moves could then mix one writer's list with the other's position: a placement no user chose (ADR-002).

**Rule.** Placement is **one** register holding `{list_id, position_key}` for a card, or `{position_key}` for a list. Create, move and reorder all write it whole; `SET_FIELD` may not touch it ([[op#checkFieldValue(field: unknown, value: unknown)]]).

**Worked example: concurrent moves** (vectors 005, 006):

| Order | A moves X to "To Do" at a1 (HLC 1010/A) | B moves X to "Done" at a0 (HLC 1011/B) | Result |
|---|---|---|---|
| R1 | applied first | applied second | X in **Done** at a0 |
| R2 | applied second | applied first | X in **Done** at a0 |

B's higher HLC wins the whole placement on both replicas. A's client notices its move lost and shows "moved by {B's name}" for 4 s ([[client#private checkMoves()]]).

### 4.7 The Orphaned list

**Problem.** A card can be live while its list is deleted (concurrently, or never seen yet). v2 hid such cards, which looked like lost edits (v3 change 4).

**Rule.** A live card whose list is deleted, unknown, or absent from its placement is shown in a built-in **Orphaned** list with the reserved nil UUID, last, and only when non-empty ([[board#(home ? home.cards : orphaned).push]]). It is a derived view, not a write. Restoring the list, or the list's add arriving, brings the cards back with no op; moving a card out is an ordinary move. No op may name the nil UUID (vector 038).

**Worked example** (vectors 008, 014–019, 047):

| Order | DELETE_LIST "To Do" (B) | ADD_CARD "Call bank" into "To Do" (A) | View |
|---|---|---|---|
| R1 | first | second | Orphaned: "Pay rent", "Call bank" |
| R2 | second | first | Orphaned: "Pay rent", "Call bank" |

### 4.8 Fractional keys (Greenspan, CC0)

**Problem.** Order must survive concurrent inserts and allow an insert between any two neighbours without touching them. v1's floats run out of precision after about 50 halvings of one gap (ADR-001).

**Rule.** A position key is a base-62 string compared as bytes, made by a **credited port of David Greenspan's fractional-indexing algorithm** as implemented in `rocicorp/fractional-indexing`, released under CC0-1.0 ([[keys#Upstream is released under CC0-1.0]]; amendment 3 corrected an earlier "MIT"). A key is a variable-length integer part plus a fraction that never ends in `0` ([[keys#export function validateKey(]]). Order is `(position_key, entity_id)` (v3 change 8). Unlike upstream, `keyBetween(a, b)` throws unless `a < b`, so a caller bug cannot pass silently ([[keys#export function keyBetween(]]).

**Examples** (`examples.ts`):

| Call | Result |
|---|---|
| `keyBetween(null, null)` (an empty list) | `a0` |
| `keyBetween("a0", null)` (append) | `a1` |
| `keyBetween("a0", "a1")` (insert between) | `a0V` |
| `keyBetween("a0", "a0V")` | `a0G` |
| `keysBetween(null, null, 4)` | `a0 a1 a2 a3` |

Appending stays short: 2,000 consecutive appends never exceed 4 characters (asserted in both `keys` test suites, ADR-001).

### 4.9 Equal keys and re-keying

**Problem.** `keyBetween` is deterministic, so two replicas appending to the same list at once pick the **same** key. That is common, not rare: 1,555 adjacent equal-key pairs over 200 fuzz scenarios (ADR-001).

**Rule.**
1. **Ties render by entity ID**, the same on every replica (vector 007).
2. **Inserting between two equal keys first re-keys** the upper neighbour, and every further one sharing that key, with ordinary `SET_PLACEMENT` ops, then computes the new key ([[keys#export function planInsert(]], [[client#private placeAt(]]).
3. **A key longer than 40 characters** makes the owner issue fresh, short keys for that list as ordinary `SET_PLACEMENT` ops under its own replica ID ([[actor#private void rebalanceIfNeeded]]). Validation caps keys at 64 characters.

**Worked example: concurrent appends** (`examples.ts`):

| Order | A appends "From A" (key a1) | B appends "From B" (key a1) | Rendered order |
|---|---|---|---|
| R1 | first | second | Pay rent (a0), From A (a1, …0002), From B (a1, …0003) |
| R2 | second | first | same: equal keys fall to entity ID |

**Re-key example:** inserting at index 2 of `[a0, a1, a1, a2]` first re-keys entry 2 to `a1V`, then uses `a1G` (`planInsert`, `examples.ts`; vector 045 covers three equal keys).

**Known anomaly, accepted:** two people inserting several cards into the same gap at once can see them interleave; an insert concurrent with a rebalance can land elsewhere (vector 046). It is deterministic on every replica, so it never breaks convergence (ADR-001).
