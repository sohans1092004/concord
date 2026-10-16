package dev.concord.session;

import dev.concord.common.Rendezvous;
import dev.concord.common.RedisKeys;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Heartbeats in the {@code instances} sorted set every 2s (SPEC §2.3 step 1). */
public final class Registry implements AutoCloseable {
    private final RedisCommands<String, String> redis;
    private final String member;
    private final LongSupplier clock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("heartbeat").daemon().factory());

    public Registry(RedisCommands<String, String> redis, Rendezvous.Instance self, LongSupplier clock) {
        this.redis = redis;
        this.member = self.member();
        this.clock = clock;
        timer.scheduleWithFixedDelay(this::beat, 0, RedisKeys.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void beat() {
        try {
            long now = clock.getAsLong();
            redis.zadd(RedisKeys.INSTANCES, now, member);
            // Readers ignore entries older than 6s; trim long-dead ones so the set stays small.
            redis.zremrangebyscore(RedisKeys.INSTANCES, Range.create(0L, now - 60_000));
        } catch (RuntimeException e) {
            // Redis down: gateways fall back to the owners they know (SPEC §8 row 5).
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        try {
            redis.zrem(RedisKeys.INSTANCES, member);
        } catch (RuntimeException e) {
            // best effort
        }
    }
}
