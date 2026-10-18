# Incident 009: the spec's claim order could lose an ACKed op

**Date:** 2026-10-16 · **Type:** design bug, found before any code ran it (SPEC v3 §6.2) · **Environment:** n/a (specification), proven with `FencingTest` · **Status:** fixed by SPEC amendment 6 (A), commit `e5ce262`; test made real in `d48cfa2`

## Summary

SPEC v3's claim sequence let a new owner load a board **before** Postgres knew about its fence. In that window a stale owner could still commit, and ACK, an op the new owner's memory would never contain. Every later snapshot would be missing an op that a user had been told was saved. The order was changed before the Session code shipped.

## What broke

Nothing in production: the order was fixed in the first Session commit. The bug was in the specification.

## Impact (had it shipped)

A user's edit, ACKed by a stale owner, would be in `board_ops` but not in the new owner's state or any snapshot after it. Other users would never see it, and replay from a snapshot would skip it. It breaks the system's first promise: *no acknowledged edit is lost*.

## Detection

Reading §6.2 against §6.3 while writing the claim code. The test that should have proved it at first did not: see [015](015-the-fencing-test-could-not-fail.md).

## Root cause

v3 wrote the new fence to Postgres lazily: the commit condition was `fence <= :fence`, raising `boards.fence` with `GREATEST` at the new owner's first commit. Between the new owner's load and its first commit, `boards.fence` still held the old owner's value. So the old owner's commit (`fence <= old`) succeeded:

1. A owns the board (fence 1). It takes an op into its batch and pauses (GC, `docker pause`).
2. A's lease expires. B draws fence 2, takes the lease, **loads the board** (without A's op), and has not committed yet.
3. A resumes. Its commit checks `fence <= 1`; `boards.fence` is still 1, so it succeeds. A ACKs the op.
4. B commits next with fence 2 and writes snapshots from a state that never had A's op.

## Fix

Amendment 6 (A), implemented in `e5ce262`. The claim order is:
1. atomic floor-and-INCR of the fence counter;
2. lease `SET NX`;
3. `UPDATE boards SET fence = :fence WHERE fence < :fence`, **before loading**;
4. load;
5. serve.

Every commit requires `fence = :fence` exactly. From step 3 on, the old owner's commits update zero rows. I kept the lease `SET NX` before the Postgres update, so a claimer that loses the lease race cannot fence out the live owner; that reading was put to the project owner, and amendment 8 confirmed it.

## Verification

- `FencingTest.staleOwnerCannotCommitAfterANewOwnerClaims` reproduces the window: A paused with an op in its batch, B claimed and loaded but not committed. A's commit must get zero rows and `NOT_OWNER`, and B's memory must equal Postgres and hold every ACKed op.
- Guard check `old-claim-order` restores v3's order and commit condition: the test fails (`stale owner ACKed`), and passes with the fix.
- Drills: [003](003-stale-owner.md), with 0 commits by the stale owner in every run.

## What I learned

- The fence has to be *in the database* before the new owner reads anything. Otherwise its read and the old owner's write race, and nothing orders them.
- A test for an ordering bug must stop at exactly the dangerous step. The first version of this test did not, and passed for the wrong reason ([015](015-the-fencing-test-could-not-fail.md)).
