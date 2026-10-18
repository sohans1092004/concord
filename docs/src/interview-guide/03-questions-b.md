
### 3.6 Postgres

**Q28. Which constraints carry the design?**
- *Answer.*
  - `board_ops PRIMARY KEY (board_id, op_id)`: an op is stored once, the last line of dedup.
  - `UNIQUE (board_id, version)`: no two ops share a version.
  - `boards.fence`: checked by every commit.
  - `CHECK (role IN …)` on memberships.
  - Foreign keys, which is how a deleted board is detected at snapshot time ([[schema#CREATE TABLE board_ops (]]).
- *Follow-up.* "Why isn't `board_ops` partitioned?" A partitioned table's keys must include the partition key; v2's didn't, so the table wouldn't compile. Hash partitioning on `board_id` later would keep both keys legal (v3 change 1, the schema comment).
- *Trap.* Calling the primary key "just an index".

**Q29. Which Postgres features does the code lean on?**
- *Answer.*
  - `UPDATE … RETURNING` to check the fence and reserve versions in one statement.
  - `SELECT … FOR UPDATE` for reconcile and for the last-owner rule.
  - `ON CONFLICT DO NOTHING`.
  - An advisory lock to serialise migrations ([[migrate#public static boolean run(DataSource ds)]]).
  - `pg_export_snapshot()` with a repeatable-read transaction, so `pg_dump` and the backup manifest describe one moment ([[backup#public final class Backup]]).
  - `DELETE … RETURNING` for single-use tickets.
  - `ctid` batches for retention.
- *Follow-up.* "Why batch retention by ctid?" So no statement holds `board_ops` for long; 5,000 at a time ([[retention#static final int BATCH = 5_000;]]).
- *Trap.* Not knowing why the ticket delete must be one statement: a SELECT then DELETE lets two gateways use the same ticket.

**Q30. Why is Postgres the bottleneck at 300 boards?**
- *Answer.* Group commit merges ops *within* a board's batch, but each board commits alone, so 300 active boards meant about 300 commits/s. Postgres peaked at 6.8 cores, and p95 rose to 294 ms **[laptop]**. Each commit is a durable disk flush.
- *Follow-up.* "Fix?" Batch commits *across* boards on an instance: one transaction per instance-window. ADR-004 rejected that for isolation, and this measurement is what would reopen it. Or synchronous commit off for a bounded window, which trades durability, so no.
- *Trap.* Blaming the JVM; the Session instances averaged under one core each.

**Q31. How are migrations safe during a rolling deploy?**
- *Answer.* Forward-only, and every migration must keep the previous code working: add, never rename or drop. The old Session instances run against the new schema until they are replaced, and a rollback runs the old code on the newer schema (`deploy/prod/deploy.sh` header). Version 2 added names, sessions, tickets and bug reports.
- *Follow-up.* "And a column you want to drop?" Two releases: stop using it, then drop it.
- *Trap.* "We run migrations then restart": the overlap is the point.

### 3.7 Redis

**Q32. What does Redis hold, and what happens if it disappears?**
- *Answer.* Nothing durable:
  - leases, owner keys and fence counters;
  - the live-instance set;
  - presence;
  - Pub/Sub channels.

  Down: owners keep committing, because Postgres fences them; gateways poll owners every second; presence goes blank. The stop-redis drill saw 268 ACKs during a 15.8 s outage **[laptop]**. Wiped: counters rebuild from Postgres.
- *Follow-up.* "Why not put the fence in Redis?" A check in Redis and a write in Postgres can't be atomic, so a pause between them reopens the hole (ADR-007).
- *Trap.* Calling Redis a cache of the board. It never holds board state.

**Q33. Why Lua scripts?**
- *Answer.* Atomicity: "INCR but never below a floor", "extend only if the lease value is mine", and "delete only if mine" must each be one step, or a concurrent claimer slips in between ([[ownership#static final String RENEW]]).
- *Follow-up.* "Why compare the lease value?" So a slow owner can't extend or delete a lease that a new owner now holds.
- *Trap.* Using `EXPIRE` without checking ownership.

**Q34. Pub/Sub loses messages. Is that OK?**
- *Answer.* Yes. Every broadcast carries the board version, so a client that sees v after v−2 asks `SYNC_REQUEST`; a quiet client learns it's behind from `BOARD_VERSION` every 5 s. A lost message costs a round trip, never an op (ADR-006). The e2e test drops 8 broadcasts and converges **[CI]**.
- *Follow-up.* "Why not Kafka?" Postgres already holds every op with its version; Kafka's durability would buy nothing and add a cluster and latency.
- *Trap.* Saying Redis Streams were needed.

**Q35. How does idle unload know nobody has the board open?**
- *Answer.* `PUBSUB NUMSUB board:{id}`: each gateway with a client on the board subscribes to its channel. Zero subscribers and 10 minutes without a request means unload. If Redis doesn't answer within 1 s, it unloads nothing ([[actor#private void unloadIfIdle()]], [[sessionmain#private long subscribers(String board)]]).
- *Follow-up.* "Why never while reconciling?" The board's true state isn't known yet.
- *Trap.* Unloading by timer alone.

### 3.8 WebSockets and the gateway

**Q36. How is a WebSocket authenticated?**
- *Answer.* The client gets a **single-use, 30-second ticket** from `POST /api/ws-ticket` with its session cookie, and puts it in the URL. The gateway checks the Origin allowlist, then consumes the ticket with one `DELETE … RETURNING` before upgrading ([[gatewaymain#app.wsBeforeUpgrade("/ws"]]). A URL in a log is worthless once used. No JWTs (amendment 14).
- *Follow-up.* "Why not the cookie on the upgrade?" Cross-site WebSocket hijacking: browsers send cookies on cross-origin upgrades. The ticket plus the Origin check closes that.
- *Trap.* Putting a long-lived token in the URL.

**Q37. What does the gateway do with a slow client?**
- *Answer.* Every send goes through a bounded outbox. Past 8 MB of unsent bytes it sends `RECONNECT_ELSEWHERE`, closes, and cuts the connection after 1 s if the client never finishes the close ([[outbox#public boolean send(String text)]], amendment 9). The client reconnects and catches up by version.
- *Follow-up.* "Why does that matter?" Jetty queues async writes without limit, so one client that stops reading would grow the gateway's memory forever.
- *Trap.* Blocking the broadcast loop on a slow client.

**Q38. How are a client's ops kept in order through the gateway?**
- *Answer.* Each connection has its own virtual thread that takes requests from a bounded inbox and forwards **one at a time**, waiting for the owner's reply ([[clientconn#private void drain()]], [[clientconn#private static Map<String, Object> await(]]). That's the fix for the `CompletableFuture` reordering (incident 011).
- *Follow-up.* "Cost?" At most one op in flight per connection. Measured, the 20 ops/s rate limit caps a connection first: 21.6 ops/s **[laptop]**.
- *Trap.* "TCP keeps order." It does, until a retry or an async callback reorders.

**Q39. What happens to clients during a rolling deploy?**
- *Answer.* The gateway on SIGTERM sends every client `RECONNECT_ELSEWHERE` and closes ([[clientconn#void closeElsewhere()]]). Owners flush, snapshot and release their leases. Clients reconnect with backoff and resend their queues. Three live deploys under editing lost nothing: 2,315, 2,306 and 2,351 ops **[host]**.
- *Follow-up.* "And Caddy?" Unchanged config isn't restarted; a changed one is reloaded gracefully.
- *Trap.* Assuming the WebSocket survives a backend restart.

### 3.9 Offline and the client

**Q40. Why save before showing an edit?**
- *Answer.* Otherwise a crash between showing and saving loses an edit the user saw. Each edit is appended to an IndexedDB log and committed before it's put on the queue, shown and sent ([[client#submit(body: Body): Op]], amendment 21). It also made edit-to-screen faster, 4.1–4.3 ms against 4.9–5.0, because a full snapshot write became one small append **[laptop]**.
- *Follow-up.* "What if IndexedDB refuses?" The edit is kept in memory rather than dropped, and logged.
- *Trap.* "localStorage is enough." It's synchronous, small and per-origin, and a snapshot write per keystroke is slow.

**Q41. What's the duplicated-tab bug?**
- *Answer.* A tab keeps its replica ID in `sessionStorage`, which browsers *copy* into a duplicated tab. Two live tabs would then share a replica ID and could stamp different ops with equal HLCs. On load a tab takes a Web Lock for its ID; if another tab holds it, it makes a new ID ([[idb#static async open(boardId: string]], amendment 17). Guard check `duplicate-tab-lock`.
- *Follow-up.* "What happens to a closed tab's queue?" The next tab adopts it, taking its lock first, in one IndexedDB transaction.
- *Trap.* One replica per browser: two tabs then race on one queue.

**Q42. How does the client detect a dead connection?**
- *Answer.* Chromium, like a dropped network, often leaves the WebSocket open with no close event. The owner sends `BOARD_VERSION` every 5 s, so **15 s of silence** drops the connection; the browser's `offline` event drops it at once and `online` reconnects immediately ([[client#export const SILENCE_MS = 15_000;]], amendment 18).
- *Follow-up.* "Why not WebSocket pings?" Browsers don't expose ping/pong to JavaScript; an application heartbeat is the portable way.
- *Trap.* Trusting `onclose`.

**Q43. A client comes back after a day with 5,000 queued edits. What happens?**
- *Answer.*
  1. JOIN with its version; it gets the ops after that, or a snapshot if more than 2,000 behind.
  2. It resends its queue in order through a token bucket matching the gateway: 20 ops/s, burst 50 ([[client#private pump()]]).
  3. 5,701 edits synced in 283 s, 0 lost **[accelerated]**.

  Before amendment 31 the client sent everything at once and resent the whole queue on every refusal: still pending after 10 minutes.
- *Follow-up.* "Can it go faster?" A batch frame carrying many ops would: a protocol change, not made under the feature freeze.
- *Trap.* Saying the server limit was the bug. The two sides didn't match.

**Q44. Name every way an edit can fail, and where it ends.**
- *Answer.*
  - `RETRY_LATER`, `UNAVAILABLE`: stays queued, resent.
  - `CLOCK_SKEW`: re-stamped, resent.
  - `INVALID_OP` behind an unacked op of ours: deferred and judged again.
  - `INVALID_OP` at the head, `UNAUTHORIZED`: **Unsent edits**, visible and kept.

  Nothing is silently dropped ([[client#private error(m: Msg)]]). Every drill asserts 0 Unsent.
- *Follow-up.* "Why defer `INVALID_OP`?" The owner may have died between an `ADD_LIST` and its `ADD_CARD`; the card was judged without the list (incident 012).
- *Trap.* Dropping "invalid" ops.

### 3.10 Java concurrency

**Q45. Why virtual threads?**
- *Answer.* One thread per board actor and per client connection lets the code block simply: `queue.poll`, JDBC, waiting for the owner's reply. A blocked virtual thread costs little, so 10,000 boards don't need 10,000 OS threads ([[actor#Thread.ofVirtual().name("board-"]], ADR-004). Javalin also runs handlers on virtual threads.
- *Follow-up.* "Pitfalls?" Pinning: a virtual thread blocked inside `synchronized` holds its carrier. The arm64 CI deadlock was a test helper doing slow work under a monitor, with a virtual thread holding it (incident 017).
- *Trap.* "Virtual threads are faster." They're cheaper to block, not faster.

**Q46. How does the actor avoid locks?**
- *Answer.* All board state (the CRDT, version, LRU, batch) is touched only by the actor's own thread. Others only `offer` items to its `LinkedBlockingQueue` mailbox ([[actor#private final BlockingQueue<Item> mailbox]]). The single `synchronized (closeLock)` makes "check closed, then enqueue" atomic against closing ([[actor#public Offered offer(Item item)]]).
- *Follow-up.* "Why bound the mailbox?" A board flooded past 5,000 items answers `RETRY_LATER` instead of growing without limit; control messages (drop, release, delete) always get in.
- *Trap.* Saying "the actor is single-threaded, so nothing is concurrent". Offers come from many threads.

**Q47. Explain the CompletableFuture reordering bug.**
- *Answer.* The first gateway draft forwarded each op with chained `thenCompose`/`thenAccept` callbacks. Callbacks run on whichever thread completes the previous stage, so two ops from one connection could complete in either order, and a `NOT_OWNER` retry could overtake a later op. An `ADD_CARD` reaching the owner before its `ADD_LIST` fails validation. Fix: the connection's own virtual thread forwards one request and *waits* for its reply ([[clientconn#private static Map<String, Object> await(]], incident 011). Found by reading the code.
- *Follow-up.* "Where do you still use CompletableFuture?" In `Router` and `Link`, where one request's lifecycle is a chain, never two requests racing.
- *Trap.* "Use a single-threaded executor": that still doesn't wait for the owner's reply.

**Q48. What's `volatile` doing in BoardActor?**
- *Answer.* `state` and `fence` are read from other threads: the lease renewer reads `fence()`, and `offer` reads `state`. `volatile` gives visibility of the actor thread's writes without a lock ([[actor#private volatile State state = State.CLAIMING;]]).
- *Follow-up.* "Is that enough for `offer`'s check-then-act?" No, hence the `closeLock`.
- *Trap.* Making everything `synchronized`.

**Q49. How does the lease renewer interact with actors?**
- *Answer.* One platform thread renews every lease every 5 s with compare-and-extend. If another owner holds the lease, or Postgres holds a newer fence, it *offers* a `Drop` to that actor rather than touching its state ([[boards#private void renewAll()]]). If Redis is down it does nothing, since Postgres still fences the owner.
- *Follow-up.* "Why not renew inside the actor?" A busy actor would miss renewals and lose its lease.
- *Trap.* Closing the actor from the renewer's thread.
