# ADR-008: Snapshot every 500 ops or 60s, keep three

Status: Accepted (SPEC §5.1, §6.2; amendment 28). Date: 2026-10-18, after the recovery measurement.

## Context

A new owner rebuilds a board from its newest valid snapshot plus the tail of ops after it (§6.2). Two things grow without snapshots: the tail it must replay, and `board_ops`, which op retention can only trim up to a snapshot (amendment 28). Snapshots cost a write of the whole board state, which for the largest board §1.2 targets (2,000 cards) is about 0.9 MB of JSON. A snapshot can also be corrupt, and a check on load must find one to fall back to (§8 row 13).

## Decision

- **An owner snapshots a board after 500 ops, or 60s after its last snapshot if anything changed** (`BoardActor.SNAPSHOT_EVERY_OPS`, `SNAPSHOT_EVERY_MS`). A graceful handover (§2.4) and an idle unload (amendment 26) also snapshot.
- **Every snapshot stores the SHA-256 of its canonical encoding;** a claim loads the newest one whose hash matches and replays the ops above it.
- **Keep three.** A corrupt newest snapshot falls back to the one before, with a longer tail.
- **Op retention deletes only ops at or below the *oldest* kept snapshot** and older than 30 days (amendment 28), so every fallback still has its complete tail.

## What the measurements show

The recovery measurement (`production/load-tests/README.md` §9) killed the owner of a board padded to a snapshot boundary, with 0 or 499 ops after it (499 is the most a tail can hold before the next snapshot). It read the new owner's own claim time: fence, lease, Postgres fence, load and verify the snapshot, replay, announce.

| Cards | Snapshot | Tail | Claim |
|---|---|---|---|
| 10 | 5 KB | 0 / 499 | 61ms / 29ms |
| 2,000 | 942 KB | 0 / 499 | 77ms / 342ms (mean of 3 claims in that window) |

From the kill to the first ACK took 13–20s in every case. That is almost all the 15s lease expiring, plus client backoff; the claim itself is under a second even at the largest size with a full tail. The owner-crash drill's "serving within 5s of lease expiry" target was met at 1,565ms (`deploy/drills/results.txt`).

## Alternatives

- **Snapshot more often (every 100 ops).** It shortens a replay that is already under a second, and writes a 0.9 MB snapshot five times as often on large boards.
- **Snapshot less often (every 5,000 ops).** Ten times the worst tail. Replay is cheap per op, but the tail also bounds retention, so `board_ops` would keep up to ten times as many rows past the 30-day window.
- **Only by time, or only by count.** Count alone leaves a quiet board's last edits unsnapshotted for good; time alone writes snapshots of a busy board every minute regardless of how much changed. The 60s rule fires only when something changed.
- **Keep one snapshot.** A single corrupt snapshot would force replay from version 0, which retention may already have deleted. Keeping three, with retention bounded by the oldest, keeps a fallback complete (`RetentionTest`, guard check `retention-oldest-snapshot`).

## Consequences

- **Recovery time is dominated by the lease, not by snapshots or replay;** shortening it is a lease decision (ADR-007), not this one.
- **Snapshot writes cost:** at 2,000 cards about 0.9 MB of JSON per snapshot, at most every 500 ops or once a minute per active board, plus three kept per board in `board_snapshots`.
- **On a quiet board** the 60s rule leaves at most a minute of ops outside a snapshot.
- **The 60s timer also fires during bursts slower than 500 ops a minute,** so real tails are usually shorter than 499. The measurement had to finish its tail within a minute to see the worst case.
