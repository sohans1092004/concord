package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SPEC §6.3: a stale owner that never writes is never fenced. Until its next lease renewal it
 * answers JOIN from stale memory: a prefix of the committed history, so a client is behind,
 * never wrong. The renewal finds another owner's lease and drops the board.
 */
class StaleReadTest {
    @Test
    void staleOwnerServesAnOlderVersionUntilItsNextRenewalDropsIt() throws Exception {
        try (Harness h = new Harness(); var redis = Infra.redis()) {
            SessionMain a = h.session;
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            assertEquals("OP_ACK", h.submit(list).get("type"));
            assertEquals(1L, ((Number) h.join(h.owner, null).get("version")).longValue());

            // Take over just after A renews, so a whole renewal interval lies ahead of us.
            var r = redis.conn.sync();
            long ttl = r.pttl(RedisKeys.lease(h.board));
            for (long t; (t = r.pttl(RedisKeys.lease(h.board))) <= ttl; ttl = t) Thread.sleep(5);
            long tookOver = System.currentTimeMillis();
            r.del(RedisKeys.lease(h.board), RedisKeys.owner(h.board));

            try (SessionMain b = Harness.start("s-b-" + Ops.uuid(), Store::new, false)) {
                var title = h.ops.setField((String) list.get("list_id"), "title", "renamed on B");
                assertEquals("OP_ACK", h.submit(b, title, h.owner).get("type"));

                // A was never fenced: it answers JOIN, from memory, one version behind.
                Map<String, Object> stale = h.join(h.owner, null);
                assertTrue(System.currentTimeMillis() - tookOver < RedisKeys.RENEW_MS, "the JOIN must land before A's next renewal");
                assertEquals("JOINED", stale.get("type"));
                assertEquals(1L, ((Number) stale.get("version")).longValue());
                assertEquals(0, a.metrics.get("fence_rejections"));

                // A's next renewal sees B's lease and drops the board; it then answers NOT_OWNER.
                for (int i = 0; i < 120 && a.boards.actor(h.board) != null; i++) Thread.sleep(50);
                assertNull(a.boards.actor(h.board), "A drops the board at its next renewal");
                Map<String, Object> after = h.join(h.owner, null);
                assertEquals(Msg.NOT_OWNER, after.get("code"), () -> "after the drop: " + after);
            }
        }
    }
}
