# Incident 007: a gateway holding 500 connections crashes (reconnect storm)

**Date:** 2026-10-18 · **Type:** induced failure (SPEC §8 row 14, §10.1 "Reconnect storm") · **Environment:** dev compose stack, laptop and CI · **Status:** passing; the drill's first check was wrong (`3a3284c`); the SPEC's unbuilt rate limit was dropped (amendment 30, `a93c6e6`)

## Summary

500 clients on 50 boards (10 per board, one editing on each) all connect to gateway1, which we kill with `SIGKILL` and start again. The promise: *no cascading failure; the time until all are reconnected is recorded.*

The promise held without the per-gateway connection-rate limit §8 row 14 listed, which had never been built and had no number. Client jitter alone spread the load. The first version of the drill reported a time that does not stand, and was flaky on CI.

## What broke

Gateway1 and its 500 WebSockets, on purpose. Every client reconnects with backoff (0.5s × 2^attempt, capped at 30s, full jitter) and fetches a new single-use ticket from the REST API, so the surviving gateway and Postgres take the burst.

## Impact

| | Laptop (corrected drill) | CI run 39116214175 |
|---|---|---|
| Clients / boards | 500 / 50 | 500 / 50 |
| All rejoined after the kill | **16,202 ms** | **12,420 ms** |
| Reconnect attempts that failed | 128 (tried gateway1 while down) | 6 |
| ACKed ops | 1,447, 0 missing | 1,474, 0 missing |
| Boards converged | 50/50 | 50/50 |
| Other services restarted | none | none |

The laptop run also logged `[vitest-worker]: Timeout calling "onTaskUpdate"`: all 500 clients share one Node process, so the time includes the test harness's own CPU starvation and is an upper bound.

## Detection

- **The flawed check:** CI on `c0d06d8` failed the drill after 346s. Gateway2's logs showed all 500 clients connected and joined within 9s of the kill, yet the drill waited out its 300s limit.
- **The unbuilt limit:** writing the drill, against §8 row 14.

## Root cause

**Of the CI failure, a bug in the drill.** It waited for "every client's status is `joined` and 500 more JOINED events in total".
- A client keeps the status `joined` until it notices its socket dropped.
- A client that reconnects twice counts twice.

So the condition could pass before every client was back (as it did locally: the first recorded 3.5s) or never see what it expected (as on CI). Gateway2's logs show the clients were fine.

## Fix

- `3a3284c`: each client must log a JOINED *after* the kill. On a timeout the drill reports how many are back, the states of the rest, and their last events. Every drill script now strips colour codes before filtering, so error lines show (the CI failure's message had been filtered out).
- `a93c6e6`, amendment 30: §8 row 14 no longer promises a connection-rate limit, with these measurements as the evidence; to be revisited if a gateway ever holds far more than 500 connections.

## Verification

- The corrected drill passed on the laptop and on CI (numbers above).
- `deploy/drills/run-all.sh` runs it on every push.
- The 3.5s figure is struck out in `deploy/drills/results.txt`, with the reason.

## What I learned

- A wait condition built from a total count is easy to satisfy by accident. "Every client did X after time T" is the precise statement.
- When a test times out but the system's own logs show success, the test is the suspect. Gateway2's 500 `client joined board` lines were the fastest route to the bug.
- Dropping a promised feature on evidence is better than building it with an invented number. The measurement says when to come back to it.
