# Incident 008: rolling deploys under live editing

**Date:** 2026-10-17 (rehearsals), 2026-10-18 (four deploys to the trial host) · **Type:** induced (SPEC §8 row 15) and real (trial deploys) · **Environment:** production compose stack, rehearsed locally behind a Cloudflare tunnel, then on the AWS host (t4g.small, Sydney) · **Status:** every deploy lost nothing; deploy problems found and fixed, see [019](019-metrics-public-on-the-trial-host.md)

## Summary

`deploy/prod/deploy.sh <sha>` takes a backup, pulls the images, migrates (forward-only), then replaces session1, session2 and the gateway one at a time. The promise (§8 row 15): *the gateway sends `RECONNECT_ELSEWHERE` and drains; each owner flushes, snapshots and releases its lease (§2.4); clients reconnect and resend; nothing is lost.*

Checked with a live-editing test running throughout: three headless clients editing one board over the public URL for 5–6 minutes. Afterwards, every op any client saw ACKed must be in the board's history, all clients must converge, and nothing may be Unsent.

## What broke

Nothing in the edit path. The deploy process itself had gaps, found during the 2026-10-18 deploys and written up in [019](019-metrics-public-on-the-trial-host.md).

## Impact (`deploy/prod/rehearsal-results.txt`)

| Deploy | Ops through it | ACKed but missing | Unsent | Rejoins | Converged |
|---|---|---|---|---|---|
| Rehearsal run 1 (2026-10-17) | 2,797 | 0 | 0 | not counted (see below) | yes |
| Rehearsal run 2 | 2,334 | 0 | 0 | 6 (gateway, then Caddy) | yes |
| Rehearsal of `1000c46` and its rollback (2026-10-18) | 1,834 and 1,854 | 0 | 0 | 3 each | yes |
| Trial host, `1000c46` (amendment 27) | 2,315 | 0 | 0 | 3 | yes |
| Trial host, `41b6b9a` | 2,306 | 0 | 0 | 3 | yes |

The other two trial deploys (`eca3941`, `e274eba`) ran without the live-editing test. Each ended healthy with the public health check passing.

## Detection

The live-editing test (`client-core/test/smoke`, `SMOKE_LIVE_SECONDS`). Rehearsal run 1 counted the wrong event for reconnects: a clean close does not log `connection_dropped`. Run 2 counts JOINs instead.

## Root cause

Not a defect in the edit path. Each replacement is graceful:
- an owner on SIGTERM stops taking ops, flushes its batch, snapshots and releases its leases, and the other instance claims;
- the gateway on SIGTERM sends `RECONNECT_ELSEWHERE`, and clients reconnect with backoff and resend their queues.

## Fix

None in the edit path. `deploy.sh` gained three checks (`41b6b9a`, `e274eba`):
- the VM's checkout must be at the deployed SHA;
- Caddy's config is reloaded from a mounted directory;
- `/metrics/prometheus` must answer 404 from outside.

## Verification

The fourth trial deploy (`e274eba`) printed `ok <- public /healthz` and `404 <- public /metrics/prometheus (internal only)`.

## What I learned

- A deploy test needs the same end-state checks as a drill: zero missing, zero unsent, converged. "The containers are healthy" says nothing about the edits in flight.
- The edit path was the part I worried about and it held every time. The deploy *process* is where the surprises were: which files the VM actually had, and what a reload actually reads.
