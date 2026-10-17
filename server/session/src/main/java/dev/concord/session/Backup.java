package dev.concord.session;

import dev.concord.common.Config;
import dev.concord.common.Db;
import dev.concord.common.Msg;
import dev.concord.crdt.Board;
import dev.concord.crdt.Json;
import dev.concord.crdt.Op;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Backups that can be checked (Phase 4a step 5). Two commands, run inside the server image:
 *
 * <pre>
 *   java -cp '/app/session/lib/*' dev.concord.session.Backup dump &lt;out.dump&gt; &lt;out.manifest.json&gt;
 *   java -cp '/app/session/lib/*' dev.concord.session.Backup verify &lt;manifest.json&gt;
 * </pre>
 *
 * {@code dump} opens a read-only repeatable-read transaction, exports its snapshot and runs
 * {@code pg_dump --snapshot}, so the dump and the manifest describe exactly the same database
 * state even while the trial keeps editing. The manifest holds every table's row count and, for
 * every board, its version and the SHA-256 of the state replayed from all of its ops.
 *
 * <p>{@code verify}, against a database restored from that dump, checks the row counts and, for
 * every board, that both the state a new owner would load (latest good snapshot plus the later
 * ops, SPEC §6.2) and a full replay of its ops hash to the manifest's value. Exit 1 on any
 * mismatch.
 */
public final class Backup {
    static final List<String> TABLES = List.of("users", "boards", "board_members", "board_ops", "board_snapshots", "sessions", "ws_tickets", "bug_reports");

    private Backup() {}

    public static void main(String[] args) throws Exception {
        Config c = Config.fromEnv();
        try (var pool = Db.pool(c)) {
            switch (args.length == 0 ? "" : args[0]) {
                case "dump" -> {
                    Map<String, Object> m = dump(pool, c, Path.of(args[1]));
                    Files.writeString(Path.of(args[2]), Msg.encode(m), StandardCharsets.UTF_8);
                    System.out.println(Msg.encode(Msg.obj("boards", ((Map<?, ?>) m.get("boards")).size(), "rows", m.get("rows"))));
                }
                case "verify" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
                    List<String> problems = verify(pool, m);
                    System.out.println(Msg.encode(Msg.obj("boards_checked", ((Map<?, ?>) m.get("boards")).size(), "rows", m.get("rows"),
                            "problems", problems)));
                    if (!problems.isEmpty()) System.exit(1);
                }
                default -> {
                    System.err.println("usage: Backup dump <out.dump> <out.manifest.json> | Backup verify <manifest.json>");
                    System.exit(2);
                }
            }
        }
    }

    /** pg_dump and the manifest, both from one exported snapshot. */
    static Map<String, Object> dump(DataSource ds, Config c, Path out) throws SQLException, IOException, InterruptedException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            String snapshot;
            try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT pg_export_snapshot()")) {
                r.next();
                snapshot = r.getString(1);
            }
            // jdbc:postgresql://host:port/db → pg_dump's own arguments; the password via PGPASSWORD.
            java.net.URI u = java.net.URI.create(c.dbUrl().substring("jdbc:".length()));
            ProcessBuilder pb = new ProcessBuilder("pg_dump", "--format=custom", "--snapshot=" + snapshot, "--no-owner",
                    "-h", u.getHost(), "-p", Integer.toString(u.getPort() > 0 ? u.getPort() : 5432), "-U", c.dbUser(),
                    "-f", out.toString(), u.getPath().substring(1));
            pb.environment().put("PGPASSWORD", c.dbPassword());
            pb.inheritIO();
            Process p = pb.start();
            Map<String, Object> manifest = manifest(conn); // while pg_dump reads the same snapshot
            if (p.waitFor() != 0) throw new IOException("pg_dump failed with exit code " + p.exitValue());
            conn.commit();
            manifest.put("snapshot", snapshot);
            manifest.put("at", java.time.Instant.now().toString());
            return manifest;
        }
    }

    static Map<String, Object> manifest(Connection conn) throws SQLException {
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String t : TABLES) rows.put(t, count(conn, "SELECT count(*) FROM " + t));
        Map<String, Object> boards = new LinkedHashMap<>();
        for (String board : boards(conn)) {
            List<Op> ops = new ArrayList<>();
            try (PreparedStatement q = conn.prepareStatement("SELECT payload::text FROM board_ops WHERE board_id = ? ORDER BY version")) {
                q.setObject(1, UUID.fromString(board));
                try (ResultSet r = q.executeQuery()) {
                    while (r.next()) ops.add(Op.parse(Json.parse(r.getString(1))));
                }
            }
            boards.put(board, Msg.obj("version", version(conn, board), "ops", (long) ops.size(), "sha256", hash(ops)));
        }
        return Msg.obj("rows", rows, "boards", boards);
    }

    /** The restored database matches the manifest: counts, and every board two ways. */
    static List<String> verify(DataSource ds, Map<String, Object> manifest) throws SQLException {
        List<String> problems = new ArrayList<>();
        Store store = new Store(ds);
        try (Connection conn = ds.getConnection()) {
            Map<?, ?> rows = (Map<?, ?>) manifest.get("rows");
            for (String t : TABLES) {
                long n = count(conn, "SELECT count(*) FROM " + t);
                if (!Long.valueOf(n).equals(rows.get(t))) problems.add(t + ": " + n + " rows, manifest " + rows.get(t));
            }
            Map<?, ?> boards = (Map<?, ?>) manifest.get("boards");
            List<String> present = boards(conn);
            if (present.size() != boards.size()) problems.add("boards: " + present.size() + ", manifest " + boards.size());
            for (var e : boards.entrySet()) {
                String board = (String) e.getKey();
                String expected = (String) ((Map<?, ?>) e.getValue()).get("sha256");
                Store.Loaded loaded = store.load(board); // what a new owner would load: snapshot + tail
                String viaSnapshot = Msg.sha256Hex(loaded.board().canonical());
                List<Op> ops = store.opsAfter(board, 0, Integer.MAX_VALUE).stream().map(Store.Stored::op).toList();
                String viaReplay = hash(ops);
                if (!expected.equals(viaSnapshot)) problems.add(board + ": snapshot + ops " + viaSnapshot + ", manifest " + expected);
                if (!expected.equals(viaReplay)) problems.add(board + ": full replay " + viaReplay + ", manifest " + expected);
                if (loaded.corruptSnapshots() > 0) problems.add(board + ": " + loaded.corruptSnapshots() + " snapshot(s) failed their checksum");
            }
        }
        return problems;
    }

    private static String hash(List<Op> ops) {
        Board b = Board.empty();
        for (Op op : ops) b.apply(op);
        return Msg.sha256Hex(b.canonical());
    }

    private static List<String> boards(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT id::text FROM boards ORDER BY id")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    private static long version(Connection conn, String board) throws SQLException {
        try (PreparedStatement q = conn.prepareStatement("SELECT version FROM boards WHERE id = ?")) {
            q.setObject(1, UUID.fromString(board));
            try (ResultSet r = q.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
