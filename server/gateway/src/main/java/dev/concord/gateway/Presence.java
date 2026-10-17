package dev.concord.gateway;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presence (SPEC §1.1, §5.2, §7.5): who is on a board and which card they have open. Lives only
 * in Redis, in the sorted set {@code presence:{board}} with member {@code user:card:state}
 * ({@code -} for no card) and score = last seen. Never touches Postgres; if Redis is down,
 * presence is simply absent.
 *
 * <p>A client sends PRESENCE on join, on change and every 10s. Each gateway sweeps the boards it
 * serves every 10s, removing entries older than 30s and announcing {@code state: "left"} for
 * users with no entry left. Several gateways may sweep the same board, so a client must treat
 * "left" idempotently.
 */
final class Presence {
    private static final Logger log = LoggerFactory.getLogger(Presence.class);
    static final long TTL_MS = 30_000, SWEEP_MS = 10_000;

    private final RedisCommands<String, String> redis;
    private final LongSupplier clock;
    /** Each connection's current member, so a change or a disconnect removes the old one. */
    private final Map<Object, String[]> current = new ConcurrentHashMap<>(); // conn → {board, member}

    Presence(RedisCommands<String, String> redis, LongSupplier clock) {
        this.redis = redis;
        this.clock = clock;
    }

    static String key(String board) {
        return RedisKeys.presence(board);
    }

    /** A connection on {@code board} reports what it shows. */
    void update(String board, Object conn, String user, String card, String state) {
        String member = user + ":" + (card == null ? "-" : card) + ":" + state;
        try {
            String[] old = current.put(conn, new String[] {board, member});
            if (old != null && !old[1].equals(member)) redis.zrem(key(old[0]), old[1]);
            redis.zadd(key(board), (double) clock.getAsLong(), member);
            redis.publish(RedisKeys.channel(board), Msg.encode(update(user, card, state)));
        } catch (RuntimeException e) {
            log.atDebug().addKeyValue("board_id", board).log("presence not recorded: {}", e.toString());
        }
    }

    /** The connection closed: drop its entry, and say the user left unless another entry of theirs remains. */
    void leave(Object conn) {
        String[] old = current.remove(conn);
        if (old == null) return;
        try {
            redis.zrem(key(old[0]), old[1]);
            String user = old[1].substring(0, old[1].indexOf(':'));
            if (live(old[0]).stream().noneMatch(m -> m.startsWith(user + ":")))
                redis.publish(RedisKeys.channel(old[0]), Msg.encode(update(user, null, "left")));
        } catch (RuntimeException e) {
            log.atDebug().addKeyValue("board_id", old[0]).log("presence leave not recorded: {}", e.toString());
        }
    }

    /** Everyone on the board now, as PRESENCE_UPDATE messages, for a client that just joined. */
    List<Map<String, Object>> snapshot(String board) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String m : live(board)) {
                String[] p = m.split(":", 3);
                out.add(update(p[0], p[1].equals("-") ? null : p[1], p[2]));
            }
        } catch (RuntimeException e) {
            // Redis down: no presence
        }
        return out;
    }

    /** Removes entries older than 30s on these boards; announces users who have none left. */
    void sweep(Iterable<String> boards) {
        long cutoff = clock.getAsLong() - TTL_MS;
        for (String board : boards) {
            try {
                List<String> stale = redis.zrangebyscore(key(board), Range.create(Double.NEGATIVE_INFINITY, (double) cutoff));
                if (stale.isEmpty()) continue;
                redis.zremrangebyscore(key(board), Range.create(Double.NEGATIVE_INFINITY, (double) cutoff));
                Set<String> still = new HashSet<>();
                for (String m : live(board)) still.add(m.substring(0, m.indexOf(':')));
                Set<String> gone = new HashSet<>();
                for (String m : stale) {
                    String user = m.substring(0, m.indexOf(':'));
                    if (!still.contains(user) && gone.add(user)) redis.publish(RedisKeys.channel(board), Msg.encode(update(user, null, "left")));
                }
            } catch (RuntimeException e) {
                log.atDebug().addKeyValue("board_id", board).log("presence sweep skipped: {}", e.toString());
            }
        }
    }

    private List<String> live(String board) {
        return redis.zrangebyscore(key(board), Range.create((double) clock.getAsLong() - TTL_MS, Double.POSITIVE_INFINITY));
    }

    static Map<String, Object> update(String user, String card, String state) {
        return Msg.obj("type", "PRESENCE_UPDATE", "user_id", user, "card_id", card, "state", state);
    }
}
