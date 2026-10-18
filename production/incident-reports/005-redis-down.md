# Incident 005: Redis goes down under load

**Date:** 2026-10-16 (first run), rerun 2026-10-18 · **Type:** induced failure (SPEC §8 row 5) · **Environment:** dev compose stack, local and CI · **Status:** passing; no defect found

## Summary

Four clients edit one board while we stop Redis for about 15 seconds. The promise: *owners keep serving (Postgres fences them); gateways switch to polling owners; presence is lost; boards with no owner wait.*

The promise held. Edits kept being committed and ACKed through the whole outage. This drill found no bug.

## What broke

Redis, on purpose (`docker compose stop redis`, then `start`). That takes away leases, the owner registry, Pub/Sub fan-out and presence.

## Impact (rerun, 2026-10-18, current code)

| | |
|---|---|
| Redis down | 15,779 ms |
| ACKs while down | **268** |
| Versions committed while down | 288 |
| Client lag at the end of the outage | 13, 4, 13 and 3 versions behind (fed by polling instead of Pub/Sub) |
| Ops | 580 submitted, 581 ACKed, 581 in `board_ops` |
| ACKed but missing / Unsent | **0 / 0** |
| Converged | yes |

CI run 39116214175: down 15,335 ms, 285 ACKs while down, lag 17–18 versions. First recorded run (2026-10-16): 15,750 ms, 264 ACKs.

## Detection

Induced (`deploy/drills/stop-redis.sh`).

## Root cause

Not a defect. Redis holds nothing durable in Concord. An owner that cannot reach Redis keeps its board, because Postgres still fences every commit (ADR-007). Each gateway notices Pub/Sub is down and polls each board's owner every second for new versions (SPEC §6.4); clients lag by about a second's worth of ops instead of the usual tens of milliseconds.

## Fix

None.

## Verification

Every CI push runs the drill. `GatewayTest` covers the polling fallback with Toxiproxy cutting one gateway's Redis.

## What I learned

- Putting the fence in Postgres (ADR-007), not Redis, is what makes this outage boring: losing Redis loses speed (fan-out falls back to polling) and presence, not safety.
- The lag numbers are the visible cost, and they are what a user would notice: other people's edits arriving in bursts once a second.
