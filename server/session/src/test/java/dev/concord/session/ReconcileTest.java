package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Amendment 6 (B): the connection to Postgres is cut after COMMIT is sent, so the commit lands
 * but its reply never arrives. The owner must not lose the op: it reconciles, then the op is
 * applied, published and ACKed exactly once from the client's point of view.
 */
class ReconcileTest {
    @Test
    void commitWhoseReplyIsLostIsAppliedPublishedAndAckedExactlyOnce() throws Exception {
        var proxy = Infra.pgProxy();
        AtomicBoolean cutAfterCommit = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
                @Override
                protected void beforeCommit() throws SQLException {
                    if (!cutAfterCommit.getAndSet(false)) return;
                    try {
                        // Everything Postgres sends from now on is dropped: COMMIT goes out, its
                        // reply never comes back.
                        proxy.toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }, true)) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));

            cutAfterCommit.set(true);
            var op = h.ops.setField((String) list.get("list_id"), "title", "committed, reply lost");
            Map<String, Object> first = h.submit(op);
            assertEquals(Msg.RETRY_LATER, first.get("code"), () -> "got " + first);
            // It did commit: Postgres has it, memory does not yet.
            assertEquals(2, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));

            // While reconciling, the actor answers RETRY_LATER and takes no snapshot.
            Thread.sleep(500);
            long snapshots = h.session.metrics.get("snapshots");
            var during = h.ops.setField((String) list.get("list_id"), "title", "while reconciling");
            assertEquals(Msg.RETRY_LATER, h.submit(during).get("code"));

            proxy.toxics().get("blackhole").remove(); // the network heals

            // The client resends under the same op_id: ACKed once, with the version it was given.
            Map<String, Object> resend = null;
            for (int i = 0; i < 60; i++) {
                resend = h.submit(op);
                if ("OP_ACK".equals(resend.get("type"))) break;
                Thread.sleep(250);
            }
            assertEquals("OP_ACK", resend.get("type"), "got " + resend);
            assertEquals(2L, resend.get("version"));
            Thread.sleep(300);
            assertEquals(1, h.probe.broadcastsOf((String) op.get("op_id")).size(), "published exactly once");
            assertEquals(2L, h.inspect(h.session).get("version"));
            assertEquals(FencingTest.fromPostgres(h), h.inspect(h.session).get("state"), "memory equals Postgres");
            assertEquals(snapshots, h.session.metrics.get("snapshots"));
            assertEquals(2, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));
            assertTrue(h.session.metrics.get("commit_failures") >= 1);
        } finally {
            for (var t : proxy.toxics().getAll()) t.remove();
        }
    }
}
