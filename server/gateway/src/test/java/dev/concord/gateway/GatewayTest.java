package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Config;
import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.crdt.Json;
import dev.concord.devtools.Dev;
import dev.concord.session.BoardActor;
import dev.concord.session.Harness;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import dev.concord.session.SessionMain;
import dev.concord.session.Store;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SPEC §2.3, §6.4, §9.3, §9.4 through real gateways and Session instances (in-process). */
class GatewayTest {
    static SessionMain s1, s2;
    static GatewayMain g1, g2;

    @BeforeAll
    static void up() {
        s1 = Harness.start("gw-s1-" + Ops.uuid(), Store::new, false);
        s2 = Harness.start("gw-s2-" + Ops.uuid(), Store::new, false);
        g1 = gateway(Infra.redisUrl());
        g2 = gateway(Infra.redisUrl());
    }

    @AfterAll
    static void down() {
        for (AutoCloseable c : new AutoCloseable[] {g1, g2, s1, s2}) {
            try {
                c.close();
            } catch (Exception e) {
                // best effort
            }
        }
    }

    static GatewayMain gateway(String redisUrl) {
        Map<String, String> env = Infra.gatewayEnv();
        env.put("CONCORD_REDIS_URL", redisUrl);
        return new GatewayMain(Config.from(env));
    }

    /** A seeded board: users.get(0) is its owner, then editors, then viewers. */
    record Board(String id, List<Map<?, ?>> users) {
        String session(int i) {
            return (String) users.get(i).get("session");
        }

        /** A fresh ticket for user {@code i}, from g1's REST API. */
        String token(int i) throws Exception {
            return WsClient.ticket(g1.port(), session(i));
        }

        String token(int port, int i) throws Exception {
            return WsClient.ticket(port, session(i));
        }
    }

    @SuppressWarnings("unchecked")
    static Board seed(int editors, int viewers) throws Exception {
        Map<String, Object> s = Dev.seed(Infra.db(), editors, viewers);
        return new Board((String) s.get("board"), (List<Map<?, ?>>) s.get("users"));
    }

    @Test
    void upgradeRequiresAValidTicketAndAnAllowedOrigin() throws Exception {
        Board b = seed(0, 0);
        assertThrows(Exception.class, () -> new WsClient(g1.port(), "not-a-ticket", null));
        String t = b.token(0);
        assertThrows(Exception.class, () -> new WsClient(g1.port(), t, "http://evil.test"));
        // A session token is not a ticket.
        assertThrows(Exception.class, () -> new WsClient(g1.port(), b.session(0), null));
        try (WsClient ok = new WsClient(g1.port(), b.token(0), "http://allowed.test"); WsClient script = new WsClient(g1.port(), b.token(0), null)) {
            assertEquals("JOINED", ok.join(b.id(), null).get("type"));
            assertEquals("JOINED", script.join(b.id(), null).get("type"));
        }
    }

    @Test
    void framesOver64KbAreRejected() throws Exception {
        Board b = seed(0, 0);
        try (WsClient c = new WsClient(g1.port(), b.token(0), null)) {
            c.sendRaw("{\"type\":\"PRESENCE\",\"pad\":\"" + "x".repeat(70_000) + "\"}");
            assertEquals(1009, c.closed.get(10, TimeUnit.SECONDS), "1009: message too big");
        }
    }

    @Test
    void clientsOnDifferentGatewaysSeeEachOthersOps() throws Exception {
        Board b = seed(1, 0);
        Ops ops = new Ops(b.id());
        try (WsClient a = new WsClient(g1.port(), b.token(0), null); WsClient c = new WsClient(g2.port(), b.token(1), null)) {
            a.join(b.id(), null);
            c.join(b.id(), null);
            String list = Ops.uuid();
            var addList = ops.addList(list, "a0", "To Do");
            assertEquals(1L, a.op(addList).get("version"));
            var seen = c.next(m -> "OP_BROADCAST".equals(m.get("type")));
            assertEquals(addList.get("op_id"), ((Map<?, ?>) seen.get("op")).get("op_id"));
            var card = new Ops(b.id()).addCard(Ops.uuid(), list, "a0", "from the other gateway");
            assertEquals(2L, c.op(card).get("version"));
            var back = a.next(m -> "OP_BROADCAST".equals(m.get("type")) && Long.valueOf(2).equals(m.get("version")));
            assertEquals(card.get("op_id"), ((Map<?, ?>) back.get("op")).get("op_id"));
        }
    }

    /** §9.3 board isolation: nothing from board Y ever reaches a client of board X. */
    @Test
    void boardIsolation() throws Exception {
        Board x = seed(0, 0), y = seed(0, 0);
        try (WsClient cx = new WsClient(g1.port(), x.token(0), null); WsClient cy = new WsClient(g1.port(), y.token(0), null)) {
            cx.join(x.id(), null);
            cy.join(y.id(), null);
            Ops oy = new Ops(y.id());
            for (int i = 0; i < 20; i++) assertEquals("OP_ACK", cy.op(oy.addList(Ops.uuid(), "a0", "y" + i)).get("type"));
            // X's user cannot join Y, cannot send ops for Y, and never hears Y.
            try (WsClient sneaky = new WsClient(g2.port(), x.token(0), null)) {
                assertEquals(Msg.UNAUTHORIZED, sneaky.join(y.id(), null).get("code"));
            }
            var crossOp = oy.addList(Ops.uuid(), "a1", "sent on X's connection");
            assertEquals(Msg.INVALID_OP, cx.op(crossOp).get("code"));
            Thread.sleep(1_000);
            for (Map<String, Object> m : cx.inbox)
                if (m.get("op") instanceof Map<?, ?> op) assertEquals(x.id(), op.get("board_id"), "leaked: " + m);
        }
    }

    @Test
    void rateLimitIs20PerSecondWithBurst50() throws Exception {
        Board b = seed(0, 0);
        Ops ops = new Ops(b.id());
        try (WsClient c = new WsClient(g1.port(), b.token(0), null)) {
            c.join(b.id(), null);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                var op = ops.addList(Ops.uuid(), "a0", "l" + i);
                ids.add((String) op.get("op_id"));
                c.send(Msg.obj("type", "OP", "op", op));
            }
            int acked = 0, limited = 0;
            java.util.Set<String> pending = new java.util.HashSet<>(ids);
            while (!pending.isEmpty()) {
                var r = c.next(m -> pending.contains(m.get("op_id")));
                pending.remove((String) r.get("op_id"));
                if ("OP_ACK".equals(r.get("type"))) acked++;
                else if (String.valueOf(r.get("detail")).startsWith("rate limited")) limited++;
            }
            assertTrue(limited >= 25, "expected about 30 of 80 refused, got " + limited);
            assertTrue(acked >= 50 && acked <= 55, "burst of 50 plus refill, got " + acked);
        }
    }

    /** §2.3 step 5: NOT_OWNER drops the cached owner, looks it up again and resends. */
    @Test
    void notOwnerRefreshesTheOwnerAndResends() throws Exception {
        Board b = seed(0, 0);
        Ops ops = new Ops(b.id());
        try (WsClient c = new WsClient(g1.port(), b.token(0), null); var redis = Infra.redis()) {
            c.join(b.id(), null);
            var list = ops.addList(Ops.uuid(), "a0", "L");
            assertEquals(1L, c.op(list).get("version")); // g1 now caches the owner for 5s
            String ownerId = (String) ((Map<?, ?>) Json.parse(redis.conn.sync().get(RedisKeys.owner(b.id())))).get("instance");
            SessionMain oldOwner = ownerId.equals(s1.config.instanceId()) ? s1 : s2, newOwner = oldOwner == s1 ? s2 : s1;
            // Ownership moves behind the gateway's back: the lease expires, the other instance claims.
            redis.conn.sync().del(RedisKeys.lease(b.id()), RedisKeys.owner(b.id()));
            var ok = new java.util.concurrent.CompletableFuture<Map<String, Object>>();
            newOwner.boards.dispatch(b.id(), new BoardActor.Ensure(ok::complete));
            assertEquals("OK", ok.get(10, TimeUnit.SECONDS).get("type"));
            // The stale owner is fenced out on commit; the gateway retries on the new owner.
            var title = ops.setField((String) list.get("list_id"), "title", "after the move");
            var r = c.op(title);
            assertEquals("OP_ACK", r.get("type"), () -> "got " + r);
            assertEquals(2L, r.get("version"));
            // The stale owner gave the board up (fenced out on commit, or its renewal found the
            // lease taken), and the op was committed exactly once, by the new owner.
            for (int i = 0; i < 100 && oldOwner.boards.actor(b.id()) != null; i++) Thread.sleep(100);
            assertEquals(null, oldOwner.boards.actor(b.id()));
            assertEquals(2, dev.concord.session.Seed.count(Infra.db(), "SELECT count(*) FROM board_ops WHERE board_id = ?", java.util.UUID.fromString(b.id())));
        }
    }

    /** §6.4 and §8 row 5: with Pub/Sub down, a gateway polls owners and fan-out keeps working. */
    @Test
    void pubSubDownFallsBackToPollingTheOwner() throws Exception {
        Board b = seed(1, 0);
        Ops ops = new Ops(b.id());
        var proxy = Infra.redisProxy();
        try (GatewayMain g3 = gateway(Infra.redisUrlViaProxy());
                WsClient writer = new WsClient(g1.port(), b.token(0), null);
                WsClient reader = new WsClient(g3.port(), b.token(1), null)) {
            writer.join(b.id(), null);
            reader.join(b.id(), null);
            var list = ops.addList(Ops.uuid(), "a0", "L");
            writer.op(list);
            reader.next(m -> "OP_BROADCAST".equals(m.get("type"))); // via Pub/Sub
            proxy.disable(); // Redis gone, for g3 only
            try {
                var title = ops.setField((String) list.get("list_id"), "title", "delivered by polling");
                assertEquals("OP_ACK", writer.op(title).get("type"));
                var polled = reader.next(m -> "OP_BROADCAST".equals(m.get("type")) && Long.valueOf(2).equals(m.get("version")));
                assertEquals(title.get("op_id"), ((Map<?, ?>) polled.get("op")).get("op_id"));
            } finally {
                proxy.enable();
            }
        }
    }
}
