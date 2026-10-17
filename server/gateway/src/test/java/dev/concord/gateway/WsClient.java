package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.crdt.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A raw protocol client for gateway tests: sends §7.5 frames, queues what it receives. */
final class WsClient implements WebSocket.Listener, AutoCloseable {
    final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    private final WebSocket ws;

    /** A fresh single-use ticket (SPEC §9.4) from the gateway on {@code port}, for {@code session}. */
    static String ticket(int port, String session) throws Exception {
        var r = HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ws-ticket"))
                .header("Authorization", "Bearer " + session).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("ticket: " + r.statusCode() + " " + r.body());
        return (String) ((Map<?, ?>) Json.parse(r.body())).get("ticket");
    }

    WsClient(int port, String ticket, String origin) throws Exception {
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder();
        if (origin != null) b.header("Origin", origin);
        ws = b.buildAsync(URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket), this).get(10, TimeUnit.SECONDS);
    }

    void send(Map<String, Object> frame) {
        sendRaw(Msg.encode(frame));
    }

    synchronized void sendRaw(String text) {
        ws.sendText(text, true).join();
    }

    Map<String, Object> join(String board, Long since) throws InterruptedException {
        send(Msg.obj("type", "JOIN", "board_id", board, "since_version", since));
        return next(m -> "JOINED".equals(m.get("type")) || "ERROR".equals(m.get("type")));
    }

    Map<String, Object> op(Map<String, Object> op) throws InterruptedException {
        send(Msg.obj("type", "OP", "op", op));
        return reply((String) op.get("op_id"));
    }

    /** The OP_ACK or ERROR for this op. */
    Map<String, Object> reply(String opId) throws InterruptedException {
        return next(m -> ("OP_ACK".equals(m.get("type")) || "ERROR".equals(m.get("type"))) && opId.equals(m.get("op_id")));
    }

    /** The next message matching {@code p} within 10s; others are discarded. */
    Map<String, Object> next(Predicate<Map<String, Object>> p) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        for (long left; (left = until - System.currentTimeMillis()) > 0; ) {
            Map<String, Object> m = inbox.poll(left, TimeUnit.MILLISECONDS);
            if (m != null && p.test(m)) return m;
        }
        throw new AssertionError("no matching message within 10s");
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            inbox.add((Map<String, Object>) Json.parse(partial.toString()));
            partial.setLength(0);
        }
        w.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
        closed.complete(code);
        return null;
    }

    @Override
    public void onError(WebSocket w, Throwable error) {
        closed.complete(-1);
    }

    @Override
    public void close() {
        ws.abort();
    }
}
