# Incident 010: a commit whose outcome is unknown

**Date:** 2026-10-16 · **Type:** design gap, found before any code ran it (SPEC v3 §6.1) · **Environment:** n/a (specification), proven with Testcontainers and Toxiproxy · **Status:** fixed by SPEC amendment 6 (B), commit `e5ce262`

## Summary

v3's write path said what to do when a commit succeeds and when it fails, but not when the owner **cannot tell**. A timeout, or a connection dropped after `COMMIT` was sent, leaves the transaction either committed or not. Treating that as "failed" loses ACKs on retry paths and duplicates versions; treating it as "succeeded" ACKs ops that may not exist. The owner now stops and finds out.

## What broke

Nothing in production: fixed in the first Session commit.

## Impact (had it shipped)

- **Counted as failed** when it had committed: the owner's memory falls behind `board_ops`. Its next commit numbers versions from a stale value, and its snapshots miss committed ops.
- **Counted as committed** when it had not: the owner ACKs and broadcasts ops that are not stored. An ACK would no longer mean "saved".

## Detection

Reading §6.1 step 3 while writing the commit path: there was no branch for "no reply".

## Root cause

A database commit is a message to another machine. The reply can be lost after the work is done (the network drops, the 2s budget expires, Postgres restarts). The only source of truth is Postgres itself.

## Fix

Amendment 6 (B), implemented in `e5ce262`. A failed or timed-out commit is an **unknown outcome**. The board's actor:

1. answers `RETRY_LATER` to the batch and to anything new;
2. takes no snapshots;
3. reconciles: under the board row lock it reads back every op above its own version from `board_ops`, applies and publishes them;
4. serves again.

Clients resend; dedup (LRU, then the primary key) returns the original ACK for anything that did commit.

Later amendments relied on this state:
- amendment 26 never unloads a board while it is reconciling;
- amendment 27 stops a reconcile that finds the board deleted, where before it would have retried forever.

## Verification

- Testcontainers test: reconcile after a lost `COMMIT` reply. Toxiproxy cuts the connection after the commit is sent; the owner must reconcile to exactly Postgres's state, and every resend must get its original ACK.
- Drill [004](004-postgres-down.md): 0 ACKs while Postgres is down and 0 ACKed ops missing after, in every run.
- On the soak's first run (2026-10-17), a disk stall caused 16 real commit failures; **every one was reconciled**, and no data was lost (`deploy/soak/results.txt`).

## What I learned

- Every remote call has three outcomes, not two. The design has to name the third one, or the code will quietly pick one of the other two.
- "Stop and read back the truth" is simple to implement and easy to test. A cleverer recovery would have been neither.
