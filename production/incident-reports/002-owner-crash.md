# Incident 002: the board's owner is killed mid-load

**Date:** 2026-10-16 (first run), rerun 2026-10-18 on current code · **Type:** induced failure (SPEC §8 row 2, §10.1 "Owner crash") · **Environment:** dev compose stack, local and CI · **Status:** passing; one bug found and fixed (see [012](012-retries-out-of-order-after-owner-crash.md))

## Summary

Four clients on both gateways edit one board while we `SIGKILL` the Session instance that owns it. The promise (§8 row 2, §10 exit criterion): *the lease expires within 15s; the next instance claims the board and is serving within 5s of the lease expiring; every ACKed op is in `board_ops`.*

The promise held in every run. The first runs also found that **2–3 valid edits per run ended in Unsent edits** after the crash, a client bug written up separately as [012](012-retries-out-of-order-after-owner-crash.md).

## What broke

The owner process, on purpose. The board had no owner until its lease (15s, renewed every 5s) expired; meanwhile the gateways answered the clients' ops with retryable errors and the clients kept them queued.

## Impact (rerun, 2026-10-18, `deploy/drills/results.txt`)

| | |
|---|---|
| Killed | session2 (owner, fence 1) |
| Lease left at the kill | 12,313 ms |
| New owner | session1, fence 1 → 2 |
| Serving after lease expiry | **1,565 ms** (target ≤ 5,000) |
| First ACK after the kill | 13,979 ms |
| Ops | 346 submitted, 347 ACKed (incl. the setup list), 347 in `board_ops` |
| ACKed but missing | **0** |
| Unsent edits | 0 |
| Converged | all 4 clients and a fresh fifth on one state hash |

First recorded run (2026-10-16, `2cfb00c`): 1,217 ms after lease expiry, 0 missing. Across the five Phase 2 runs: 210, 1,474, 417, 287 and 1,221 ms.

## Detection

Induced. The drill (`deploy/drills/kill-owner.sh`, `client-core/test/drills/drills.test.ts`) is self-checking: it fails unless every ACKed op is stored, every client converges and nothing ends in Unsent edits.

## Root cause

Not a defect: an owner crash is expected. Recovery time is bounded by the lease (up to 15s of no owner), then the claim (Lua floor-and-INCR, lease `SET NX`, Postgres fence update, snapshot load and replay). The 1.2–1.6s after expiry is mostly the gateways' 1s claim watchdog noticing the board has no owner.

## Fix

None needed for the server. The client fix from the first runs is [012](012-retries-out-of-order-after-owner-crash.md) (`5a2a495`).

## Verification

`deploy/drills/run-all.sh` runs this drill on every CI push. Latest local rerun on current code: above (all pass, 2026-10-18T08:56Z, commit `41b6b9a`).

## What I learned

- The time a user waits is dominated by the lease, not the claim. Shortening the lease shortens the outage, but raises the chance a paused owner loses its lease while healthy. 15s was kept.
- A drill that checks only the server's promise would have passed while users lost edits to Unsent edits. Checking the client's end state (nothing Unsent) is what found [012](012-retries-out-of-order-after-owner-crash.md).
