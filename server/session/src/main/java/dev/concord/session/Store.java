package dev.concord.session;

import dev.concord.common.Msg;
import dev.concord.crdt.Board;
import dev.concord.crdt.Canonical;
import dev.concord.crdt.Json;
import dev.concord.crdt.Op;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every SQL statement the Session Service runs. Plain JDBC; read top to bottom. */
public class Store {
    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final Executor DIRECT = Runnable::run;
    private final DataSource ds;

    public Store(DataSource ds) {
        this.ds = ds;
    }

    public DataSource dataSource() {
        return ds;
    }

    /** An op waiting in a board's batch: the parsed op, its JSON and the user who sent it. */
    public record Pending(Op op, Map<String, Object> json, String userId) {}

    /** A committed op read back from board_ops. */
    /** A committed op, with the user who sent it (SYSTEM_USER for the owner's own rebalancing). */
    public record Stored(long version, Map<String, Object> json, Op op, String userId) {}

    /** Outcome of a group commit. {@code fenced}: a newer owner exists and nothing was written. */
    public record Committed(boolean fenced, Map<String, Long> duplicates, List<Long> versions) {}

    // ---- write path, SPEC §6.1 step 3 ------------------------------------------------------

    /**
     * One transaction for one board's batch. {@code versions.get(i)} is the version given to the
     * i-th op of {@code ops} not in {@code duplicates}. Any exception means the outcome is
     * unknown: the caller must reconcile (SPEC §6.1 step 5, amendment 6).
     */
    public Committed commit(String board, long fence, List<Pending> ops, long deadlineMs) throws SQLException {
        try (Connection c = connection(deadlineMs)) {
            c.setAutoCommit(false);
            // 3.1 Drop op_ids already stored: duplicates the LRU missed.
            Map<String, Long> dups = storedVersions(c, board, ops.stream().map(p -> p.op().opId()).toList());
            List<Pending> fresh = ops.stream().filter(p -> !dups.containsKey(p.op().opId())).toList();
            if (fresh.isEmpty()) {
                c.commit();
                return new Committed(false, dups, List.of());
            }
            budget(c, deadlineMs);
            // 3.2 Fence check and version range in one statement. Zero rows: a newer owner exists.
            long end;
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE boards SET version = version + ? WHERE id = ? AND fence = ? RETURNING version")) {
                u.setLong(1, fresh.size());
                u.setObject(2, UUID.fromString(board));
                u.setLong(3, fence);
                try (ResultSet r = u.executeQuery()) {
                    if (!r.next()) {
                        c.rollback();
                        return new Committed(true, Map.of(), List.of());
                    }
                    end = r.getLong(1);
                }
            }
            budget(c, deadlineMs);
            // 3.3 Insert with consecutive versions ending at the returned one.
            long first = end - fresh.size() + 1;
            List<Long> versions = new ArrayList<>();
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO board_ops (board_id, op_id, version, type, payload, hlc_physical_ms, hlc_counter, replica_id, user_id, fence) "
                            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                for (int i = 0; i < fresh.size(); i++) {
                    Pending p = fresh.get(i);
                    long version = first + i;
                    ins.setObject(1, UUID.fromString(board));
                    ins.setObject(2, UUID.fromString(p.op().opId()));
                    ins.setLong(3, version);
                    ins.setString(4, (String) p.json().get("type"));
                    ins.setString(5, Canonical.encode(p.json()));
                    ins.setLong(6, p.op().hlc().physicalMs());
                    ins.setInt(7, (int) p.op().hlc().counter());
                    ins.setObject(8, UUID.fromString(p.op().replicaId()));
                    ins.setObject(9, UUID.fromString(p.userId()));
                    ins.setLong(10, fence);
                    ins.addBatch();
                    versions.add(version);
                }
                for (int n : ins.executeBatch())
                    if (n != 1) throw new SQLException("board_ops insert conflicted inside a fenced batch");
            }
            budget(c, deadlineMs);
            beforeCommit();
            budget(c, deadlineMs); // a commit that would start after the deadline is not sent
            c.commit();
            return new Committed(false, dups, versions);
        }
    }

    /** Test seam: runs after the inserts, before COMMIT is sent. */
    protected void beforeCommit() throws SQLException {}

    /** A pooled connection whose socket gives up at the deadline. Pool wait counts toward it. */
    private Connection connection(long deadlineMs) throws SQLException {
        Connection c = ds.getConnection();
        try {
            budget(c, deadlineMs);
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    private static void budget(Connection c, long deadlineMs) throws SQLException {
        long left = deadlineMs - System.currentTimeMillis();
        if (left <= 0) throw new SQLTimeoutException("commit deadline passed");
        c.setNetworkTimeout(DIRECT, (int) left);
    }

    private static Map<String, Long> storedVersions(Connection c, String board, List<String> opIds) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (PreparedStatement q = c.prepareStatement(
                "SELECT op_id::text, version FROM board_ops WHERE board_id = ? AND op_id = ANY(?)")) {
            q.setObject(1, UUID.fromString(board));
            q.setArray(2, c.createArrayOf("uuid", opIds.stream().map(UUID::fromString).toArray()));
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) out.put(r.getString(1), r.getLong(2));
            }
        }
        return out;
    }

    /** The stored version of one op, or null: the dedup backstop for a rejected resend. */
    public Long storedVersion(String board, String opId) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return storedVersions(c, board, List.of(opId)).get(opId);
        }
    }

    // ---- reconcile after an unknown commit outcome, amendment 6 ------------------------------

    /** Result of reconciling: the fence now in Postgres, and every op after {@code applied}. */
    public record Reconciled(long fence, List<Stored> ops) {}

    /**
     * Locks the board row, which waits out any transaction of ours still holding it, then
     * reads what was committed after {@code applied}.
     */
    public Reconciled reconcile(String board, long applied) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setNetworkTimeout(DIRECT, 5_000); // fail and retry rather than hang on a dead network
            c.setAutoCommit(false);
            long fence;
            try (PreparedStatement q = c.prepareStatement("SELECT fence FROM boards WHERE id = ? FOR UPDATE")) {
                q.setObject(1, UUID.fromString(board));
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) throw new SQLException("board " + board + " vanished");
                    fence = r.getLong(1);
                }
            }
            List<Stored> ops = opsAfter(c, board, applied, Integer.MAX_VALUE);
            c.commit();
            return new Reconciled(fence, ops);
        }
    }

    // ---- claiming, SPEC §6.2 (amendment 6) --------------------------------------------------

    /** boards.fence, or null if the board does not exist. */
    public Long fence(String board) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement q = c.prepareStatement("SELECT fence FROM boards WHERE id = ?")) {
            q.setObject(1, UUID.fromString(board));
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? r.getLong(1) : null;
            }
        }
    }

    /**
     * Claim step 3: record the new owner's fence before loading anything. From this commit on,
     * no older fence can commit, so the replay that follows sees every op an older owner wrote.
     */
    public boolean raiseFence(String board, long fence) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement u = c.prepareStatement("UPDATE boards SET fence = ? WHERE id = ? AND fence < ?")) {
            u.setLong(1, fence);
            u.setObject(2, UUID.fromString(board));
            u.setLong(3, fence);
            return u.executeUpdate() == 1;
        }
    }

    /** A board loaded from its newest valid snapshot plus the tail after it. */
    public record Loaded(Board board, long version, int corruptSnapshots, int tail) {}

    /** Claim steps 4–5: newest snapshot whose SHA-256 matches (else the previous one), then replay. */
    public Loaded load(String board) throws SQLException {
        try (Connection c = ds.getConnection()) {
            Board state = Board.empty();
            long version = 0;
            int corrupt = 0;
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT version, state::text, sha256 FROM board_snapshots WHERE board_id = ? ORDER BY version DESC")) {
                q.setObject(1, UUID.fromString(board));
                try (ResultSet r = q.executeQuery()) {
                    while (r.next()) {
                        Board b = verified(r.getString(2), r.getString(3));
                        if (b != null) {
                            state = b;
                            version = r.getLong(1);
                            break;
                        }
                        corrupt++;
                        log.atError().addKeyValue("board_id", board).addKeyValue("version", r.getLong(1))
                                .log("ALERT snapshot checksum mismatch; falling back to the previous snapshot");
                    }
                }
            }
            List<Stored> tail = opsAfter(c, board, version, Integer.MAX_VALUE);
            for (Stored s : tail) {
                state.apply(s.op());
                version = s.version();
            }
            return new Loaded(state, version, corrupt, tail.size());
        }
    }

    /** The snapshot's state if its SHA-256 (over the canonical re-encoding) matches, else null. */
    static Board verified(String stateJson, String sha) {
        try {
            Board b = Board.decode(Json.parse(stateJson));
            return Msg.sha256Hex(b.canonical()).equals(sha) ? b : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- snapshots, SPEC §5.1 ---------------------------------------------------------------

    /** Writes a snapshot and keeps only the newest three for the board. */
    public void writeSnapshot(String board, long version, String canonical) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO board_snapshots (board_id, version, state, sha256) VALUES (?, ?, ?::jsonb, ?) ON CONFLICT DO NOTHING")) {
                ins.setObject(1, UUID.fromString(board));
                ins.setLong(2, version);
                ins.setString(3, canonical);
                ins.setString(4, Msg.sha256Hex(canonical));
                ins.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM board_snapshots WHERE board_id = ? AND version NOT IN "
                            + "(SELECT version FROM board_snapshots WHERE board_id = ? ORDER BY version DESC LIMIT 3)")) {
                del.setObject(1, UUID.fromString(board));
                del.setObject(2, UUID.fromString(board));
                del.executeUpdate();
            }
            c.commit();
        }
    }

    // ---- reads for JOIN, SYNC_REQUEST and polling -------------------------------------------

    public List<Stored> opsAfter(String board, long version, int limit) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return opsAfter(c, board, version, limit);
        }
    }

    private static List<Stored> opsAfter(Connection c, String board, long version, int limit) throws SQLException {
        List<Stored> out = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement(
                "SELECT version, payload::text, user_id::text FROM board_ops WHERE board_id = ? AND version > ? ORDER BY version LIMIT ?")) {
            q.setObject(1, UUID.fromString(board));
            q.setLong(2, version);
            q.setInt(3, limit);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = (Map<String, Object>) Json.parse(r.getString(2));
                    out.add(new Stored(r.getLong(1), json, Op.parse(json), r.getString(3)));
                }
            }
        }
        return out;
    }

    // ---- membership, SPEC §4.2 step 2 -------------------------------------------------------

    /** Every member: user ID → {name, role}, for JOINED (SPEC §7.5). */
    public Map<String, Object> members(String board) throws SQLException {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try (Connection c = ds.getConnection(); PreparedStatement q = c.prepareStatement(
                "SELECT m.user_id::text, u.name, m.role FROM board_members m JOIN users u ON u.id = m.user_id WHERE m.board_id = ? ORDER BY m.user_id")) {
            q.setObject(1, UUID.fromString(board));
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) out.put(r.getString(1), dev.concord.common.Msg.obj("name", r.getString(2), "role", r.getString(3)));
            }
        }
        return out;
    }

    /** The user's role on the board, or null if not a member. */
    public String role(String board, String user) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement q = c.prepareStatement("SELECT role FROM board_members WHERE board_id = ? AND user_id = ?")) {
            q.setObject(1, UUID.fromString(board));
            q.setObject(2, UUID.fromString(user));
            try (ResultSet r = q.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }
}
