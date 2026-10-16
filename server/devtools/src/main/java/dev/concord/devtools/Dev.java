package dev.concord.devtools;

import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import dev.concord.common.Tokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Dev-only stand-in for Phase 3's sign-in (SPEC §10 Phase 2). Seeds users, a board and its
 * members, and mints JWTs for them.
 *
 * <p>It cannot be enabled outside dev: it refuses to run unless CONCORD_ENV=dev, and it signs
 * only with {@link Config#DEV_JWT_SECRET}, which every non-dev server refuses at startup
 * ({@link Config#check()}), so its tokens never validate anywhere else.
 *
 * <pre>
 * seed &lt;editors&gt; [viewers]    → {"board":id,"users":[{"id","role","token"}]}
 * token &lt;user_id&gt;              → {"token"}
 * revoke &lt;board_id&gt; &lt;user_id&gt;  → removes the membership and clears every owner's cache
 * </pre>
 */
public final class Dev {
    private Dev() {}

    public static void main(String[] args) throws Exception {
        Config c = Config.fromEnv();
        String out = run(c, args);
        System.out.println(out);
    }

    public static String run(Config c, String... args) throws SQLException {
        if (!c.dev()) throw new IllegalStateException("devtools runs only with CONCORD_ENV=dev");
        Tokens tokens = new Tokens(Config.DEV_JWT_SECRET);
        switch (args.length == 0 ? "" : args[0]) {
            case "seed" -> {
                int editors = Integer.parseInt(args[1]), viewers = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                try (var pool = Db.pool(c)) {
                    return Msg.encode(seed(pool, tokens, editors, viewers));
                }
            }
            case "token" -> {
                return Msg.encode(Msg.obj("token", tokens.mint(args[1], Instant.now())));
            }
            case "revoke" -> {
                try (var pool = Db.pool(c); var redis = new Redis(c.redisUrl())) {
                    exec(pool, "DELETE FROM board_members WHERE board_id = ?::uuid AND user_id = ?::uuid", args[1], args[2]);
                    redis.conn.sync().publish(RedisKeys.MEMBERSHIP_CHANNEL, args[1]);
                }
                return Msg.encode(Msg.obj("revoked", args[2]));
            }
            default -> throw new IllegalArgumentException("usage: seed <editors> [viewers] | token <user_id> | revoke <board_id> <user_id>");
        }
    }

    /** One board; the first user is its owner, then editors, then viewers. */
    public static Map<String, Object> seed(DataSource ds, Tokens tokens, int editors, int viewers) throws SQLException {
        String board = UUID.randomUUID().toString();
        List<Map<String, Object>> users = new ArrayList<>();
        for (int i = 0; i < 1 + editors + viewers; i++) {
            String id = UUID.randomUUID().toString();
            String role = i == 0 ? "owner" : i <= editors ? "editor" : "viewer";
            exec(ds, "INSERT INTO users (id, email, password_hash) VALUES (?::uuid, ?, 'dev-no-login')", id, id + "@dev.local");
            if (i == 0) exec(ds, "INSERT INTO boards (id, title, created_by) VALUES (?::uuid, 'dev board', ?::uuid)", board, id);
            exec(ds, "INSERT INTO board_members (board_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)", board, id, role);
            users.add(Msg.obj("id", id, "role", role, "token", tokens.mint(id, Instant.now())));
        }
        return Msg.obj("board", board, "users", users);
    }

    private static void exec(DataSource ds, String sql, Object... args) throws SQLException {
        try (Connection conn = ds.getConnection(); PreparedStatement s = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        }
    }
}
