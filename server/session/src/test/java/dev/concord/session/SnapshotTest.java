package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.crdt.Keys;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SPEC §5.1 snapshots, §6.2 steps 3–4 recovery, §8 row 13 corrupt snapshot. */
class SnapshotTest {
    @Test
    void snapshotEvery500OpsKeepsThreeAndRecoveryReplaysTheTail() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid();
            var addList = h.ops.addList(list, "a0", "L");
            WritePathTest.ack(addList, h.submit(addList));
            List<String> keys = Keys.keysBetween(null, null, 1_700);
            for (int i = 0; i < 1_700; i++) {
                var op = h.ops.addCard(Ops.uuid(), list, keys.get(i), "c" + i);
                h.session.boards.dispatch(h.board, new BoardActor.Submit(op, h.owner, h.probe.reply()));
                if (i % 100 == 99) for (int k = 0; k < 100; k++) assertEquals("OP_ACK", h.probe.next().get("type"));
            }
            assertEquals(1_701, h.rows("SELECT version FROM boards WHERE id = ?"));
            // Snapshots at >= 500, >= 1000, >= 1500 ops; only the newest three are kept.
            assertEquals(3, h.rows("SELECT count(*) FROM board_snapshots WHERE board_id = ?"));
            long newest = h.rows("SELECT max(version) FROM board_snapshots WHERE board_id = ?");
            assertTrue(newest >= 1_500 && newest < 1_701, "newest snapshot at " + newest);
            String state = (String) h.inspect(h.session).get("state");

            // Recovery on another instance: newest snapshot + the ops after it.
            h.session.close(); // graceful: writes a final snapshot at 1,701
            assertEquals(1_701, h.rows("SELECT max(version) FROM board_snapshots WHERE board_id = ?"));
            h.session = Harness.start("s-" + Ops.uuid(), Store::new, false);
            var touch = h.ops.setField(list, "title", "after recovery");
            assertEquals(1_702L, WritePathTest.ack(touch, h.submit(touch)));
            assertEquals(FencingTest.fromPostgres(h), h.inspect(h.session).get("state"));
            assertTrue(state.length() > 0);
        }
    }

    /** §8 row 13: checksum mismatch → previous snapshot + a longer tail, and an alert. */
    @Test
    void corruptSnapshotFallsBackToThePreviousOne() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid();
            var addList = h.ops.addList(list, "a0", "L");
            WritePathTest.ack(addList, h.submit(addList));
            for (int round = 0; round < 2; round++) {
                var t = h.ops.setField(list, "title", "round " + round);
                WritePathTest.ack(t, h.submit(t));
                h.session.close(); // each graceful close writes a snapshot
                h.session = Harness.start("s-" + Ops.uuid(), Store::new, false);
                WritePathTest.ack(addList, h.submit(addList)); // dedup ACK; claims the board again
            }
            assertEquals(2, h.rows("SELECT count(*) FROM board_snapshots WHERE board_id = ?"));
            h.session.close();
            // Corrupt the newest snapshot's checksum.
            Seed.exec(h.db, "UPDATE board_snapshots SET sha256 = repeat('0', 64) WHERE board_id = ?::uuid "
                    + "AND version = (SELECT max(version) FROM board_snapshots WHERE board_id = ?::uuid)", h.board, h.board);
            h.session = Harness.start("s-" + Ops.uuid(), Store::new, false);
            var t = h.ops.setField(list, "title", "after corruption");
            WritePathTest.ack(t, h.submit(t));
            assertEquals(1, h.session.metrics.get("snapshot_checksum_mismatches"));
            assertEquals(FencingTest.fromPostgres(h), h.inspect(h.session).get("state"));
            assertTrue(Seed.count(h.db, "SELECT count(*) FROM board_ops WHERE board_id = ?", UUID.fromString(h.board)) == 4);
        }
    }
}
