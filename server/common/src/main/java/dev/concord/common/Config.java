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
        String jwtSecret,
        String instanceId,
        String advertise, // session: ws:// address gateways use to reach this instance
        int port,
        int batchMs, // group-commit window (SPEC §6.1 step 3)
        List<String> allowedOrigins) {

    /**
     * The well-known dev signing key. Dev tokens (devtools) are signed only with this, and a
     * non-dev server refuses to start with it, so a dev token can never validate in prod.
     */
    public static final String DEV_JWT_SECRET = "concord-dev-only-secret-never-use-in-prod-000";

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
                e.getOrDefault("CONCORD_JWT_SECRET", env.equals("dev") ? DEV_JWT_SECRET : ""),
                e.getOrDefault("CONCORD_INSTANCE_ID", UUID.randomUUID().toString()),
                e.getOrDefault("CONCORD_ADVERTISE", "ws://localhost:" + e.getOrDefault("CONCORD_PORT", "7000") + "/internal"),
                Integer.parseInt(e.getOrDefault("CONCORD_PORT", "7000")),
                Integer.parseInt(e.getOrDefault("CONCORD_BATCH_MS", "15")),
                Arrays.stream(e.getOrDefault("CONCORD_ALLOWED_ORIGINS", "http://localhost:5173").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public boolean dev() {
        return env.equals("dev");
    }

    /** Throws if this configuration must not run. */
    public Config check() {
        if (!env.equals("dev") && !env.equals("prod")) throw new IllegalStateException("CONCORD_ENV must be dev or prod, not " + env);
        if (!dev()) {
            if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalStateException("CONCORD_JWT_SECRET must be at least 32 bytes outside dev");
            if (jwtSecret.equals(DEV_JWT_SECRET))
                throw new IllegalStateException("the dev JWT secret is refused outside dev");
        }
        if (batchMs < 0) throw new IllegalStateException("CONCORD_BATCH_MS must be >= 0");
        return this;
    }
}
