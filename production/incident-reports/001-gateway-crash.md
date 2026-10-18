# Incident 001: a gateway crashes under its clients

**Date:** 2026-10-16 (found), rerun on every CI push since · **Type:** induced failure (SPEC §8 row 1) · **Environment:** dev compose stack (2 gateways, 3 Session instances), local and CI · **Status:** passing; one bug found and fixed (`7a98605`)

## Summary

We killed a gateway (`docker compose kill -s SIGKILL gateway1`) while two clients, one on each gateway, kept editing the same board. The promise in §8 row 1: *clients reconnect to another gateway and resend their pending queues; nothing ACKed is lost.*

The promise held. Running the scenario next to the other §8 tests found one real bug: **a client could wait in "connecting" forever** after a gateway restart.

## What broke

Right after a gateway restart, Docker's port forwarder accepted the client's TCP connection before the gateway process was listening behind it. The WebSocket handshake then never completed and never failed. The client had no timer on that phase, so it stayed in `connecting` with its queue unsent.

## Impact

- In the test suite: the lost-broadcast test, which ran right after the gateway-crash test, hung until its timeout.
- In production terms: a user whose browser picked that moment to reconnect would have seen "connecting" until they reloaded. Edits stayed in their local queue, so nothing was lost, but nothing synced either.

## Detection

The end-to-end suite (`client-core/test/e2e/failures.test.ts`): the lost-broadcast test, run right after the gateway-crash test, timed out waiting for its client to join.

## Root cause

The client's connection state machine had timers for backoff and, once joined, for liveness, but none between "socket opened" and "JOINED". A connection the network accepted but the server never answered had no way out.

## Fix

`7a98605`: a connection that has not reached `JOINED` within 10s is abandoned, and the client moves to the next gateway (SPEC amendment 10 approved the 10s JOIN timeout).

## Verification

`failures.test.ts` "1: gateway crash" runs on every CI push. It makes 30 edits on each client, kills gateway1, makes 30 more (queued on the client that lost its gateway), then requires:

- that client rejoined exactly once more, on gateway2;
- both clients on the same state hash;
- every ACKed op in `board_ops`.

Latest CI run (39116214175, commit `3a3284c`): `gateway crash: client back on gateway2 0.1s after the kill`, passed. The lost-broadcast test that found the hang passed in the same run (`8 dropped, 8 SYNC_REQUESTs, converged`).

## What I learned

- Every state a client can wait in needs a timer that leads out of it. The two waits we had covered (backoff, liveness) were the ones we had thought about; the hang was in the one we had not.
- Tests that run back to back find bugs that each test alone does not: the crash test left the stack in exactly the state that exposed the next test's hang.
