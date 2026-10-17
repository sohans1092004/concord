package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Board state that grows by design (Phase 4a, amendment 26): measured on a real owner, as the
 * JVM's heap after a full GC between phases. Run by hand; heap deltas are too noisy for every CI run:
 *   cd server && MEASURE_GROWTH=1 ./gradlew :session:test --tests '*StateGrowthTest*' -i | grep GROWTH
 * Phases on one board:
 *   0. a list and 50 cards;
 *   A1. 10,000 edits (titles, descriptions, moves) of those 50 cards: the ACK cache fills (§4.3);
 *   A2. 10,000 more: the cache is full and the cards are fixed, so this should cost nothing;
 *   B. 2,500 cards added and deleted: what each deleted card keeps (tombstone, registers; decision 9).
 */
@EnabledIfEnvironmentVariable(named = "MEASURE_GROWTH", matches = "1")
class StateGrowthTest {
    @Test
    void measuresBytesPerOpAndPerDeletedCard() throws Exception {
        try (Harness h = new Harness()) {
            var list = h.ops.addList(Ops.uuid(), "a0", "L");
            String listId = (String) list.get("list_id");
            List<Map<String, Object>> setup = new ArrayList<>(List.of(list));
            List<String> cards = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String c = Ops.uuid();
                cards.add(c);
                setup.add(h.ops.addCard(c, listId, "a" + (i % 9 + 1), "card " + i));
            }
            pump(h, setup);
            // The Probe keeps every broadcast it hears; in this JVM that would be measured as the
            // board's growth (a first run read ~2.7 KB per op from exactly that). Stop it listening.
            h.probe.unsubscribe();
            h.probe.channel.clear();
            long h0 = heap(), s0 = snapshotBytes(h);

            pump(h, edits(h, cards, listId, 10_000, 0));
            long h1 = heap(), s1 = snapshotBytes(h);
            pump(h, edits(h, cards, listId, 10_000, 10_000));
            long h2 = heap(), s2 = snapshotBytes(h);

            List<Map<String, Object>> churn = new ArrayList<>();
            for (int i = 0; i < 2_500; i++) {
                var add = h.ops.addCard(Ops.uuid(), listId, "a5", "short-lived " + i);
                churn.add(add);
                churn.add(h.ops.deleteCard((String) add.get("card_id"), List.of((String) add.get("op_id"))));
            }
            pump(h, churn);
            long h3 = heap(), s3 = snapshotBytes(h);

            double perOpFilling = (h1 - h0) / (10_000.0 - 0), perOpFull = (h2 - h1) / 10_000.0, perDeleted = (h3 - h2) / 2_500.0;
            System.out.printf("GROWTH heap_after_gc_bytes=[%d, %d, %d, %d] snapshot_bytes=[%d, %d, %d, %d]%n", h0, h1, h2, h3, s0, s1, s2, s3);
            System.out.printf("GROWTH per_op_while_ack_cache_fills=%.0f B, per_op_cache_full_fixed_cards=%.1f B, per_deleted_card=%.0f B (heap), %.0f B (snapshot)%n",
                    perOpFilling, perOpFull, perDeleted, (s3 - s2) / 2_500.0);
            assertTrue(Math.abs(perOpFull) < 50, "editing fixed cards with a full ACK cache costs ~0 bytes per op, got " + perOpFull);
        }
    }

    private static List<Map<String, Object>> edits(Harness h, List<String> cards, String listId, int n, int from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            String c = cards.get(i % cards.size());
            out.add(switch (i % 3) {
                case 0 -> h.ops.setField(c, "title", "title " + i);
                case 1 -> h.ops.setField(c, "description", "description " + i);
                default -> h.ops.move(c, listId, "a" + (i % 9 + 1));
            });
        }
        return out;
    }

    /** Submits everything without waiting op by op (the actor batches), in chunks under the mailbox limit. */
    private static void pump(Harness h, List<Map<String, Object>> ops) throws InterruptedException {
        for (int from = 0; from < ops.size(); from += 2_000) {
            List<Map<String, Object>> chunk = ops.subList(from, Math.min(ops.size(), from + 2_000));
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

    private static long heap() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(300);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long snapshotBytes(Harness h) throws Exception {
        return ((String) h.inspect(h.session).get("state")).length();
    }
}
