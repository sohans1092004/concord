# Incident 020: the client could send a telemetry report the gateway refuses

**Date:** 2026-10-18 · **Type:** client bug, found by a new unit test before deploy · **Environment:** `client-core` telemetry (SPEC §9.5) · **Status:** fixed in `eca3941`, before the feature reached the trial host

## Summary

Clients report sync latency and trace spans to `POST /api/telemetry`. The gateway refuses any report over 100 items with 413, and the whole report is lost. The client checked the cap *after* adding to its buffer, and one peer edit adds **two** items (a latency sample and a `peer.render` span). So a buffer holding 99 items became 101 before the check flushed it.

## What broke

The client's buffer cap, by one item.

## Impact

None in production: found before deploy. Had it shipped, a busy board would have lost roughly one report in fifty to a 413, with all its samples. That would have biased the sync-latency dashboard toward quiet periods.

## Detection

A new unit test (`client-core/test/unit/features.test.ts`, "§9.5 telemetry"): 250 peer edits, every report must hold at most 100 items. It failed on its first run.

## Root cause

The check `if (items >= 100) flush()` ran after up to two pushes, which assumes items arrive one at a time.

## Fix

In `eca3941`, before the feature shipped: flush *before* adding whenever the new items would not fit; then add; then flush at exactly 100.

## Verification

- The unit test passes: 250 sync samples and 250 `peer.render` spans across reports of at most 100 items, and the client's own op never reported as a peer's.
- Guard check `telemetry-client-cap`: without the pre-flush the test fails, and restored it passes.
- The gateway side has its own guard checks (`telemetry-size`, `telemetry-rate`).

## What I learned

- A cap must be checked against the largest single addition, not one at a time.
- Writing the test at the same time as the feature found this in minutes. Without it, the 413s would have been silent: the client drops a refused report by design.
