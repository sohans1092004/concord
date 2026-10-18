# Incident 016: the memory soak failed, and the growth was by design

**Date:** 2026-10-17 · **Type:** finding from the Phase 4a 2-hour memory soak · **Environment:** dev compose stack (2 gateways, 3 Session instances), then the production compose with its memory limits · **Status:** resolved: idle boards unload (amendment 26, `00bea31`); growth measured (`7207093`); later soaks flat

## Summary

The Phase 4a criterion: *every JVM's heap after a full GC is flat across the second hour* (slope ≤ 2.0 MB/h). Soak run 1 **failed** on session3, at 4.61 MB/h.

It was not a leak. The heap grew with the boards that instance owned, because:
- the workload grew those boards without end;
- nothing ever unloaded a board nobody had open.

## What broke

The criterion, on one JVM (`deploy/soak/results.txt`):

| JVM | Second-hour slope | Verdict |
|---|---|---|
| gateway1 / gateway2 | 0.11 / −0.08 MB/h | flat |
| session1 | 0.03 MB/h | flat |
| session2 | 1.62 MB/h | flat, but close |
| **session3** | **4.61 MB/h** | **growing** |

Load: 21,900 edits, 5,170 connections, 62 slow consumers cut. There were also 4 errors, from a disk stall (Postgres checkpoints of 62s and 54s) while images were being built on the same laptop. The 16 commits that failed then were all reconciled, and no data was lost.

## Impact

No user-visible impact in the run. Left alone, a Session instance's heap would grow with the history of every board it had ever loaded, until the container's memory limit killed it. That matters on a 2 GB host.

## Detection

The soak's verdict script (`deploy/soak/verdict.py`), from heap-after-full-GC samples every 5 minutes.

## Root cause

- **Growth tracked the active boards each instance owned:** session3 owned 3 of the 4 soak boards, session2 one, session1 none. That is about 1.5 MB/h per active board, about 1 KB per op.
- **The heap histogram showed CRDT state, not garbage:** 12,016 `Lww$Register` and 8,878 `Hlc` objects. On one board, 1,175 of 1,228 added cards had been deleted. Their tombstones are kept for the board's life (SPEC decision 9). Each board also keeps its last 10,000 ACKed op_ids (§4.3), and that cache was still filling.
- **The SPEC had no rule for unloading a board.** A loaded board stayed in memory until its instance stopped or handed it over.
- **The workload added and deleted cards forever**, so it grew board state by design. It could not be flat on a JVM owning an active board, leak or not.

## Fix

- `00bea31` (amendment 26): an owner **unloads a board** after 10 minutes with no request and no gateway subscribed to its channel, through the graceful handover path (snapshot, release the lease, close). It never unloads while reconciling, with ops in the mailbox or batch, or when Redis cannot count subscribers.
- `7207093`: the growth measured on a real owner (`StateGrowthTest`, 3 runs):
  - 163 B per op while the 10,000-op ACK cache fills;
  - about 0 per op once it is full on a fixed set of cards;
  - about 3.0 KB of heap and 534 B of snapshot per deleted card.
- The soak's default workload became fixed-size (renames, moves and presence on a fixed set of cards), so it tests for leaks rather than for growth the SPEC allows.

## Verification

- **Soak run 2** (`92844f3`, production compose, 125 min): **passed**, every JVM flat in the second hour. Gateway −0.01 MB/h; sessions −0.16 and −0.00 MB/h; 118,863 edits, 0 errors.
- **Soak run 3**, with the 2 GB host's memory limits (`d0678c4`): flat heaps, 0 OOM kills.
- `IdleUnloadTest`, plus guard checks `idle-unload-not-reconciling` and `idle-unload-subscribers`.

## What I learned

- A memory soak needs a workload whose correct heap is flat. Otherwise it measures the data model, not leaks.
- "Bounded" has to cover time, not just size. Every queue was bounded (amendment 9), but the set of loaded boards was not.
- Measuring the growth per op and per deleted card turned "it grows" into numbers someone can plan capacity with.
