package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phase 4a step 5: a restore is verified against the manifest written with the dump. Here the
 * manifest is taken from the live database and checked against it; then the data is damaged,
 * and the check must say so. (The pg_dump/pg_restore path itself runs in the rehearsal.)
 */
class BackupTest {
    @Test
    void verifyPassesOnTheSameDataAndCatchesDamage() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid();
            assertEquals("OP_ACK", h.submit(h.ops.addList(list, "a0", "To do")).get("type"));
            for (int i = 0; i < 5; i++) assertEquals("OP_ACK", h.submit(h.ops.addCard(Ops.uuid(), list, "a" + i, "card " + i)).get("type"));
            // A snapshot in the middle, so a load is snapshot + tail.
            BoardActor a = h.session.boards.actor(h.board);
            new Store(h.db).writeSnapshot(h.board, 6, (String) a.inspect().get().get("state"));
            assertEquals("OP_ACK", h.submit(h.ops.setField(list, "title", "Doing")).get("type"));

            Map<String, Object> manifest;
            try (Connection c = h.db.getConnection()) {
                c.setAutoCommit(false);
                manifest = Backup.manifest(c);
                c.commit();
            }
            // The shared test database holds other tests' boards (some deliberately corrupt), so
            // judge only this board; the row counts are the whole database's, taken just now.
            List<String> before = Backup.verify(h.db, manifest);
            assertTrue(before.stream().noneMatch(p -> p.contains(h.board) || p.contains("rows")), before.toString());

            // Damage an op from before the snapshot: the load (snapshot + tail) still looks fine,
            // only the replay from the oldest base (here version 0: nothing pruned) shows it.
            Seed.count(h.db, "WITH x AS (UPDATE board_ops SET payload = jsonb_set(payload, '{title}', '\"tampered\"') "
                    + "WHERE board_id = ? AND version = 2 RETURNING 1) SELECT count(*) FROM x", UUID.fromString(h.board));
            List<String> problems = Backup.verify(h.db, manifest);
            assertTrue(problems.stream().anyMatch(p -> p.contains(h.board) && p.contains("replay from the oldest base")), problems.toString());

            // Damage the snapshot: its checksum fails, and the problem is reported.
            Seed.count(h.db, "WITH x AS (UPDATE board_snapshots SET state = jsonb_set(state, '{registers}', '{}') "
                    + "WHERE board_id = ? RETURNING 1) SELECT count(*) FROM x", UUID.fromString(h.board));
            problems = Backup.verify(h.db, manifest);
            assertTrue(problems.stream().anyMatch(p -> p.contains(h.board) && p.contains("checksum")), problems.toString());
        }
    }
}
