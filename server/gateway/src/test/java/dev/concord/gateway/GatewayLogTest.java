package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.concord.common.Config;
import dev.concord.common.RedactingJsonEncoder;
import dev.concord.common.Tokens;
import dev.concord.session.Harness;
import dev.concord.session.Infra;
import dev.concord.session.Ops;
import dev.concord.session.SessionMain;
import dev.concord.session.Store;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Amendment 10: an access token never appears in any log. Every path a token takes is
 * exercised (valid, expired, garbage, wrong origin, a join, an op, an oversized frame, a
 * disconnect) and every log event of every logger is captured.
 */
class GatewayLogTest {
    /** Captures both the raw event text and what the production encoder writes. */
    static final class Capture extends AppenderBase<ILoggingEvent> {
        final List<String> raw = new ArrayList<>(), encoded = new ArrayList<>();
        final RedactingJsonEncoder encoder = new RedactingJsonEncoder();

        @Override
        protected synchronized void append(ILoggingEvent e) {
            StringBuilder b = new StringBuilder(String.valueOf(e.getFormattedMessage()));
            if (e.getKeyValuePairs() != null) e.getKeyValuePairs().forEach(kv -> b.append(' ').append(kv.key).append('=').append(kv.value));
            if (e.getThrowableProxy() != null) b.append(' ').append(e.getThrowableProxy().getMessage());
            e.getMDCPropertyMap().forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
            raw.add(b.toString());
            encoded.add(new String(encoder.encode(e), StandardCharsets.UTF_8));
        }
    }

    /** Runs every token path with all loggers at {@code level}; returns what was logged. */
    static Capture exercise(Level level, List<String> tokens) throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Capture cap = new Capture();
        cap.setContext(ctx);
        cap.encoder.setContext(ctx);
        cap.encoder.start();
        cap.start();
        Map<Logger, Level> old = new HashMap<>();
        for (Logger l : ctx.getLoggerList()) {
            old.put(l, l.getLevel());
            if (l.getLevel() != null) l.setLevel(level.isGreaterOrEqual(l.getLevel()) ? level : l.getLevel());
        }
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        if (level == Level.TRACE) for (Logger l : ctx.getLoggerList()) l.setLevel(Level.TRACE);
        root.addAppender(cap);
        try {
            GatewayTest.Board b = GatewayTest.seed(0, 0);
            String good = b.token(0);
            String expired = new Tokens(Config.DEV_JWT_SECRET).mint((String) b.users().get(0).get("id"), Instant.now().minusSeconds(16 * 60));
            tokens.add(good);
            tokens.add(expired);
            Map<String, String> env = new HashMap<>();
            env.put("CONCORD_ENV", "dev");
            env.put("CONCORD_REDIS_URL", Infra.redisUrl());
            env.put("CONCORD_PORT", Integer.toString(Infra.freePort()));
            env.put("CONCORD_ALLOWED_ORIGINS", "http://allowed.test");
            try (SessionMain s = Harness.start("log-s-" + Ops.uuid(), Store::new, false); GatewayMain g = new GatewayMain(Config.from(env))) {
                assertTrue(s.port() > 0);
                assertThrows(Exception.class, () -> new WsClient(g.port(), expired, null));
                assertThrows(Exception.class, () -> new WsClient(g.port(), good + "x", null));
                assertThrows(Exception.class, () -> new WsClient(g.port(), good, "http://evil.test"));
                try (WsClient c = new WsClient(g.port(), good, "http://allowed.test")) {
                    c.join(b.id(), null);
                    c.op(new Ops(b.id()).addList(Ops.uuid(), "a0", "L"));
                    c.sendRaw("{\"type\":\"PRESENCE\",\"pad\":\"" + "x".repeat(70_000) + "\"}"); // closes the connection
                    c.closed.get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
                Thread.sleep(300);
            }
        } finally {
            root.detachAppender(cap);
            old.forEach(Logger::setLevel);
        }
        return cap;
    }

    @Test
    void tokensNeverReachTheLogs() throws Exception {
        // At the production levels, nothing Concord or its libraries log contains a token,
        // even before redaction.
        List<String> tokens = new ArrayList<>();
        Capture prod = exercise(Level.INFO, tokens);
        assertTrue(prod.raw.size() > 0, "expected some log events");
        for (String line : prod.raw)
            for (String t : tokens) assertFalse(line.contains(t) || line.contains(t.substring(t.lastIndexOf('.') + 1)), "token in a log line: " + line);

        // With every logger at TRACE (Jetty logs request lines there), the encoder's output still
        // never contains one.
        List<String> tokens2 = new ArrayList<>();
        Capture trace = exercise(Level.TRACE, tokens2);
        boolean redactedSomething = false;
        for (String line : trace.encoded) {
            for (String t : tokens2) assertFalse(line.contains(t) || line.contains(t.substring(t.lastIndexOf('.') + 1)), "token in encoded output: " + line);
            redactedSomething |= line.contains("access_token=REDACTED") || line.contains("REDACTED-JWT");
        }
        System.out.printf("token logging: %d events at INFO, %d at TRACE, redaction fired at TRACE: %s%n",
                prod.raw.size(), trace.encoded.size(), redactedSomething);
    }
}
