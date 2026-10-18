# Concord: How the Spec Was Built

2026-10-18 · Sohan · companion: [interview guide](interview-guide.md)

**How to read this.** Every claim cites its source: a code line (a link such as `BoardActor.java:386`), a test name, a commit, or a report. Every line reference was checked against commit `1230291` by `docs/tools/refs.py check` (the result is at the end of section 14). Measurements carry where they were made:

| Tag | Meaning |
|---|---|
| **[laptop]** | Measured on the development laptop (i7-12700H, 20 logical cores, 15.6 GB, Docker Desktop), with nothing else running (`production/load-tests/README.md`) |
| **[host]** | Measured on the live trial host, AWS t4g.small in Sydney (`deploy/prod/rehearsal-results.txt`) |
| **[CI]** | Measured in GitHub Actions, on shared runners |
| **[accelerated]** | Simulated time: hours compressed into minutes (section 11) |
| **[design]** | Designed and argued, not measured |

## 1. Overview

> **Concord is built, measured and running a small trial: every functional requirement is met, two measured targets are missed and say so, and the system's central promise held in every test, drill and live deploy.**

Concord is a collaborative kanban board. Several people edit the same board at once (lists of cards; card titles, descriptions, assignees, labels; moves and deletes), keep working offline for up to a day, and never lose an edit or get asked which version to keep. It is the specification's "structured workspace, not a rich-text editor" (`docs/SPEC.md`, "Status and what changed").

**Live:** `https://concord-serve.duckdns.org`, invite-only, an AWS t4g.small (2 GB) in ap-southeast-2 (Sydney) (`docs/runbook.md`, top).

**What exists today**

| Part | What it is | Size and proof |
|---|---|---|
| CRDT engine, twice | `crdt-ts/` (TypeScript) and `crdt-java/` (Java 21): HLC, OR-Set, LWW registers, fractional keys, board composition, canonical encoding | 109 tests each; 55 hand-written conformance vectors; a differential fuzz between the two (`docs/phases.md:83-84`) |
| Server | `server/`: Java 21 on Javalin; **gateways** (WebSockets, REST API, fan-out) and **Session instances** (one actor per board, the only writer) | 61 integration tests on real Postgres 16, Redis 7 and Toxiproxy |
| Sync client | `client-core/`: offline queue in IndexedDB, in-order paced resend, clock correction, presence, telemetry | 35 unit tests, 7 end-to-end tests, 7 drills |
| Web app | `web/`: React, drag and drop, history, trash, Unsent edits, bug reports | 19 Playwright tests in Chromium |
| Safety evidence | 42 remove-the-guard checks: each safety guard is deleted and its test must fail | `server/tools/guard_checks.out` |
| Operations | One VM: Caddy (HTTPS), 1 gateway, 2 Session instances, Postgres, Redis, nightly encrypted backups | 5 live deploys, 3 of them under live editing with 0 edits lost (`deploy/prod/rehearsal-results.txt`) |
| Records | 32 SPEC amendments, 10 ADRs, 21 postmortems, every §10.1 measurement | `docs/SPEC.md`, `docs/adr/`, `production/` |

**The core promise** has two halves.
1. **Convergence:** two replicas that have seen the same set of edits, in any order and any number of times, hold byte-identical state. This is what the CRDT engine guarantees, and what the fuzzers and vectors test.
2. **No acknowledged edit is ever lost:** when the server sends a client `OP_ACK`, the edit is committed to Postgres. The client then forgets it for good, so a wrong ACK would be an edit nobody holds. This is what the server's write path and ownership rules guarantee, and what the drills test.

**What it deliberately does not promise** (`docs/SPEC.md` §1.3):
- **Character-level text merging.** A description is one last-writer-wins value: two people editing it at once means one version wins whole.
- **Caret-level cursors, unbounded undo, moving cards between boards.**
- **SSO, two-factor sign-in, email invites.**
- **That a user's own concurrent move always wins.** One of two concurrent moves wins, deterministically, and the loser sees "moved by {name}" (§3.5).
- **Exactly-once delivery to the network.** Edits are resent until acknowledged, and the server deduplicates them by `op_id`.

**Two measured targets are missed** and are stated where they are measured (section 11):
- **Large board:** about 1 render in 300 takes 16–26 ms, against a budget of every render under 16 ms **[laptop]**.
- **Sync latency end to end from India to Sydney:** p95 315–354 ms against 200 ms **[host]**. The server's share is about 17 ms; the rest is network distance.

At 1,000 connections the p95 sync latency is 294 ms **[laptop]**, above the 200 ms target. §1.2 sets that target for 2–10 editors and asks only that 1,000 connections be measured.
