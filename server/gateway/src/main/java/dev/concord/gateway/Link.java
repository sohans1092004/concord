package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.crdt.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's one WebSocket to one Session instance (SPEC §2.3 step 4). Requests are
 * multiplexed by {@code req} id; each gets exactly one reply, or UNAVAILABLE if the link dies
 * or the owner does not answer within 10s.
 */
final class Link implements WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(Link.class);
    static final long REPLY_TIMEOUT_MS = 10_000;

    final String address;
    private final AtomicLong nextReq = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final StringBuilder partial = new StringBuilder();
    private final Object sendLock = new Object();
    private volatile WebSocket ws;
    private volatile boolean closed;

    private Link(String address) {
        this.address = address;
    }

    /** Opens a link; fails if the instance is unreachable. */
    static CompletableFuture<Link> open(HttpClient http, String address, String secret) {
        Link l = new Link(address);
        return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
                .header(dev.concord.common.Config.INTERNAL_HEADER, secret)
                .buildAsync(URI.create(address), l)
                .thenApply(ws -> {
                    l.ws = ws;
                    return l;
                });
    }

    boolean open() {
        return !closed;
    }

    /** Sends one request frame (without {@code req}); completes with the owner's reply message. */
    CompletableFuture<Map<String, Object>> request(Map<String, Object> frame) {
        long req = nextReq.incrementAndGet();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (closed) {
            f.complete(Msg.error(Msg.UNAVAILABLE, null, "owner unreachable"));
            return f;
        }
        pending.put(req, f);
        frame.put("req", req);
        String text = Msg.encode(frame);
        try {
            synchronized (sendLock) { // java.net.http allows one outstanding send at a time
                ws.sendText(text, true).get(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            fail("send failed: " + e);
        }
        f.orTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionally(e -> null);
        return f.handle((m, e) -> {
            pending.remove(req);
            return m != null ? m : Msg.error(Msg.UNAVAILABLE, null, "owner did not answer");
        });
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                Map<?, ?> f = (Map<?, ?>) Json.parse(text);
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) f.get("msg");
                CompletableFuture<Map<String, Object>> p = pending.remove((Long) f.get("req"));
                if (p != null) p.complete(msg);
            } catch (RuntimeException e) {
                log.warn("bad frame from {}: {}", address, e.toString());
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        fail("closed: " + statusCode + " " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        fail("error: " + error);
    }

    private void fail(String why) {
        if (!closed) log.atInfo().addKeyValue("address", address).log("link down: {}", why);
        closed = true;
        for (var p : pending.values()) p.complete(Msg.error(Msg.UNAVAILABLE, null, "owner unreachable"));
        pending.clear();
    }

    void close() {
        closed = true;
        WebSocket w = ws;
        if (w != null) w.abort();
    }
}
