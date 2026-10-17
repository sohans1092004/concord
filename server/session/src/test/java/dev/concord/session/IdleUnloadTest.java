package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Amendment 26: an owner unloads a board nobody has open (no request for CONCORD_IDLE_UNLOAD_MS
 * and no gateway subscribed to its channel) through the graceful handover path: snapshot,
 * release the lease, close. Never while reconciling an unknown commit outcome. The Probe stands
 * in for a gateway: while it is subscribed, the board has a subscriber.
 */
class IdleUnloadTest {
    static final Map<String, String> IDLE = Map.of("CONCORD_IDLE_UNLOAD_MS", "1500");

    @Test
    void anIdleBoardIsUnloadedAndARejoinConverges() throws Exception {
        try (Harness h = new Harness(Store::new, false, IDLE)) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            for (int i = 0; i < 5; i++) assertEquals("OP_ACK", h.submit(h.ops.addCard(Ops.uuid(), (String) list.get("list_id"), "a" + i, "card " + i)).get("type"));
            Map<String, Object> before = h.inspect(h.session);

            // Subscribed (someone has it open): kept well past the idle time.
            Thread.sleep(3_000);
            assertNotNull(h.session.boards.actor(h.board), "a board with a subscriber stays loaded");
            assertEquals(0, h.session.metrics.get("idle_unloads"));

            h.probe.unsubscribe();
            awaitUnloaded(h);
            assertEquals(1, h.session.metrics.get("idle_unloads"));
            assertEquals(1, h.rows("SELECT count(*) FROM board_snapshots WHERE board_id = ? AND version = 6"), "snapshot at the last version");

            // Rejoin: claimed again, loaded from the snapshot, the same state as before and as Postgres.
            Map<String, Object> joined = h.join(h.owner, 0L);
            assertEquals("JOINED", joined.get("type"), () -> "got " + joined);
            assertEquals(6L, joined.get("version"));
            Map<String, Object> after = h.inspect(h.session);
            assertEquals(before, after);
            assertEquals(FencingTest.fromPostgres(h), after.get("state"));
            assertEquals(2, h.session.metrics.get("claims"));
            assertEquals("OP_ACK", h.submit(h.ops.setField((String) list.get("list_id"), "title", "after the rejoin")).get("type"));
        }
    }

    @Test
    void aResendAfterAnUnloadGetsItsOriginalAck() throws Exception {
        try (Harness h = new Harness(Store::new, false, IDLE)) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            String listId = (String) list.get("list_id");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            var card = h.ops.addCard(Ops.uuid(), listId, "a0", "sent once");
            var title = h.ops.setField(listId, "title", "renamed once");
            Map<String, Object> cardAck = h.submit(card), titleAck = h.submit(title);
            assertEquals(2L, cardAck.get("version"));
            assertEquals(3L, titleAck.get("version"));

            h.probe.unsubscribe();
            awaitUnloaded(h);

            // The new actor's ACK cache is empty: the primary key on board_ops answers. An ADD
            // that already exists fails validation, then the stored-op check finds it (§4.3 step 2);
            // a SET_FIELD reaches the commit, which finds it by its op_id.
            Map<String, Object> cardAgain = h.submit(card), titleAgain = h.submit(title);
            assertEquals("OP_ACK", cardAgain.get("type"), () -> "got " + cardAgain);
            assertEquals(2L, cardAgain.get("version"));
            assertEquals("OP_ACK", titleAgain.get("type"), () -> "got " + titleAgain);
            assertEquals(3L, titleAgain.get("version"));
            assertEquals(3, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"), "stored once each");
            assertEquals(2, h.session.metrics.get("duplicates"));
        }
    }

    @Test
    void neverUnloadedWhileReconcilingAnUnknownCommitOutcome() throws Exception {
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
            }, true, IDLE)) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            h.probe.unsubscribe(); // nobody has it open: only the reconciling rule keeps it
            BoardActor actor = h.session.boards.actor(h.board);

            cutAfterCommit.set(true);
            var op = h.ops.setField((String) list.get("list_id"), "title", "committed, reply lost");
            assertEquals("RETRY_LATER", h.submit(op).get("code"));

            // Well past the idle time, still reconciling, still loaded.
            Thread.sleep(6_000);
            assertSame(actor, h.session.boards.actor(h.board));
            assertEquals(BoardActor.State.RECONCILING, actor.state());
            assertEquals(0, h.session.metrics.get("idle_unloads"));

            proxy.toxics().get("blackhole").remove();
            Map<String, Object> resend = null;
            for (int i = 0; i < 60; i++) {
                resend = h.submit(op);
                if ("OP_ACK".equals(resend.get("type"))) break;
                Thread.sleep(250);
            }
            assertEquals("OP_ACK", resend.get("type"), "got " + resend);
            assertEquals(2L, resend.get("version"));
            assertSame(actor, h.session.boards.actor(h.board), "the same owner reconciled and kept serving");
            assertEquals(0, h.session.metrics.get("idle_unloads"));
            assertEquals(1, h.session.metrics.get("claims"));
        } finally {
            for (var t : proxy.toxics().getAll()) t.remove();
        }
    }

    private static void awaitUnloaded(Harness h) throws InterruptedException {
        for (int i = 0; i < 100 && h.session.boards.actor(h.board) != null; i++) Thread.sleep(100);
        assertNull(h.session.boards.actor(h.board), "unloaded within 10s of going idle");
    }
}
