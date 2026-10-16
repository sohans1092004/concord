package dev.concord.session;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.sql.SQLException;

/**
 * Claiming, renewing and releasing boards (SPEC §6.2, amendment 6). Redis only says who should
 * be serving; Postgres decides who may commit (§6.3), because the claim writes its fence to
 * boards.fence before loading anything and every commit requires {@code fence = :fence}.
 */
public final class Ownership {
    /** Step 1, atomically: INCR the counter, but never below boards.fence + 1 (survives a wipe). */
    static final String FENCE = """
            local v = redis.call('INCR', KEYS[1])
            local floor = tonumber(ARGV[1])
            if v <= floor then
              v = floor + 1
              redis.call('SET', KEYS[1], v)
            end
            return v
            """;

    /**
     * Compare-and-extend (step 6). 1: extended. 2: the lease was gone (expired or Redis wiped)
     * and has been re-taken; Postgres still fences us, so this is safe. 0: someone else holds it.
     */
    static final String RENEW = """
            local v = redis.call('GET', KEYS[1])
            if v == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
              return 1
            end
            if not v then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[2])
              return 2
            end
            return 0
            """;

    /** Graceful release (SPEC §2.4): delete the lease and owner key only if still ours. */
    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2])
              return 1
            end
            return 0
            """;

    /** Someone else owns the board, or it cannot be claimed right now. */
    public static final class NotOwner extends Exception {
        NotOwner(String why) {
            super(why);
        }
    }

    /** The board row does not exist. */
    public static final class NoSuchBoard extends Exception {
        NoSuchBoard(String board) {
            super("no board " + board);
        }
    }

    private final RedisCommands<String, String> redis;
    private final Store store;
    private final String instance;
    private final String address;

    public Ownership(RedisCommands<String, String> redis, Store store, String instance, String address) {
        this.redis = redis;
        this.store = store;
        this.instance = instance;
        this.address = address;
    }

    public String leaseValue(long fence) {
        return instance + ":" + fence;
    }

    String ownerValue(long fence) {
        return Msg.encode(Msg.obj("instance", instance, "address", address, "fence", fence));
    }

    /**
     * Steps 1–3: fence, lease, then the fence in Postgres. Returns the fence. Loading and
     * replaying (steps 4–5) happen after this returns, so they see every older owner's commits.
     */
    public long claim(String board) throws NotOwner, NoSuchBoard, SQLException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (redis.exists(RedisKeys.lease(board)) == 1) throw new NotOwner("lease held");
            Long floor = store.fence(board);
            if (floor == null) throw new NoSuchBoard(board);
            long fence = redis.eval(FENCE, ScriptOutputType.INTEGER, new String[] {RedisKeys.fence(board)}, Long.toString(floor));
            String ok = redis.set(RedisKeys.lease(board), leaseValue(fence), SetArgs.Builder.nx().px(RedisKeys.LEASE_MS));
            if (!"OK".equals(ok)) throw new NotOwner("lease taken");
            if (store.raiseFence(board, fence)) return fence;
            // A higher fence reached Postgres between our read and our raise: retry.
            release(board, fence);
        }
        throw new NotOwner("fence kept moving");
    }

    /** Step 6: publish the owner for gateway lookup (SPEC §2.3 step 3). */
    public void announce(String board, long fence) {
        redis.set(RedisKeys.owner(board), ownerValue(fence), SetArgs.Builder.px(RedisKeys.LEASE_MS));
    }

    /** 1 extended, 2 re-taken after loss, 0 lost to another owner. Throws if Redis is unreachable. */
    public long renew(String board, long fence) {
        return redis.eval(RENEW, ScriptOutputType.INTEGER, new String[] {RedisKeys.lease(board), RedisKeys.owner(board)},
                leaseValue(fence), Long.toString(RedisKeys.LEASE_MS), ownerValue(fence));
    }

    public boolean release(String board, long fence) {
        Long r = redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[] {RedisKeys.lease(board), RedisKeys.owner(board)}, leaseValue(fence));
        return r != null && r == 1;
    }
}
