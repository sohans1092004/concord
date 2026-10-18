# Incident 004: Postgres goes down under load

**Date:** 2026-10-16 (first run), rerun 2026-10-18 · **Type:** induced failure (SPEC §8 row 4) · **Environment:** dev compose stack, local and CI · **Status:** passing; no defect found

## Summary

Four clients edit one board while we stop Postgres for about 10 seconds. The promise: *owners commit nothing and ACK nothing; they answer `RETRY_LATER`; clients keep editing locally; cross-client sync for new ops pauses until Postgres returns; nothing ACKed is lost.*

The promise held in every run. This drill found no bug.

## What broke

Postgres, on purpose (`docker compose stop postgres`, then `start`).

## Impact (rerun, 2026-10-18, current code)

| | |
|---|---|
| Postgres down | 10,582 ms |
| ACKs while down | **0** |
| Peak pending edits per client | 49 |
| First ACK after Postgres started | 7,760 ms |
| Ops | 619 submitted, 620 ACKed, 620 in `board_ops` |
| ACKed but missing / Unsent | **0 / 0** |
| Converged | yes |

CI run 39116214175 (`3a3284c`): down 10,223 ms, 0 ACKs while down, peak 52 pending, first ACK 13,130 ms after start, 0 missing. First recorded run (2026-10-16): first ACK 7,823 ms after start.

## Detection

Induced (`deploy/drills/stop-postgres.sh`).

## Root cause

Not a defect. A commit that fails or times out has an unknown outcome, so the owner stops ACKing, answers `RETRY_LATER`, takes no snapshots, and reconciles from `board_ops` once Postgres answers (amendment 6 B; see [010](010-commit-with-unknown-outcome.md)). The 7.8–13.1s from Postgres starting to the first ACK covers Postgres crash recovery, the pool's reconnect, the reconcile, and the clients' backoff (0.5s × 2^attempt, capped at 30s, full jitter).

## Fix

None.

## Verification

Every CI push runs the drill. The related Testcontainers test (reconcile after a lost COMMIT reply, Toxiproxy) runs in the server suite.

## What I learned

- "0 ACKs while down" is the line that matters. An ACK is the server's promise that the op is stored; during the outage the honest answer is to make none.
- The time to the first ACK after recovery is mostly client backoff. That is the price of not having every client hammer a database that just came back.
