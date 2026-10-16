package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.common.Msg;
import dev.concord.common.RedisKeys;
import dev.concord.crdt.Keys;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** SPEC §6.1 write path, §4.2 validation, §4.3 dedup, on one Session instance. */
class WritePathTest {
    static Map<String, Object> err(String code, Map<String, Object> reply) {
        assertEquals("ERROR", reply.get("type"), () -> "expected ERROR " + code + ", got " + reply);
        assertEquals(code, reply.get("code"), () -> "got " + reply);
        return reply;
    }

    static long ack(Map<String, Object> op, Map<String, Object> reply) {
        assertEquals("OP_ACK", reply.get("type"), () -> "expected OP_ACK, got " + reply);
        assertEquals(op.get("op_id"), reply.get("op_id"));
        return (Long) reply.get("version");
    }

    @Test
    void acksAfterCommitWithConsecutiveVersionsAndPublishesOnce() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid(), card = Ops.uuid();
            var a = h.ops.addList(list, "a0", "To Do");
            var b = h.ops.addCard(card, list, "a0", "Card");
            assertEquals(1, ack(a, h.submit(a)));
            assertEquals(2, ack(b, h.submit(b)));
            assertEquals(2, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));
            assertEquals(2, h.rows("SELECT version FROM boards WHERE id = ?"));
            Thread.sleep(200);
            assertEquals(1, h.probe.broadcastsOf((String) a.get("op_id")).size());
            assertEquals(2L, h.probe.broadcastsOf((String) b.get("op_id")).get(0).get("version"));
            assertEquals(2L, h.inspect(h.session).get("version"));
        }
    }

    /** §9.3: inject a commit failure; no ACK, no broadcast, no memory change. */
    @Test
    void noAckBeforeCommit() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
            @Override
            protected void beforeCommit() throws SQLException {
                if (fail.get()) throw new SQLException("injected commit failure");
            }
        })) {
            var list = h.ops.addList(Ops.uuid(), "a0", "To Do");
            ack(list, h.submit(list));
            String before = (String) h.inspect(h.session).get("state");

            fail.set(true);
            var card = h.ops.addCard(Ops.uuid(), (String) list.get("list_id"), "a0", "never stored");
            err(Msg.RETRY_LATER, h.submit(card));
            assertTrue(h.probe.quiet(300), "no ACK may follow the RETRY_LATER");
            assertEquals(before, h.inspect(h.session).get("state"), "memory must not change");
            assertEquals(1L, h.inspect(h.session).get("version"));
            assertEquals(0, h.probe.broadcastsOf((String) card.get("op_id")).size());
            assertEquals(1, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));

            fail.set(false); // the client's resend now commits, exactly once
            assertEquals(2, ack(card, h.submit(card)));
            Thread.sleep(200);
            assertEquals(1, h.probe.broadcastsOf((String) card.get("op_id")).size());
        }
    }

    /** §6.1 step 5: a transaction that would take over 2s is not committed and not ACKed. */
    @Test
    void commitOverTwoSecondsIsRetryLater() throws Exception {
        AtomicBoolean slow = new AtomicBoolean();
        try (Harness h = new Harness(ds -> new Store(ds) {
            @Override
            protected void beforeCommit() {
                if (slow.get()) {
                    try {
                        Thread.sleep(2_100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        })) {
            slow.set(true);
            var op = h.ops.addList(Ops.uuid(), "a0", "slow");
            err(Msg.RETRY_LATER, h.submit(op));
            assertEquals(0, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));
            assertEquals(0L, h.inspect(h.session).get("version"));
        }
    }

    /** §4.3 and §8 row 9: same op many times; original ACK, stored once, broadcast once. */
    @Test
    void dedupReturnsTheOriginalAckAndNeverRebroadcasts() throws Exception {
        try (Harness h = new Harness()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "To Do");
            var title = h.ops.setField((String) list.get("list_id"), "title", "Renamed");
            assertEquals(1, ack(list, h.submit(list)));
            assertEquals(2, ack(title, h.submit(title)));
            for (int i = 0; i < 3; i++) {
                assertEquals(1, ack(list, h.submit(list)));
                assertEquals(2, ack(title, h.submit(title)));
            }
            // A new owner has an empty LRU: the primary key is the backstop (§4.3 step 2).
            h.session.close();
            h.session = Harness.start("s-" + Ops.uuid(), Store::new, false);
            assertEquals(1, ack(list, h.submit(list))); // ADD for an existing ID, but it is the same op
            assertEquals(2, ack(title, h.submit(title))); // goes through the batch: step 3.1 drops it
            assertEquals(2, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));
            Thread.sleep(200);
            assertEquals(1, h.probe.broadcastsOf((String) list.get("op_id")).size());
            assertEquals(1, h.probe.broadcastsOf((String) title.get("op_id")).size());
            assertTrue(h.session.metrics.get("duplicates") >= 2);
        }
    }

    /** §4.2 and §8 row 12: malformed or malicious ops are rejected before persist. */
    @Test
    void rejectsInvalidOpsBeforePersist() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid(), card = Ops.uuid();
            var addList = h.ops.addList(list, "a0", "L");
            ack(addList, h.submit(addList));
            var addCard = h.ops.addCard(card, list, "a0", "C");
            ack(addCard, h.submit(addCard));
            List<Map<String, Object>> bad = new ArrayList<>();

            var tooLong = h.ops.setField(card, "title", "x".repeat(201));
            bad.add(err(Msg.INVALID_OP, h.submit(tooLong)));
            var nul = h.ops.setField(card, "description", "nul" + (char) 0);
            bad.add(err(Msg.INVALID_OP, h.submit(nul)));
            var otherBoard = h.ops.setField(card, "title", "t");
            otherBoard.put("board_id", Ops.uuid());
            bad.add(err(Msg.INVALID_OP, h.submit(otherBoard)));
            var extra = h.ops.setField(card, "title", "t");
            extra.put("evil", true);
            bad.add(err(Msg.INVALID_OP, h.submit(extra)));
            // §4.2 step 4 (amendment 6): references and admission rules.
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.addCard(Ops.uuid(), Ops.uuid(), "a0", "no such list"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.addList(list, "a1", "same list_id, new op"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.addCard(list, list, "a1", "a list's ID as a card"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.setField(list, "description", "lists have no description"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.setField(Ops.uuid(), "title", "names nothing"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.move(card, Ops.uuid(), "a1"))));
            bad.add(err(Msg.INVALID_OP, h.submit(h.ops.deleteCard(Ops.uuid(), List.of()))));
            // §3.1 skew guard: 60,001ms ahead is rejected with the server time.
            var skewed = h.ops.addListAt(System.currentTimeMillis() + 120_000, Ops.uuid());
            Map<String, Object> skew = err(Msg.CLOCK_SKEW, h.submit(skewed));
            assertNotNull(skew.get("server_time_ms"));
            for (Map<String, Object> e : bad) assertNotNull(e.get("op_id"));

            assertEquals(2, h.rows("SELECT count(*) FROM board_ops WHERE board_id = ?"));
            assertEquals(2L, h.inspect(h.session).get("version"));
        }
    }

    @Test
    void viewersAndNonMembersCannotEditAndRevocationTakesEffectOnTheNextOp() throws Exception {
        try (Harness h = new Harness(); var redis = Infra.redis()) {
            String viewer = Seed.user(h.db), editor = Seed.user(h.db), stranger = Seed.user(h.db);
            Seed.member(h.db, h.board, viewer, "viewer");
            Seed.member(h.db, h.board, editor, "editor");
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            ack(list, h.submit(h.session, list, editor));
            err(Msg.UNAUTHORIZED, h.submit(h.session, h.ops.setField((String) list.get("list_id"), "title", "v"), viewer));
            err(Msg.UNAUTHORIZED, h.submit(h.session, h.ops.setField((String) list.get("list_id"), "title", "s"), stranger));
            // Revoke the editor: the 30s cache is cleared by the membership channel (§4.2 step 2).
            Seed.removeMember(h.db, h.board, editor);
            redis.conn.sync().publish(RedisKeys.MEMBERSHIP_CHANNEL, h.board);
            Thread.sleep(300);
            err(Msg.UNAUTHORIZED, h.submit(h.session, h.ops.setField((String) list.get("list_id"), "title", "e"), editor));
        }
    }

    /** §6.1 step 3: ops arriving together share one transaction, with consecutive versions. */
    @Test
    void groupCommitBatchesOpsArrivingTogether() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid();
            List<Map<String, Object>> sent = new ArrayList<>();
            sent.add(h.ops.addList(list, "a0", "L")); // the cards below reference it within the batch
            List<String> keys = Keys.keysBetween(null, null, 49);
            for (int i = 0; i < 49; i++) sent.add(h.ops.addCard(Ops.uuid(), list, keys.get(i), "c" + i));
            long commitsBefore = h.session.metrics.get("commits");
            for (var op : sent) h.session.boards.dispatch(h.board, new BoardActor.Submit(op, h.owner, h.probe.reply()));
            Set<Long> versions = new HashSet<>();
            for (int i = 0; i < sent.size(); i++) {
                Map<String, Object> r = h.probe.next();
                assertEquals("OP_ACK", r.get("type"), () -> "got " + r);
                versions.add((Long) r.get("version"));
            }
            for (long v = 1; v <= 50; v++) assertTrue(versions.contains(v), "missing version " + v);
            long commits = h.session.metrics.get("commits") - commitsBefore;
            assertTrue(commits < 50, "expected batching, got " + commits + " commits for 50 ops");
        }
    }

    /** §7.3 step 3 and §7.5: JOINED carries ops after since_version, or a snapshot. */
    @Test
    void joinReturnsOpsOrSnapshotAndRequiresMembership() throws Exception {
        try (Harness h = new Harness()) {
            String viewer = Seed.user(h.db), stranger = Seed.user(h.db);
            Seed.member(h.db, h.board, viewer, "viewer");
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            ack(list, h.submit(list));
            var title = h.ops.setField((String) list.get("list_id"), "title", "T");
            ack(title, h.submit(title));

            Map<String, Object> fresh = h.join(viewer, null);
            assertEquals("JOINED", fresh.get("type"));
            assertEquals("viewer", fresh.get("role"));
            assertNotNull(fresh.get("snapshot"));
            assertNotNull(fresh.get("server_time_ms"));
            assertEquals(2L, fresh.get("version"));

            Map<String, Object> behind = h.join(viewer, 1L);
            List<?> ops = (List<?>) behind.get("ops");
            assertEquals(1, ops.size());
            assertEquals(2L, ((Map<?, ?>) ops.get(0)).get("version"));
            assertEquals(title.get("op_id"), ((Map<?, ?>) ((Map<?, ?>) ops.get(0)).get("op")).get("op_id"));

            err(Msg.UNAUTHORIZED, h.join(stranger, null));
        }
    }
}
