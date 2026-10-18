package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.concord.common.Msg;
import dev.concord.common.Redis;
import dev.concord.common.RedisKeys;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Amendment 27: a board deleted while its owner has it loaded (an account deletion takes the
 * boards only that user belonged to) is dropped: announced, at once; otherwise at the next commit,
 * reconcile or snapshot. Never a fence alert, never an endless reconcile, never a snapshot error.
 */
class BoardDeletedTest {
    /** What Accounts.delete does to a solo board, in one transaction. */
    static void deleteBoard(DataSource db, String board) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            for (String t : new String[] {"bug_reports", "board_snapshots", "board_ops", "board_members"}) {
                try (PreparedStatement p = c.prepareStatement("DELETE FROM " + t + " WHERE board_id = ?")) {
                    p.setObject(1, UUID.fromString(board));
                    p.executeUpdate();
                }
            }
            try (PreparedStatement p = c.prepareStatement("DELETE FROM boards WHERE id = ?")) {
                p.setObject(1, UUID.fromString(board));
                p.executeUpdate();
            }
            c.commit();
        }
    }

    @Test
    void anAnnouncedDeletionDropsTheBoardAtOnceWithNoSnapshot() throws Exception {
        try (Harness h = new Harness(); Redis redis = Infra.redis()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            long snapshots = h.session.metrics.get("snapshots");

            deleteBoard(h.db, h.board);
            redis.conn.sync().publish(RedisKeys.BOARD_DELETED_CHANNEL, h.board);
            awaitDropped(h);

            assertEquals(1, h.session.metrics.get("boards_deleted"));
            assertEquals(snapshots, h.session.metrics.get("snapshots"), "no snapshot of a deleted board");
            assertEquals(0, h.session.metrics.get("fence_rejections"));
            Map<String, Object> join = h.join(h.owner, 0L);
            assertEquals(Msg.UNAUTHORIZED, join.get("code"), () -> "got " + join);
        }
    }

    @Test
    void anEditCommittingAfterTheDeletionIsRefusedNotAFenceAlert() throws Exception {
        try (Harness h = new Harness()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            BoardActor actor = h.session.boards.actor(h.board);

            // Deleted without the announcement (Redis lost it): the commit finds out.
            deleteBoard(h.db, h.board);
            var edit = h.ops.setField((String) list.get("list_id"), "title", "after the deletion");
            Map<String, Object> reply = h.submit(edit);
            assertEquals(Msg.UNAUTHORIZED, reply.get("code"), () -> "got " + reply);
            assertEquals("board deleted", reply.get("detail"));
            awaitDropped(h);
            assertEquals(BoardActor.State.CLOSED, actor.state());
            assertEquals(0, h.session.metrics.get("fence_rejections"), "a deleted board is not a newer owner");
            assertEquals(1, h.session.metrics.get("boards_deleted"));
        }
    }

    @Test
    void reconcilingOnADeletedBoardEndsInsteadOfRetryingForever() throws Exception {
        var proxy = Infra.pgProxy();
        AtomicBoolean cutAfterCommit = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
                @Override
                protected void beforeCommit() throws SQLException {
                    if (!cutAfterCommit.getAndSet(false)) return;
                    try {
                        proxy.toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0); // the reply is lost
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }, true)) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            BoardActor actor = h.session.boards.actor(h.board);

            cutAfterCommit.set(true);
            assertEquals(Msg.RETRY_LATER, h.submit(h.ops.setField((String) list.get("list_id"), "title", "outcome unknown")).get("code"));
            assertEquals(BoardActor.State.RECONCILING, actor.state());

            deleteBoard(h.db, h.board); // h.db is direct, not through the proxy
            proxy.toxics().get("blackhole").remove();

            awaitDropped(h);
            assertSame(BoardActor.State.CLOSED, actor.state());
            assertEquals(1, h.session.metrics.get("boards_deleted"));
        } finally {
            for (var t : proxy.toxics().getAll()) t.remove();
        }
    }

    private static void awaitDropped(Harness h) throws InterruptedException {
        for (int i = 0; i < 150 && h.session.boards.actor(h.board) != null; i++) Thread.sleep(100);
        assertNull(h.session.boards.actor(h.board), "the deleted board is dropped within 15s");
    }
}
