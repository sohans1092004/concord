# ADR-006: Redis Pub/Sub, not Kafka, for fan-out

Status: Accepted (SPEC §2.3 step 6, §6.4). Date: 2026-10-16.

## Context

After an owner commits an op it must reach every gateway serving a client of that board,
within the 200ms p95 budget (§1.2). Reliant used Kafka, because there the stream *was* the
record: consumers had to see every event, in order, after a crash. Here the record is
`board_ops` in Postgres, written and committed before anything is published (§6.1).

## Decision

Owners `PUBLISH` `{op, version}` on Redis channel `board:{id}` after commit, and a
`BOARD_VERSION` every 5s. A gateway subscribes to a board's channel while it has a client
on that board and forwards messages unchanged. Delivery is best-effort; correctness comes
from versions:

- every broadcast carries its board version, so a client that sees `v` after `v-2` asks
  for `SYNC_REQUEST {since_version}` before applying anything newer;
- `BOARD_VERSION` lets a quiet client notice it is behind within 5s;
- if Pub/Sub is down, each gateway polls the owner once a second for ops after the last
  version it delivered (`Fanout.poll`), so fan-out continues, slower.

## Alternatives

- **Kafka (Reliant's choice).** Durable, ordered, replayable. But durability buys nothing
  when Postgres already holds every op with its version; it adds a broker cluster, partition
  planning per board, consumer groups per gateway, and tens of milliseconds of latency.
- **Postgres LISTEN/NOTIFY.** No new system, but every gateway holds a Postgres connection,
  payloads are capped at 8 KB, and fan-out load lands on the database that is the
  bottleneck for commits.
- **Owner pushes directly to gateways.** Owners would track which gateways serve which
  boards: membership state that Pub/Sub keeps for us.

## Consequences

- A lost message costs one SYNC round trip, never an op: the stop-redis drill keeps ACKing
  and delivering through a 15.7s Redis outage (gateways poll), and the lost-broadcast test
  (§8 row 7) drops every fifth broadcast and still converges.
- Redis holds nothing that cannot be lost (§5): leases, owner keys and fence counters are
  rebuilt (ADR-007), presence is ephemeral.
- Fan-out cost is Redis' (one publish per op, one delivery per subscribed gateway), not the
  owner's.
- Two control channels use the same best-effort path, each with a fallback for a lost message:
  `membership` (access changes; owners also refresh their 30s membership cache, amendment 8)
  and `board-deleted` (the owner also notices at its next commit, reconcile or snapshot,
  amendment 27).
- In contrast with Reliant: there, losing a Kafka event was data loss; here, losing a
  Pub/Sub message is a version gap that the protocol detects and repairs.
