package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Amendment 9: a board's mailbox is bounded; once full, new requests get RETRY_LATER. */
class MailboxLimitTest {
    @Test
    void aFullMailboxAnswersRetryLater() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
            @Override
            public Committed commit(String board, long fence, List<Pending> ops, long deadlineMs) throws SQLException {
                if (block.getAndSet(false)) {
                    stuck.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    deadlineMs = System.currentTimeMillis() + 2_000;
                }
                return super.commit(board, fence, ops, deadlineMs);
            }
        })) {
            String list = Ops.uuid();
            var first = h.ops.addList(list, "a0", "L");
            assertEquals("OP_ACK", h.submit(first).get("type"));
            block.set(true); // the actor's next commit hangs, so nothing drains its mailbox
            h.session.boards.dispatch(h.board, new BoardActor.Submit(h.ops.setField(list, "title", "t"), h.owner, h.probe.reply()));
            assertTrue(stuck.await(10, TimeUnit.SECONDS));
            int extra = 500;
            for (int i = 0; i < BoardActor.MAILBOX_LIMIT + extra; i++)
                h.session.boards.dispatch(h.board, new BoardActor.Submit(h.ops.setField(list, "title", "t" + i), h.owner, h.probe.reply()));
            int overloaded = 0;
            for (Map<String, Object> m; (m = h.probe.replies.poll(2, TimeUnit.SECONDS)) != null; )
                if (Msg.RETRY_LATER.equals(m.get("code")) && "board is overloaded".equals(m.get("detail"))) overloaded++;
                else break;
            assertEquals(extra, overloaded, "exactly the requests past the limit are refused");
            assertEquals(extra, h.session.metrics.get("mailbox_full"));
            release.countDown();
            // Everything that was accepted is then served.
            int acks = 0;
            for (Map<String, Object> m; (m = h.probe.replies.poll(20, TimeUnit.SECONDS)) != null && acks < BoardActor.MAILBOX_LIMIT + 1; )
                if ("OP_ACK".equals(m.get("type"))) acks++;
            assertEquals(BoardActor.MAILBOX_LIMIT + 1, acks);
        }
    }
}
