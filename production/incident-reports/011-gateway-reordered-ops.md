# Incident 011: the gateway could reorder a client's ops

**Date:** 2026-10-16 · **Type:** code bug, found by reading the code before it shipped · **Environment:** gateway (`ClientConn`) · **Status:** fixed in the gateway's first commit, `a916b65`

## Summary

The first draft of the gateway forwarded each client op to the board's owner through chained `CompletableFuture` callbacks. Those callbacks do not run in submission order. A `NOT_OWNER` retry (the owner moved) could also let a later op overtake an earlier one. The owner checks references on admission, so an `ADD_CARD` arriving before the `ADD_LIST` it belongs to is rejected as `INVALID_OP`, and the user sees a valid edit in Unsent edits.

## What broke

Nothing in a running system: the draft was rewritten before it was committed.

## Impact (had it shipped)

Valid edits rejected at random, more often around owner changes, when `NOT_OWNER` retries happen. The same symptom later appeared from a different cause in the client ([012](012-retries-out-of-order-after-owner-crash.md)).

## Detection

Reading the code, not a test. No test at the time sent dependent ops fast enough across an owner change.

## Root cause

`thenCompose`/`thenAccept` chains run on whatever thread completes the previous stage. Two requests from the same connection, each with its own chain, complete in whatever order their replies (or retries) arrive. Per-connection order was never stated as an invariant in the code, so nothing kept it.

## Fix

`a916b65`: each connection forwards **one request at a time, in arrival order**, including across `NOT_OWNER` retries. A per-connection worker takes the next frame only after the previous one has its reply.

## Verification

- `client-core/test/e2e/convergence.test.ts` (1,000 random ops, adds, moves and deletes, across two gateways) and the drills: 0 Unsent edits.
- The cost was measured in §10.1 "Per-connection throughput" (`production/load-tests/README.md`): one op in flight per connection.

## What I learned

- Asynchronous code needs its ordering guarantees written down, because nothing preserves them by default. "Strictly in arrival order, one at a time" is now in `ClientConn`'s class comment.
- The simple fix (one at a time) has a cost: per-connection throughput is bounded by one round trip per op. I flagged it at the time and measured it in Phase 4b.
