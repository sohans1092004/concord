package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.crdt.Board;
import dev.concord.crdt.Json;
import dev.concord.crdt.Op;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * SPEC §6.3, §8 row 3, amendment 6: two owners at once. Owner A pauses with an op in its batch
 * (as under a long GC pause or {@code docker pause}), its lease expires, B claims, then A
 * resumes and tries to commit. A's commit must be rejected, and B's state must hold every op
 * that was ever ACKed.
 */
class FencingTest {
    @Test
    void staleOwnerCannotCommitAfterANewOwnerClaims() throws Exception {
        CountDownLatch paused = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicBoolean pauseNext = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
            @Override
            public Committed commit(String board, long fence, List<Pending> ops, long deadlineMs) throws SQLException {
                if (pauseNext.getAndSet(false)) {
                    paused.countDown();
                    try {
                        resume.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    deadlineMs = System.currentTimeMillis() + 2_000; // the pause is not A's commit time
                }
                return super.commit(board, fence, ops, deadlineMs);
            }
        }); var redis = Infra.redis()) {
            SessionMain a = h.session;
            String list = Ops.uuid();
            List<Map<String, Object>> acked = new ArrayList<>();
            var addList = h.ops.addList(list, "a0", "To Do");
            assertEquals("OP_ACK", h.submit(a, addList, h.owner).get("type"));
            acked.add(addList);
            var card = h.ops.addCard(Ops.uuid(), list, "a0", "acked by A");
            assertEquals("OP_ACK", h.submit(a, card, h.owner).get("type"));
            acked.add(card);
            long fenceA = a.boards.actor(h.board).fence();

            // A takes an op into its batch and pauses just before its commit transaction.
            pauseNext.set(true);
            var stale = h.ops.addCard(Ops.uuid(), list, "a1", "sent to A while paused");
            a.boards.dispatch(h.board, new BoardActor.Submit(stale, h.owner, h.probe.reply()));
            assertTrue(paused.await(10, TimeUnit.SECONDS));

            // A's lease expires; B claims and loads, but has not committed anything yet. This is
            // the window in which v3's claim order lost ops: Postgres still held A's fence.
            redis.conn.sync().del(RedisKeys.lease(h.board), RedisKeys.owner(h.board));
            try (SessionMain b = Harness.start("s-b-" + Ops.uuid(), Store::new, false)) {
                b.boards.dispatch(h.board, new BoardActor.Ensure(h.probe.reply()));
                assertEquals("OK", h.probe.next().get("type"));
                long fenceB = b.boards.actor(h.board).fence();
                assertTrue(fenceB > fenceA, "B's fence " + fenceB + " must exceed A's " + fenceA);

                // A resumes and commits with its old fence: zero rows, NOT_OWNER, nothing stored.
                resume.countDown();
                Map<String, Object> r = h.probe.next();
                assertEquals("ERROR", r.get("type"), () -> "stale owner ACKed: " + r);
                assertEquals(Msg.NOT_OWNER, r.get("code"));

                var fromB = h.ops.addCard(Ops.uuid(), list, "a2", "acked by B");
                assertEquals("OP_ACK", h.submit(b, fromB, h.owner).get("type"));
                acked.add(fromB);
                assertEquals(0, Seed.count(h.db, "SELECT count(*) FROM board_ops WHERE board_id = ? AND op_id = ?",
                        java.util.UUID.fromString(h.board), java.util.UUID.fromString((String) stale.get("op_id"))));
                Thread.sleep(200);
                assertEquals(0, h.probe.broadcastsOf((String) stale.get("op_id")).size(), "a fenced owner never broadcasts");
                waitClosed(a, h.board);
                assertNull(a.boards.actor(h.board), "A drops the board at once");
                assertEquals(1, a.metrics.get("fence_rejections"));

                // B's memory holds every ACKed op, and equals Postgres.
                Board expected = Board.empty();
                for (var op : acked) expected.apply(Op.parse(op));
                assertEquals(expected.canonical(), h.inspect(b).get("state"));
                assertEquals(expected.canonical(), fromPostgres(h));
            }
        }
    }

    static void waitClosed(SessionMain s, String board) throws InterruptedException {
        for (int i = 0; i < 100 && s.boards.actor(board) != null; i++) Thread.sleep(50);
    }

    /** The board rebuilt from board_ops alone. */
    static String fromPostgres(Harness h) throws SQLException {
        Board b = Board.empty();
        for (Store.Stored s : new Store(h.db).opsAfter(h.board, 0, Integer.MAX_VALUE)) b.apply(s.op());
        return b.canonical();
    }

    @Test
    void claimAfterRedisWipeRebuildsTheFenceFromPostgres() throws Exception {
        try (Harness h = new Harness(); var redis = Infra.redis()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            long before = h.session.boards.actor(h.board).fence();
            h.session.close();
            redis.conn.sync().del(RedisKeys.fence(h.board), RedisKeys.lease(h.board), RedisKeys.owner(h.board)); // the wipe, for this board
            h.session = Harness.start("s-" + Ops.uuid(), Store::new, false);
            var title = h.ops.setField((String) list.get("list_id"), "title", "after wipe");
            assertEquals("OP_ACK", h.submit(title).get("type"));
            long after = h.session.boards.actor(h.board).fence();
            assertTrue(after > before, "fence " + after + " must exceed " + before + " after a wipe");
            assertEquals(after, h.rows("SELECT fence FROM boards WHERE id = ?"));
            assertEquals(Long.toString(after), redis.conn.sync().get(RedisKeys.fence(h.board)));
            Map<?, ?> owner = (Map<?, ?>) Json.parse(redis.conn.sync().get(RedisKeys.owner(h.board)));
            assertEquals(after, owner.get("fence"));
        }
    }

    /**
     * Amendment 13: after a Redis wipe, a stale owner's renewal must not re-take the lease. If it
     * did, the real owner's next renewal would find a foreign lease and drop the board.
     */
    @Test
    void staleOwnerDoesNotRetakeAWipedLease() throws Exception {
        try (Harness h = new Harness(); var redis = Infra.redis()) {
            SessionMain a = h.session;
            assertEquals("OP_ACK", h.submit(h.ops.addList(Ops.uuid(), "a0", "L")).get("type"));
            long fenceA = a.boards.actor(h.board).fence();
            Ownership staleA = new Ownership(a.redis.conn.sync(), a.store, a.config.instanceId(), a.config.advertise(), a.metrics);
            var r = redis.conn.sync();
            r.del(RedisKeys.lease(h.board), RedisKeys.owner(h.board));
            try (SessionMain b = Harness.start("s-b-" + Ops.uuid(), Store::new, false)) {
                b.boards.dispatch(h.board, new BoardActor.Ensure(h.probe.reply()));
                assertEquals("OK", h.probe.next().get("type"));
                long fenceB = b.boards.actor(h.board).fence();
                waitClosed(a, h.board); // A's own renewer saw B's lease

                // Wipe just after B renews, so B's next renewal is ~5s away, then let A renew first.
                long ttl = r.pttl(RedisKeys.lease(h.board));
                for (long t; (t = r.pttl(RedisKeys.lease(h.board))) <= ttl; ttl = t) Thread.sleep(5);
                r.del(RedisKeys.lease(h.board), RedisKeys.owner(h.board));
                assertEquals(0, staleA.renew(h.board, fenceA), "a stale owner must not re-take the lease");
                assertNull(r.get(RedisKeys.lease(h.board)));

                // B's renewal re-takes it, and B keeps the board.
                for (int i = 0; i < 140 && r.get(RedisKeys.lease(h.board)) == null; i++) Thread.sleep(50);
                assertEquals(b.config.instanceId() + ":" + fenceB, r.get(RedisKeys.lease(h.board)));
                assertTrue(b.boards.actor(h.board) != null, "the real owner keeps the board");
            }
        }
    }
}
