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
 * Brings the database to schema version {@link #VERSION}. A fresh database gets schema.sql
 * (exactly SPEC §5.1) and every version recorded at once; an older one gets each
 * migrations/N.sql in turn. Safe to re-run and to run from several processes at once: an
 * advisory lock serialises them and schema_migrations records what was applied.
 */
public final class Migrate {
    private static final Logger log = LoggerFactory.getLogger(Migrate.class);
    private static final long LOCK = 0x636f6e636f7264L; // "concord"
    static final int VERSION = 2;

    private Migrate() {}

    public static void main(String[] args) throws Exception {
        try (var pool = Db.pool(Config.fromEnv().check())) {
            run(pool);
        }
    }

    /** Returns true if this call changed the schema, false if it was already current. */
    public static boolean run(DataSource ds) throws SQLException, IOException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT pg_advisory_xact_lock(" + LOCK + ")");
                s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            }
            int current;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT coalesce(max(version), 0) FROM schema_migrations")) {
                r.next();
                current = r.getInt(1);
            }
            if (current >= VERSION) {
                c.commit();
                log.info("schema version {} already applied", current);
                return false;
            }
            try (Statement s = c.createStatement()) {
                if (current == 0) s.execute(resource("/schema.sql"));
                else for (int v = current + 1; v <= VERSION; v++) s.execute(resource("/migrations/" + v + ".sql"));
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)")) {
                for (int v = current + 1; v <= VERSION; v++) {
                    ins.setInt(1, v);
                    ins.executeUpdate();
                }
            }
            c.commit();
            log.info("schema version {} applied (was {})", VERSION, current);
            return true;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Migrate.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException(name + " missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
