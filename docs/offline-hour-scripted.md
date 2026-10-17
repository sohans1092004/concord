# Offline hour, scripted (SPEC §10 Phase 3 exit)

**This run was scripted, not done by hand.** Playwright drove two real Chromium browsers in
real time, with no developer tools. The manual run is `docs/manual-offline-run.md`, which
the project owner will do and record separately.

- **Command:**
  `cd web && OFFLINE_HOUR=1 npx playwright test e2e/offline-hour.spec.ts --output=test-results-hour`
  (`web/e2e/offline-hour.spec.ts`).
- **Setup:** against the compose stack, on commit `8036e03`'s build, which includes the
  §7.2 append-first saving (amendment 21) and per-connection presence (amendment 22).
- **Run:** 2026-10-17, 05:51–06:51 UTC, one laptop, with no other test running.
- **Result:** Playwright reported **1 passed (1.0h)**.

What it does:
- Browser B goes offline (`context.setOffline`) for 60 real minutes.
  - It makes one random edit every 15s: adds, moves within and across lists, title and label
    edits, deletes and restores of cards and lists.
  - It reloads at minute 15, and closes its tab and opens a new one at minute 30.
- Browser A stays online and edits every 30s.
- Then B goes back online without any other action. Both must converge to the same state
  hash, with nothing in Unsent edits.

## Log (verbatim)

```
2026-10-17T05:51:12.593Z B offline; board bae8488d-e9da-4941-9f0d-5cb5f1069cf9
2026-10-17T05:51:12.602Z minute 0: B 1 edits (1 pending), A 0 edits
2026-10-17T06:01:13.463Z minute 10: B 41 edits (41 pending), A 20 edits
2026-10-17T06:06:14.161Z reload at minute 15: 60 pending before, 60 after
2026-10-17T06:11:14.720Z minute 20: B 81 edits (81 pending), A 40 edits
2026-10-17T06:21:15.956Z tab closed and reopened at minute 30: 120 pending, adopted 120
2026-10-17T06:21:15.973Z minute 30: B 121 edits (121 pending), A 60 edits
2026-10-17T06:31:17.069Z minute 40: B 161 edits (161 pending), A 80 edits
2026-10-17T06:41:18.182Z minute 50: B 201 edits (201 pending), A 100 edits
2026-10-17T06:51:19.114Z hour over: B made 240 edits offline, 240 pending; reconnecting
2026-10-17T06:51:30.596Z converged 11457ms after reconnecting; version 380; hash 48c201ca1c283209f29f0c272b814d9a70f2eebe1fab8c19c56150409d6d4584; unsent 0 and 0
  1 passed (1.0h)
```

## Result

| Field | Value |
|---|---|
| Offline duration | 60 minutes |
| Edits on B while offline | 240 (all 240 pending at reconnect) |
| Edits on A meanwhile | 120 (one every 30s; 100 by minute 50) |
| Reload at minute 15 | 60 pending before, 60 after |
| Tab closed and reopened at minute 30 | 120 pending; the new tab adopted all 120 |
| Reconnect to converged | 11,457 ms |
| Converged | yes: board version 380, both browsers on state hash `48c201ca…d6d4584` |
| Unsent edits | 0 on A, 0 on B |
| Test status | passed |

## The earlier attempt

The first run (03:34–04:34 UTC, commit `2dfb84c`) reached the same checks and passed them:
240 offline edits, converged in 11,237 ms, 0 unsent. Playwright then marked it failed in
teardown: other test runs I started during the hour cleared `test-results/` and deleted its
trace file. The test now records no trace, and runs with its own output directory and
nothing else running.
