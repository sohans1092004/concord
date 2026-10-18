# ADR-007: Fence in Postgres, not Redis; owners keep serving while Redis is down

Status: Accepted (SPEC §6.2–6.3; amendment 6, decision A). Date: 2026-10-16.

## Context

A lease in Redis says which instance *should* own a board. It cannot guarantee that only
one instance *does*: a Redis failover can lose a lease, and a process paused past its lease
(GC, `docker pause`, Reliant's incident 004) wakes up still believing it owns the board.
If both "owners" can write, a stale one can store and broadcast ops the new one never
applies, and the new owner's snapshots then skip acknowledged edits.

## Decision

Safety rests on the database that stores the ops.

- **Fence values come from Redis**, `INCR board:fence:{id}` in a Lua script that never goes
  below `boards.fence + 1`, so they are unique and increasing even after a wipe.
- **The claim writes its fence to Postgres before loading** (amendment 6):
  lease `SET NX` → `UPDATE boards SET fence = :fence WHERE fence < :fence` → load snapshot →
  replay tail. From that commit on no older fence can commit, so the replay sees everything.
- **Every commit checks it** in the same transaction:
  `UPDATE boards SET version = version + :n WHERE id = :board AND fence = :fence`.
  Zero rows: a newer owner exists; the actor drops the board and answers `NOT_OWNER`.
  Nothing is applied or broadcast before commit, so a fenced owner can do neither.
- **Redis down is not a reason to stop.** Lease renewal failures are ignored; the owner
  keeps committing because Postgres still fences it. Unowned boards wait (`UNAVAILABLE`).

## Alternatives

- **Fence check in Redis** (compare the lease before each commit). Two systems, no
  atomicity between the check and the write; a pause between them reopens the hole.
- **v3's original order** (fence reaches Postgres with the first commit, `fence <= :fence`).
  `FencingTest` replays the interleaving that breaks it: A pauses, B claims and loads, A
  commits and ACKs an op B never applies. The remove-the-guard check shows the test failing
  with that order (`server/tools/guard_checks.out`).
- **Stop serving when Redis is unreachable.** Turns every Redis blip into an outage of
  every board, for no safety gain.

## Consequences

- One extra small transaction per claim.
- The pause-owner drill: the stale owner made zero commits after its pause. The wipe-redis
  drill: after `FLUSHALL`, the next claim got `boards.fence + 1` from the rebuilt counter.
- The stop-redis drill kept ACKing (264 ACKs in 15.7s) with Redis down.
- Correctness of ownership no longer depends on Redis at all, only liveness does.
- The same rule settles the one case Redis cannot (amendment 13): a renewal that finds its
  lease gone, as after a wipe, re-takes it only while `boards.fence` still equals its own
  fence. Otherwise a stale owner would take the board's routing back from the real one
  (`FencingTest.staleOwnerDoesNotRetakeAWipedLease`; incident report 014).
- The fence alone is proven end to end by the pause-owner-no-redis drill (amendment 11): the
  stale owner cannot reach Redis, so only Postgres stops it. Every run: one fence rejection,
  zero commits; the guard check `fence-check-e2e` fails the drill without the fence condition.
