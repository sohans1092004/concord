package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.common.Rendezvous;
import dev.concord.crdt.Json;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a board's owner and forwards to it (SPEC §2.3 steps 2–5). The owner is cached per board
 * for 5s. An answer of NOT_OWNER drops the cache entry, looks the owner up again and resends,
 * which is safe because every op carries an op_id (§4.3). While Redis is unreachable, the last
 * known owner is used, however old (§6.3: owners keep serving; amendment 7).
 */
final class Router {
    private static final Logger log = LoggerFactory.getLogger(Router.class);
    static final long CACHE_MS = 5_000;
    static final int ATTEMPTS = 3;

    private record Cached(String address, long at) {}

    private final RedisCommands<String, String> redis;
    private final LongSupplier clock;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Cached> owners = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Link>> links = new ConcurrentHashMap<>();
    private volatile List<Rendezvous.Instance> lastLive = List.of();

    private final String secret;

    Router(RedisCommands<String, String> redis, LongSupplier clock, String secret) {
        this.redis = redis;
        this.secret = secret;
        this.clock = clock;
    }

    /** Forwards one request frame for {@code board} and completes with the owner's reply. */
    CompletableFuture<Map<String, Object>> forward(String board, Map<String, Object> frame) {
        return attempt(board, frame, 1);
    }

    private CompletableFuture<Map<String, Object>> attempt(String board, Map<String, Object> frame, int n) {
        String address = owner(board);
        if (address == null) return CompletableFuture.completedFuture(Msg.error(Msg.UNAVAILABLE, null, "no live Session instance"));
        return link(address).thenCompose(l -> l.request(new java.util.LinkedHashMap<>(frame))).exceptionally(e -> {
            links.remove(address);
            return Msg.error(Msg.UNAVAILABLE, null, "owner unreachable");
        }).thenCompose(reply -> {
            boolean notOwner = Msg.NOT_OWNER.equals(reply.get("code"));
            boolean unreachable = Msg.UNAVAILABLE.equals(reply.get("code"));
            if ((notOwner || unreachable) && n < ATTEMPTS) {
                owners.remove(board);
                return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(100L * n, TimeUnit.MILLISECONDS))
                        .thenCompose(x -> attempt(board, frame, n + 1));
            }
            if (notOwner) {
                // Never surfaces to the client: the client treats it like any outage and retries.
                Map<String, Object> m = Msg.error(Msg.UNAVAILABLE, (String) reply.get("op_id"), "board is changing owner");
                return CompletableFuture.completedFuture(m);
            }
            return CompletableFuture.completedFuture(reply);
        });
    }

    /** The owner's address: cache (5s), then board:owner:{id}, then the preferred live instance. */
    String owner(String board) {
        long now = clock.getAsLong();
        Cached c = owners.get(board);
        if (c != null && now - c.at() < CACHE_MS) return c.address();
        try {
            String raw = redis.get(RedisKeys.owner(board));
            String address = raw != null ? (String) ((Map<?, ?>) Json.parse(raw)).get("address") : preferred(board, now);
            if (address != null) owners.put(board, new Cached(address, now));
            return address;
        } catch (RuntimeException e) {
            // Redis down: keep using the last owner we knew, or the last live set.
            if (c != null) return c.address();
            List<Rendezvous.Instance> order = Rendezvous.order(board, lastLive);
            return order.isEmpty() ? null : order.get(0).address();
        }
    }

    /** Is board:owner:{id} set? Null if Redis cannot be asked. */
    Boolean owned(String board) {
        try {
            return redis.exists(RedisKeys.owner(board)) == 1;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String preferred(String board, long now) {
        List<Rendezvous.Instance> live = redis.zrangebyscore(RedisKeys.INSTANCES, Range.create((double) now - RedisKeys.INSTANCE_TTL_MS, Double.POSITIVE_INFINITY))
                .stream().map(Rendezvous.Instance::parse).toList();
        lastLive = live;
        List<Rendezvous.Instance> order = Rendezvous.order(board, live);
        return order.isEmpty() ? null : order.get(0).address();
    }

    void forget(String board) {
        owners.remove(board);
    }

    private CompletableFuture<Link> link(String address) {
        CompletableFuture<Link> f = links.compute(address, (a, cur) ->
                cur == null || (cur.isDone() && (cur.isCompletedExceptionally() || !cur.join().open())) ? Link.open(http, a, secret) : cur);
        return f.whenComplete((l, e) -> {
            if (e != null) {
                links.remove(address, f);
                log.atInfo().addKeyValue("address", address).log("cannot reach instance: {}", e.toString());
            }
        });
    }

    void close() {
        for (var f : links.values()) f.thenAccept(Link::close);
    }
}
