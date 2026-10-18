# Walkthrough: one op, from a client's send to every other client's view

This follows one edit, "add a card", through every process it touches in Phase 2. File and
line references are as of the end of Phase 2 (commit `d4db821`); Phase 3 moved some lines and
replaced the JWT with a WebSocket ticket. The client's half, current, is in
`docs/walkthrough-phase3.md`. Each step
names the file and line where it happens, so you can read the code in the same order the op
travels. Read it beside the code; the SPEC sections are the rules each step implements.

```
client A ──WebSocket──▶ gateway 1 ──internal WebSocket──▶ owner (Session instance)
                                                             │ board actor: dedup, validate,
                                                             │ group commit in Postgres,
                                                             │ apply, ACK
   ◀────────────── OP_ACK ◀────────────────────────────────┘
                                                             │ PUBLISH board:{id}
client B ◀──WebSocket── gateway 2 ◀── Redis Pub/Sub ◀────────┘
```

The processes (`deploy/docker-compose.yml`): two gateways (ports 8081, 8082), three Session
instances (`session1`–`3`, internal port 7000), Postgres and Redis. Gateways hold no board
state. Exactly one Session instance, the board's *owner*, holds a board in memory.

## 1. The client makes the op (SPEC §7.2)

1. The UI (Phase 3) calls `addCard(listId, title)`. It computes a position key from the
   current view with `keys.planInsert`, re-keying an equal neighbour first if needed (§3.7),
   then calls `submit`, `client-core/src/client.ts:168`.
2. `submit` ticks the hybrid logical clock with the corrected wall clock (local time plus
   the offset learned from `JOINED`), builds the op with a fresh `op_id`, and checks it
   with the engine's `validateShape`, so a client bug fails here, loudly.
3. It appends the op to the pending queue, `client.ts:173`. `changed()` (`client.ts:535`)
   saves the state through the `Storage` interface and invalidates the view, so the card
   appears at once: the view is the confirmed replica with the pending ops merged on top.
4. `sendOp`, `client.ts:316`, sends `{"type":"OP","op":{...}}` if the client is joined.
   If not, the op waits; after the next `JOINED` the whole queue is resent in order.

## 2. The gateway receives it (SPEC §2.1, §9.4)

5. The connection was authenticated once, at upgrade: `wsBeforeUpgrade` checks the Origin
   allowlist and the credential (a JWT in Phase 2; since Phase 3 a single-use ticket, §9.4), `server/gateway/src/main/java/dev/concord/gateway/GatewayMain.java:52`.
   Frames over 64 KB close the connection (Jetty limit, `GatewayMain.java:41`).
6. Jetty delivers the frame to `onMessage`, `GatewayMain.java:67`, which hands it to this
   connection's `ClientConn.onMessage`, `ClientConn.java:79`.
7. The frame is parsed **once, by the server's own parser**, `ClientConn.java:82`, which
   rejects duplicate member names (§4.2). From here on, only canonical, server-produced
   JSON travels.
8. The rate limit, a token bucket of 20/s with burst 50 kept in memory (amendment 7), is
   checked on arrival, `ClientConn.java:91`. Refused ops get `ERROR RETRY_LATER` at once.
9. The frame is queued for this connection's own virtual thread, `drain()`,
   `ClientConn.java:50`. That thread forwards one request at a time, in arrival order: the
   owner's reference checks need a client's `ADD_LIST` to arrive before its `ADD_CARD`.
10. `op()`, `ClientConn.java:137`, checks the op is for the joined board and forwards
    `{"k":"OP","board_id","user_id","op"}`, waiting for the reply, `ClientConn.java:149`.
    The `user_id` comes from the verified credential, never from the client's JSON.

## 3. Finding the owner (SPEC §2.3)

11. `Router.forward`, `Router.java:45`, asks `owner()`, `Router.java:73`:
    - a per-board cache of 5s;
    - else `GET board:owner:{id}` in Redis, `Router.java:78`, written by the owner when it
      claimed the board;
    - else no one owns it: the preferred live instance by rendezvous hashing over the
      `instances` sorted set, `Router.java:99`. That instance will claim it on arrival.
    - If Redis is unreachable, the last owner it knew is used (§6.3: owners keep serving).
12. The request goes over the gateway's single WebSocket to that instance, `Link.request`,
    `Link.java:54`: it gets a `req` id, a pending future (`Link.java:61`), and is sent
    (`Link.java:66`).
13. If the answer is `NOT_OWNER`, or the instance is unreachable, `Router.java:56` drops the
    cached owner, looks it up again and resends (up to 3 tries). Resending is safe because
    the owner deduplicates by `op_id`.

## 4. Into the owner's board actor (SPEC §2.2, ADR-004)

14. The Session instance's internal endpoint, `InternalServer.java:33`, parses the frame
    in `handle`, `InternalServer.java:41`, builds a `Submit` item (`InternalServer.java:59`)
    with a reply callback that writes `{"k":"R","req",msg}` back on the same socket
    (`InternalServer.java:51`), and calls `boards.dispatch`, `InternalServer.java:67`.
15. `Boards.dispatch`, `Boards.java:33`, finds the board's actor, or starts one
    (`Boards.java:39`). A new actor first **claims** the board, `BoardActor.claim`,
    `BoardActor.java:165`, via `Ownership.claim`, `Ownership.java:93`: fence from Redis,
    lease `SET NX`, fence written to Postgres, then snapshot plus tail loaded (§6.2,
    amendment 6). The item goes into the actor's mailbox, `offer`, `BoardActor.java:138`.
16. The actor's one virtual thread, `run`, `BoardActor.java:148`, loops in `serveOnce`,
    `BoardActor.java:200`, taking items from the mailbox, `BoardActor.java:205`. Only this
    thread touches the board's state, so nothing here takes a lock.
17. A `Submit` goes to `admit`, `BoardActor.java:220` → `BoardActor.java:235`.

## 5. Dedup, then validation (SPEC §6.1 step 2, §4.2, §4.3)

18. **Dedup first.** If the `op_id` is in the 10,000-entry LRU, its original ACK is sent
    and nothing else happens, `BoardActor.java:239`. If it is already waiting in the
    current batch, this sender is added to its waiters, `BoardActor.java:245`.
19. **Validation**, `validate`, `BoardActor.java:269`:
    - shape: the engine's `checkShape` (lengths, UUIDs, keys, no U+0000), `BoardActor.java:270`;
    - the op names this board;
    - role: owner or editor, from the 30s membership cache, `BoardActor.java:276` →
      `Membership.java:28`;
    - clock: no more than 60s ahead of server time (`checkClock`), `BoardActor.java:282`;
    - references (amendment 6, E): `referenceError`, `BoardActor.java:284` → `:319`. The
      list this card goes into must have existed, the card ID must be new. "Existed"
      includes ops already in this batch.
20. A rejection goes through `rejectOrAckStored`, `BoardActor.java:293`: before saying
    `INVALID_OP` or `UNAUTHORIZED`, it looks the `op_id` up in `board_ops`, because a
    resend of an op committed before a failover is a duplicate, not an error.

## 6. The group commit (SPEC §6.1 step 3, amendment 6)

21. The op is parsed (`BoardActor.java:259`) and added to the batch (`BoardActor.java:263`).
    The first op of a batch sets its deadline, 5ms away by default (15ms when this was written; amendment 32), `BoardActor.java:260`.
22. When the deadline passes, `serveOnce` calls `flush`, `BoardActor.java:209` →
    `BoardActor.java:351`, which runs one transaction for the whole batch,
    `Store.commit`, `BoardActor.java:360` → `Store.java:53`:
    - a pooled connection whose socket gives up at the 2s deadline, pool wait included,
      `Store.java:117`, `Store.java:128`;
    - **3.1** drop `op_id`s already stored, `Store.java:57`;
    - **3.2** the fence check and version range in one statement,
      `UPDATE boards SET version = version + :n WHERE id = :board AND fence = :fence RETURNING version`,
      `Store.java:67`. Zero rows: a newer owner exists;
    - **3.3** insert the ops with consecutive versions, `Store.java:84`;
    - `COMMIT`, `Store.java:108` (unless the deadline has passed, `Store.java:107`).
23. If the transaction fails or times out (`BoardActor.java:360` throws), every sender gets
    `RETRY_LATER` and the actor *reconciles*, `BoardActor.java:367` → `reconcile`,
    `BoardActor.java:467`. It does not know whether the COMMIT landed, so it re-reads
    `board_ops` before serving again (amendment 6, B).
24. If the fence check updated zero rows (`BoardActor.java:370`), the actor answers
    `NOT_OWNER`, drops the board and stops. Nothing was stored or published.

## 7. Apply, ACK, publish (SPEC §6.1 step 4)

25. Only now, after the commit, `flush` walks the batch, `BoardActor.java:381`, and calls
    `applyCommitted`, `BoardActor.java:391` → `BoardActor.java:448`:
    - the op is applied to the in-memory board, `board.apply(op)`, `BoardActor.java:454` →
      `crdt-java/src/main/java/dev/concord/crdt/Board.java:30`, the same engine the
      conformance vectors check;
    - its ACK is remembered in the LRU, `BoardActor.java:457`;
    - `{"type":"OP_BROADCAST","op","version"}` is published on Redis channel `board:{id}`,
      `BoardActor.java:459`, fire and forget (`Publisher.java:13`).
26. The sender gets `OP_ACK {op_id, version}`, `BoardActor.java:392`, through the reply
    callback, which writes to the gateway's socket, `InternalServer.java:71`.
27. If any key in the batch passed 40 characters, the owner queues its own `SET_PLACEMENT`
    ops to rebalance that list, `BoardActor.java:394` (§3.7).

## 8. The ACK returns to client A

28. On the gateway, `Link.onText`, `Link.java:79`, matches the `req` id and completes the
    future, `Link.java:89`. `ClientConn.op` sends the reply to the client,
    `ClientConn.java:151`, without blocking, `ClientConn.java:67`.
29. In the client, `onmessage`, `client.ts:280` → `handle`, `client.ts:322` → `OP_ACK`,
    `client.ts:344` → `acked`, `client.ts:400`: the op leaves the pending queue and goes into
    the confirmed replica at its version (`confirmedOp`, `client.ts:380`; `drain`,
    `client.ts:391`).

## 9. Fan-out to client B, on the other gateway (SPEC §2.3 step 6, §6.4, ADR-006)

30. Gateway 2 subscribed to `board:{id}` when client B joined, `ClientConn.java:127` →
    `Fanout.join`, `Fanout.java:53`, subscribe at `Fanout.java:57`.
31. Lettuce calls `message`, `Fanout.java:46`, then `deliver`, `Fanout.java:88`, which records
    the version and sends the text to every local client of that board, `Fanout.java:97`.
32. If Pub/Sub is down, `tick`, `Fanout.java:100`, notices once a second and `poll`,
    `Fanout.java:119`, asks the owner for ops after the last version this gateway
    delivered, and delivers those instead.

## 10. Client B merges it (SPEC §7.1, §6.4)

33. `handle`, `client.ts:322` → `OP_BROADCAST`, `client.ts:341` → `confirmedOp`,
    `client.ts:380`:
    - if the version is the next one, `drain`, `client.ts:391`, applies it to the confirmed
      replica;
    - if it skips one or more versions (a lost message), the op is held and the client sends
      `SYNC_REQUEST {since_version}`, `client.ts:417`, before applying anything newer;
    - if the version is old, it is ignored (merge is idempotent anyway).
34. `changed()`, `client.ts:535`, invalidates the view; the next `view()`, `client.ts:140`,
    rebuilds it as confirmed + held-ahead ops + B's own pending ops, `client.ts:509`. The
    card is on B's screen.

## When something goes wrong on the way

| Where | What happens | Where in the code |
|---|---|---|
| Client offline | the op waits in the queue; resent in order after `JOINED` | `client.ts:316`, `client.ts:322` |
| Gateway dies | the client reconnects to the next URL with backoff, resends its queue | `client.ts` `disconnected()` |
| Owner dies | `Link` fails → `UNAVAILABLE`; after the lease expires, the gateway's 1s tick asks the preferred instance to claim | `Fanout.java:100` |
| Postgres down or slow | `RETRY_LATER`, nothing ACKed; the actor reconciles before serving again | `BoardActor.java:367`, `:467` |
| Stale owner | its commit updates zero rows; it drops the board | `Store.java:67`, `BoardActor.java:370` |
| Broadcast lost | the next broadcast or `BOARD_VERSION` shows a gap; `SYNC_REQUEST` | `client.ts:380`, `client.ts:417` |
| Op invalid | `ERROR INVALID_OP`; the client moves it to Unsent edits (only once nothing of its own is ahead of it) | `BoardActor.java:293`, `client.ts:427` |
