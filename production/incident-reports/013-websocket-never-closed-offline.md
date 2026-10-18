# Incident 013: Chromium's WebSocket never closed when the network went away

**Date:** 2026-10-17 · **Type:** client bug, found by a browser test · **Environment:** Playwright, Chromium, dev compose stack · **Status:** fixed in `d0ebd87` (amendment 18)

## Summary

The Phase 3 offline test takes a browser offline (`context.setOffline(true)`) and expects edits to queue and the app to show it is offline. Instead the client kept saying **"joined"**. Chromium's offline mode, like a real dropped network, leaves an open WebSocket open: no `close`, no `error`. The client waited for an event that never came.

## What broke

The client's only signal that a connection had died was the socket closing.

## Impact

- A user whose network dropped (Wi-Fi gone, laptop lid closed and reopened elsewhere) would see "joined" while nothing was being sent or received.
- Their edits would queue locally and be safe (§7.2), but they would not know they were offline. Other users' edits would stop arriving without any sign.

## Detection

The Phase 3 offline browser test (`web/e2e/offline.spec.ts`): it asserted the offline state and timed out waiting for it.

## Root cause

TCP does not notice a silent network on its own: with no packets in flight, nothing times out for minutes. A WebSocket only learns the peer is gone when a send fails or a keep-alive is missed, and browsers do not send keep-alives on an idle WebSocket. The client had no liveness check of its own.

## Fix

`d0ebd87` (amendment 18):

- **Liveness:** the owner sends `BOARD_VERSION` every 5s to every client of a board, so 15s with no message at all is a reliable death signal. The client then drops the connection and reconnects (`SILENCE_MS`).
- **Browser events:** the `offline` event drops the connection at once, and `online` reconnects without waiting for backoff.

## Verification

- `web/e2e/offline.spec.ts` and the scripted offline hour (`docs/offline-hour-scripted.md`: 60 minutes offline, 240 edits, converged 11,457 ms after reconnecting, 0 Unsent).
- Guard checks `silence-drop` and `offline-event`: each test fails with its guard removed.

## What I learned

- "The socket will tell us when it dies" is false on real networks. A connection is alive only if something was heard from it recently, so the protocol needs a heartbeat it can count on.
- Only a real browser found this. The headless Node client's sockets behave differently, which is why Phase 3 runs Chromium against the real stack.
