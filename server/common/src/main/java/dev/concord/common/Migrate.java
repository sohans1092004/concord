package dev.concord.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies schema.sql (SPEC §5.1) once. Safe to re-run and to run from several processes at once:
 * an advisory lock serialises them and schema_migrations records what was applied.
 */
public final class Migrate {
    private static final Logger log = LoggerFactory.getLogger(Migrate.class);
    private static final long LOCK = 0x636f6e636f7264L; // "concord"
    static final int VERSION = 1;

    private Migrate() {}

    public static void main(String[] args) throws Exception {
        try (var pool = Db.pool(Config.fromEnv().check())) {
            run(pool);
        }
    }

    /** Returns true if this call applied the schema, false if it was already there. */
    public static boolean run(DataSource ds) throws SQLException, IOException {
        String schema;
        try (InputStream in = Migrate.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IOException("schema.sql missing from the classpath");
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT pg_advisory_xact_lock(" + LOCK + ")");
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            }
            try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
                q.setInt(1, VERSION);
                try (ResultSet r = q.executeQuery()) {
                    if (r.next()) {
                        c.commit();
                        log.info("schema version {} already applied", VERSION);
                        return false;
                    }
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute(schema);
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)")) {
                ins.setInt(1, VERSION);
                ins.executeUpdate();
            }
            c.commit();
            log.info("schema version {} applied", VERSION);
            return true;
        }
    }
}
