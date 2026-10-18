# Concord: Interview Guide

Private prep · 2026-10-18 · companion: [how Concord was built](how-concord-was-built.md)

**How to use this.** Learn the pitches by heart (section 1), then the numbers (section 9), then work through the question bank (section 3) out loud. Every answer points at the code it relies on; open the link and read the lines before you trust the answer. The self-test (section 12) has its answers at the very end. Measurements are tagged **[laptop]**, **[host]** (the live AWS VM), **[CI]**, **[accelerated]** (compressed time) or **[design]** (argued, not measured), exactly as in the main document. Every code link here was checked against the code by `python docs/tools/refs.py check` (288 references across both documents, all resolving).

## 1. Pitches

> **Three lengths of the same story: what it does, why it is hard, how I know it works, and what it does not do.**

### 30 seconds

"Concord is a collaborative kanban board: several people edit the same board at once, work offline for a day, and never lose an edit or get asked which version to keep. Under it is a hand-rolled CRDT engine, built twice, in TypeScript for the browser and Java for the server, proven byte-identical by shared test vectors and a cross-language fuzzer. Around it is an actor-per-board server that never acknowledges an edit until Postgres has committed it, with ownership fenced in Postgres. It's live on AWS. Every safety guard has a test I've watched fail with the guard removed, and the failure drills (killed owners, frozen owners, Postgres and Redis outages) lost zero acknowledged edits."

### 2 minutes

1. **The problem.** Real-time collaboration plus offline work means two copies of a board change independently and must end up identical, without asking users to pick. That's convergence. And when the server says "saved", it must be true forever. That's durability.
2. **The data model.** The board is two observed-remove sets (which lists and cards exist) and a map of last-writer-wins registers (titles, labels, placements), ordered by hybrid logical clocks. A card's list and position are one atomic register, so concurrent moves never produce a placement nobody chose. Delete wins over a concurrent edit, but the edit is kept and comes back on restore. Order uses fractional string keys.
3. **The server.** Each board has exactly one owner, an actor on one virtual thread, chosen by a Redis lease. The lease isn't trusted for safety: the claim writes a fencing token to Postgres before loading, and every commit checks it in the same transaction. The owner commits a batch, then applies, acknowledges and broadcasts. Redis Pub/Sub fans out, every broadcast carries a version, and clients repair gaps.
4. **The client.** It saves each edit to IndexedDB before showing it, resends its queue in order at the server's pace, and nothing it can't send disappears: it goes to a visible Unsent list.
5. **Evidence.** 55 hand-written vectors, a permutation fuzzer at 100,000 seeds nightly, a differential fuzzer between the languages, 61 integration tests on real Postgres and Redis, 7 failure drills, and 42 remove-the-guard checks. Two of those checks caught tests that couldn't fail.
6. **Honest limits.** p95 sync latency is 32 ms on the laptop, but 315–354 ms from India to the Sydney host, because the network is about 300 ms. One render in 300 on a 2,000-card board misses the 16 ms budget. At 300 busy boards, Postgres commits are the bottleneck.

### 10 minutes: the walkthrough script, and what to draw when

| Minute | Say | Draw |
|---|---|---|
| 0–1 | The promise: convergence plus "an ACK means stored"; what it does not promise (no character-level text, one move wins) | nothing yet |
| 1–3 | The architecture: browsers → gateways → one owner per board → Postgres; Redis for leases and fan-out only | **box diagram** (drill 6.1): browsers, gateway, Session instances, Postgres, Redis, with arrows labelled |
| 3–5 | The life of one edit: stamp, save, send, validate, group commit, apply, ACK, publish, merge | **sequence** (drill 6.2) |
| 5–7 | Why fencing: the paused-owner story; the claim order bug in the original spec; fence checked inside the commit | **the interleaving** (drill 6.3) |
| 7–8 | The CRDT rules that matter: OR-Set tags, delete wins, atomic placement | **the concurrent-move table** (drill 6.4) |
| 8–9 | Evidence: vectors, fuzz, drills, guard checks; one story (the fencing test that couldn't fail) | the guard-check loop: remove → must fail → restore → must pass |
| 9–10 | Limits and what I'd do next: region, Postgres commits across boards, the render tail | point at Postgres in the box diagram |

## 2. How it was built, honestly

> **Spec-driven and AI-assisted: I owned the specification, directed the build phase by phase, and reviewed and approved every decision; Claude Code wrote the code. My ownership is in the decisions, the review findings and the evidence I insisted on.**

**What to say, plainly:**
- "I owned the specification. It carries my name and went through three versions; v3 fixed eleven correctness gaps found in review (`docs/SPEC.md`, 'Changes from v2')." The repo does not record how the spec itself was drafted. If AI helped draft it too, say so in the same breath.
- "I built it with an AI coding agent under fixed ground rules:
  - follow the spec;
  - stop and ask at every gap;
  - record every change as a dated amendment with its evidence;
  - never invent a number;
  - never weaken a test;
  - prove every safety guard by removing it and watching its test fail."
- "I reviewed every proposal and approved or redirected it. I can walk you through any of them."

Don't claim you typed the code. Do claim the decisions, and be ready to defend each one line by line: section 7 is the code tour for that.

**Decisions I made or approved** (each recorded in `docs/SPEC.md`, "Amendments log"):

| Decision | What I chose, and why | Amendment |
|---|---|---|
| Hand-roll the CRDTs, in two languages | Our rules (delete-wins, Orphaned) aren't Automerge's defaults; no Java implementation exists; two implementations prove the spec is precise | ADR-000, ADR-000b |
| The claim order | The fence goes into Postgres before the new owner loads anything, and the lease is taken before that write | 6 A, 8 |
| A commit with an unknown outcome reconciles from Postgres before serving again | A lost COMMIT reply is not a failure | 6 B |
| Per-entity tombstones | A delete naming X can't touch Y (the cross-card delete) | 4 |
| `INVALID_OP` edits go to Unsent, never dropped | Users must see what didn't save | 4, 10 |
| No JWTs: hashed session cookies and single-use WebSocket tickets | As in Reliant; tokens in URLs end up in logs | 14 |
| The laptop is the measuring machine; CI runs looser regression bounds | Shared CI runners aren't benchmarks | 20 |
| Idle boards unload after 10 minutes | The soak's heap grew with every board ever loaded | 26 |
| Retention bounded by the *oldest* kept snapshot | The newest would break the corrupt-snapshot fallback | 28 |
| Drop the connection-rate limit for reconnect storms | The drill showed no cascade without one, and I wouldn't invent a number | 30 |
| The client paces its sends | A day's offline queue would otherwise take hours | 31 |
| A 5 ms commit window | Measured: as much batching as 15 ms, 11 ms faster | 32 |

**Problems found in review or by the evidence, before or after they could hurt** (incident reports in `production/incident-reports/`):

| Problem | How it was found | Result |
|---|---|---|
| v3's claim order could lose an ACKed op | Reading §6.2 against §6.3 while implementing claims | amendment 6 A; incident 009 |
| No rule for a commit whose reply is lost | Reading §6.1: no branch for "no reply" | amendment 6 B; incident 010 |
| The cross-card delete: a flat tombstone set let a delete for X remove Y | Reading the data shape; no fuzzer could find it, because convergence held | amendment 4; vectors 051–054 |
| Lease before or after the Postgres fence | Comparing the proposed order with the race it allows | kept the lease first; amendment 8 confirmed |
| A stale owner could re-take its lease after a Redis wipe | Writing up stale reads (§6.3) | amendment 13; incident 014 |
| The gateway's `CompletableFuture` chains could reorder ops | Reading the code | one request at a time per connection; incident 011 |
| The fencing test, and the LRU test, could not fail | Remove-the-guard checks | incident 015 |
| Chromium never closes a dead WebSocket | The Phase 3 offline browser test | amendment 18; incident 013 |
| A board deleted while loaded | The live host's logs | amendment 27; incident 018 |
| `/metrics` public on the live host | Checking the URL while building observability | incident 019 |
| A day's offline queue would take hours | The 24 h measurement | amendment 31; incident 021 |
