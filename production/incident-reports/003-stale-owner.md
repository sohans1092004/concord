# Incident 003: two owners at once (a paused owner wakes up)

**Date:** 2026-10-16 (pause-owner), 2026-10-17 (no-Redis variant), rerun 2026-10-18 · **Type:** induced failure (SPEC §8 row 3, §10.1 "Stale owner") · **Environment:** dev compose stack with Toxiproxy in front of each Session instance's Redis · **Status:** passing; the drill itself was fixed twice (`2cfb00c`, `c092c96`)

## Summary

We freeze the owner with `docker pause` past its 15s lease (as in Reliant's incident 004), let another instance claim the board, then unpause it. The promise: *the stale owner's commit updates zero rows; it drops the board and answers `NOT_OWNER`; it never broadcast anything uncommitted.*

The promise held. Two problems were in the drill, not the server:

1. **The basic drill never reached the Postgres fence.** On waking, the stale owner's lease renewal found the new owner and dropped the board before it tried to commit. The fence was tested only by unit tests.
2. **The variant built to reach it was flaky** (1 run in 3 had no fence rejection).

## What broke

The owner, on purpose: frozen for ~20s, so for a moment two instances each believe they own the board.

## Impact (rerun, 2026-10-18)

| | pause-owner | pause-owner-no-redis |
|---|---|---|
| Paused | session2, 20,532 ms | session3 (Redis also cut) |
| New owner | session1 after 16,357 ms, fence 1 → 2 | session1 after 16,589 ms, fence 1 → 2 |
| Commits by the stale owner after the pause | **0** | **0** (rows 99 → 99) |
| How it stopped | lease renewal found the new owner | **Postgres fence rejection: 1**, ALERT logged, renewal failures 1 |
| Probe op (only the stale owner sees it) | n/a | reply `NOT_OWNER`, never stored |
| ACKed but missing / Unsent | 0 / 0 | 0 / 0 |

## Detection

Induced. The gap in the basic drill was found by reading its numbers: `stale_owner_fence_rejections: 0`. The flakiness was found by running the variant three times.

## Root cause

1. **Two layers of protection, and the outer one always won.** A woken owner renews its lease within 5s; the renewal sees another owner and drops the board. Only an owner that cannot reach Redis gets as far as committing, so only that case exercises the fence in Postgres.
2. **The variant's stale owner had nothing to commit.** While it was frozen, gateways timed out and clients resent their ops to the new owner. When it woke, everything queued in its socket was already in `board_ops`; a batch of duplicates commits nothing, so it needs no fence and just returns the stored ACKs. That is correct, but not the path under test.

## Fix

- `2cfb00c` (amendment 11): **pause-owner-no-redis**. Each Session instance reaches Redis through its own Toxiproxy route, so the drill cuts the owner's Redis before pausing it. On waking, its renewal cannot tell it it was replaced, so only the Postgres fence (`UPDATE boards … WHERE fence = :fence`) can stop it.
- `c092c96`: a **probe op** delivered straight to the paused owner's internal endpoint (dev secret). No one else can commit it, so on waking the stale owner must try, and its reply must be `NOT_OWNER`.

## Verification

- 3 of 3 runs after `c092c96`: `NOT_OWNER`, probe never stored. Every CI push since runs both drills.
- Guard check `fence-check-e2e` (`server/tools/guard_checks.out`): with the fence condition removed, the stale owner ACKs the probe and the drill fails; restored, it passes.

## What I learned

- A drill can pass without ever exercising the guard it is named after. Each drill now logs the counter that proves the path ran (`stale_owner_fence_rejections`), and a guard check proves the drill fails without the guard.
- "Correct but not the path under test" is the hardest kind of flaky: the run was valid, just not useful. A probe that only one actor can act on removes the ambiguity.
