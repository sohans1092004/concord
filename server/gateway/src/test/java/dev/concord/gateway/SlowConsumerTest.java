package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import dev.concord.crdt.Json;
import dev.concord.crdt.Keys;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * Amendment 9: a client that stops reading cannot grow the gateway's memory without limit.
 * The stalled client is a raw TCP socket with a 4 KB receive buffer that completes the
 * WebSocket handshake, then keeps asking for full catch-ups (each a few hundred KB) and never
 * reads a byte. The gateway must keep its backlog for that connection under the limit and
 * disconnect it.
 */
class SlowConsumerTest {
    static final long LIMIT = 1L << 20; // 1 MB, small so the test is quick

    @Test
    @SuppressWarnings("unchecked")
    void aStalledClientIsDisconnectedAndItsBacklogStaysUnderTheLimit() throws Exception {
        GatewayTest.Board b = GatewayTest.seed(1, 0);
        Map<String, String> env = new java.util.HashMap<>();
        env.put("CONCORD_ENV", "dev");
        env.put("CONCORD_REDIS_URL", Infra.redisUrl());
        env.put("CONCORD_PORT", Integer.toString(Infra.freePort()));
        env.put("CONCORD_CLIENT_OUTBOX_BYTES", Long.toString(LIMIT));
        try (var session = dev.concord.session.Harness.start("slow-s-" + Ops.uuid(), dev.concord.session.Store::new, false);
                GatewayMain g = new GatewayMain(dev.concord.common.Config.from(env));
                WsClient writer = new WsClient(g.port(), b.token(0), null)) {
            assertTrue(session.port() > 0); // the board's owner, in process
            // A board whose full catch-up is a few hundred KB: 40 cards with 9,000-character descriptions.
            writer.join(b.id(), null);
            Ops ops = new Ops(b.id());
            String list = Ops.uuid();
            writer.op(ops.addList(list, "a0", "L"));
            List<String> keys = Keys.keysBetween(null, null, 40);
            for (int i = 0; i < 40; i++) {
                String card = Ops.uuid();
                writer.op(ops.addCard(card, list, keys.get(i), "c" + i));
                assertEquals("OP_ACK", writer.op(ops.setField(card, "description", "d".repeat(9_000))).get("type"));
            }

            try (Socket stalled = new Socket()) {
                stalled.setReceiveBufferSize(4_096);
                stalled.connect(new java.net.InetSocketAddress("localhost", g.port()));
                handshake(stalled, g.port(), b.token(1));
                OutputStream out = stalled.getOutputStream();
                frame(out, Msg.encode(Msg.obj("type", "JOIN", "board_id", b.id(), "since_version", 0L)));
                long start = System.currentTimeMillis();
                long closes = 0;
                for (int i = 0; i < 400 && closes == 0; i++) {
                    try {
                        frame(out, Msg.encode(Msg.obj("type", "SYNC_REQUEST", "board_id", b.id(), "since_version", 0L)));
                    } catch (java.io.IOException e) {
                        break; // the gateway cut us
                    }
                    Thread.sleep(20);
                    closes = (Long) metrics(g).get("slow_consumer_closes");
                }
                for (int i = 0; i < 100 && closes == 0; i++) {
                    Thread.sleep(100);
                    closes = (Long) metrics(g).get("slow_consumer_closes");
                }
                Map<String, Object> m = metrics(g);
                System.out.printf("slow consumer: disconnected after %d ms, backlog peak %d bytes, limit %d bytes%n",
                        System.currentTimeMillis() - start, m.get("outbox_peak_bytes"), LIMIT);
                assertEquals(1L, m.get("slow_consumer_closes"), "the stalled client is disconnected");
                assertTrue((Long) m.get("outbox_peak_bytes") <= LIMIT, "backlog never exceeds the limit: " + m);
                for (int i = 0; i < 50 && (Long) metrics(g).get("connections") != 1L; i++) Thread.sleep(100);
                assertEquals(1L, metrics(g).get("connections"), "only the writer is left");
            }
            // The writer, which reads, is unaffected.
            assertEquals("OP_ACK", writer.op(ops.setField(list, "title", "still fine")).get("type"));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> metrics(GatewayMain g) throws Exception {
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + g.port() + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return (Map<String, Object>) Json.parse(body);
    }

    static void handshake(Socket s, int port, String token) throws Exception {
        String req = "GET /ws?access_token=" + token + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
        s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
        assertTrue(head.toString().startsWith("HTTP/1.1 101"), head.toString());
        // From here on, this socket is never read again.
    }

    /** One masked text frame, as a client must send (RFC 6455 §5.2). */
    static void frame(OutputStream out, String text) throws java.io.IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x81);
        if (p.length < 126) f.write(0x80 | p.length);
        else {
            f.write(0x80 | 126);
            f.write(p.length >> 8);
            f.write(p.length & 0xff);
        }
        byte[] mask = new byte[4];
        ThreadLocalRandom.current().nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i % 4]);
        out.write(f.toByteArray());
        out.flush();
    }
}
