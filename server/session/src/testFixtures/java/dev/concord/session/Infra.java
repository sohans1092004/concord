package dev.concord.session;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.concord.common.Config;
import dev.concord.common.Migrate;
import dev.concord.common.Redis;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres 16, Redis 7 and Toxiproxy, started once per test JVM and shared by every test.
 * Tests isolate themselves by using fresh board and user IDs, not fresh containers.
 */
public final class Infra {
    private Infra() {}

    public static final Network NET = Network.newNetwork();

    public static final PostgreSQLContainer PG = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withNetwork(NET).withNetworkAliases("pg").withDatabaseName("concord").withUsername("concord").withPassword("concord");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NET).withNetworkAliases("redis").withExposedPorts(6379);

    public static final ToxiproxyContainer TOXI = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"))
            .withNetwork(NET);

    private static DataSource direct;
    private static Proxy pgProxy, redisProxy;

    public static synchronized void start() {
        if (direct != null) return;
        PG.start();
        REDIS.start();
        TOXI.start();
        HikariConfig h = new HikariConfig();
        h.setJdbcUrl(PG.getJdbcUrl());
        h.setUsername("concord");
        h.setPassword("concord");
        h.setMaximumPoolSize(4);
        direct = new HikariDataSource(h);
        try {
            Migrate.run(direct);
            ToxiproxyClient toxi = new ToxiproxyClient(TOXI.getHost(), TOXI.getControlPort());
            pgProxy = toxi.createProxy("pg", "0.0.0.0:8666", "pg:5432");
            redisProxy = toxi.createProxy("redis", "0.0.0.0:8667", "redis:6379");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A pool that bypasses Toxiproxy: for seeding and assertions. */
    public static DataSource db() {
        start();
        return direct;
    }

    /** The Postgres proxy, for injecting network faults between a session and Postgres. */
    public static Proxy pgProxy() {
        start();
        return pgProxy;
    }

    /** The Redis proxy, for taking Redis away from one service only. */
    public static Proxy redisProxy() {
        start();
        return redisProxy;
    }

    public static String redisUrlViaProxy() {
        start();
        return "redis://" + TOXI.getHost() + ":" + TOXI.getMappedPort(8667);
    }

    public static String redisUrl() {
        start();
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    public static Redis redis() {
        return new Redis(redisUrl());
    }

    /**
     * A port to bind later, below the OS ephemeral range (Linux 32768+, Windows 49152+). Port 0
     * would come from that range, which Docker also uses for Testcontainers' mapped ports, so it
     * could be taken between this check and the bind (CI on d1e9ae3: port 32799 already in use).
     */
    public static int freePort() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int port = 20_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12_000);
            try (ServerSocket s = new ServerSocket(port)) {
                return s.getLocalPort();
            } catch (IOException e) {
                // taken: try another
            }
        }
        throw new UncheckedIOException(new IOException("no free port in 20000-31999"));
    }

    /** Config for an in-process service. {@code viaProxy}: reach Postgres through Toxiproxy. */
    public static Config config(String instanceId, int port, boolean viaProxy) {
        return config(instanceId, port, viaProxy, Map.of());
    }

    /** {@code extra}: more CONCORD_* settings, e.g. a short CONCORD_IDLE_UNLOAD_MS. */
    public static Config config(String instanceId, int port, boolean viaProxy, Map<String, String> extra) {
        start();
        Map<String, String> env = new HashMap<>();
        env.put("CONCORD_ENV", "dev");
        env.put("CONCORD_DB_URL", viaProxy
                ? "jdbc:postgresql://" + TOXI.getHost() + ":" + TOXI.getMappedPort(8666) + "/concord"
                : PG.getJdbcUrl());
        env.put("CONCORD_DB_POOL", "4");
        env.put("CONCORD_REDIS_URL", redisUrl());
        env.put("CONCORD_INSTANCE_ID", instanceId);
        env.put("CONCORD_PORT", Integer.toString(port));
        env.put("CONCORD_ADVERTISE", "ws://localhost:" + port + "/internal");
        env.put("CONCORD_ALLOWED_ORIGINS", "http://allowed.test");
        env.putAll(extra);
        return Config.from(env);
    }

    /** Environment for an in-process gateway: dev, the shared Postgres and Redis, a free port. */
    public static Map<String, String> gatewayEnv() {
        start();
        Map<String, String> env = new HashMap<>();
        env.put("CONCORD_ENV", "dev");
        env.put("CONCORD_DB_URL", PG.getJdbcUrl());
        env.put("CONCORD_DB_POOL", "4");
        env.put("CONCORD_REDIS_URL", redisUrl());
        env.put("CONCORD_PORT", Integer.toString(freePort()));
        env.put("CONCORD_ALLOWED_ORIGINS", "http://allowed.test");
        return env;
    }

    public static Config config(String instanceId) {
        return config(instanceId, freePort(), false);
    }
}
