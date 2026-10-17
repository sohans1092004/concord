package dev.concord.common;

/** Redis key names, exactly SPEC §5.2. */
public final class RedisKeys {
    private RedisKeys() {}

    /** Sorted set of live Session instances; member {@code id@address}, score = heartbeat ms. */
    public static final String INSTANCES = "instances";
    /** Live = heartbeat within this many ms (SPEC §2.3). */
    public static final long INSTANCE_TTL_MS = 6_000;
    public static final long HEARTBEAT_MS = 2_000;
    public static final long LEASE_MS = 15_000;
    public static final long RENEW_MS = 5_000;
    /** Membership changed on a board; payload is the board ID (clears §4.2 caches). */
    public static final String MEMBERSHIP_CHANNEL = "membership";

    public static String lease(String board) {
        return "board:lease:" + board;
    }

    public static String fence(String board) {
        return "board:fence:" + board;
    }

    public static String owner(String board) {
        return "board:owner:" + board;
    }

    /** Presence of a board's users (SPEC §5.2): member user:card:state, score = last seen. */
    public static String presence(String board) {
        return "presence:" + board;
    }

    public static String channel(String board) {
        return "board:" + board;
    }
}
