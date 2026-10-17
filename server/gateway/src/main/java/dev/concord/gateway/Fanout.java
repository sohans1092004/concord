package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.crdt.Json;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers committed ops to this gateway's clients (SPEC §2.3 step 6, §6.4). Subscribes to a
 * board's channel while at least one client is on it. Once a second, per board:
 * <ul>
 *   <li>if Pub/Sub is down, polls the owner for ops after the last version delivered here;</li>
 *   <li>if the board has no owner (its lease expired), asks the preferred instance to claim it,
 *       so a crashed owner is replaced without waiting for a client's retry (SPEC §8 row 2).</li>
 * </ul>
 */
final class Fanout implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Fanout.class);

    /** One client connection's delivery callback, keyed by connection. */
    private final Map<String, Map<Object, Consumer<String>>> clients = new ConcurrentHashMap<>();
    private final Map<String, Long> delivered = new ConcurrentHashMap<>();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final StatefulRedisPubSubConnection<String, String> pubsub;
    private final Router router;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("fanout").daemon().factory());
    private volatile boolean polling;

    Fanout(StatefulRedisPubSubConnection<String, String> pubsub, Router router) {
        this.pubsub = pubsub;
        this.router = router;
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                deliver(channel.substring("board:".length()), message);
            }
        });
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    void join(String board, Object conn, Consumer<String> send) {
        clients.computeIfAbsent(board, b -> new ConcurrentHashMap<>()).put(conn, send);
        if (subscribed.add(board)) {
            try {
                pubsub.sync().subscribe(RedisKeys.channel(board));
            } catch (RuntimeException e) {
                // Redis down: polling covers delivery; Lettuce resubscribes on reconnect only for
                // channels it knows, so retry from tick().
                subscribed.remove(board);
            }
        }
    }

    void leave(String board, Object conn) {
        Map<Object, Consumer<String>> m = clients.get(board);
        if (m == null) return;
        m.remove(conn);
        if (m.isEmpty()) {
            clients.remove(board, m);
            delivered.remove(board);
            if (subscribed.remove(board)) {
                try {
                    pubsub.sync().unsubscribe(RedisKeys.channel(board));
                } catch (RuntimeException e) {
                    // nothing to do; the subscription dies with the connection
                }
            }
        }
    }

    /** A version some client of this gateway has been brought up to (JOINED, SYNC). */
    void saw(String board, long version) {
        delivered.merge(board, version, Math::max);
    }

    private void deliver(String board, String message) {
        Map<Object, Consumer<String>> m = clients.get(board);
        if (m == null) return;
        try {
            Map<?, ?> msg = (Map<?, ?>) Json.parse(message);
            if ("OP_BROADCAST".equals(msg.get("type")) && msg.get("version") instanceof Long v) saw(board, v);
        } catch (RuntimeException e) {
            return;
        }
        for (Consumer<String> send : m.values()) send.accept(message);
    }

    private void tick() {
        boolean pubsubUp = pubsub.isOpen();
        if (!pubsubUp != polling) {
            polling = !pubsubUp;
            log.atWarn().log(polling ? "Pub/Sub down: polling owners every 1s" : "Pub/Sub back: polling stopped");
        }
        for (String board : List.copyOf(clients.keySet())) {
            try {
                if (pubsubUp && !subscribed.contains(board) && subscribed.add(board)) pubsub.sync().subscribe(RedisKeys.channel(board));
                if (!pubsubUp) poll(board);
                if (Boolean.FALSE.equals(router.owned(board)))
                    router.forward(board, Msg.obj("k", "ENSURE", "board_id", board));
            } catch (RuntimeException e) {
                log.atDebug().addKeyValue("board_id", board).log("tick failed: {}", e.toString());
            }
        }
    }

    /** SPEC §6.4: Pub/Sub is down, so fetch what this gateway has not delivered yet. */
    private void poll(String board) {
        long since = delivered.getOrDefault(board, 0L);
        router.forward(board, Msg.obj("k", "POLL", "board_id", board, "since_version", since)).thenAccept(r -> {
            if (!"OPS".equals(r.get("type"))) return;
            for (Object e : (List<?>) r.get("ops")) {
                Map<?, ?> entry = (Map<?, ?>) e;
                @SuppressWarnings("unchecked")
                Map<String, Object> op = (Map<String, Object>) entry.get("op");
                deliver(board, Msg.encode(Msg.broadcast(op, (Long) entry.get("version"), (String) entry.get("user_id"))));
            }
            Map<Object, Consumer<String>> m = clients.get(board);
            if (m != null) {
                String bv = Msg.encode(Msg.boardVersion((Long) r.get("version")));
                for (Consumer<String> send : m.values()) send.accept(bv);
            }
        });
    }

    int clientCount() {
        return clients.values().stream().mapToInt(Map::size).sum();
    }

    @Override
    public void close() {
        timer.shutdownNow();
        pubsub.close();
    }
}
