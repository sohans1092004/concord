# Incident 021: a long offline queue took far too long to sync

**Date:** 2026-10-18 · **Type:** client bug, found by the §10.1 offline measurement · **Environment:** dev compose stack, the accelerated 8h and 24h offline runs · **Status:** fixed by amendment 31, commit `816037b`; deployed to the trial host in `1b3c843` (2026-10-18, 2,351 ops through the deploy, 0 lost)

## Summary

A client that comes back after hours offline must resend its queue. With 1,885 queued edits (8 hours, accelerated), syncing took **487 seconds**. With about 5,760 (24 hours), **5,243 were still pending after 10 minutes**, and the run failed on its timeout. Nothing was lost: every edit stayed queued and safe. But the user would have waited a long time to see their day's work reach anyone else, and the gateway was flooded while they waited.

## What broke

The client's resend path, under the gateway's per-connection rate limit (20 ops/s, burst 50, §9.4).

## Impact

- **Users:** no data loss. A long offline session would have synced at under 4 ops/s, and slower the longer the queue.
- **The gateway:** each cycle received thousands of frames and refused almost all of them, about as many refusals as there were edits queued.
- **The trial host:** runs the old client until the next deploy. No trial user had queued anywhere near this much.

## Detection

The §10.1 offline-merge measurement (`client-core/test/load/load.test.ts`, `LOAD=offline`), at 8 and 24 simulated hours. The real-time 1-hour run had shown nothing: 240 edits fit in a few bursts.

## Root cause

Three behaviours, each reasonable alone:

1. **On JOIN, the client sent its entire queue at once** (§7.3 step 5: "resend the whole pending queue").
2. **The gateway rate-limits OP frames on arrival** and answers each one over the limit with `RETRY_LATER`, so a flood is refused at once rather than queued.
3. **On any `RETRY_LATER`, the client resent the whole queue** after a backoff. That backoff resets on every ACK, so with a few ACKs per cycle it stayed short.

So every cycle sent the entire queue, got a burst's worth accepted, and was refused for the rest. The work per cycle grew with the queue while the progress per cycle stayed at about one burst: 3.9 ops/s for 1,885 queued, under 1 op/s for 5,701.

## Fix

`816037b`, amendment 31 (§7.3 step 5). The client paces every OP frame it sends with a token bucket of the gateway's shape (20 ops/s, burst 50):
- the bucket is full on each new connection, and timed on real time;
- `RETRY_LATER` still resends the queue from the start, in order, but through the same pacer, never as a burst.

## Verification

- **Unit test:** "a long offline queue is sent at the gateway's pace, and RETRY_LATER does not re-flood it". 200 queued edits send exactly 50 at once, then about 20 a second; a `RETRY_LATER` mid-drain adds at most what the bucket holds.
- **Guard check `client-send-pacing`:** without the pacing, the test fails (`expected 200 to be 50`).
- **The same measurements, after the fix** (`production/load-tests/README.md` §6):

| | Before | After |
|---|---|---|
| 8h: 1,885 edits | 487s | **92s** |
| 24h: 5,701 edits | not done after 600s | **283s** |
| Lost / unsent | 0 / 0 | 0 / 0 |

Both now drain at the gateway's 20 ops/s. The pre-fix outputs are in `production/load-tests/raw/before-amendment-31/`.

## What I learned

- A limit enforced on one side needs a matching pace on the other. The gateway's limit was tested, and the client's in-order resend was tested; nothing tested the two together at volume.
- "Resend everything on any error" is safe for correctness, since the server dedups, but its cost grows with the queue. The real-time 1-hour run was too small to show it.
- Measuring at the extremes the SPEC names (8h, 24h) found what the comfortable size (1h) could not.
