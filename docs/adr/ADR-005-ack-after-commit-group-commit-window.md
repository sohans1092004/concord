# ADR-005: ACK only after commit; a 5ms group-commit window

Status: Accepted (SPEC §6.1; amendments 6 and 32). Date: 2026-10-18, after the §10.1 batching measurement.

## Context

An `OP_ACK` is the server's promise that an edit is stored: the client drops it from its queue and never resends it. If the ACK goes out before the commit and the commit then fails, the edit is gone with nobody holding it. Committing every op on its own keeps the promise, but costs one Postgres transaction per op. A board actor (ADR-004) can instead collect the ops that arrive within a short window and commit them together. The window trades latency, since every op waits for it, against commits per second.

## Decision

- **Persist, then apply, then ACK, then publish** (§6.1; v3 fixed v2's apply-first order). Memory holds only committed ops, so a snapshot is always a correct state. A commit with an unknown outcome stops the actor until it has reconciled from `board_ops` (amendment 6 B; incident report 010).
- **Group commit with a 5ms window** (`CONCORD_BATCH_MS`, default 5; amendment 32). The first op of a batch sets the deadline; everything admitted until then commits in one transaction and is ACKed together.

Measured with 30 editors at 1 op/s each on one board, 60s per setting, on the development laptop (`production/load-tests/README.md` §3):

| Window | Sync p50 | p95 | p99 | Ops per commit | Commits/s |
|---|---|---|---|---|---|
| 0ms | 18ms | 36ms | 47ms | 1.00 | 29.7 |
| **5ms** | **24ms** | **34ms** | **39ms** | **2.55** | **11.6** |
| 15ms (the SPEC's first value) | 35ms | 46ms | 53ms | 2.53 | 11.8 |
| 30ms | 49ms | 58ms | 64ms | 1.80 | 16.5 |

5ms gets the batching of 15ms (2.55 against 2.53 ops per commit, under half the commits of 0ms) for 11ms less median latency. 30ms adds latency without batching more.

## Alternatives

- **ACK on receipt, commit later.** Lowest latency. An ACK would then mean "received", and a crash between the ACK and the commit would lose an edit the client has already forgotten. Rejected: it breaks the system's first promise.
- **0ms (commit each op alone).** The lowest median (18ms), but one transaction per op: at 30 ops/s on one board, 29.7 commits/s against 11.6.
- **15ms, the SPEC's first value.** No more batching than 5ms at this load, and 11ms slower on every edit.
- **An adaptive window** (wait only while a commit is in flight). It would behave well at both ends, but it is more machinery than the measurements justify at one board's load; noted for when a board's rate is far above 30 ops/s.

## Consequences

- **The latency budget:** each edit pays at most the window plus one commit. At 30 editors the median sync latency is 24ms on the laptop, far inside the 200ms target.
- **Commits per board stay low,** but they do not merge *across* boards: at 1,000 connections on 300 boards, Postgres made about 300 commits/s and was the busiest process (peak 6.8 cores). That is where a per-instance writer (rejected in ADR-004 for isolation) would come back.
- **Per-connection throughput is capped by the gateway's rate limit** (20 ops/s, burst 50), not by the window: one connection with one op in flight managed 21.6 ops/s, with the client pacing itself (amendment 31).
- **The ops-per-commit gain depends on load.** At 1 op/s per editor spread over the second, few ops ever share a window. A burstier board would batch more, at the same 5ms cost.
