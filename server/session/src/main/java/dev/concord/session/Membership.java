package dev.concord.session;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The 30s membership cache (SPEC §4.2 step 2): roles are re-read per op from here, and a board's
 * entries are cleared when its membership changes (Redis channel {@code membership}).
 */
public final class Membership {
    static final long TTL_MS = 30_000;
    private static final String NONE = "";

    private record Entry(String role, long expires) {}

    private final Store store;
    private final LongSupplier clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public Membership(Store store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The role ("owner", "editor", "viewer"), or null if not a member. */
    public String role(String board, String user) throws SQLException {
        String key = board + "/" + user;
        long now = clock.getAsLong();
        Entry e = cache.get(key);
        if (e == null || e.expires() <= now) {
            String role = store.role(board, user);
            e = new Entry(role == null ? NONE : role, now + TTL_MS);
            cache.put(key, e);
        }
        return e.role().equals(NONE) ? null : e.role();
    }

    public static boolean canEdit(String role) {
        return "owner".equals(role) || "editor".equals(role);
    }

    /** Membership of {@code board} changed: forget it. */
    public void invalidate(String board) {
        cache.keySet().removeIf(k -> k.startsWith(board + "/"));
    }
}
