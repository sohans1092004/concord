package dev.concord.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.concord.crdt.Board;
import dev.concord.crdt.Json;
import dev.concord.crdt.Keys;
import dev.concord.crdt.Op;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC §3.7: when a key passes 40 characters, the owner rebalances with ordinary ops. */
class RebalanceTest {
    @Test
    @SuppressWarnings("unchecked")
    void ownerRebalancesAListWhoseKeyPasses40Characters() throws Exception {
        try (Harness h = new Harness()) {
            String list = Ops.uuid();
            Board mirror = Board.empty(); // what a client would see, built from the channel
            var addList = h.ops.addList(list, "a0", "L");
            WritePathTest.ack(addList, h.submit(addList));
            mirror.apply(Op.parse(addList));
            int longest = 0, seen = 0;
            for (int i = 0; i < 300 && h.session.metrics.get("rebalances") == 0; i++) {
                // Always insert just after the first card: the gap that makes keys grow.
                List<Map<String, Object>> cards = (List<Map<String, Object>>) ((List<Map<String, Object>>) mirror.view().get("lists")).get(0).get("cards");
                List<String> keys = cards.stream().map(c -> (String) c.get("position_key")).toList();
                var plan = Keys.planInsert(keys, Math.min(1, keys.size()));
                for (var r : plan.rekeys()) {
                    var rk = h.ops.move((String) cards.get(r.index()).get("id"), list, r.key());
                    WritePathTest.ack(rk, h.submit(rk));
                    mirror.apply(Op.parse(rk));
                }
                var add = h.ops.addCard(Ops.uuid(), list, plan.key(), "c" + i);
                WritePathTest.ack(add, h.submit(add));
                mirror.apply(Op.parse(add));
                longest = Math.max(longest, plan.key().length());
            }
            assertTrue(h.session.metrics.get("rebalances") >= 1, "no rebalance after a key of " + longest);
            assertTrue(longest > Keys.MAX_KEY_LENGTH - 24, "keys grew to " + longest);
            // The owner's SET_PLACEMENTs were committed and published like any op.
            Thread.sleep(500);
            for (Map<String, Object> m : h.probe.channel)
                if ("OP_BROADCAST".equals(m.get("type"))) {
                    mirror.apply(Op.parse(m.get("op")));
                    seen++;
                }
            List<Map<String, Object>> cards = (List<Map<String, Object>>) ((List<Map<String, Object>>) mirror.view().get("lists")).get(0).get("cards");
            for (Map<String, Object> c : cards)
                assertTrue(((String) c.get("position_key")).length() <= 4, "after rebalance: " + c.get("position_key"));
            assertEquals(FencingTest.fromPostgres(h), mirror.canonical());
            assertEquals(Json.parse(mirror.canonical()), Json.parse((String) h.inspect(h.session).get("state")));
            assertTrue(seen > 0);
        }
    }
}
