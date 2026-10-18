
## 3. Question bank

> **Sixty-six questions in fifteen groups; each has a model answer grounded in the code, the follow-up most likely to come next, and the trap that loses the point.**

Read each answer, open its code link, then say the answer out loud without looking.

### 3.1 CRDT theory

**Q1. What is a CRDT, and why use one here instead of a central lock?**
- *Answer.* A conflict-free replicated data type is a structure whose replicas can apply the same operations in any order, any number of times, and end identical with no coordination. Concord needs offline editing for up to a day, so a client must change its copy without asking anyone. A lock would make offline editing impossible. The board is two OR-Sets plus an LWW map ([[board#export class Board {]]).
- *Follow-up.* "Then why have a server at all?" Durability, access control, a total version order for gap detection, and history. The server is a replica that also persists (section 7 of the main document).
- *Trap.* Saying a CRDT "resolves conflicts". It *defines* the result so that there is nothing to resolve; the product rules (delete-wins, one move wins) are choices that someone may dislike.

**Q2. Name the three algebraic laws merge must satisfy, and what each buys you.**
- *Answer.*
  - **Commutative** (A⊔B = B⊔A): arrival order doesn't matter.
  - **Associative** ((A⊔B)⊔C = A⊔(B⊔C)): grouping doesn't matter, so replicas can merge pairwise in any topology.
  - **Idempotent** (A⊔A = A): duplicates are harmless, so resending is safe.

  Property tests check all three per module ([[crdt-ts/test/orset.test.ts#merge is commutative, associative and idempotent]]).
- *Follow-up.* "Is your op-based apply also commutative?" Yes: `apply` equals merging single-op states, which is tested ([[crdt-ts/test/board.test.ts#apply agrees with merge]]).
- *Trap.* Confusing idempotence with "exactly once". Concord gets idempotence from the data type *and* from server dedup.

**Q3. State-based or op-based?**
- *Answer.* Both. Clients and the owner exchange **ops**. The owner stores ops and **state snapshots**, and a far-behind client gets a snapshot that replaces its confirmed state ([[client#private catchUp(m: Msg)]]). Because applying an op equals merging its single-op state, the two views agree.
- *Follow-up.* "What does an op-based CRDT normally require of delivery?" Causal or exactly-once delivery. Concord's ops are idempotent and commutative, so it needs only eventual delivery; versions provide a total order anyway.
- *Trap.* Claiming Concord needs causal delivery: it doesn't, and vector 012 shows a delete arriving before its add.

**Q4. Explain the OR-Set in Concord with an example.**
- *Answer.* Each add or restore puts a tag (its own `op_id`) under the entity; a delete tombstones exactly the tags its sender saw ([[orset#remove(id: string, observed: Iterable<string>)]]). An entity is live while one of its tags is not tombstoned. Example: a restore concurrent with a delete survives, because the delete never saw the restore's tag (vector 004).
- *Follow-up.* "Why keep tombstones forever?" A delete can arrive before its add, for a client offline 24 h, and must still win. At 2,000 cards that costs only IDs (v3 change 9).
- *Trap.* Calling it a two-phase set. A 2P-set can never re-add; the OR-Set can.

**Q5. What was the cross-card delete bug?**
- *Answer.* The first shape stored one flat set of tombstoned tags. A `DELETE_CARD` naming X but listing a tag of card Y deleted Y. Convergence still held, so no fuzzer could find it; it was found by reading the shape. Tombstones became per entity, so a delete naming X can only touch X (amendment 4; vectors 051–054).
- *Follow-up.* "Why not just validate tags on the server?" That fixes only the server path; clients merge each other's ops too, so the shape itself had to make it impossible.
- *Trap.* Calling it a convergence bug. It was a semantics and safety bug in a convergent design.

**Q6. Why does delete win over a concurrent edit?**
- *Answer.* An edit writes a register but adds no tag, so it cannot make a deleted card live again; "add wins" would need edits to carry tags (v3 change 3). The edit is kept, though: restoring the card shows it (vectors 002, 003).
- *Follow-up.* "Isn't that bad for the person who was typing?" Their text is kept, and the card is one click from restored in the trash view. The alternative, an un-deletable card while anyone types, is worse.
- *Trap.* Saying the edit is lost. It isn't.

**Q7. What's the Orphaned list?**
- *Answer.* A *derived view*, not data: a live card whose list is deleted, unknown or not yet seen is shown in a built-in list with the nil UUID ([[board#(home ? home.cards : orphaned).push]]). Restoring the list brings the cards back with no write (vectors 008, 014–019).
- *Follow-up.* "Why not cascade-delete the cards?" A cascade is a write that races with concurrent moves into the list, and it would turn one undo into many.
- *Trap.* Saying Orphaned is stored or synced; it is computed identically on every replica.

**Q8. How do you know the TypeScript and Java cores agree?**
- *Answer.* Three ways (ADR-000b):
  1. 55 hand-written conformance vectors, whose expected hashes come from a third implementation (Python), never from either core.
  2. A differential fuzzer where TypeScript drives Java through a CLI and compares state hashes, view hashes and validation verdicts.
  3. A canonical byte encoding, so "equal" means equal bytes.
- *Follow-up.* "What disagreed in practice?" String ordering (UTF-16 against UTF-8 bytes), what counts as an integer (`1.0` in JavaScript), code points against UTF-16 lengths, and NFC tables (ADR-000b).
- *Trap.* Saying one core generated the vectors for the other; that would only prove they share bugs.

### 3.2 HLC and clocks

**Q9. What's a hybrid logical clock and why not wall time?**
- *Answer.* `(physical_ms, counter, replica_id)`. `tick` takes the maximum of the last physical time and now, bumping the counter on a tie; `receive` takes the maximum of all three times, so the next stamp outranks everything seen ([[hlc#export function receive(]]). It stays close to real time but never goes backwards, and the replica ID makes stamps unique.
- *Follow-up.* "Give a concrete case." B's clock says 995; it receives A's 1005/2; its clock becomes 1005/3, so B's next edit beats A's (`docs/tools/examples.ts`).
- *Trap.* Saying an HLC detects concurrency like a vector clock. It orders; it doesn't detect.

**Q10. A client's clock is a day fast. What happens?**
- *Answer.* The owner rejects its op with `CLOCK_SKEW`: more than 60,000 ms ahead of server time ([[op#export function checkClock(]]). The error carries server time; the client recomputes its offset and re-stamps under the same `op_id`. If its HLC had already run ahead, it continues as a **fresh replica ID**, because an HLC can't go backwards ([[client#if (this.clock.physical_ms > this.physicalNow())]]).
- *Follow-up.* "Why does the fresh replica matter?" Its old ID's sequence already reached the future; a new ID can never repeat one of the old ticks.
- *Trap.* Saying the server rewrites the timestamp. It doesn't; it refuses and the client fixes itself.

**Q11. What if two ops have exactly equal HLCs?**
- *Answer.* Only a buggy client can produce that, because the replica ID is part of the stamp. Concord resolves it deterministically by the larger canonical encoding as unsigned UTF-8 bytes, and counts it as a "collision" metric ([[lww#export function pick(a: Register, b: Register)]]). The fuzz test fails if a valid scenario ever needs this tie-break.
- *Follow-up.* "Why bytes and not 'first arrival'?" Arrival order differs per replica: that was one of the bugs injected to test the fuzzer.
- *Trap.* Saying it can't happen. It can't happen *correctly*, so it is handled anyway.

**Q12. How is sync latency measured if every device's clock differs?**
- *Answer.* Two ways.
  - **In load tests, exactly:** all clients are in one process, so submit and apply times share a clock ([[load#class Latency {]]).
  - **From real clients:** latency is "applied here minus the op's HLC time", with both clocks corrected by the offset taken at JOIN. The error is the difference of the two clients' one-way delays, and that is stated in the SPEC (§9.5).
- *Follow-up.* "And the trace spans?" Server spans share one clock; the client spans carry the same correction error. In the test, `peer.render` appeared about 14 ms before `gateway.deliver` locally.
- *Trap.* Presenting client-reported numbers as exact.

### 3.3 Consistency models

**Q13. What consistency does Concord give a user?**
- *Answer.* Strong eventual consistency for the board state: replicas that have seen the same ops are identical. A client also sees its own writes at once (optimistic view), and the owner gives a single total order of versions per board, which is what gap detection and history use.
- *Follow-up.* "Is it linearizable?" Commits per board are serialised by one owner and fenced by Postgres, so the committed history is a total order. A client's view, though, includes its own uncommitted edits, so reads are not linearizable.
- *Trap.* Calling the whole system "strongly consistent".

**Q14. Can a client read stale data?**
- *Answer.* Yes, in one documented case (amendment 12): a stale owner that never writes is never fenced, and may answer `JOIN` from memory that is behind. It is a prefix of the committed history, behind but never wrong. It's bounded by the gateway's 5 s owner cache and the stale owner's next renewal.
- *Follow-up.* "Why not fence reads too?" Every read would need a Postgres round trip; the prefix guarantee plus `BOARD_VERSION` catch-up is enough for a board.
- *Trap.* Claiming reads are always fresh.

**Q15. Where do you need a total order, and where don't you?**
- *Answer.* Merge doesn't need one, since it commutes. The total order of versions exists for durability bookkeeping: "the client is at version v", gap detection, history and retention. One owner per board makes it cheap.
- *Follow-up.* "Could you drop the owner and have peers sync directly?" Convergence would hold, but the ACK promise, access control and history would need another home.
- *Trap.* Saying the CRDT needs the server's order.

### 3.4 Ownership and fencing

**Q16. Why does each board have one owner?**
- *Answer.* Validation needs current state (an `ADD_CARD` must name a list that exists), and versions need one sequence. One actor per board does both with no locks: one virtual thread drains its mailbox ([[actor#Thread.ofVirtual().name("board-"]], ADR-004).
- *Follow-up.* "What about a hot board?" Its throughput is one commit at a time. At 30 editors the p95 was 42 ms, with commits levelling off around 13–16/s **[laptop]**.
- *Trap.* Saying the owner makes the CRDT correct; it makes validation and versioning simple.

**Q17. Explain fencing tokens.**
- *Answer.* A number that grows with every new owner; storage refuses writes carrying an old one. Concord draws it from Redis with a Lua script that never goes below `boards.fence + 1` ([[ownership#static final String FENCE]]), writes it to Postgres before loading, and every commit checks `fence = :fence` in the same `UPDATE` that reserves the versions ([[store#WHERE id = ? AND fence = ? RETURNING version]]).
- *Follow-up.* "Why check in the same transaction?" A check and a write in two systems leave a gap a pause can fall into.
- *Trap.* Saying the lease guarantees one owner. It guarantees nothing during a pause.

**Q18. Walk me through the claim-order bug.**
- *Answer.* The original spec wrote the fence to Postgres at the new owner's *first commit*:
  1. A pauses with an op in its batch.
  2. B claims and loads.
  3. A wakes and commits: `boards.fence` still held A's value, so the commit succeeded and A ACKed.
  4. B's state never had that op.

  The fix writes the fence before loading ([[store#UPDATE boards SET fence = ? WHERE id = ? AND fence < ?]]), and commits require equality (amendment 6 A; incident 009).
- *Follow-up.* "How do you know the fix works?" `FencingTest` stops in exactly that window. The guard check that restores the old order makes it fail with `stale owner ACKed`.
- *Trap.* Not knowing that the first version of that test passed with the bug: it let B commit first.

**Q19. Why take the lease before writing the fence to Postgres?**
- *Answer.* If the Postgres write came first, a claimer that then loses the lease race would already have fenced out the live owner, an outage for nothing. Lease first means only the lease winner touches Postgres ([[ownership#public long claim(String board)]]). I kept that order against the order first proposed; amendment 8 confirmed it.
- *Follow-up.* "What if two claimers both pass?" `SET NX` admits one. The Postgres update is conditional (`fence < :f`), so even a racing claim can't lower it; a loser releases and retries, counted as `claim_fence_retries`.
- *Trap.* Forgetting that the counter's floor is what survives a Redis wipe.

**Q20. What happens when an owner is paused for 20 seconds?**
- *Answer.* Its lease expires; another instance claims, writes fence n+1 to Postgres and serves. When the paused one wakes:
  - **If it can reach Redis,** its renewal sees a foreign lease and drops the board.
  - **If it can't,** it tries to commit and gets zero rows (`ALERT fenced out`), answers `NOT_OWNER` and closes, with nothing stored or broadcast ([[actor#if (c.fenced())]]).

  Drills: 0 stale commits both ways **[laptop]**.
- *Follow-up.* "How did you make sure the drill reached the fence?" A probe op sent straight to the frozen owner, which nobody else could commit (`c092c96`).
- *Trap.* Saying the stale owner "might commit a little". It commits nothing.

**Q21. And after a Redis wipe?**
- *Answer.*
  - **Fence counters rebuild** from `boards.fence + 1` ([[ownership#local floor = tonumber(ARGV[1])]]).
  - **A renewal that finds its lease gone** re-takes it only while `boards.fence` still equals its fence ([[ownership#if (current == null || current != fence) return 0;]], amendment 13). Otherwise a stale owner would steal routing back from the real one.
  - **The drill** requires the forced claim to get exactly `boards.fence + 1` with zero retries.
- *Follow-up.* "Why count retries?" A retry would also reach the right number and hide a counter that restarted at 1.
- *Trap.* Saying a wipe loses data. Redis holds nothing that can't be rebuilt.

**Q22. How does a gateway find the owner?**
- *Answer.* A 5 s cache, then `board:owner:{id}` in Redis, then rendezvous hashing over live instances (heartbeats every 2 s, live within 6 s) ([[router#String owner(String board)]], [[rendezvous#public static List<Instance> order(]]). A `NOT_OWNER` drops the cache and tries again, 3 attempts in all. With Redis down, the last known owner is used.
- *Follow-up.* "Why rendezvous hashing?" Every gateway computes the same preferred owner with no coordination, and losing an instance moves only that instance's boards.
- *Trap.* Saying the hash decides ownership. It only says who *should* claim; the lease and fence decide.

### 3.5 Durability

**Q23. What exactly does an ACK promise, and how is it kept?**
- *Answer.* That the op is committed in Postgres. The owner applies, ACKs and publishes only after `COMMIT` returns ([[actor#private void applyCommitted]]). v2 applied first, so a failed append could leave an unstored op in memory and in the next snapshot (v3 change 5). Guard check `apply-after-commit` proves the test catches that.
- *Follow-up.* "And if COMMIT's reply is lost?" Next question.
- *Trap.* "ACK means received."

**Q24. A commit times out. Did it commit?**
- *Answer.* Unknown, and Concord treats it so (amendment 6 B):
  1. It answers `RETRY_LATER` and takes no snapshots.
  2. It locks the board row with `SELECT … FOR UPDATE`, which waits out any of its own transactions still holding it ([[store#public Reconciled reconcile(String board, long applied)]]).
  3. It reads every op above its version and applies them.
  4. It serves again.

  Clients resend, and dedup returns the original ACK for anything that did commit.
- *Follow-up.* "Seen in practice?" Yes: a disk stall during the first soak caused 16 commit failures, and all were reconciled.
- *Trap.* Retrying the same commit blindly, which can double-apply without the primary key.

**Q25. How do you deduplicate?**
- *Answer.* Two layers:
  1. **The LRU:** the last 10,000 op_ids with their versions, in the owner's memory. It answers even with Postgres down ([[actor#static final int LRU_SIZE = 10_000;]]).
  2. **The primary key** `(board_id, op_id)`: inside the commit, stored op_ids are dropped and answered with their version.

  A rejected resend is checked against the table before being called invalid ([[actor#private void rejectOrAckStored]]). Never re-broadcast.
- *Follow-up.* "Why both?" The LRU is fast and works through outages; the key is the truth after a failover empties the LRU.
- *Trap.* Not knowing the LRU's test had to be added: removing the LRU first changed nothing.

**Q26. What's in the 2-second commit budget?**
- *Answer.* Pool wait plus the statements. Each statement gets a network timeout of what's left, and a `COMMIT` that would start after the deadline is never sent ([[store#budget(c, deadlineMs); // a commit that would start after the deadline is not sent]]).
- *Follow-up.* "Why not send it late?" A late commit is exactly the unknown-outcome case. Not sending it keeps the outcome known: not committed.
- *Trap.* Thinking a timeout means "not committed".

**Q27. How does the system recover a board after a crash?**
- *Answer.* The newest snapshot whose SHA-256 matches, then replay of the ops after it ([[store#public Loaded load(String board)]]). A corrupt snapshot falls back to the previous one, of three kept. A claim takes 29–342 ms even at 2,000 cards with a 499-op tail **[laptop]**.
- *Follow-up.* "Then why did users wait 13–20 s?" The 15 s lease plus client backoff (ADR-008).
- *Trap.* Blaming replay for recovery time.
