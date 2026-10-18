# ADR-004: An actor per board, not stateless merge-on-read

Status: Accepted (SPEC §2.2, §6.1; amendment 6). Date: 2026-10-16.

## Context

Every op must be validated against the board's current state (references, existing IDs,
§4.2 step 4), deduplicated, given the next version, stored, applied and broadcast. Something
has to hold that state and decide the order. Two shapes are possible: a stateless service
that loads the board for every op and merges, or one long-lived owner per board.

## Decision

Exactly one Session instance owns a board at a time (leases and fences, ADR-007). Inside it,
the board is an actor: `BoardActor` has one `LinkedBlockingQueue` mailbox drained by one
virtual thread. The board's `Board` (CRDT state), version, 10,000-entry ACK LRU and pending
batch are touched only by that thread, so the merge code has no locks.

The actor is also its board's writer (amendment 6, decision D): it admits ops into a batch
for up to 5ms (ADR-005; 15ms until amendment 32), commits the batch in one transaction over a connection pool shared by the
instance's boards, and only then applies, ACKs and publishes. Board actors never wait for
each other; a slow board stalls only itself.

## Alternatives

- **Stateless merge-on-read.** Each request loads snapshot + tail (or a cache), validates,
  appends, merges. Simple to scale out, but every op pays a load, version assignment needs
  a database round trip under a lock anyway, the dedup LRU cannot live in memory, and two
  requests for one board race unless the database serialises them, which moves the actor
  into Postgres row locks.
- **A per-instance writer batching all boards (v3's §2.2 text).** One transaction for many
  boards amortises commits further, but one board's fence failure or slow insert then
  holds up every other board on the instance, and the actor and writer need a hand-off
  protocol. Rejected. The Phase 4b measurements kept it rejected for one board's load (ADR-005),
  and show where it would come back: at 1,000 connections on 300 boards, Postgres made about
  300 commits/s, one per board, and was the busiest process (`production/load-tests/README.md` §4).
- **One platform thread per board.** Same model, but 10,000 boards would need 10,000 OS
  threads. Virtual threads make the obvious blocking code (JDBC, `queue.take()`) cheap.

## Consequences

- The threading model is visible in one class: read `BoardActor.run()` top to bottom.
- Merge order is the mailbox order, and correctness never depends on it: merge commutes.
- A board's throughput is bounded by one commit at a time: about one batch per
  (window + commit latency). Measured in Phase 4b: 30 editors at 1 op/s each on one board kept
  p95 sync latency at 42ms, with commits levelling off around 13–16/s (§10.1).
- A board leaves memory when nobody has it open for 10 minutes (amendment 26), and is dropped at
  once if it is deleted while loaded (amendment 27; incident report 018).
- The owner must be found (rendezvous hashing, owner key, gateway cache) and handed over
  (graceful release, lease expiry). That machinery is ADR-007 and SPEC §2.3–2.4, and it is
  what the drills exercise.
- Memory holds only committed ops, so a snapshot of it is always a correct state at its
  version, and a commit with an unknown outcome forces a reconcile (amendment 6, B).
