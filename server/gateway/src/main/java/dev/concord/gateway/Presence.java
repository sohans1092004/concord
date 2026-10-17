package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presence (SPEC §1.1, §5.2, §7.5): who is on a board and which card they have open. Lives only
 * in Redis, in the sorted set {@code presence:{board}} with one member per connection,
 * {@code user:connection:card:state} ({@code -} for no card), score = last seen. Never touches
 * Postgres; if Redis is down, presence is simply absent.
 *
 * <p>A client sends PRESENCE on join, on change and every 10s. Each gateway sweeps the boards it
 * serves every 10s, removing entries older than 30s. Every removal (a disconnect, a sweep) is
 * announced as {@code state: "left"} for that one connection; a client shows a user while any of
 * their connections is present. Several gateways may sweep the same board, so a client must
 * treat "left" idempotently (amendment 22).
 */
final class Presence {
    private static final Logger log = LoggerFactory.getLogger(Presence.class);
    static final long TTL_MS = 30_000, SWEEP_MS = 10_000;

    private final RedisCommands<String, String> redis;
    private final LongSupplier clock;
    /** Each connection's current member, so a change or a disconnect removes the old one. */
    private final Map<String, String[]> current = new ConcurrentHashMap<>(); // connection → {board, member}

    Presence(RedisCommands<String, String> redis, LongSupplier clock) {
        this.redis = redis;
        this.clock = clock;
    }

    static String key(String board) {
        return RedisKeys.presence(board);
    }

    /** A connection on {@code board} reports what it shows. */
    void update(String board, String connection, String user, String card, String state) {
        String member = user + ":" + connection + ":" + (card == null ? "-" : card) + ":" + state;
        try {
            String[] old = current.put(connection, new String[] {board, member});
            if (old != null && !old[1].equals(member)) redis.zrem(key(old[0]), old[1]);
            redis.zadd(key(board), (double) clock.getAsLong(), member);
            redis.publish(RedisKeys.channel(board), Msg.encode(update(user, connection, card, state)));
        } catch (RuntimeException e) {
            log.atDebug().addKeyValue("board_id", board).log("presence not recorded: {}", e.toString());
        }
    }

    /** The connection closed: drop its entry and announce that this connection left. */
    void leave(String connection) {
        String[] old = current.remove(connection);
        if (old == null) return;
        try {
            redis.zrem(key(old[0]), old[1]);
            String[] p = old[1].split(":", 4);
            redis.publish(RedisKeys.channel(old[0]), Msg.encode(update(p[0], p[1], null, "left")));
        } catch (RuntimeException e) {
            log.atDebug().addKeyValue("board_id", old[0]).log("presence leave not recorded: {}", e.toString());
        }
    }

    /** Every connection on the board now, as PRESENCE_UPDATE messages, for a client that just joined. */
    List<Map<String, Object>> snapshot(String board) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String m : redis.zrangebyscore(key(board), Range.create((double) clock.getAsLong() - TTL_MS, Double.POSITIVE_INFINITY))) {
                String[] p = m.split(":", 4);
                out.add(update(p[0], p[1], p[2].equals("-") ? null : p[2], p[3]));
            }
        } catch (RuntimeException e) {
            // Redis down: no presence
        }
        return out;
    }

    /** Removes entries older than 30s on these boards and announces each removed connection as left. */
    void sweep(Iterable<String> boards) {
        long cutoff = clock.getAsLong() - TTL_MS;
        for (String board : boards) {
            try {
                Range<Double> old = Range.create(Double.NEGATIVE_INFINITY, (double) cutoff);
                List<String> stale = redis.zrangebyscore(key(board), old);
                if (stale.isEmpty()) continue;
                redis.zremrangebyscore(key(board), old);
                for (String m : stale) {
                    String[] p = m.split(":", 4);
                    redis.publish(RedisKeys.channel(board), Msg.encode(update(p[0], p[1], null, "left")));
                }
            } catch (RuntimeException e) {
                log.atDebug().addKeyValue("board_id", board).log("presence sweep skipped: {}", e.toString());
            }
        }
    }

    static Map<String, Object> update(String user, String connection, String card, String state) {
        return Msg.obj("type", "PRESENCE_UPDATE", "user_id", user, "connection_id", connection, "card_id", card, "state", state);
    }
}
