# Incident 018: a board was deleted while its owner had it loaded

**Date:** 2026-10-18 · **Type:** real bug, found on the live trial host · **Environment:** AWS t4g.small (Sydney), production compose · **Status:** fixed by amendment 27, commit `f21aac5`; deployed (`1000c46`) and verified live

## Summary

The smoke test ends by deleting its two test accounts, which deletes their board (§9.4). On the live host, session1 still had that board loaded. Its next snapshot **failed on the `board_snapshots` foreign key**: the board row was gone. Reading the code around it found worse cases in the same gap.

## What broke

The owner's assumption that a board it holds exists in Postgres.

## Impact

- **On the host:** a failed snapshot and an ERROR in the logs, for a test board. No user data.
- **In the same gap, had a user hit them:**
  - An edit committing after the deletion updated zero rows and was reported as **`ALERT fenced out by a newer owner`**, a false alarm for the most serious condition the system can report.
  - A reconcile ([010](010-commit-with-unknown-outcome.md)) that found no board row **retried forever**. Amendment 26 never unloads a reconciling board, so it would have stayed in memory for good.

## Detection

Reading the session logs on the live host after the smoke test: one ERROR, the snapshot's foreign-key failure.

## Root cause

Deletion is a REST operation in the gateway; the board's owner is a Session instance that learns about changes only through its own commits. Nothing told the owner the board was gone, and its three Postgres paths each read "zero rows" as a different condition:

| Path | What zero rows meant to it |
|---|---|
| commit | fenced out by a newer owner |
| reconcile | try again |
| snapshot | an exception |

## Fix

`f21aac5` (amendment 27, SPEC §2.6):

- **Announced:** the gateway publishes deleted boards on the Redis channel `board-deleted`. The owner drops the board at once: no snapshot, lease released. Queued requests get `UNAUTHORIZED "board deleted"`, so an edit in flight lands in its sender's Unsent edits, not in silence.
- **If the message is lost:** the owner finds out at its next commit (zero rows *and* no board row means deleted, not fenced; no alert), reconcile (stops) or snapshot (foreign key and no row), and drops the board the same way.

## Verification

- `BoardDeletedTest` (3 tests):
  - announced and dropped with no snapshot;
  - an edit after deletion gets `UNAUTHORIZED`, not a fence alert;
  - a reconcile on a deleted board ends.
- Guard checks `board-deleted-commit`, `board-deleted-reconcile` and `board-deleted-announced`: each test fails without its guard.
- **Rehearsed** under live editing: deploy to `1000c46` and back, 1,834 and 1,854 ops, 0 missing, 0 unsent.
- **Live:** deployed to the host, with 2,315 ops through the deploy, 0 missing, 0 unsent. Afterwards the smoke test's account deletion again deleted its loaded board. Session logs: **0 ERROR**, and `board deleted (announced): dropped` once.

## What I learned

- "Zero rows" is an answer to a question I did not ask. Each path must check *why* (fenced, or deleted) before acting, especially before raising an alert.
- The trial host found this in its first hour, with the project's own smoke test. Real deployments exercise paths, like deletion during use, that the drills did not.
