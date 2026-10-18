# Incident 019: /metrics was public on the trial host, and two deploys did not close it

**Date:** 2026-10-18 · **Type:** real exposure on the live host, then two deploy-process bugs · **Environment:** AWS trial host (`concord-serve.duckdns.org`), Caddy 2.8.4 in front of the gateway · **Status:** fixed; the fourth deploy (`e274eba`) verified `/metrics` and `/metrics/prometheus` answer 404 from outside

## Summary

The gateway's `/metrics` was reachable from the internet through Caddy. The fix, a Caddy rule answering 404 for `/metrics*`, was written in `eca3941` and deployed, but `/metrics` stayed public through **two more deploys**:

1. The VM never received the new Caddyfile. The deploy command skipped the runbook's `git pull`, and `deploy.sh` did not check for it.
2. Caddy then reloaded the *old* file even after the checkout updated it, because it was bind-mounted as a single file.

## What broke

- **The exposure:** Caddy proxied every path to the gateway, including the monitoring endpoint.
- **Deploy 2 (`eca3941`):** it pulled the new images, but the Caddyfile comes from the VM's git checkout, which was still at the old commit. Compose also does not restart Caddy when a mounted file changes.
- **Deploy 3 (`41b6b9a`):** `deploy.sh` now required the checkout to match and ran `caddy reload`, and its new final check printed `WARNING: public /metrics/prometheus answered 200, expected 404`.

## Impact

- **Exposed:** operational counters only. Before `eca3941` that was a 115-byte JSON of totals. From `eca3941` it was the Prometheus text:
  - per-board op counters, which include **board IDs**;
  - connection counts and latency histograms.
- **Not exposed:** no tokens, user data, emails or board contents.
- **Window:** from the stack's first start on the host to the fourth deploy, which finished at 09:37:32 UTC on 2026-10-18. At that point Postgres reported "Up 6 hours".
- **Trial users:** the host had one or none for most of it (backups at 08:36, 09:03, 09:09 and 09:36 UTC counted 0, 1, 1 and 0 users).
- Board IDs are random UUIDs and opening a board needs membership (§9.4), so a leaked ID alone gives no access.

## Detection

1. **The exposure:** found while building observability (Phase 4b). A request to the public URL's `/metrics` returned 200 (120 bytes).
2. **Deploy 2:** checked from the laptop after the deploy: `/metrics` 200 (115 B) and `/metrics/prometheus` 200 (237 B). The container list showed Caddy "Up 5 hours".
3. **Deploy 3:** its own new check printed the WARNING above.
   - The response carried Caddy's headers (HSTS, Alt-Svc) and the gateway's body.
   - `caddy adapt` of the committed Caddyfile put the 404 route before `reverse_proxy`, so the file was right and Caddy was not reading it.
   - Confirmed on the VM: `docker compose exec caddy grep -c metrics /etc/caddy/Caddyfile` printed `0`.

## Root cause

1. **No internal/external split at the proxy.** Caddy forwarded everything, and nothing checked which paths the internet could reach.
2. **Deploy files came from an unchecked source.** `deploy.sh` pinned the image SHA but not the checkout holding `compose.yaml`, the `Caddyfile` and `summary.sh`.
3. **A single-file bind mount pins the file's inode.** `git checkout` replaces a changed file with a new one rather than editing it in place, and the container kept the old inode, reload or not.

## Fix

- `eca3941`: `@internal path /metrics /metrics/*` → `respond @internal 404` in the Caddyfile; `summary.sh` reads metrics inside the Docker network.
- `41b6b9a`: `deploy.sh` refuses to run unless the checkout is at the deployed SHA, always runs `caddy reload` (graceful), and ends by requiring `/metrics/prometheus` to answer 404 from outside. The runbook's deploy and rollback rows match.
- `e274eba`: compose mounts the directory `deploy/prod/caddy/` instead of the file.

## Verification

Fourth deploy (`e274eba`, 2026-10-18 09:36–09:37 UTC):
- Caddy was recreated ("Up 1 second") and reloaded;
- `deploy.sh` printed `404 <- public /metrics/prometheus (internal only)`;
- from the laptop afterwards: `/metrics` 404, `/metrics/prometheus` 404, `/healthz` 200.

## What I learned

- Every deploy should end by checking from the outside the thing it was meant to change. The check added in `41b6b9a` is what caught deploy 3.
- Pinning the images is half of a reproducible deploy; the config files need pinning too. `deploy.sh` now pins both.
- A bind-mounted single file and an editor-style replace do not mix. Mount the directory.
- I gave the deploy command without the runbook's `git pull`. The script, not the operator's memory, should hold every step.
