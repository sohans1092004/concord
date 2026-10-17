package dev.concord.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Process configuration, read from environment variables (CONCORD_*). Every service builds one
 * at startup; {@link #check()} refuses unsafe combinations before anything listens.
 */
public record Config(
        String env, // "dev" or "prod"; anything else is refused
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String redisUrl,
        String instanceId,
        String advertise, // session: ws:// address gateways use to reach this instance
        int port,
        int batchMs, // group-commit window (SPEC §6.1 step 3)
        List<String> allowedOrigins,
        String internalSecret, // shared secret on the gateway ↔ session WebSocket (amendment 8)
        long clientOutboxBytes, // per-connection unsent bytes before the gateway drops a client
        String inviteCode, // sign-up requires this code when set; empty: open sign-up
        boolean cookieSecure, // the session cookie's Secure flag (browsers treat http://localhost as secure)
        String webDir, // gateway: built web app to serve at /, or empty
        int signInIpBurst) { // sign-in/up attempts per IP before 1 per 10s (SPEC §9.4: 30)

    /** The dev internal secret; a non-dev server refuses to start with it. */
    public static final String DEV_INTERNAL_SECRET = "concord-dev-only-internal-secret-0000000";

    /** HTTP header carrying the internal secret on the gateway's upgrade to /internal. */
    public static final String INTERNAL_HEADER = "X-Concord-Internal";

    public static Config fromEnv() {
        return from(System.getenv());
    }

    public static Config from(Map<String, String> e) {
        String env = e.getOrDefault("CONCORD_ENV", "prod");
        return new Config(
                env,
                e.getOrDefault("CONCORD_DB_URL", "jdbc:postgresql://localhost:5432/concord"),
                e.getOrDefault("CONCORD_DB_USER", "concord"),
                e.getOrDefault("CONCORD_DB_PASSWORD", "concord"),
                Integer.parseInt(e.getOrDefault("CONCORD_DB_POOL", "10")),
                e.getOrDefault("CONCORD_REDIS_URL", "redis://localhost:6379"),
                e.getOrDefault("CONCORD_INSTANCE_ID", UUID.randomUUID().toString()),
                e.getOrDefault("CONCORD_ADVERTISE", "ws://localhost:" + e.getOrDefault("CONCORD_PORT", "7000") + "/internal"),
                Integer.parseInt(e.getOrDefault("CONCORD_PORT", "7000")),
                Integer.parseInt(e.getOrDefault("CONCORD_BATCH_MS", "15")),
                Arrays.stream(e.getOrDefault("CONCORD_ALLOWED_ORIGINS", "http://localhost:5173").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList(),
                e.getOrDefault("CONCORD_INTERNAL_SECRET", env.equals("dev") ? DEV_INTERNAL_SECRET : ""),
                Long.parseLong(e.getOrDefault("CONCORD_CLIENT_OUTBOX_BYTES", Long.toString(8L << 20))),
                e.getOrDefault("CONCORD_INVITE_CODE", ""),
                !e.getOrDefault("CONCORD_COOKIE_SECURE", "true").equals("false"),
                e.getOrDefault("CONCORD_WEB_DIR", ""),
                Integer.parseInt(e.getOrDefault("CONCORD_SIGNIN_IP_BURST", "30")));
    }

    public boolean dev() {
        return env.equals("dev");
    }

    /** Throws if this configuration must not run. */
    public Config check() {
        if (!env.equals("dev") && !env.equals("prod")) throw new IllegalStateException("CONCORD_ENV must be dev or prod, not " + env);
        if (!dev()) {
            if (internalSecret.getBytes(StandardCharsets.UTF_8).length < 32 || internalSecret.equals(DEV_INTERNAL_SECRET))
                throw new IllegalStateException("CONCORD_INTERNAL_SECRET must be at least 32 bytes, and not the dev one, outside dev");
        }
        if (batchMs < 0) throw new IllegalStateException("CONCORD_BATCH_MS must be >= 0");
        if (clientOutboxBytes < 64 * 1024) throw new IllegalStateException("CONCORD_CLIENT_OUTBOX_BYTES must be at least 64 KB");
        return this;
    }
}
