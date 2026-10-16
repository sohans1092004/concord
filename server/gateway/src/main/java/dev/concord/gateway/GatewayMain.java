package dev.concord.gateway;

import dev.concord.common.Config;
import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.Tokens;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A gateway (SPEC §2.1): stateless WebSocket front door. Checks the JWT and Origin on upgrade,
 * caps frames at 64 KB, rate-limits each connection, forwards to the board's owner and delivers
 * broadcasts. Holds no CRDT state.
 */
public final class GatewayMain implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);
    static final long MAX_FRAME = 64 * 1024; // SPEC §9.4

    private final Redis redis;
    private final Router router;
    private final Fanout fanout;
    private final Javalin app;
    private final Map<String, ClientConn> conns = new ConcurrentHashMap<>();

    public GatewayMain(Config config) {
        config.check();
        Tokens tokens = new Tokens(config.jwtSecret());
        redis = new Redis(config.redisUrl());
        router = new Router(redis.conn.sync(), System::currentTimeMillis, config.internalSecret());
        fanout = new Fanout(redis.pubSub(), router);
        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.showJavalinBanner = false;
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(MAX_FRAME);
                f.setMaxBinaryMessageSize(MAX_FRAME);
                f.setMaxFrameSize(MAX_FRAME);
                f.setIdleTimeout(Duration.ofMinutes(5));
            });
        });
        app.get("/health", ctx -> ctx.result("ok"));
        app.get("/metrics", ctx -> ctx.contentType("application/json")
                .result(Msg.encode(Msg.obj("connections", (long) conns.size(), "clients_on_boards", (long) fanout.clientCount()))));
        // SPEC §9.4: Origin allowlist (cross-site WebSocket hijacking) and JWT, before upgrading.
        // Browsers always send Origin; a client without one (a script) still needs a token.
        app.wsBeforeUpgrade("/ws", ctx -> {
            String origin = ctx.header("Origin");
            if (origin != null && !config.allowedOrigins().contains(origin)) throw new ForbiddenResponse("origin not allowed");
            if (tokens.verify(ctx.queryParam("access_token")) == null) throw new UnauthorizedResponse("bad or expired token");
        });
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String user = tokens.verify(ctx.queryParam("access_token"));
                if (user == null) {
                    ctx.closeSession(4401, "unauthorized");
                    return;
                }
                conns.put(ctx.sessionId(), new ClientConn(user, ctx, router, fanout, System::currentTimeMillis));
                log.atInfo().addKeyValue("user_id", user).log("client connected");
            });
            ws.onMessage(ctx -> {
                ClientConn c = conns.get(ctx.sessionId());
                if (c != null) c.onMessage(ctx.message());
            });
            ws.onClose(ctx -> {
                ClientConn c = conns.remove(ctx.sessionId());
                if (c != null) c.onClose();
            });
        });
        app.start(config.port());
        log.atInfo().addKeyValue("port", app.port()).log("gateway up");
    }

    public int port() {
        return app.port();
    }

    /** Rolling deploy (SPEC §8 row 15): tell every client to reconnect elsewhere, then stop. */
    @Override
    public void close() {
        for (ClientConn c : conns.values()) c.closeElsewhere();
        app.stop();
        fanout.close();
        router.close();
        redis.close();
    }

    public static void main(String[] args) {
        GatewayMain g = new GatewayMain(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(g::close, "shutdown"));
    }
}
