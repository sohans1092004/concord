package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import dev.concord.common.Migrate;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** The schema is applied once; re-running is a no-op; an old database upgrades to the same schema as a fresh one. */
class MigrateTest {
    static final List<String> TABLES = List.of("users", "boards", "board_members", "board_ops", "board_snapshots", "sessions", "ws_tickets", "bug_reports");

    @Test
    void migrateIsSafeToReRunConcurrently() throws Exception {
        var db = Infra.db(); // already migrated once by Infra.start()
        try (var pool = Executors.newFixedThreadPool(3)) {
            List<Future<Boolean>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) runs.add(pool.submit((Callable<Boolean>) () -> Migrate.run(db)));
            for (Future<Boolean> f : runs) assertFalse(f.get(), "schema must not be applied twice");
        }
        assertEquals(2, Seed.count(db, "SELECT count(*) FROM schema_migrations"));
        for (String t : TABLES)
            assertEquals(1, Seed.count(db, "SELECT count(*) FROM information_schema.tables WHERE table_name = ? AND table_schema = 'public'", t));
    }

    @Test
    void aVersion1DatabaseUpgradesToTheFreshSchema() throws Exception {
        String name = "upgrade_" + Ops.uuid().replace("-", "");
        try (Connection c = Infra.db().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        HikariDataSource old = new HikariDataSource();
        old.setJdbcUrl(Infra.PG.getJdbcUrl().replace("/concord", "/" + name));
        old.setUsername("concord");
        old.setPassword("concord");
        try (old) {
            try (Connection c = old.getConnection(); Statement s = c.createStatement();
                 var in = MigrateTest.class.getResourceAsStream("/schema-v1.sql")) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                s.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                s.execute("INSERT INTO schema_migrations (version) VALUES (1)");
                s.execute("INSERT INTO users (id, email, password_hash) VALUES (gen_random_uuid(), 'Old@Example.com', 'x')");
            }
            assertTrue(Migrate.run(old));
            assertFalse(Migrate.run(old));
            assertEquals(columns(Infra.db()), columns(old));
            assertEquals(1, Seed.count(old, "SELECT count(*) FROM users WHERE email = 'old@example.com' AND name = ''"));
        }
    }

    /** Every column of every table: name, type, nullability, default. */
    static List<String> columns(javax.sql.DataSource ds) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT table_name, column_name, data_type, is_nullable, coalesce(column_default, '') FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name <> 'schema_migrations' ORDER BY table_name, column_name")) {
            while (r.next()) out.add(r.getString(1) + "." + r.getString(2) + " " + r.getString(3) + " " + r.getString(4) + " " + r.getString(5));
        }
        return out;
    }
}
