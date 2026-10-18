package dev.concord.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import dev.concord.session.Backup;
import dev.concord.session.BoardActor;
import dev.concord.session.Harness;
import dev.concord.session.Ops;
import dev.concord.session.Store;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Op retention (SPEC §5.1, amendment 28) on a real board: 1,600 ops, three kept snapshots, every
 * op up to the newest snapshot backdated past 35 days. Only ops at or below the OLDEST kept
 * snapshot may go; the board then loads the same, even falling back past two corrupt snapshots;
 * a client behind the oldest kept op gets a full snapshot; the backup manifest still verifies.
 */
class RetentionTest {
    @Test
    void deletesOnlyOldOpsAtOrBelowTheOldestKeptSnapshot() throws Exception {
        try (Harness h = new Harness()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            String listId = (String) list.get("list_id");
            List<Map<String, Object>> ops = new ArrayList<>(List.of(list));
            for (int i = 1; i < 1_600; i++) ops.add(h.ops.setField(listId, "title", "title " + i));
            // In chunks of 500, each waiting for its snapshot (every 500 ops, §5.1), so the three
            // kept snapshots are at exactly 500, 1,000 and 1,500, with 100 ops above the newest.
            for (int k = 0; k < 3; k++) {
                pump(h, ops.subList(500 * k, 500 * (k + 1)));
                awaitSnapshot(h, 500L * (k + 1));
            }
            pump(h, ops.subList(1_500, 1_600));
            long version = 1_600, oldest = 500, newest = 1_500;
            assertEquals(List.of(500L, 1_000L, 1_500L), snapshots(h));
            String state = (String) h.inspect(h.session).get("state");

            // Everything up to the NEWEST snapshot is older than 35 days; the rest is recent.
            exec(h, "UPDATE board_ops SET committed_at = now() - interval '40 days' WHERE board_id = ? AND version <= " + newest);

            long deleted = Retention.prune(h.db, Retention.DAYS);
            assertEquals(oldest, deleted, "only ops 1..500: the oldest kept snapshot, not the newest (1,500)");
            assertEquals(oldest + 1, scalar(h, "SELECT min(version) FROM board_ops WHERE board_id = ?"));
            assertEquals(version - oldest, scalar(h, "SELECT count(*) FROM board_ops WHERE board_id = ?"));
            assertEquals(0, Retention.prune(h.db, Retention.DAYS), "idempotent");

            // The backup's independent replay starts from the oldest kept snapshot, and verifies.
            Map<String, Object> manifest;
            try (Connection c = h.db.getConnection()) {
                manifest = Backup.manifest(c);
            }
            assertEquals(Msg.sha256Hex(state), ((Map<?, ?>) ((Map<?, ?>) manifest.get("boards")).get(h.board)).get("sha256"));
            assertEquals(List.of(), Backup.verify(h.db, manifest));

            // A client behind the oldest kept op (it needs op 500, deleted) gets a full snapshot,
            // although 1,101 ops is within the 2,000-op catch-up limit; one after it gets ops.
            Map<String, Object> behind = h.join(h.owner, oldest - 1);
            assertNotNull(behind.get("snapshot"), () -> "got " + behind.keySet());
            Map<String, Object> recent = h.join(h.owner, version - 10);
            assertNull(recent.get("snapshot"));
            assertEquals(10, ((List<?>) recent.get("ops")).size());

            // The corrupt-snapshot fallback (§8 row 13) still loads the same board: the two newest
            // snapshots fail their checksum, so it replays from the oldest kept one.
            exec(h, "UPDATE board_snapshots SET sha256 = 'corrupt' WHERE board_id = ? AND version > " + oldest);
            Store.Loaded loaded = new Store(h.db).load(h.board);
            assertEquals(2, loaded.corruptSnapshots());
            assertEquals(version, loaded.version());
            assertEquals(state, loaded.board().canonical(), "the fallback replays a complete tail");
        }
    }

    private static List<Long> snapshots(Harness h) throws Exception {
        List<Long> v = new ArrayList<>();
        try (Connection c = h.db.getConnection();
                PreparedStatement q = c.prepareStatement("SELECT version FROM board_snapshots WHERE board_id = ? ORDER BY version")) {
            q.setObject(1, UUID.fromString(h.board));
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) v.add(r.getLong(1));
            }
        }
        return v;
    }

    private static void awaitSnapshot(Harness h, long version) throws Exception {
        for (int i = 0; i < 100 && !snapshots(h).contains(version); i++) Thread.sleep(100);
        assertTrue(snapshots(h).contains(version), "a snapshot at " + version + ", found " + snapshots(h));
    }

    /** Submits without waiting op by op (the actor batches), in chunks under the mailbox limit. */
    private static void pump(Harness h, List<Map<String, Object>> ops) throws InterruptedException {
        for (int from = 0; from < ops.size(); from += 1_000) {
            List<Map<String, Object>> chunk = ops.subList(from, Math.min(ops.size(), from + 1_000));
            CountDownLatch done = new CountDownLatch(chunk.size());
            AtomicInteger acks = new AtomicInteger();
            for (var op : chunk) h.session.boards.dispatch(h.board, new BoardActor.Submit(op, h.owner, m -> {
                if ("OP_ACK".equals(m.get("type"))) acks.incrementAndGet();
                done.countDown();
            }));
            assertTrue(done.await(120, TimeUnit.SECONDS));
            assertEquals(chunk.size(), acks.get(), "every op ACKed");
        }
    }

    private static void exec(Harness h, String sql) throws Exception {
        try (Connection c = h.db.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, UUID.fromString(h.board));
            p.executeUpdate();
        }
    }

    private static long scalar(Harness h, String sql) throws Exception {
        try (Connection c = h.db.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, UUID.fromString(h.board));
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
