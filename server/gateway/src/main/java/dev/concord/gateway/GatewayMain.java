package dev.concord.gateway;

import com.zaxxer.hikari.HikariDataSource;
import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Msg;
import dev.concord.common.Outbox;
import dev.concord.common.Redis;
import io.javalin.Javalin;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A gateway (SPEC §2.1): stateless WebSocket front door and the REST API ({@link Api}). Checks
 * the single-use ticket and Origin on upgrade, caps frames at 64 KB, rate-limits each connection, forwards to the board's owner and delivers
 * broadcasts. Holds no CRDT state.
 */
public final class GatewayMain implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);
    static final long MAX_FRAME = 64 * 1024; // SPEC §9.4

    private final Redis redis;
    private final HikariDataSource db;
    private final java.util.concurrent.ScheduledExecutorService cleaner =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("cleanup").daemon().factory());
    private final Router router;
    private final Fanout fanout;
    private final Presence presence;
    private final Javalin app;
    private final Map<String, ClientConn> conns = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong slowConsumerCloses = new java.util.concurrent.atomic.AtomicLong();
    /** Largest backlog any one client connection reached, in bytes. */
    final java.util.concurrent.atomic.AtomicLong outboxPeak = new java.util.concurrent.atomic.AtomicLong();

    public GatewayMain(Config config) {
        config.checkPublic();
        redis = new Redis(config.redisUrl());
        db = Db.pool(config);
        router = new Router(redis.conn.sync(), System::currentTimeMillis, config.internalSecret());
        fanout = new Fanout(redis.pubSub(), router);
        presence = new Presence(redis.conn.sync(), System::currentTimeMillis);
        cleaner.scheduleWithFixedDelay(() -> presence.sweep(fanout.boards()), Presence.SWEEP_MS, Presence.SWEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        app = Javalin.create(cfg -> {
            cfg.useVirtualThreads = true;
            cfg.showJavalinBanner = false;
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(MAX_FRAME);
                f.setMaxBinaryMessageSize(MAX_FRAME);
                f.setMaxFrameSize(MAX_FRAME);
                f.setIdleTimeout(Duration.ofMinutes(5));
            });
            if (!config.webDir().isEmpty()) {
                cfg.staticFiles.add(config.webDir(), Location.EXTERNAL);
                cfg.spaRoot.addFile("/", config.webDir() + "/index.html", Location.EXTERNAL);
            }
        });
        Api api = new Api(app, db, redis.conn.sync(), config, System::currentTimeMillis);
        cleaner.scheduleWithFixedDelay(api::cleanup, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        app.get("/health", ctx -> ctx.result("ok"));
        // For the uptime monitor and compose: up, Postgres answering (Redis down is survivable, §8),
        // and the disk not nearly full. The container's root sits on the host's Docker disk, so
        // its free space is the host's: under 10% free, the public /healthz fails and the uptime
        // monitor emails (docs/runbook.md, "Monitoring").
        app.get("/healthz", ctx -> {
            java.io.File root = new java.io.File("/");
            long free = root.getUsableSpace(), total = root.getTotalSpace();
            if (total > 0 && free * 10 < total) {
                ctx.status(503).result("disk nearly full: " + (free >> 20) + " MB free of " + (total >> 20) + " MB");
                return;
            }
            try (var c = db.getConnection(); var s = c.createStatement()) {
                s.execute("SELECT 1");
                ctx.result("ok");
            } catch (java.sql.SQLException e) {
                ctx.status(503).result("postgres unavailable");
            }
        });
        // The Content-Security-Policy the web app passes (Phase 4a); Caddy adds HSTS and the rest.
        if (!config.publicOrigin().isEmpty()) {
            String host = config.publicOrigin().substring("https://".length());
            String csp = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
                    + "connect-src 'self' wss://" + host + "; worker-src 'self'; manifest-src 'self'; object-src 'none'; "
                    + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
            app.after(ctx -> ctx.header("Content-Security-Policy", csp));
        }
        app.get("/metrics", ctx -> ctx.contentType("application/json")
                .result(Msg.encode(Msg.obj("connections", (long) conns.size(), "clients_on_boards", (long) fanout.clientCount(),
                        "slow_consumer_closes", slowConsumerCloses.get(), "outbox_peak_bytes", outboxPeak.get(),
                        "outbox_limit_bytes", config.clientOutboxBytes()))));
        // SPEC §9.4: Origin allowlist (cross-site WebSocket hijacking) and a single-use ticket,
        // before upgrading. Browsers always send Origin; a client without one (a script) still
        // needs a ticket. The ticket is used up here, so the URL is worthless once it is open.
        app.wsBeforeUpgrade("/ws", ctx -> {
            String origin = ctx.header("Origin");
            if (origin != null && !config.allowedOrigins().contains(origin)) throw new ForbiddenResponse("origin not allowed");
            String user;
            try {
                user = Api.consumeTicket(db, ctx.queryParam("ticket"));
            } catch (java.sql.SQLException e) {
                throw new ServiceUnavailableResponse("storage unavailable");
            }
            if (user == null) throw new UnauthorizedResponse("bad, used or expired ticket");
            ctx.attribute("user_id", user);
        });
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String user = ctx.attribute("user_id");
                if (user == null) {
                    ctx.closeSession(4401, "unauthorized");
                    return;
                }
                Outbox outbox = new Outbox(ctx.session, config.clientOutboxBytes(), Msg.encode(Msg.obj("type", "RECONNECT_ELSEWHERE")), () -> {
                    slowConsumerCloses.incrementAndGet();
                    log.atWarn().addKeyValue("user_id", user).log("slow consumer: backlog over the limit, disconnecting");
                }, outboxPeak);
                conns.put(ctx.sessionId(), new ClientConn(user, ctx, router, fanout, System::currentTimeMillis, outbox, presence));
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
        cleaner.shutdownNow();
        fanout.close();
        router.close();
        redis.close();
        db.close();
    }

    public static void main(String[] args) {
        GatewayMain g = new GatewayMain(Config.fromEnv());
        Runtime.getRuntime().addShutdownHook(new Thread(g::close, "shutdown"));
    }
}
