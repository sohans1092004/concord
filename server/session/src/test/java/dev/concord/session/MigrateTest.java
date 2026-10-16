package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.concord.common.Migrate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** schema.sql is applied once; re-running, even from several processes at once, is a no-op. */
class MigrateTest {
    @Test
    void migrateIsSafeToReRunConcurrently() throws Exception {
        var db = Infra.db(); // already migrated once by Infra.start()
        try (var pool = Executors.newFixedThreadPool(3)) {
            List<Future<Boolean>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) runs.add(pool.submit((Callable<Boolean>) () -> Migrate.run(db)));
            for (Future<Boolean> f : runs) assertFalse(f.get(), "schema must not be applied twice");
        }
        assertEquals(1, Seed.count(db, "SELECT count(*) FROM schema_migrations"));
        for (String t : List.of("users", "boards", "board_members", "board_ops", "board_snapshots"))
            assertEquals(1, Seed.count(db, "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", t));
    }
}
