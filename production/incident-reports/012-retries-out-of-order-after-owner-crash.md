# Incident 012: valid edits ended in Unsent edits after an owner crash

**Date:** 2026-10-16 · **Type:** client bug, found by a drill · **Environment:** dev compose stack, kill-owner drill · **Status:** fixed in `5a2a495`

## Summary

In the first runs of the kill-owner drill ([002](002-owner-crash.md)), **2–3 valid edits per run** ended in Unsent edits with `ADD_CARD INVALID_OP: placement.list_id names no list on this board`. The server had done nothing wrong: the client was resending ops out of order after the crash.

## What broke

The client's retry logic, after the owner it was talking to died.

## Impact

Each run, a few of a user's own edits were rejected and shown as Unsent, although every one was valid. The user would have had to redo them. Nothing ACKed was lost, but edits the user believed they had made were not saved.

## Detection

The kill-owner drill's end-state check: *nothing ends in Unsent edits*. The server-side checks (every ACKed op stored, convergence) all passed.

## Root cause

Two problems in the client:

1. **Each failed op had its own backoff timer.** After the owner died, an `ADD_LIST` and the `ADD_CARD` placed in it each failed with a retryable error and each scheduled its own resend. With random jitter, the `ADD_CARD` could reach the new owner first and fail the reference check.
2. **Even in order, an op could be judged without its predecessor.** An op sent right after one that hit the dead owner reached the new owner while its predecessor was still waiting to be resent.

## Fix

`5a2a495` (behaviour approved in amendment 10):

- Every retryable error resends the **whole queue, in order, on one timer**.
- `INVALID_OP` is final only for the op at the **head** of the queue. An op with an unacknowledged op of its own ahead of it is held and judged again once everything ahead is ACKed.

## Verification

- The kill-owner drill: 0 Unsent edits in every run since (five Phase 2 runs, every CI push, and the 2026-10-18 rerun).
- `client-core/test/unit` covers head-of-queue `INVALID_OP` and in-order resend.

## What I learned

- A drill must check the user's end state, not only the server's. The server kept every promise and users still lost edits to the Unsent list.
- Independent timers for dependent work recreate the reordering the gateway fix ([011](011-gateway-reordered-ops.md)) had just removed. Order has to hold end to end, client included.
