package dev.concord.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Rows for tests: users, boards, memberships. */
public final class Seed {
    private Seed() {}

    public static String user(DataSource ds) {
        String id = UUID.randomUUID().toString();
        exec(ds, "INSERT INTO users (id, email, name, password_hash) VALUES (?::uuid, ?, 'Test user', 'test-no-login')", id, id + "@test");
        return id;
    }

    /** A board created by {@code owner}, who is its owner member. */
    public static String board(DataSource ds, String owner) {
        String id = UUID.randomUUID().toString();
        exec(ds, "INSERT INTO boards (id, title, created_by) VALUES (?::uuid, 'test board', ?::uuid)", id, owner);
        member(ds, id, owner, "owner");
        return id;
    }

    public static void member(DataSource ds, String board, String user, String role) {
        exec(ds, "INSERT INTO board_members (board_id, user_id, role) VALUES (?::uuid, ?::uuid, ?) "
                + "ON CONFLICT (board_id, user_id) DO UPDATE SET role = EXCLUDED.role", board, user, role);
    }

    public static void removeMember(DataSource ds, String board, String user) {
        exec(ds, "DELETE FROM board_members WHERE board_id = ?::uuid AND user_id = ?::uuid", board, user);
    }

    public static void exec(DataSource ds, String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long count(DataSource ds, String sql, Object... args) {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
