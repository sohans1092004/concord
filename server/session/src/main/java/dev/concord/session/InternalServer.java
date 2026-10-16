package dev.concord.session;

import dev.concord.common.Msg;
import dev.concord.crdt.Hlc;
import dev.concord.crdt.Json;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The endpoint gateways forward to: one WebSocket per gateway (SPEC §2.3 step 4), frames as in
 * {@link Msg}. Each request becomes a mailbox item; its reply goes back on the same socket.
 * Gateways authenticate with a shared secret header on upgrade (amendment 8).
 */
public final class InternalServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InternalServer.class);
    private final Javalin app;

    public InternalServer(Boards boards, Metrics metrics, int port, String secret) {
        byte[] expected = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.showJavalinBanner = false;
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(64L << 20); // snapshots for 2,000-card boards fit easily
                f.setIdleTimeout(Duration.ofMinutes(10));
            });
        });
        app.get("/metrics", ctx -> ctx.contentType("application/json").result(Msg.encode(metrics.snapshot())));
        app.get("/health", ctx -> ctx.result("ok"));
        // Only gateways holding the shared secret may open the internal socket (amendment 8).
        app.wsBeforeUpgrade("/internal", ctx -> {
            String got = ctx.header(dev.concord.common.Config.INTERNAL_HEADER);
            if (got == null || !java.security.MessageDigest.isEqual(got.getBytes(java.nio.charset.StandardCharsets.UTF_8), expected)) {
                metrics.inc("internal_auth_rejections");
                throw new io.javalin.http.UnauthorizedResponse("bad internal secret");
            }
        });
        app.ws("/internal", ws -> ws.onMessage(ctx -> handle(boards, ctx, ctx.message())));
        app.start(port);
    }

    public int port() {
        return app.port();
    }

    private static void handle(Boards boards, WsContext ctx, String text) {
        Map<?, ?> f;
        try {
            f = (Map<?, ?>) Json.parse(text);
        } catch (RuntimeException e) {
            log.warn("unparseable internal frame: {}", e.toString());
            return;
        }
        Object req = f.get("req");
        String board = f.get("board_id") instanceof String b && Hlc.isUuid(b) ? b : null;
        BoardActor.Reply reply = msg -> send(ctx, Msg.encode(Msg.obj("k", "R", "req", req, "msg", msg)));
        if (board == null) {
            reply.send(Msg.error(Msg.INVALID_OP, null, "board_id is not a lowercase canonical UUID"));
            return;
        }
        String user = f.get("user_id") instanceof String u ? u : null;
        Long since = f.get("since_version") instanceof Long l ? l : null;
        BoardActor.Item item = switch (String.valueOf(f.get("k"))) {
            case "OP" -> new BoardActor.Submit(f.get("op"), user, reply);
            case "JOIN" -> new BoardActor.Join(user, since, reply);
            case "SYNC_REQUEST" -> new BoardActor.SyncRequest(user, since, reply);
            case "POLL" -> new BoardActor.Poll(since == null ? 0 : since, reply);
            case "ENSURE" -> new BoardActor.Ensure(reply);
            default -> null;
        };
        if (item == null) reply.send(Msg.error(Msg.INVALID_OP, null, "unknown frame"));
        else boards.dispatch(board, item);
    }

    /** Jetty allows one blocking send at a time per session; actors reply from many threads. */
    private static void send(WsContext ctx, String text) {
        synchronized (ctx.session) {
            if (ctx.session.isOpen()) {
                try {
                    ctx.send(text);
                } catch (RuntimeException e) {
                    log.debug("reply dropped, gateway gone: {}", e.toString());
                }
            }
        }
    }

    @Override
    public void close() {
        app.stop();
    }
}
