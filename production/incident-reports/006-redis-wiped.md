# Incident 006: Redis is wiped and restarts empty

**Date:** 2026-10-16 (first run), rerun 2026-10-18 · **Type:** induced failure (SPEC §8 row 6) · **Environment:** dev compose stack, local and CI · **Status:** passing; the drill's own check was wrong once (`ade7f57`); the server bug it pointed at is [014](014-stale-owner-retakes-lease-after-redis-wipe.md)

## Summary

Four clients edit one board while we run `FLUSHALL` on Redis: leases, the owner registry and every board's fence counter vanish. The promise: *fence counters rebuild from `boards.fence`; leases are reclaimed; nothing durable is lost.* The drill then kills the owner to force a fresh claim, which must get fence `boards.fence + 1`, never a reused fence.

The promise held. The first CI run of the drill failed on an assertion that tested the wrong thing.

## What broke

Redis's contents, on purpose. Two things then race to put an owner back: the owner's own lease renewal (every 5s), and the gateways' claim watchdog (every 1s).

## Impact (rerun, 2026-10-18)

| | |
|---|---|
| Owner before / after the wipe | session1, fence 1 / session1, fence 1 |
| Owner key back after | 4,309 ms |
| `boards.fence` | 1 → 1 (nothing claimed in between) |
| Forced claim after killing the owner | session2, fence **2** (= `boards.fence` + 1) |
| Claim retries | **0** (the counter was rebuilt from Postgres in one step) |
| Ops | 498 submitted, 499 ACKed, 499 stored; 0 missing, 0 Unsent; converged |

## Detection

The first CI run of the drill (run 38945530253) failed on `expect(EXISTS board:fence) == 0`.

## Root cause

**Of the failure: the assertion was wrong, not the server.** After `FLUSHALL` the owner key is gone too, so a gateway's 1s claim watchdog may ask an instance to claim before the owner's 5s renewal re-takes the lease. That claim legitimately recreates the counter. What must hold is that no claim after the wipe ever reuses a fence.

**What it led to:** asking "what if a claim does happen in between?" exposed a real gap in the SPEC's renewal rule, written up as [014](014-stale-owner-retakes-lease-after-redis-wipe.md).

## Fix

- `ade7f57`: the drill asserts the fence property. The forced claim's fence is `boards.fence + 1`, and Postgres records it.
- `2cfb00c` (amendment 11): the drill also requires the claim to need **no retry** (`claim_fence_retries`). Without that, a counter that restarted at 1 would still end at `boards.fence + 1`, because the claim's retry increments again and hides the bug.

## Verification

- Every CI push runs the drill.
- Guard check `fence-counter-rebuild` (floor removed from the fence Lua script): the drill fails (`without a retry: expected 1 to be +0`), and passes when restored.

## What I learned

- Assert the property, not the mechanism. "The counter is absent" was a guess about how the system would get it right; "no fence is ever reused" is what being right means.
- A retry can hide the bug it recovers from. Counting retries turned a passing-by-luck result into a real check.
