package dev.concord.common;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;

/**
 * Lettuce connections. Redis may be down or wiped at any time (SPEC §5, §8 rows 5–6): commands
 * fail fast (1s timeout, rejected while disconnected) instead of queueing, and connections
 * reconnect on their own.
 */
public final class Redis implements AutoCloseable {
    public final RedisClient client;
    public final StatefulRedisConnection<String, String> conn;

    public Redis(String url) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(Duration.ofSeconds(1));
        client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                // Bounded (amendment 9): a slow Redis fails publishes (version gaps) instead of
                // queueing them in this process without limit.
                .requestQueueSize(10_000)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(1)))
                .build());
        conn = connectWithRetry();
    }

    /** Starting while Redis is down is allowed; keep trying in the background of the caller. */
    private StatefulRedisConnection<String, String> connectWithRetry() {
        while (true) {
            try {
                return client.connect();
            } catch (RuntimeException e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public StatefulRedisPubSubConnection<String, String> pubSub() {
        return client.connectPubSub();
    }

    @Override
    public void close() {
        conn.close();
        client.shutdown();
    }
}
