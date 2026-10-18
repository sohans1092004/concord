package dev.concord.gateway;

import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Deleting one user's data (Phase 4a step 8, docs/trial-guide.md "Privacy"), in one transaction:
 * <ul>
 *   <li>boards whose only member they are: deleted entirely (ops, snapshots, members, board);</li>
 *   <li>shared boards: they leave; if they were the last owner, the longest-standing member is
 *       made owner. Their past edits stay in the board (other people's replicas hold them, and
 *       the history needs them to converge), but no longer carry a name: the user row is gone;</li>
 *   <li>their bug reports, sessions, tickets and the account itself.</li>
 * </ul>
 * Used by {@code DELETE /api/me} and, for a request by email, by {@code delete-user.sh}:
 * <pre>java -cp '/app/gateway/lib/*' dev.concord.gateway.Accounts delete &lt;email&gt;</pre>
 */
final class Accounts {
    private Accounts() {}

    record Deleted(String userId, List<String> boardsDeleted, List<String> boardsLeft, int bugReports) {}

    static Deleted delete(DataSource db, String userId) throws SQLException {
        UUID user = UUID.fromString(userId);
        List<String> deleted = new ArrayList<>(), left = new ArrayList<>();
        int reports;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String board : strings(c, "SELECT board_id::text FROM board_members WHERE user_id = ? ORDER BY board_id FOR UPDATE", user)) {
                    UUID b = UUID.fromString(board);
                    List<String> others = strings(c, "SELECT user_id::text FROM board_members WHERE board_id = ? AND user_id <> ?", b, user);
                    if (others.isEmpty()) {
                        for (String sql : new String[] {"DELETE FROM bug_reports WHERE board_id = ?", "DELETE FROM board_snapshots WHERE board_id = ?",
                                "DELETE FROM board_ops WHERE board_id = ?", "DELETE FROM board_members WHERE board_id = ?", "DELETE FROM boards WHERE id = ?"})
                            exec(c, sql, b);
                        deleted.add(board);
                        continue;
                    }
                    exec(c, "DELETE FROM board_members WHERE board_id = ? AND user_id = ?", b, user);
                    if (strings(c, "SELECT user_id::text FROM board_members WHERE board_id = ? AND role = 'owner'", b).isEmpty()) {
                        // The longest-standing member: board_members keeps no date, so the first to have made an op, else any.
                        String heir = strings(c, "SELECT m.user_id::text FROM board_members m LEFT JOIN (SELECT user_id, min(version) v FROM board_ops "
                                + "WHERE board_id = ? GROUP BY user_id) o ON o.user_id = m.user_id WHERE m.board_id = ? ORDER BY o.v NULLS LAST, m.user_id LIMIT 1", b, b).get(0);
                        exec(c, "UPDATE board_members SET role = 'owner' WHERE board_id = ? AND user_id = ?", b, UUID.fromString(heir));
                    }
                    exec(c, "UPDATE boards SET created_by = (SELECT user_id FROM board_members WHERE board_id = ? AND role = 'owner' ORDER BY user_id LIMIT 1) "
                            + "WHERE id = ? AND created_by = ?", b, b, user);
                    left.add(board);
                }
                // Boards they created but are no longer a member of.
                exec(c, "UPDATE boards b SET created_by = (SELECT user_id FROM board_members m WHERE m.board_id = b.id AND role = 'owner' ORDER BY user_id LIMIT 1) "
                        + "WHERE created_by = ?", user);
                reports = exec(c, "DELETE FROM bug_reports WHERE user_id = ?", user);
                exec(c, "DELETE FROM users WHERE id = ?", user); // sessions and tickets cascade
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        return new Deleted(userId, deleted, left, reports);
    }

    /**
     * Owners drop the changed boards from their membership caches (SPEC §5.2), and drop deleted
     * boards altogether (amendment 27). Called after the deletion committed.
     */
    static void announce(io.lettuce.core.api.sync.RedisCommands<String, String> redis, Deleted d) {
        for (String b : d.boardsLeft()) {
            try {
                redis.publish(RedisKeys.MEMBERSHIP_CHANNEL, b);
            } catch (RuntimeException e) {
                // a lost message only delays revocation to the 30s cache TTL
            }
        }
        for (String b : d.boardsDeleted()) {
            try {
                redis.publish(RedisKeys.BOARD_DELETED_CHANNEL, b);
            } catch (RuntimeException e) {
                // a lost message: the owner finds the board gone at its next commit, reconcile or
                // snapshot, and drops it then (Store.BoardDeleted)
            }
        }
    }

    static Map<String, Object> json(Deleted d) {
        return Msg.obj("user_id", d.userId(), "boards_deleted", d.boardsDeleted(), "boards_left", d.boardsLeft(), "bug_reports_deleted", (long) d.bugReports());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("delete")) {
            System.err.println("usage: Accounts delete <email>");
            System.exit(2);
        }
        Config config = Config.fromEnv();
        try (var pool = Db.pool(config); var redis = new Redis(config.redisUrl())) {
            List<String> id = strings0(pool, "SELECT id::text FROM users WHERE email = ?", args[1].trim().toLowerCase(java.util.Locale.ROOT));
            if (id.isEmpty()) {
                System.err.println("no user with email " + args[1]);
                System.exit(1);
            }
            Deleted d = delete(pool, id.get(0));
            announce(redis.conn.sync(), d);
            System.out.println(Msg.encode(json(d)));
        }
    }

    private static List<String> strings0(DataSource db, String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection()) {
            return strings(c, sql, args);
        }
    }

    private static List<String> strings(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet r = s.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (r.next()) out.add(r.getString(1));
                return out;
            }
        }
    }

    private static int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }
}
