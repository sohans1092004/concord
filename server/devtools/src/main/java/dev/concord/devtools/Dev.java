package dev.concord.devtools;

import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import dev.concord.common.Secrets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Dev-only shortcuts past sign-up, for tests and drills. Seeds users (who cannot sign in: they
 * have no password), a board and its members, and gives each a session token, used as
 * {@code Authorization: Bearer} on the REST API to fetch WebSocket tickets (SPEC §9.4).
 *
 * <p>It refuses to run unless CONCORD_ENV=dev, and needs direct access to the database.
 *
 * <pre>
 * seed &lt;editors&gt; [viewers]    → {"board":id,"users":[{"id","role","session"}]}
 * session &lt;user_id&gt;            → {"session"}
 * bug-reports [since_iso]        → the bug reports since then (default: all), as JSON
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
        switch (args.length == 0 ? "" : args[0]) {
            case "seed" -> {
                int editors = Integer.parseInt(args[1]), viewers = args.length > 2 ? Integer.parseInt(args[2]) : 0;
                try (var pool = Db.pool(c)) {
                    return Msg.encode(seed(pool, editors, viewers));
                }
            }
            case "session" -> {
                try (var pool = Db.pool(c)) {
                    return Msg.encode(Msg.obj("session", Secrets.createSession(pool, args[1])));
                }
            }
            case "bug-reports" -> {
                try (var pool = Db.pool(c); Connection conn = pool.getConnection(); PreparedStatement s = conn.prepareStatement(
                        "SELECT r.id::text, r.created_at::text, u.email, r.board_id::text, r.description, r.diagnostics::text "
                                + "FROM bug_reports r JOIN users u ON u.id = r.user_id WHERE r.created_at >= ?::timestamptz ORDER BY r.created_at")) {
                    s.setString(1, args.length > 1 ? args[1] : "-infinity");
                    StringBuilder out = new StringBuilder("[");
                    try (var r = s.executeQuery()) {
                        while (r.next()) {
                            if (out.length() > 1) out.append(",\n");
                            out.append(Msg.encode(Msg.obj("id", r.getString(1), "created_at", r.getString(2), "email", r.getString(3),
                                    "board_id", r.getString(4), "description", r.getString(5),
                                    "diagnostics", dev.concord.crdt.Json.parse(r.getString(6)))));
                        }
                    }
                    return out.append("]").toString();
                }
            }
            case "revoke" -> {
                try (var pool = Db.pool(c); var redis = new Redis(c.redisUrl())) {
                    exec(pool, "DELETE FROM board_members WHERE board_id = ?::uuid AND user_id = ?::uuid", args[1], args[2]);
                    redis.conn.sync().publish(RedisKeys.MEMBERSHIP_CHANNEL, args[1]);
                }
                return Msg.encode(Msg.obj("revoked", args[2]));
            }
            default -> throw new IllegalArgumentException("usage: seed <editors> [viewers] | session <user_id> | revoke <board_id> <user_id> | bug-reports [since]");
        }
    }

    /** One board; the first user is its owner, then editors, then viewers. */
    public static Map<String, Object> seed(DataSource ds, int editors, int viewers) throws SQLException {
        String board = UUID.randomUUID().toString();
        List<Map<String, Object>> users = new ArrayList<>();
        for (int i = 0; i < 1 + editors + viewers; i++) {
            String id = UUID.randomUUID().toString();
            String role = i == 0 ? "owner" : i <= editors ? "editor" : "viewer";
            exec(ds, "INSERT INTO users (id, email, name, password_hash) VALUES (?::uuid, ?, ?, 'dev-no-login')", id, id + "@dev.local", role + " " + i);
            if (i == 0) exec(ds, "INSERT INTO boards (id, title, created_by) VALUES (?::uuid, 'dev board', ?::uuid)", board, id);
            exec(ds, "INSERT INTO board_members (board_id, user_id, role) VALUES (?::uuid, ?::uuid, ?)", board, id, role);
            users.add(Msg.obj("id", id, "role", role, "name", role + " " + i, "session", Secrets.createSession(ds, id)));
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
