# Offline hour, scripted (SPEC §10 Phase 3 exit)

**This run was scripted, not done by hand.** Playwright drove two real Chromium browsers in
real time, with no developer tools. The manual run is `docs/manual-offline-run.md`, which
the project owner will do and record separately.

- **Command:** `cd web && OFFLINE_HOUR=1 npx playwright test e2e/offline-hour.spec.ts`
  (`web/e2e/offline-hour.spec.ts`).
- **Setup:** against the compose stack, on commit `2dfb84c`'s build.
- **Run:** 2026-10-17, 03:34–04:34 UTC, one laptop.

What it does:
- Browser B goes offline (`context.setOffline`) for 60 real minutes.
  - It makes one random edit every 15s: adds, moves within and across lists, title and label
    edits, deletes and restores of cards and lists.
  - It reloads at minute 15, and closes its tab and opens a new one at minute 30.
- Browser A stays online and edits every 30s.
- Then B goes back online without any other action, and both must converge, with nothing in
  Unsent edits.

## Log (verbatim)

```
2026-10-17T03:34:14.908Z B offline; board 2617f155-a383-4275-bc78-e1b739da3cf6
2026-10-17T03:34:14.926Z minute 0: B 1 edits (1 pending), A 0 edits
2026-10-17T03:44:16.915Z minute 10: B 41 edits (41 pending), A 20 edits
2026-10-17T03:49:17.850Z reload at minute 15: 60 pending before, 60 after
2026-10-17T03:54:18.678Z minute 20: B 81 edits (81 pending), A 40 edits
2026-10-17T04:04:20.669Z tab closed and reopened at minute 30: 120 pending, adopted 120
2026-10-17T04:04:20.700Z minute 30: B 121 edits (121 pending), A 60 edits
2026-10-17T04:14:22.262Z minute 40: B 161 edits (161 pending), A 80 edits
2026-10-17T04:24:23.855Z minute 50: B 201 edits (201 pending), A 100 edits
2026-10-17T04:34:25.193Z hour over: B made 240 edits offline, 240 pending; reconnecting
2026-10-17T04:34:36.458Z converged 11237ms after reconnecting; version 380; hash d316a445a94be77bd7484578611c096e3364c7895b24334ac247fc7041d54c61; unsent 0 and 0
```

## Result

| Field | Value |
|---|---|
| Offline duration | 60 minutes |
| Edits on B while offline | 240 (all 240 pending at reconnect) |
| Edits on A meanwhile | about 120 (one every 30s) |
| Reload at minute 15 | 60 pending before, 60 after |
| Tab closed and reopened at minute 30 | 120 pending; the new tab adopted all 120 |
| Reconnect to converged | 11,237 ms |
| Converged | yes: board version 380, both browsers on state hash `d316a445…41d54c61` |
| Unsent edits | 0 on A, 0 on B |

## Test status: reported as failed, in teardown

Playwright marked this run **failed**. Every assertion passed first: both browsers on the
same hash, and `unsent` 0 on each. The log line `converged … unsent 0 and 0` is printed only
after those checks. The failure came after them, in `a.close()`:

```
Error: apiRequestContext._wrapApiCall: ENOENT: no such file or directory,
open '.../web/test-results/.playwright-artifacts-0/traces/...'
```

Playwright could not save the run's trace file. I ran other Playwright tests from the same
`web/` directory during the hour (board and perf tests), and each run clears
`test-results/`, deleting this run's trace directory. That is a fault in how I ran it, not
in Concord. A clean rerun, with nothing else running, is needed before this counts as a pass.
