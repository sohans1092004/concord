# Incident 015: the fencing test could not fail

**Date:** 2026-10-16 · **Type:** test bug, found by a remove-the-guard check · **Environment:** `server/session` Testcontainers suite · **Status:** fixed in `d48cfa2`

## Summary

`FencingTest` was written to prove the fix in [009](009-claim-order-lost-acked-ops.md): with v3's claim order, a stale owner could commit an ACKed op the new owner never saw. When the remove-the-guard script put v3's order back, **the test still passed.** It was not testing the dangerous window at all.

In the same first run, a second guard, the dedup LRU, could also be removed without any test failing.

## What broke

The test, not the code: it would have kept passing if the claim-order fix were ever reverted.

## Impact

A safety property, *no ACKed op lost when two owners overlap*, had a test that gave false confidence. Nothing was lost in practice, because the code was right.

## Detection

`server/tools/guard_checks.py`, check `old-claim-order`: it restores v3's claim order and commit condition and expects `FencingTest` to fail. It passed.

## Root cause

**The test let the new owner commit before the stale one woke.** B's commit raised `boards.fence` in Postgres. So when A resumed, its commit was rejected even under the old order, which only goes wrong in the window *before* the new owner's first commit.

**The LRU check:** removing the in-memory dedup changed nothing, because the database's primary key still caught every duplicate. No test exercised a path only the LRU could serve.

## Fix

`d48cfa2`:

- `FencingTest` makes B **claim and load without committing**, then resumes A: exactly the window in which v3's order lost an ACKed op.
- A new test, `lruAnswersResendsWithoutTheDatabase`: with Postgres unreachable (Toxiproxy disabled), a resend must still get its original ACK, which only the LRU can do.

## Verification

`server/tools/guard_checks.out`, first run after the fix:

| Guard removed | Without it | Restored |
|---|---|---|
| Fence check in the commit `UPDATE` | FencingTest fails: `stale owner ACKed` | passes |
| v3's claim order | FencingTest fails: `stale owner ACKed` | passes |
| Dedup LRU | `lruAnswersResendsWithoutTheDatabase` fails | passes |

Every safety guard added since has a remove-the-guard check: 41 by Phase 4b (`server/tools/guard_checks.py`), each recorded failing without its guard.

## What I learned

- A test that has never failed has not shown it can detect anything. Removing the guard and watching the test fail is the cheapest proof there is.
- Both broken tests were "correct" in what they asserted. They were wrong about *when*: the interleaving, and which layer answered. The fix in each case was to pin the test to the one path where only the guard can save you.
