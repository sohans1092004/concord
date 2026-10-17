package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Config;
import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.session.Harness;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import dev.concord.session.SessionMain;
import dev.concord.session.Store;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** SPEC §1.1, §5.2, §7.5: presence, in Redis only, never Postgres. */
class PresenceTest {
    /**
     * Every client is connected (tickets need Postgres), then the gateway's Postgres is cut off.
     * Presence still flows: it never touches Postgres. It stays on its board.
     */
    @Test
    @SuppressWarnings("try") // a is closed early on purpose: the disconnect is under test
    void presenceFlowsWithTheGatewaysPostgresCutOffAndStaysOnItsBoard() throws Exception {
        GatewayTest.Board b = GatewayTest.seed(2, 0), other = GatewayTest.seed(0, 0);
        Map<String, String> env = Infra.gatewayEnv();
        env.put("CONCORD_DB_URL", "jdbc:postgresql://" + Infra.TOXI.getHost() + ":" + Infra.TOXI.getMappedPort(8666) + "/concord");
        var pg = Infra.pgProxy();
        try (SessionMain s = Harness.start("pres-s-" + Ops.uuid(), Store::new, false);
                GatewayMain g = new GatewayMain(Config.from(env));
                WsClient a = new WsClient(g.port(), b.token(g.port(), 0), null);
                WsClient c = new WsClient(g.port(), b.token(g.port(), 1), null);
                WsClient late = new WsClient(g.port(), b.token(g.port(), 2), null);
                WsClient elsewhere = new WsClient(g.port(), other.token(g.port(), 0), null)) {
            assertTrue(s.port() > 0);
            assertEquals("JOINED", a.join(b.id(), null).get("type"));
            assertEquals("JOINED", c.join(b.id(), null).get("type"));
            assertEquals("JOINED", elsewhere.join(other.id(), null).get("type"));
            pg.disable();
            try {
                String card = Ops.uuid();
                a.send(Msg.obj("type", "PRESENCE", "board_id", b.id(), "card_id", card, "state", "active"));
                Map<String, Object> seen = c.next(m -> "PRESENCE_UPDATE".equals(m.get("type")));
                assertEquals(b.users().get(0).get("id"), seen.get("user_id"));
                assertEquals(card, seen.get("card_id"));
                assertEquals("active", seen.get("state"));
                String aConnection = (String) seen.get("connection_id");
                assertTrue(aConnection != null && !aConnection.isEmpty(), "each update names its connection");

                // A client joining later is told who is already here.
                assertEquals("JOINED", late.join(b.id(), null).get("type"));
                assertEquals(card, late.next(m -> "PRESENCE_UPDATE".equals(m.get("type"))).get("card_id"));

                // Wrong board, bad card ID: refused, never recorded.
                a.send(Msg.obj("type", "PRESENCE", "board_id", other.id(), "card_id", null, "state", "active"));
                assertEquals("INVALID_OP", a.next(m -> "ERROR".equals(m.get("type"))).get("code"));
                a.send(Msg.obj("type", "PRESENCE", "board_id", b.id(), "card_id", "not-a-uuid", "state", "active"));
                assertEquals("INVALID_OP", a.next(m -> "ERROR".equals(m.get("type"))).get("code"));

                a.close(); // disconnect: c hears that a left
                Map<String, Object> left = c.next(m -> "PRESENCE_UPDATE".equals(m.get("type")) && "left".equals(m.get("state")));
                assertEquals(b.users().get(0).get("id"), left.get("user_id"));
                assertEquals(aConnection, left.get("connection_id"), "left names the connection that went");
                Thread.sleep(300);
                assertTrue(elsewhere.inbox.stream().noneMatch(m -> "PRESENCE_UPDATE".equals(m.get("type"))), "presence never crosses boards");
            } finally {
                pg.enable();
            }
        }
    }

    /** The sweep removes entries older than 30s and announces each removed connection as left (amendment 22). */
    @Test
    void sweepRemovesStaleEntriesAndAnnouncesEachConnectionThatWent() throws Exception {
        String board = Ops.uuid(), gone = Ops.uuid(), stays = Ops.uuid();
        AtomicLong now = new AtomicLong(1_000_000_000L);
        var heard = new LinkedBlockingQueue<Map<?, ?>>();
        try (var redis = Infra.redis(); var sub = redis.pubSub()) {
            sub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    heard.add((Map<?, ?>) dev.concord.crdt.Json.parse(message));
                }
            });
            sub.sync().subscribe(RedisKeys.channel(board));
            Presence p = new Presence(redis.conn.sync(), now::get);
            p.update(board, "conn-1", gone, null, "active");
            p.update(board, "conn-2", stays, null, "active");
            now.addAndGet(20_000);
            p.update(board, "conn-3", stays, null, "idle"); // a second tab of the same user, still fresh
            now.addAndGet(11_000); // the first two are now 31s old
            heard.clear();
            p.sweep(List.of(board));
            assertEquals(List.of(stays + ":conn-3:-:idle"), redis.conn.sync().zrange(RedisKeys.presence(board), 0, -1));
            // Pub/Sub delivers asynchronously, so the updates above may still arrive: read only "left".
            List<Map<?, ?>> lefts = new java.util.ArrayList<>();
            long until = System.currentTimeMillis() + 2_000;
            for (Map<?, ?> m; System.currentTimeMillis() < until; )
                if ((m = heard.poll(100, TimeUnit.MILLISECONDS)) != null && "left".equals(m.get("state"))) lefts.add(withoutNull(m));
            assertEquals(java.util.Set.of(Map.of("type", "PRESENCE_UPDATE", "user_id", gone, "connection_id", "conn-1", "state", "left"),
                    Map.of("type", "PRESENCE_UPDATE", "user_id", stays, "connection_id", "conn-2", "state", "left")), java.util.Set.copyOf(lefts),
                    "each stale connection is announced as left, and only those; conn-3 of the same user stays");
            assertEquals(2, lefts.size());
        }
    }

    private static Map<?, ?> withoutNull(Map<?, ?> m) {
        var out = new java.util.HashMap<Object, Object>(m);
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }
}
