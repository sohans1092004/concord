package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.crdt.Hlc;
import dev.concord.crdt.Json;
import io.javalin.websocket.WsContext;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;
import dev.concord.common.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client WebSocket (SPEC §7.5). Authenticated at upgrade; may join one board. Client JSON
 * is parsed here, once, by the server's own parser, which rejects duplicate member names
 * (SPEC §4.2); everything forwarded from here on is canonical, server-produced JSON.
 *
 * <p>Requests are forwarded strictly in arrival order, one at a time, by this connection's own
 * virtual thread: the owner's reference checks (§4.2 step 4) rely on a client's ADD_LIST
 * reaching it before the ADD_CARD into that list, including across NOT_OWNER retries.
 * ponytail: one request in flight per connection caps a connection at roughly one op per
 * group-commit window; pipeline with per-connection sequence numbers if Phase 4 load shows it.
 */
final class ClientConn {
    private static final Logger log = LoggerFactory.getLogger(ClientConn.class);
    static final double OPS_PER_SECOND = 20, BURST = 50; // SPEC §9.4

    final String userId;
    private final WsContext ctx;
    private final Router router;
    private final Fanout fanout;
    private final LongSupplier clock;
    private final TokenBucket bucket;
    /** Requests waiting for this connection's forwarder; bounded (amendment 9). */
    static final int INBOX_LIMIT = 256;

    private final BlockingQueue<Map<?, ?>> inbox = new LinkedBlockingQueue<>(INBOX_LIMIT);
    private final Outbox outbox;
    private final Thread worker;
    private final Presence presence;
    /** PRESENCE frames: 5/s, burst 10. They only touch Redis, but a flood would still hammer it. */
    private final TokenBucket presenceBucket;
    private volatile String board;
    private volatile boolean joined;
    /** Names this connection in presence (amendment 22): a user may have several. */
    final String connectionId = java.util.UUID.randomUUID().toString();

    ClientConn(String userId, WsContext ctx, Router router, Fanout fanout, LongSupplier clock, Outbox outbox, Presence presence) {
        this.outbox = outbox;
        this.presence = presence;
        this.presenceBucket = new TokenBucket(5, 10, clock.getAsLong());
        this.userId = userId;
        this.ctx = ctx;
        this.router = router;
        this.fanout = fanout;
        this.clock = clock;
        this.bucket = new TokenBucket(OPS_PER_SECOND, BURST, clock.getAsLong());
        this.worker = Thread.ofVirtual().name("client-" + userId).start(this::drain);
    }

    private void drain() {
        try {
            while (true) {
                Map<?, ?> f = inbox.take();
                switch (String.valueOf(f.get("type"))) {
                    case "JOIN" -> join(f);
                    case "OP" -> op(f);
                    case "SYNC_REQUEST" -> sync(f);
                    default -> {}
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Non-blocking and bounded: a slow client never holds up delivery to others, and one that
     * stops reading is disconnected with RECONNECT_ELSEWHERE once its backlog passes the limit;
     * it catches up by version when it reconnects (§7.3).
     */
    void send(String text) {
        outbox.send(text);
    }

    void send(Map<String, Object> msg) {
        send(Msg.encode(msg));
    }

    void onMessage(String text) {
        Map<?, ?> f;
        try {
            f = (Map<?, ?>) Json.parse(text);
        } catch (RuntimeException e) {
            send(Msg.error(Msg.INVALID_OP, null, "frame is not one JSON object without duplicate members"));
            return;
        }
        switch (String.valueOf(f.get("type"))) {
            case "JOIN", "SYNC_REQUEST" -> {
                if (!inbox.offer(f)) send(Msg.error(Msg.RETRY_LATER, null, "too many requests in flight"));
            }
            case "OP" -> {
                // Rate limit on arrival, so a flood is refused at once rather than queued.
                if (!bucket.take(clock.getAsLong())) send(Msg.error(Msg.RETRY_LATER, opIdOf(f), "rate limited: 20 ops/s, burst 50"));
                else if (!inbox.offer(f)) send(Msg.error(Msg.RETRY_LATER, opIdOf(f), "too many requests in flight"));
            }
            case "PRESENCE" -> presence(f);
            default -> send(Msg.error(Msg.INVALID_OP, null, "unknown message type"));
        }
    }

    /** SPEC §7.5 PRESENCE, handled here and in Redis only: never forwarded to the owner, never stored. */
    private void presence(Map<?, ?> f) {
        String b = board;
        if (!joined || b == null || !b.equals(f.get("board_id"))) {
            send(Msg.error(Msg.INVALID_OP, null, "PRESENCE needs a joined board"));
            return;
        }
        Object card = f.get("card_id"), state = f.get("state");
        if (!(card == null || (card instanceof String c && Hlc.isUuid(c))) || !("active".equals(state) || "idle".equals(state))) {
            send(Msg.error(Msg.INVALID_OP, null, "PRESENCE needs card_id (a UUID or null) and state active or idle"));
            return;
        }
        if (!presenceBucket.take(clock.getAsLong())) return; // dropped: the next heartbeat repeats it
        presence.update(b, connectionId, userId, (String) card, (String) state);
    }

    private static String opIdOf(Map<?, ?> f) {
        return f.get("op") instanceof Map<?, ?> m && m.get("op_id") instanceof String s ? s : null;
    }

    /** Waits for the owner's reply: this is what keeps one connection's requests in order. */
    private static Map<String, Object> await(java.util.concurrent.CompletableFuture<Map<String, Object>> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Msg.error(Msg.UNAVAILABLE, null, "gateway closing");
        } catch (java.util.concurrent.ExecutionException e) {
            return Msg.error(Msg.UNAVAILABLE, null, "owner unreachable");
        }
    }

    private void join(Map<?, ?> f) {
        String b = f.get("board_id") instanceof String s && Hlc.isUuid(s) ? s : null;
        if (b == null) {
            send(Msg.error(Msg.INVALID_OP, null, "board_id is not a lowercase canonical UUID"));
            return;
        }
        if (board != null && !board.equals(b)) {
            send(Msg.error(Msg.INVALID_OP, null, "one board per connection"));
            return;
        }
        Object since = f.get("since_version");
        board = b;
        fanout.join(b, this, this::send); // subscribe first, so nothing after JOINED is missed
        Map<String, Object> r = await(router.forward(b, Msg.obj("k", "JOIN", "board_id", b, "user_id", userId, "since_version", since)));
        if ("JOINED".equals(r.get("type"))) fanout.saw(b, (Long) r.get("version"));
        else if (Msg.UNAUTHORIZED.equals(r.get("code"))) {
            fanout.leave(b, this);
            board = null;
        }
        send(r);
        if ("JOINED".equals(r.get("type"))) {
            joined = true;
            for (Map<String, Object> p : presence.snapshot(b)) send(p); // who is here already
        }
    }

    private void op(Map<?, ?> f) {
        Object op = f.get("op");
        String opId = opIdOf(f);
        String b = board;
        if (b == null) {
            send(Msg.error(Msg.INVALID_OP, opId, "JOIN a board first"));
            return;
        }
        if (!(op instanceof Map<?, ?> m) || !b.equals(m.get("board_id"))) {
            send(Msg.error(Msg.INVALID_OP, opId, "op.board_id is not the joined board"));
            return;
        }
        Map<String, Object> r = await(router.forward(b, Msg.obj("k", "OP", "board_id", b, "user_id", userId, "op", op)));
        if ("ERROR".equals(r.get("type")) && r.get("op_id") == null) r.put("op_id", opId);
        send(r);
    }

    private void sync(Map<?, ?> f) {
        String b = board;
        if (b == null) {
            send(Msg.error(Msg.INVALID_OP, null, "JOIN a board first"));
            return;
        }
        Map<String, Object> r = await(router.forward(b,
                Msg.obj("k", "SYNC_REQUEST", "board_id", b, "user_id", userId, "since_version", f.get("since_version"))));
        if ("SYNC".equals(r.get("type"))) fanout.saw(b, (Long) r.get("version"));
        send(r);
    }

    void onClose() {
        worker.interrupt();
        presence.leave(connectionId);
        String b = board;
        if (b != null) fanout.leave(b, this);
    }

    void closeElsewhere() {
        send(Msg.obj("type", "RECONNECT_ELSEWHERE"));
        try {
            ctx.closeSession(1001, "gateway draining");
        } catch (RuntimeException e) {
            // already gone
        }
    }
}
