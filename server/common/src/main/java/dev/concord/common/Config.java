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
        int signInIpBurst, // sign-in/up attempts per IP before 1 per 10s (SPEC §9.4: 30)
        String trustedProxy, // gateway: host whose X-Forwarded-For is believed (Caddy); empty: none
        String publicOrigin, // gateway: https://host the app is served at (CSP, Origin allowlist); empty in dev
        long idleUnloadMs) { // session: an owned board nobody has open is unloaded after this (amendment 26)

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
                Integer.parseInt(e.getOrDefault("CONCORD_SIGNIN_IP_BURST", "30")),
                e.getOrDefault("CONCORD_TRUSTED_PROXY", ""),
                e.getOrDefault("CONCORD_PUBLIC_ORIGIN", ""),
                Long.parseLong(e.getOrDefault("CONCORD_IDLE_UNLOAD_MS", "600000")));
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
            if (dbPassword.length() < 16 || dbPassword.equals("concord"))
                throw new IllegalStateException("CONCORD_DB_PASSWORD must be at least 16 characters, and not the dev one, outside dev");
        }
        if (batchMs < 0) throw new IllegalStateException("CONCORD_BATCH_MS must be >= 0");
        if (idleUnloadMs < 1_000) throw new IllegalStateException("CONCORD_IDLE_UNLOAD_MS must be at least 1000");
        if (clientOutboxBytes < 64 * 1024) throw new IllegalStateException("CONCORD_CLIENT_OUTBOX_BYTES must be at least 64 KB");
        return this;
    }

    /**
     * The gateway faces the internet: outside dev it refuses every dev setting (Phase 4a). The
     * public origin is https, is the only allowed Origin, the cookie is Secure, sign-up needs an
     * invite code, the per-IP limit is the SPEC's, and X-Forwarded-For comes only from the proxy.
     */
    public Config checkPublic() {
        check();
        if (dev()) return this;
        if (!publicOrigin.startsWith("https://") || publicOrigin.contains("localhost") || publicOrigin.endsWith("/"))
            throw new IllegalStateException("CONCORD_PUBLIC_ORIGIN must be https://<host>, not " + publicOrigin);
        if (!allowedOrigins.equals(List.of(publicOrigin)))
            throw new IllegalStateException("CONCORD_ALLOWED_ORIGINS must be exactly CONCORD_PUBLIC_ORIGIN outside dev, not " + allowedOrigins);
        if (!cookieSecure) throw new IllegalStateException("CONCORD_COOKIE_SECURE=false is refused outside dev");
        if (inviteCode.length() < 12) throw new IllegalStateException("CONCORD_INVITE_CODE must be set (12+ characters) outside dev");
        if (signInIpBurst > 30) throw new IllegalStateException("CONCORD_SIGNIN_IP_BURST above 30 is a dev setting");
        if (trustedProxy.isEmpty()) throw new IllegalStateException("CONCORD_TRUSTED_PROXY must name the proxy (caddy) outside dev");
        return this;
    }
}
