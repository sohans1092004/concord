package dev.concord.session;

import dev.concord.common.Msg;
import dev.concord.common.Prom;
import dev.concord.common.Tracing;
import dev.concord.crdt.Board;
import dev.concord.crdt.Hlc;
import dev.concord.crdt.Op;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One board's actor (SPEC §2.2): a mailbox drained by one virtual thread, so the board's state
 * is touched by exactly one thread and the merge code takes no locks. This thread also runs the
 * board's writer (§6.1 step 3, amendment 6): it batches admitted ops for up to {@code batchMs}
 * and commits them in one transaction, then applies them. Memory changes only after commit.
 *
 * <pre>
 *   CLAIMING ──claim+load ok──▶ SERVING ──commit failed / timed out──▶ RECONCILING
 *       │                        │  ▲                                     │
 *       └──not owner──▶ CLOSED ◀─┘  └──────────── reload ok ─────────────┘
 *                         ▲  fenced out, lease lost, released, unloaded when idle (amendment 26),
 *                            board deleted (amendment 27)
 * </pre>
 */
public final class BoardActor {
    private static final Logger log = LoggerFactory.getLogger(BoardActor.class);

    static final int LRU_SIZE = 10_000; // SPEC §4.3
    static final long COMMIT_TIMEOUT_MS = 2_000; // SPEC §6.1 step 5
    static final long BOARD_VERSION_EVERY_MS = 5_000; // SPEC §6.4
    static final int SNAPSHOT_EVERY_OPS = 500; // SPEC §5.1
    static final long SNAPSHOT_EVERY_MS = 60_000;
    static final int CATCH_UP_LIMIT = 2_000; // SPEC §7.3 step 3
    static final int REBALANCE_AT = 40; // SPEC §3.7
    /** Requests queued for one board before new ones get RETRY_LATER (amendment 9). */
    static final int MAILBOX_LIMIT = 5_000;
    /** board_ops.user_id for ops the owner itself issues (rebalancing). */
    static final String SYSTEM_USER = Op.NIL_UUID;

    /** Where a reply goes: the gateway connection that asked, or a test. */
    @FunctionalInterface
    public interface Reply {
        void send(Map<String, Object> msg);
    }

    /** Mailbox items. */
    public sealed interface Item {}

    public record Submit(Object op, String userId, Reply reply) implements Item {}

    public record Join(String userId, Long since, Reply reply) implements Item {}

    public record SyncRequest(String userId, Long since, Reply reply) implements Item {}

    public record Poll(long since, Reply reply) implements Item {}

    public record Ensure(Reply reply) implements Item {}

    /** The lease was lost to another owner: stop serving now. */
    public record Drop(String reason) implements Item {}

    /** Graceful handover (SPEC §2.4): flush, snapshot, release the lease. */
    public record Release(CompletableFuture<Void> done) implements Item {}

    /** The board was deleted (amendment 27): drop it now, no snapshot, release the lease. */
    public record Deleted() implements Item {}

    enum State { CLAIMING, SERVING, RECONCILING, CLOSED }

    /** Everything an actor needs from its instance. */
    public record Deps(Store store, Ownership ownership, Membership membership, Publisher publisher,
                       Metrics metrics, LongSupplier clock, int batchMs, Subscribers subscribers, long idleUnloadMs) {}

    /** Gateways subscribed to the board's channel, one per gateway with a client on it; -1 if unknown. */
    @FunctionalInterface
    public interface Subscribers {
        long count(String board);
    }

    private final String id;
    private final Deps d;
    private final Runnable onClose;
    private final BlockingQueue<Item> mailbox = new LinkedBlockingQueue<>();
    private final Object closeLock = new Object();
    private volatile State state = State.CLAIMING;
    private volatile long fence = -1;

    // ---- state touched only by the actor thread -------------------------------------------
    private Board board = Board.empty();
    private long version;
    /** SPEC §4.3: op_id → version of its ACK, last 10,000. */
    private final Map<String, Long> lru = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > LRU_SIZE;
        }
    };

    /** An admitted op waiting for the group commit, and everyone waiting for its ACK. */
    private record Waiting(Store.Pending pending, List<Reply> replies, long admittedNanos) {}

    private final LinkedHashMap<String, Waiting> batch = new LinkedHashMap<>();
    private final Set<String> batchLists = new HashSet<>(), batchCards = new HashSet<>();
    private long batchDeadline;
    private long nextBoardVersion;
    /** When the last request reached this actor, and when to next ask whether anyone has it open. */
    private long lastActivity, nextIdleCheck;
    private int opsSinceSnapshot;
    private long lastSnapshotAt;
    /** The owner's own replica (SPEC §3.7 rebalancing): fresh per claim, ahead of every HLC applied. */
    private final String replica = java.util.UUID.randomUUID().toString();
    private Hlc clock = Hlc.zero(replica);

    private BoardActor(String id, Deps d, Runnable onClose) {
        this.id = id;
        this.d = d;
        this.onClose = onClose;
    }

    /** Starts the actor's virtual thread; it claims the board before serving anything. */
    public static BoardActor start(String id, Deps d, Runnable onClose) {
        BoardActor a = new BoardActor(id, d, onClose);
        Thread.ofVirtual().name("board-" + id).start(a::run);
        return a;
    }

    public String id() {
        return id;
    }

    /** The fence this actor commits with, or -1 before its claim succeeds. */
    public long fence() {
        return state == State.SERVING || state == State.RECONCILING ? fence : -1;
    }

    State state() {
        return state;
    }

    public enum Offered { ACCEPTED, FULL, CLOSED }

    /**
     * CLOSED once the actor has closed: the caller must route the item elsewhere. FULL when the
     * mailbox holds MAILBOX_LIMIT requests: the caller answers RETRY_LATER. Drop and Release
     * are always accepted, so an overloaded board can still be shed or handed over.
     */
    public Offered offer(Item item) {
        synchronized (closeLock) {
            if (state == State.CLOSED) return Offered.CLOSED;
            boolean control = item instanceof Drop || item instanceof Release || item instanceof Deleted;
            if (!control && mailbox.size() >= MAILBOX_LIMIT) return Offered.FULL;
            mailbox.add(item);
            return Offered.ACCEPTED;
        }
    }

    // ---- the actor thread -----------------------------------------------------------------

    private void run() {
        try {
            claim();
            while (state == State.SERVING || state == State.RECONCILING) {
                if (state == State.RECONCILING) reconcile();
                else serveOnce();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(Msg.NOT_OWNER, "interrupted");
        } catch (RuntimeException e) {
            log.atError().addKeyValue("board_id", id).setCause(e).log("board actor crashed");
            close(Msg.UNAVAILABLE, "board actor crashed");
        }
    }

    /** SPEC §6.2: fence, lease, fence in Postgres, then snapshot + replay, then announce. */
    private void claim() {
        long started = d.clock().getAsLong();
        try {
            fence = d.ownership().claim(id);
        } catch (Ownership.NotOwner e) {
            close(Msg.NOT_OWNER, e.getMessage());
            return;
        } catch (Ownership.NoSuchBoard e) {
            close(Msg.UNAUTHORIZED, "no such board");
            return;
        } catch (SQLException | RuntimeException e) {
            log.atWarn().addKeyValue("board_id", id).log("claim failed: {}", e.toString());
            close(Msg.UNAVAILABLE, "cannot claim the board now");
            return;
        }
        try {
            Store.Loaded l = d.store().load(id);
            board = l.board();
            version = l.version();
            d.metrics().add("snapshot_checksum_mismatches", l.corruptSnapshots());
            d.ownership().announce(id, fence);
            long now = d.clock().getAsLong();
            lastSnapshotAt = now;
            nextBoardVersion = now;
            lastActivity = now;
            state = State.SERVING;
            d.metrics().inc("claims");
            log.atInfo().addKeyValue("board_id", id).addKeyValue("fence", fence).addKeyValue("version", version)
                    .addKeyValue("tail", l.tail()).addKeyValue("claim_ms", now - started).log("claimed board");
            d.metrics().prom.observe("claim_ms", Prom.MS, now - started); // recovery time, 9.5
        } catch (SQLException | RuntimeException e) {
            log.atWarn().addKeyValue("board_id", id).log("load after claim failed: {}", e.toString());
            safeRelease();
            close(Msg.UNAVAILABLE, "cannot load the board now");
        }
    }

    private void serveOnce() throws InterruptedException {
        long now = d.clock().getAsLong();
        long wake = nextBoardVersion;
        if (!batch.isEmpty()) wake = Math.min(wake, batchDeadline);
        if (opsSinceSnapshot > 0) wake = Math.min(wake, lastSnapshotAt + SNAPSHOT_EVERY_MS);
        wake = Math.min(wake, Math.max(lastActivity + d.idleUnloadMs(), nextIdleCheck));
        Item item = mailbox.poll(Math.max(0, wake - now), TimeUnit.MILLISECONDS);
        if (item != null) {
            lastActivity = d.clock().getAsLong();
            handle(item);
        }
        if (state != State.SERVING) return;
        now = d.clock().getAsLong();
        if (!batch.isEmpty() && now >= batchDeadline) flush();
        if (state != State.SERVING) return;
        if (now >= nextBoardVersion) {
            d.publisher().publish(id, Msg.encode(Msg.boardVersion(version)));
            nextBoardVersion = now + BOARD_VERSION_EVERY_MS;
        }
        if (opsSinceSnapshot >= SNAPSHOT_EVERY_OPS || (opsSinceSnapshot > 0 && now - lastSnapshotAt >= SNAPSHOT_EVERY_MS)) snapshot();
        unloadIfIdle();
    }

    private void handle(Item item) {
        switch (item) {
            case Submit s -> admit(s);
            case Join j -> catchUp("JOINED", j.userId(), j.since(), j.reply());
            case SyncRequest s -> {
                d.metrics().inc("sync_requests"); // a client saw a version gap (6.4)
                catchUp("SYNC", s.userId(), s.since(), s.reply());
            }
            case Poll p -> poll(p);
            case Ensure e -> e.reply().send(Msg.obj("type", "OK", "version", version));
            case Drop dr -> {
                log.atWarn().addKeyValue("board_id", id).addKeyValue("fence", fence).log("dropping board: {}", dr.reason());
                close(Msg.NOT_OWNER, dr.reason());
            }
            case Release r -> release(r);
            case Deleted x -> dropDeleted("announced");
        }
    }

    // ---- SPEC §6.1 step 2: dedup, then validate --------------------------------------------

    private void admit(Submit s) {
        String opId = s.op() instanceof Map<?, ?> m && m.get("op_id") instanceof String x ? x : null;
        // Dedup first (SPEC §4.3): a repeated op_id gets the original ACK and is never re-applied.
        if (opId != null) {
            Long acked = lru.get(opId);
            if (acked != null) {
                d.metrics().inc("duplicates");
                s.reply().send(Msg.ack(opId, acked));
                return;
            }
            Waiting w = batch.get(opId);
            if (w != null) {
                d.metrics().inc("duplicates");
                w.replies().add(s.reply());
                return;
            }
        }
        Map<String, Object> reject = validate(s, opId);
        if (reject != null) {
            rejectOrAckStored(s, opId, reject);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) s.op();
        Op op = Op.parse(json);
        if (batch.isEmpty()) batchDeadline = d.clock().getAsLong() + d.batchMs();
        List<Reply> replies = new ArrayList<>();
        replies.add(s.reply());
        batch.put(opId, new Waiting(new Store.Pending(op, json, s.userId()), replies, Tracing.nowNanos()));
        if (op instanceof Op.AddList a) batchLists.add(a.listId());
        if (op instanceof Op.AddCard c) batchCards.add(c.cardId());
    }

    /** SPEC §4.2 steps 1–5. Returns the ERROR to send, or null if the op is admitted. */
    private Map<String, Object> validate(Submit s, String opId) {
        Op.Check shape = Op.checkShape(s.op());
        if (!shape.ok()) return Msg.error(Msg.INVALID_OP, opId, shape.reason());
        Op op = Op.parse(s.op());
        if (!op.boardId().equals(id)) return Msg.error(Msg.INVALID_OP, opId, "board_id is not this board");
        String role;
        try {
            role = d.membership().role(id, s.userId());
        } catch (SQLException e) {
            return Msg.error(Msg.RETRY_LATER, opId, "membership unavailable");
        }
        if (!Membership.canEdit(role)) return Msg.error(Msg.UNAUTHORIZED, opId, role == null ? "not a member" : "viewers cannot edit");
        long now = d.clock().getAsLong();
        Op.Check clock = Op.checkClock(op, now);
        if (!clock.ok()) return Msg.clockSkew(opId, clock.reason(), now);
        String ref = referenceError(op);
        return ref == null ? null : Msg.error(Msg.INVALID_OP, opId, ref);
    }

    /**
     * A resend of an op committed before a failover misses the new owner's LRU and may now fail
     * validation (its ADD already exists, its sender lost access). Before rejecting, check the
     * primary-key backstop (SPEC §4.3 step 2): if it is stored, return its original ACK.
     */
    private void rejectOrAckStored(Submit s, String opId, Map<String, Object> reject) {
        if (opId != null && Hlc.isUuid(opId) && !Msg.RETRY_LATER.equals(reject.get("code"))) {
            try {
                Long stored = d.store().storedVersion(id, opId);
                if (stored != null) {
                    d.metrics().inc("duplicates");
                    lru.put(opId, stored);
                    s.reply().send(Msg.ack(opId, stored));
                    return;
                }
            } catch (SQLException e) {
                // Cannot tell a duplicate from a bad op without Postgres; never call a stored op invalid.
                s.reply().send(Msg.error(Msg.RETRY_LATER, opId, "storage unavailable"));
                return;
            }
        }
        d.metrics().inc("rejected_" + reject.get("code"));
        log.atInfo().addKeyValue("board_id", id).addKeyValue("op_id", opId).addKeyValue("user_id", s.userId())
                .addKeyValue("code", reject.get("code")).log("rejected op: {}", reject.get("detail"));
        s.reply().send(reject);
    }

    /**
     * SPEC §4.2 step 4 (amendment 6), server admission only: the engine's merge still accepts
     * any op in any order. "Has existed" includes ops waiting in this batch.
     */
    private String referenceError(Op op) {
        return switch (op) {
            case Op.AddList a -> known(a.listId()) ? "list_id already exists" : null;
            case Op.AddCard a -> known(a.cardId()) ? "card_id already exists"
                    : isList(a.listId()) ? null : "placement.list_id names no list on this board";
            case Op.SetField f -> isList(f.entityId()) ? (f.field().equals("title") ? null : "a list has only a title")
                    : isCard(f.entityId()) ? null : "entity_id names nothing on this board";
            case Op.SetPlacement p -> isList(p.entityId()) ? (p.listId() == null ? null : "a list takes the list form of placement")
                    : !isCard(p.entityId()) ? "entity_id names nothing on this board"
                    : p.listId() == null ? "a card takes the card form of placement"
                    : isList(p.listId()) ? null : "placement.list_id names no list on this board";
            case Op.DeleteList x -> isList(x.listId()) ? null : "list_id names no list on this board";
            case Op.RestoreList x -> isList(x.listId()) ? null : "list_id names no list on this board";
            case Op.DeleteCard x -> isCard(x.cardId()) ? null : "card_id names no card on this board";
            case Op.RestoreCard x -> isCard(x.cardId()) ? null : "card_id names no card on this board";
        };
    }

    private boolean isList(String id) {
        return board.everAdded(true, id) || batchLists.contains(id);
    }

    private boolean isCard(String id) {
        return board.everAdded(false, id) || batchCards.contains(id);
    }

    private boolean known(String id) {
        return isList(id) || isCard(id);
    }

    // ---- SPEC §6.1 steps 3–5: group commit, then apply ---------------------------------------

    private void flush() {
        List<Waiting> waiting = new ArrayList<>(batch.values());
        List<Store.Pending> ops = waiting.stream().map(Waiting::pending).toList();
        batch.clear();
        batchLists.clear();
        batchCards.clear();
        long started = d.clock().getAsLong();
        long commitStart = Tracing.nowNanos();
        Store.Committed c;
        try {
            c = d.store().commit(id, fence, ops, started + COMMIT_TIMEOUT_MS);
        } catch (Store.BoardDeleted e) {
            // Amendment 27: nothing can be stored on a deleted board. The ops end in the senders'
            // Unsent edits (UNAUTHORIZED), where they can read and export them.
            for (Waiting w : waiting) for (Reply r : w.replies()) r.send(Msg.error(Msg.UNAUTHORIZED, w.pending().op().opId(), "board deleted"));
            dropDeleted("found at commit");
            return;
        } catch (SQLException | RuntimeException e) {
            // Step 5: apply nothing, ACK nothing. The outcome is unknown, so reconcile before
            // anything else: the transaction may have committed after all (amendment 6).
            d.metrics().inc("commit_failures");
            log.atWarn().addKeyValue("board_id", id).addKeyValue("ops", ops.size()).log("commit failed, reconciling: {}", e.toString());
            for (Waiting w : waiting) for (Reply r : w.replies()) r.send(Msg.error(Msg.RETRY_LATER, w.pending().op().opId(), "commit failed"));
            state = State.RECONCILING;
            return;
        }
        if (c.fenced()) {
            // §6.3: zero rows, a newer owner exists. Nothing was stored or broadcast.
            d.metrics().inc("fence_rejections");
            log.atError().addKeyValue("board_id", id).addKeyValue("fence", fence).log("ALERT fenced out by a newer owner");
            for (Waiting w : waiting) for (Reply r : w.replies()) r.send(Msg.error(Msg.NOT_OWNER, w.pending().op().opId(), "fenced out"));
            close(Msg.NOT_OWNER, "fenced out");
            return;
        }
        d.metrics().inc("commits");
        d.metrics().add("committed_ops", c.versions().size());
        d.metrics().prom.observe("commit_ms", Prom.MS, d.clock().getAsLong() - started);
        d.metrics().prom.observe("batch_size", Prom.COUNT, ops.size());
        long commitEnd = Tracing.nowNanos();
        for (Waiting w : waiting) { // SPEC 9.5 traces: time in the batch, then the commit
            String o = w.pending().op().opId();
            Tracing.span(o, "owner.admit", w.admittedNanos(), commitStart, "board_id", id);
            Tracing.span(o, "owner.commit", commitStart, commitEnd, "board_id", id, "batch_size", Integer.toString(ops.size()));
        }
        int next = 0;
        for (Waiting w : waiting) {
            String opId = w.pending().op().opId();
            Long dup = c.duplicates().get(opId);
            if (dup != null) {
                d.metrics().inc("duplicates");
                lru.put(opId, dup);
                for (Reply r : w.replies()) r.send(Msg.ack(opId, dup));
                continue;
            }
            long v = c.versions().get(next++);
            applyCommitted(w.pending().op(), w.pending().json(), v, w.pending().userId());
            for (Reply r : w.replies()) r.send(Msg.ack(opId, v));
        }
        rebalanceIfNeeded(waiting.stream().map(w -> w.pending().op()).toList());
    }

    /**
     * SPEC §3.7: when a key passes 40 characters, the owner issues ordinary SET_PLACEMENT ops
     * with fresh keys for that list (or for the lists), under its own replica ID. They are
     * committed like any batch and converge like any other op.
     */
    @SuppressWarnings("unchecked")
    private void rebalanceIfNeeded(List<Op> committed) {
        java.util.Set<String> containers = new java.util.LinkedHashSet<>(); // a list ID, or "" for the lists
        for (Op op : committed) {
            switch (op) {
                case Op.AddList a -> { if (a.positionKey().length() > REBALANCE_AT) containers.add(""); }
                case Op.AddCard a -> { if (a.positionKey().length() > REBALANCE_AT) containers.add(a.listId()); }
                case Op.SetPlacement p -> {
                    if (p.positionKey().length() > REBALANCE_AT) containers.add(p.listId() == null ? "" : p.listId());
                }
                default -> {}
            }
        }
        if (containers.isEmpty()) return;
        List<Map<String, Object>> lists = (List<Map<String, Object>>) board.view().get("lists");
        for (String container : containers) {
            List<Map<String, Object>> entries = container.isEmpty()
                    ? lists.stream().filter(l -> !Op.NIL_UUID.equals(l.get("id"))).toList()
                    : lists.stream().filter(l -> container.equals(l.get("id"))).findFirst()
                            .map(l -> (List<Map<String, Object>>) l.get("cards")).orElse(List.of());
            List<String> keys = entries.stream().map(e -> (String) e.get("position_key")).toList();
            List<String> fresh = dev.concord.crdt.Keys.rebalance(keys);
            int n = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (fresh.get(i).equals(keys.get(i))) continue;
                clock = clock.tick(d.clock().getAsLong());
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("op_id", Op.newOpId());
                json.put("board_id", id);
                json.put("hlc", clock.encode());
                json.put("replica_id", replica);
                json.put("type", "SET_PLACEMENT");
                json.put("entity_id", entries.get(i).get("id"));
                json.put("placement", Op.placement(container.isEmpty() ? null : container, fresh.get(i)));
                Op op = Op.parse(json);
                if (batch.isEmpty()) batchDeadline = d.clock().getAsLong();
                batch.put(op.opId(), new Waiting(new Store.Pending(op, json, SYSTEM_USER), new ArrayList<>(), Tracing.nowNanos()));
                n++;
            }
            d.metrics().inc("rebalances");
            log.atInfo().addKeyValue("board_id", id).addKeyValue("list_id", container.isEmpty() ? "lists" : container)
                    .addKeyValue("ops", n).log("rebalancing keys");
        }
    }

    /** Step 4, for one committed op: apply to memory, remember its ACK, publish. */
    private void applyCommitted(Op op, Map<String, Object> json, long v, String userId) {
        try {
            clock = clock.receive(op.hlc(), d.clock().getAsLong()); // our next op outranks every applied one
        } catch (RuntimeException e) {
            // a counter at its maximum: tick() moves on with the wall clock
        }
        int collisions = board.apply(op);
        if (collisions > 0) d.metrics().add("lww_collisions", collisions);
        version = v;
        lru.put(op.opId(), v);
        opsSinceSnapshot++;
        d.metrics().prom.inc("board_ops", "board_id", id);
        long published = Tracing.nowNanos();
        d.publisher().publish(id, Msg.encode(Msg.broadcast(json, v, userId)));
        Tracing.span(op.opId(), "owner.broadcast", published, Tracing.nowNanos(), "board_id", id, "version", Long.toString(v));
    }

    /**
     * After an unknown commit outcome (amendment 6): lock the board row, read back everything
     * committed after our version, apply and publish it, then serve again. Until then every
     * request gets RETRY_LATER and no snapshot is taken. Retries until Postgres answers.
     */
    private void reconcile() throws InterruptedException {
        long backoff = 200;
        while (state == State.RECONCILING) {
            try {
                Store.Reconciled r = d.store().reconcile(id, version);
                if (r.fence() != fence) {
                    close(Msg.NOT_OWNER, "fenced out while reconciling");
                    return;
                }
                for (Store.Stored s : r.ops()) applyCommitted(s.op(), s.json(), s.version(), s.userId());
                log.atInfo().addKeyValue("board_id", id).addKeyValue("recovered_ops", r.ops().size())
                        .addKeyValue("version", version).log("reconciled after commit failure");
                state = State.SERVING;
                return;
            } catch (Store.BoardDeleted e) {
                dropDeleted("found while reconciling"); // retrying would never end
                return;
            } catch (SQLException | RuntimeException e) {
                long until = d.clock().getAsLong() + backoff;
                for (long left; state == State.RECONCILING && (left = until - d.clock().getAsLong()) > 0; ) {
                    Item item = mailbox.poll(left, TimeUnit.MILLISECONDS);
                    if (item != null) {
                        lastActivity = d.clock().getAsLong();
                        whileReconciling(item);
                    }
                    unloadIfIdle(); // refuses while reconciling; asked here so that rule is what keeps the board
                }
                backoff = Math.min(backoff * 2, 5_000);
            }
        }
    }

    private void whileReconciling(Item item) {
        switch (item) {
            case Drop dr -> close(Msg.NOT_OWNER, dr.reason());
            case Release r -> {
                // Cannot flush or snapshot without Postgres; let the lease go so a new owner
                // reloads from Postgres when it returns.
                safeRelease();
                close(Msg.NOT_OWNER, "released");
                r.done().complete(null);
            }
            case Deleted x -> dropDeleted("announced while reconciling");
            default -> replyError(item, Msg.RETRY_LATER, "board is reconciling with storage");
        }
    }

    // ---- reads ----------------------------------------------------------------------------

    /** JOINED or SYNC (SPEC §7.3 step 3): ops after since_version, or a snapshot if far behind. */
    private void catchUp(String type, String userId, Long since, Reply reply) {
        String role;
        try {
            role = d.membership().role(id, userId);
        } catch (SQLException e) {
            reply.send(Msg.error(Msg.RETRY_LATER, null, "membership unavailable"));
            return;
        }
        if (role == null) {
            reply.send(Msg.error(Msg.UNAUTHORIZED, null, "not a member"));
            return;
        }
        Map<String, Object> m;
        try {
            m = catchUpMessage(type, since);
        } catch (SQLException e) {
            reply.send(Msg.error(Msg.RETRY_LATER, null, "storage unavailable"));
            return;
        }
        if (type.equals("JOINED")) {
            m.put("server_time_ms", d.clock().getAsLong());
            m.put("role", role);
            try {
                m.put("members", d.store().members(id)); // SPEC §7.5: user_id → {name, role}
            } catch (SQLException e) {
                reply.send(Msg.error(Msg.RETRY_LATER, null, "storage unavailable"));
                return;
            }
        }
        reply.send(m);
    }

    private Map<String, Object> catchUpMessage(String type, Long since) throws SQLException {
        if (since != null && since >= 0 && since <= version && version - since <= CATCH_UP_LIMIT) {
            List<Store.Stored> ops = d.store().opsAfter(id, since, CATCH_UP_LIMIT);
            // Fewer than expected means the range is no longer kept: send a snapshot instead.
            if (ops.size() == version - since) return Msg.catchUp(type, entries(ops), null, version);
        }
        return Msg.catchUp(type, null, board.encode(), version);
    }

    private void poll(Poll p) {
        try {
            List<Store.Stored> ops = p.since() < version ? d.store().opsAfter(id, p.since(), CATCH_UP_LIMIT) : List.of();
            p.reply().send(Msg.obj("type", "OPS", "ops", entries(ops), "version", version));
        } catch (SQLException e) {
            p.reply().send(Msg.error(Msg.RETRY_LATER, null, "storage unavailable"));
        }
    }

    private static List<Map<String, Object>> entries(List<Store.Stored> ops) {
        return ops.stream().map(s -> Msg.obj("op", s.json(), "version", s.version(), "user_id", s.userId())).toList();
    }

    // ---- snapshots and handover ----------------------------------------------------------

    private void snapshot() {
        long started = d.clock().getAsLong();
        try {
            String canonical = board.canonical();
            d.store().writeSnapshot(id, version, canonical);
            d.metrics().inc("snapshots");
            d.metrics().prom.observe("snapshot_ms", Prom.MS, d.clock().getAsLong() - started);
            d.metrics().prom.observe("snapshot_bytes", Prom.BYTES, canonical.length());
            log.atInfo().addKeyValue("board_id", id).addKeyValue("version", version).addKeyValue("bytes", canonical.length())
                    .addKeyValue("ms", d.clock().getAsLong() - started).log("snapshot written");
        } catch (Store.BoardDeleted e) {
            dropDeleted("found at snapshot");
            return;
        } catch (SQLException | RuntimeException e) {
            log.atError().addKeyValue("board_id", id).log("snapshot failed: {}", e.toString());
        }
        opsSinceSnapshot = 0;
        lastSnapshotAt = d.clock().getAsLong();
    }

    /**
     * Amendment 26: a board nobody has open (no request for idleUnloadMs, and no gateway on its
     * channel) is unloaded the way a graceful handover releases it: snapshot, release the lease,
     * close. The next request claims it again and loads it from that snapshot. Never while
     * reconciling an unknown commit outcome, and never with ops still in the mailbox or batch.
     */
    private void unloadIfIdle() {
        long now = d.clock().getAsLong();
        if (now - lastActivity < d.idleUnloadMs() || now < nextIdleCheck) return;
        nextIdleCheck = now + Math.min(30_000, d.idleUnloadMs() / 4);
        if (state != State.SERVING) return; // never while reconciling: the board's state is not known yet
        if (!batch.isEmpty() || !mailbox.isEmpty()) return; // never with ops still to commit or answer
        if (d.subscribers().count(id) != 0) return; // someone has it open, or Redis cannot say
        if (opsSinceSnapshot > 0) snapshot();
        if (state != State.SERVING) return; // the snapshot found the board deleted
        safeRelease();
        d.metrics().inc("idle_unloads");
        log.atInfo().addKeyValue("board_id", id).addKeyValue("version", version)
                .addKeyValue("idle_ms", now - lastActivity).log("unloaded idle board");
        close(Msg.NOT_OWNER, "board unloaded: idle");
    }

    /** SPEC §2.4: stop taking ops (the instance already does), flush, snapshot, release. */
    private void release(Release r) {
        if (!batch.isEmpty()) flush();
        if (state == State.SERVING) {
            if (opsSinceSnapshot > 0) snapshot();
            if (state == State.SERVING) { // the snapshot may have found the board deleted
                safeRelease();
                close(Msg.NOT_OWNER, "released");
            }
        }
        r.done().complete(null);
    }

    /**
     * Amendment 27: the board was deleted while loaded. Close with UNAUTHORIZED for everything
     * queued (it does not exist any more), release the lease, and take no snapshot.
     */
    private void dropDeleted(String how) {
        if (state == State.CLOSED) return;
        d.metrics().inc("boards_deleted");
        log.atInfo().addKeyValue("board_id", id).log("board deleted ({}): dropped", how);
        safeRelease();
        close(Msg.UNAUTHORIZED, "board deleted");
    }

    private void safeRelease() {
        try {
            d.ownership().release(id, fence);
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("board_id", id).log("lease release failed: {}", e.toString());
        }
    }

    /** Stops the actor: everything queued, and everything in the batch, gets {@code code}. */
    private void close(String code, String why) {
        synchronized (closeLock) {
            state = State.CLOSED;
        }
        d.metrics().prom.remove("board_ops", "board_id", id);
        for (Waiting w : batch.values()) for (Reply r : w.replies()) r.send(Msg.error(code, w.pending().op().opId(), why));
        batch.clear();
        for (Item item; (item = mailbox.poll()) != null; ) {
            if (item instanceof Release r) r.done().complete(null);
            else replyError(item, code, why);
        }
        onClose.run();
    }

    private static void replyError(Item item, String code, String why) {
        switch (item) {
            case Submit s -> {
                String opId = s.op() instanceof Map<?, ?> m && m.get("op_id") instanceof String x ? x : null;
                s.reply().send(Msg.error(code, opId, why));
            }
            case Join j -> j.reply().send(Msg.error(code, null, why));
            case SyncRequest s -> s.reply().send(Msg.error(code, null, why));
            case Poll p -> p.reply().send(Msg.error(code, null, why));
            case Ensure e -> e.reply().send(Msg.error(code, null, why));
            case Drop dr -> {}
            case Release r -> r.done().complete(null);
            case Deleted x -> {}
        }
    }

    // ---- test access ----------------------------------------------------------------------

    /** Canonical state and version, read on the actor thread. For tests and drills. */
    public CompletableFuture<Map<String, Object>> inspect() {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (offer(new Ensure(m -> f.complete(Msg.obj("version", version, "state", board.canonical(), "reply", m)))) != Offered.ACCEPTED)
            f.completeExceptionally(new IllegalStateException("closed"));
        return f;
    }
}
