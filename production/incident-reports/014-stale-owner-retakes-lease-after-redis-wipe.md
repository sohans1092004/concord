# Incident 014: a stale owner could re-take its lease after a Redis wipe

**Date:** 2026-10-17 · **Type:** design bug in SPEC v3 §6.2 step 6, found while documenting §6.3 · **Environment:** Session `Ownership` (lease renewal) · **Status:** fixed by amendment 13, commit `4b20d57`

## Summary

§6.2 step 6 said: *"If renewal finds the lease gone (expired, or Redis wiped), it re-takes it with the same value; Postgres still fences this owner, so this is safe."* Writes were indeed safe. But a **stale** owner, one already replaced, would re-take the lease after a wipe and take the board's reads and routing with it.

## What broke

Nothing in a running system: found and fixed before the trial.

## Impact (had it shipped)

After a Redis wipe while a stale owner was alive:

1. The stale owner's renewal finds no lease and re-writes `board:owner` to itself.
2. The real owner's next renewal sees a foreign lease and **drops the board**.
3. Gateways route to the stale owner. It answers `JOIN` and `SYNC` from old memory (behind, never wrong: amendment 12), and its first commit is fenced out by Postgres.
4. The board has a needless outage, and its readers are served stale state for longer than the 5s amendment 12 allows.

## Detection

Writing up §6.3 (a stale owner that never writes, amendment 12) and asking what bounds its stale reads. One of the bounds, "its next renewal drops it", is false after a wipe.

## Root cause

The renewal treated "the lease is gone" as "the lease expired and nobody else has the board". After a wipe, the lease being gone says nothing about who owns the board. Postgres's `boards.fence` does.

## Fix

`4b20d57` (amendment 13): a renewal that finds the lease gone first reads `boards.fence`. It re-takes the lease with `SET NX` **only if that still equals its own fence**; otherwise it drops the board.

## Verification

- `FencingTest.staleOwnerDoesNotRetakeAWipedLease`: A replaced by B; Redis wiped just after B renews, so A renews first. A must not re-take the lease, and B must keep the board.
- Guard check `retake-fence-check`: the test fails without the fence comparison and passes with it.
- Drill [006](006-redis-wiped.md) on every CI push.

## What I learned

- "Safe because Postgres fences it" covered writes only. Reads and routing have their own safety, and the spec had argued only about writes.
- The authoritative state (Postgres) should decide every ownership question, including the ones Redis normally answers quickly.
