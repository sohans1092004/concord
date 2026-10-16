package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Permutation fuzz (SPEC §9.1), the Java port of crdt-ts/test/fuzz/sim.ts with the same
 * weights. Each seed simulates 3–5 replicas making 50–500 ops from partial views, then delivers
 * the missing ops to every replica in its own random order with ~20% duplicates, plus two fresh
 * replicas. All canonical states must be byte-identical, equal to the merge of the pre-delivery
 * states, and no LWW tie-break may fire. FUZZ_RUNS sets the seed count, FUZZ_SEED the first seed.
 */
class FuzzTest {
    static final long T0 = 1760000000000L;
    static final int REBALANCE_AT = 40;
    static final String[] TITLES = {"a", "b", "Ünïcødé", "😀 emoji", "tab\there", "line\nbreak", ""};
    static final String[] DESCRIPTIONS = {"", "d1", "longer description", "\u0000ctl"};
    static final List<List<String>> LABELS = List.of(List.of(), List.of("bug"), List.of("bug", "p1"), List.of("p1", "ux"), List.of("é"));

    static String id(String prefix, Random r) {
        StringBuilder b = new StringBuilder(prefix).append('-');
        for (int i = 0; i < 22; i++) {
            if (i == 4) b.append("-4");
            else if (i == 7) b.append("-8");
            else if (i == 10) b.append('-');
            b.append(Character.forDigit(r.nextInt(16), 16));
        }
        return b.toString();
    }

    static final class Replica {
        Board board = Board.empty();
        Hlc hlc;
        final Set<Integer> known = new HashSet<>();
        final List<String> lists = new ArrayList<>(), cards = new ArrayList<>();

        Replica(String id) {
            hlc = Hlc.zero(id);
        }
    }

    record Generated(List<Op> ops, List<Board> partial, List<List<Integer>> unseen, int collisions) {}

    @SuppressWarnings("unchecked")
    static Generated generate(long seed, int nReplicas, int nSteps, boolean sameGap, boolean dominant) {
        Random rnd = new Random(seed);
        List<Replica> reps = new ArrayList<>();
        long[] skews = new long[nReplicas];
        for (int i = 0; i < nReplicas; i++) {
            reps.add(new Replica(id("a0000000", rnd)));
            skews[i] = rnd.nextInt(179_001) - 120_000;
        }
        String board = id("b0a4d000", rnd);
        List<Op> ops = new ArrayList<>();
        int[] collisions = {0};
        long t = 0;
        for (int step = 0; step < nSteps; step++) {
            t += rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(2000);
            int r = dominant && rnd.nextInt(5) != 0 ? 0 : rnd.nextInt(nReplicas);
            Replica rep = reps.get(r);
            long now = T0 + t + skews[r];

            // 1. Receive some unseen ops, sometimes a duplicate.
            int roll = rnd.nextInt(10);
            int pull = roll < 6 ? 0 : roll < 9 ? 1 + rnd.nextInt(3) : 4 + rnd.nextInt(57);
            if (pull > 0) {
                List<Integer> unseen = new ArrayList<>();
                for (int i = 0; i < ops.size(); i++) if (!rep.known.contains(i)) unseen.add(i);
                for (int k = 0; k < pull && !unseen.isEmpty(); k++) deliver(rep, ops, unseen.remove(rnd.nextInt(unseen.size())), now, collisions);
                if (!rep.known.isEmpty() && rnd.nextInt(5) == 0) {
                    List<Integer> known = new ArrayList<>(rep.known);
                    Collections.sort(known);
                    deliver(rep, ops, known.get(rnd.nextInt(known.size())), now, collisions);
                }
            }

            // 2. Make ops from this replica's own view.
            final long nowF = now;
            java.util.function.Consumer<Map<String, Object>> emit = body -> {
                rep.hlc = rep.hlc.tick(nowF);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("op_id", id("0e000000", rnd));
                m.put("board_id", board);
                m.put("hlc", rep.hlc.encode());
                m.put("replica_id", rep.hlc.replicaId());
                m.putAll(body);
                Op.Check c = Op.checkShape(m);
                if (!c.ok()) throw new IllegalStateException("generator bug: " + c.reason() + ": " + Canonical.encode(m));
                ops.add(Op.parse(m));
                deliver(rep, ops, ops.size() - 1, nowF, collisions);
            };
            Map<String, Object> view = rep.board.view();
            List<Map<String, Object>> viewLists = (List<Map<String, Object>>) view.get("lists");
            boolean appendBias = rnd.nextInt(3) == 0;
            int yIdx = rnd.nextInt(Integer.MAX_VALUE);
            java.util.function.IntUnaryOperator insertIndex = n -> sameGap ? Math.min(1, n) : appendBias ? n : yIdx % (n + 1);
            Supplier<String> hot = () -> rnd.nextDouble() < 0.7 && !rep.cards.isEmpty() ? rep.cards.get(0) : rep.cards.get(rnd.nextInt(rep.cards.size()));

            java.util.function.BiFunction<String, String, Map<String, Object>> place = (card, listId) -> {
                List<Map<String, Object>> cards = new ArrayList<>();
                for (Map<String, Object> l : viewLists)
                    if (l.get("id").equals(listId)) for (Map<String, Object> c : (List<Map<String, Object>>) l.get("cards")) if (!c.get("id").equals(card)) cards.add(c);
                List<String> keys = new ArrayList<>(cards.stream().map(c -> (String) c.get("position_key")).toList());
                Keys.InsertPlan plan = Keys.planInsert(keys, insertIndex.applyAsInt(keys.size()));
                if (plan.key().length() > REBALANCE_AT || plan.rekeys().stream().anyMatch(x -> x.key().length() > REBALANCE_AT)) {
                    List<String> fresh = Keys.rebalance(keys);
                    for (int i = 0; i < cards.size(); i++)
                        emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", cards.get(i).get("id"), "placement", Op.placement(listId, fresh.get(i))));
                    keys = new ArrayList<>(fresh);
                    plan = Keys.planInsert(keys, insertIndex.applyAsInt(keys.size()));
                }
                for (Keys.Rekey x : plan.rekeys())
                    emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", cards.get(x.index()).get("id"), "placement", Op.placement(listId, x.key())));
                return Op.placement(listId, plan.key());
            };
            java.util.function.Function<String, String> placeList = except -> {
                List<Map<String, Object>> ls = new ArrayList<>();
                for (Map<String, Object> l : viewLists) if (l.containsKey("position_key") && !l.get("id").equals(except)) ls.add(l);
                List<String> keys = new ArrayList<>(ls.stream().map(l -> (String) l.get("position_key")).toList());
                Keys.InsertPlan plan = Keys.planInsert(keys, insertIndex.applyAsInt(keys.size()));
                if (plan.key().length() > REBALANCE_AT || plan.rekeys().stream().anyMatch(x -> x.key().length() > REBALANCE_AT)) {
                    List<String> fresh = Keys.rebalance(keys);
                    for (int i = 0; i < ls.size(); i++)
                        emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", ls.get(i).get("id"), "placement", Op.placement(null, fresh.get(i))));
                    keys = new ArrayList<>(fresh);
                    plan = Keys.planInsert(keys, insertIndex.applyAsInt(keys.size()));
                }
                for (Keys.Rekey x : plan.rekeys())
                    emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", ls.get(x.index()).get("id"), "placement", Op.placement(null, x.key())));
                return plan.key();
            };

            int kind = rnd.nextInt(100);
            if (sameGap && kind >= 6) kind = 6 + (kind % 20);
            if (rep.lists.isEmpty()) kind = 0;
            else if (rep.cards.isEmpty() && kind >= 26) kind = 6;

            if (kind < 6) {
                emit.accept(Map.of("type", "ADD_LIST", "list_id", id("11000000", rnd), "placement", Op.placement(null, placeList.apply(null)),
                        "title", TITLES[rnd.nextInt(TITLES.length)]));
            } else if (kind < 26) {
                String cid = id("cc000000", rnd);
                String listId = sameGap || rnd.nextDouble() < 0.6 ? rep.lists.get(0) : rep.lists.get(rnd.nextInt(rep.lists.size()));
                emit.accept(Map.of("type", "ADD_CARD", "card_id", cid, "placement", place.apply(cid, listId), "title", TITLES[rnd.nextInt(TITLES.length)]));
            } else if (kind < 46) {
                String card = hot.get();
                emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", card, "placement", place.apply(card, rep.lists.get(rnd.nextInt(rep.lists.size())))));
            } else if (kind < 56) {
                String card = hot.get();
                String home = null;
                for (Map<String, Object> l : viewLists)
                    if (!l.get("id").equals(Op.NIL_UUID) && ((List<Map<String, Object>>) l.get("cards")).stream().anyMatch(c -> c.get("id").equals(card))) home = (String) l.get("id");
                if (home == null) home = rep.lists.get(rnd.nextInt(rep.lists.size()));
                emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", card, "placement", place.apply(card, home)));
            } else if (kind < 68) {
                String field = List.of("title", "description", "assignee", "labels").get(rnd.nextInt(4));
                Object value = switch (field) {
                    case "title" -> TITLES[rnd.nextInt(TITLES.length)];
                    case "description" -> DESCRIPTIONS[rnd.nextInt(DESCRIPTIONS.length)];
                    case "assignee" -> rnd.nextInt(3) == 0 ? null : Gen.uuidN("ee000000", 1 + rnd.nextInt(2));
                    default -> LABELS.get(rnd.nextInt(LABELS.size()));
                };
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("type", "SET_FIELD");
                body.put("entity_id", hot.get());
                body.put("field", field);
                body.put("value", value);
                emit.accept(body);
            } else if (kind < 78) {
                String card = hot.get();
                emit.accept(Map.of("type", "DELETE_CARD", "card_id", card, "observed_tags", rep.board.liveTags(false, card)));
            } else if (kind < 86) {
                List<String> deleted = rep.cards.stream().filter(c -> rep.board.liveTags(false, c).isEmpty()).toList();
                emit.accept(Map.of("type", "RESTORE_CARD", "card_id", !deleted.isEmpty() && rnd.nextDouble() < 0.8 ? deleted.get(rnd.nextInt(deleted.size())) : hot.get()));
            } else if (kind < 91) {
                String list = rep.lists.get(rnd.nextInt(rep.lists.size()));
                emit.accept(Map.of("type", "DELETE_LIST", "list_id", list, "observed_tags", rep.board.liveTags(true, list)));
            } else if (kind < 95) {
                List<String> deleted = rep.lists.stream().filter(l -> rep.board.liveTags(true, l).isEmpty()).toList();
                emit.accept(Map.of("type", "RESTORE_LIST", "list_id", !deleted.isEmpty() ? deleted.get(rnd.nextInt(deleted.size())) : rep.lists.get(rnd.nextInt(rep.lists.size()))));
            } else if (kind < 98) {
                String list = rep.lists.get(rnd.nextInt(rep.lists.size()));
                emit.accept(Map.of("type", "SET_PLACEMENT", "entity_id", list, "placement", Op.placement(null, placeList.apply(list))));
            } else {
                emit.accept(Map.of("type", "SET_FIELD", "entity_id", rep.lists.get(rnd.nextInt(rep.lists.size())), "field", "title", "value", TITLES[rnd.nextInt(TITLES.length)]));
            }
        }
        List<Board> partial = new ArrayList<>();
        List<List<Integer>> unseen = new ArrayList<>();
        for (Replica rep : reps) {
            partial.add(rep.board.copy());
            List<Integer> u = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) if (!rep.known.contains(i)) u.add(i);
            unseen.add(u);
        }
        return new Generated(ops, partial, unseen, collisions[0]);
    }

    static void deliver(Replica rep, List<Op> ops, int i, long now, int[] collisions) {
        Op op = ops.get(i);
        collisions[0] += rep.board.apply(op);
        if (rep.known.add(i)) {
            if (op instanceof Op.AddList a) rep.lists.add(a.listId());
            if (op instanceof Op.AddCard c) rep.cards.add(c.cardId());
        }
        rep.hlc = rep.hlc.receive(op.hlc(), now);
    }

    /** A random delivery order of indices with ~20% of them delivered twice. */
    static List<Integer> order(List<Integer> indices, long seed) {
        Random r = new Random(seed);
        List<Integer> out = new ArrayList<>(indices);
        Collections.shuffle(out, r);
        for (int i : indices) if (r.nextInt(5) == 0) out.add(r.nextInt(out.size() + 1), i);
        return out;
    }

    static String problem(Generated g, long seed) {
        int collisions = g.collisions();
        List<String> states = new ArrayList<>();
        for (int r = 0; r < g.partial().size(); r++) {
            Board b = g.partial().get(r).copy();
            for (int i : order(g.unseen().get(r), seed + r + 1)) collisions += b.apply(g.ops().get(i));
            states.add(b.canonical());
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < g.ops().size(); i++) all.add(i);
        for (int f = 0; f < 2; f++) {
            Board b = Board.empty();
            for (int i : order(all, seed + 1000 + f)) collisions += b.apply(g.ops().get(i));
            states.add(b.canonical());
        }
        Board merged = Board.empty();
        for (Board p : g.partial()) merged = Board.merge(merged, p).board();
        if (collisions != 0) return collisions + " LWW tie-breaks fired on valid ops";
        for (int i = 1; i < states.size(); i++) if (!states.get(i).equals(states.get(0))) return "replica " + i + " diverged from replica 0";
        if (!merged.canonical().equals(states.get(0))) return "merge of pre-delivery states differs from delivered state";
        return null;
    }

    static boolean converges(List<Op> ops) {
        String first = null;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) all.add(i);
        for (long s = 1; s <= 8; s++) {
            Board b = Board.empty();
            for (int i : order(all, s)) if (b.apply(ops.get(i)) != 0) return false;
            if (first == null) first = b.canonical();
            else if (!first.equals(b.canonical())) return false;
        }
        return true;
    }

    /** Delta debugging over the op list. */
    static List<Op> minimize(List<Op> ops) {
        List<Op> cur = new ArrayList<>(ops);
        int n = 2;
        while (cur.size() >= 2) {
            int size = (cur.size() + n - 1) / n;
            boolean reduced = false;
            for (int i = 0; i < cur.size(); i += size) {
                List<Op> without = new ArrayList<>(cur.subList(0, i));
                without.addAll(cur.subList(Math.min(cur.size(), i + size), cur.size()));
                if (!converges(without)) {
                    cur = without;
                    n = Math.max(n - 1, 2);
                    reduced = true;
                    break;
                }
            }
            if (!reduced) {
                if (n >= cur.size()) break;
                n = Math.min(n * 2, cur.size());
            }
        }
        return cur;
    }

    static void fuzz(String name, int runs, boolean forceSameGap) {
        long first = Long.parseLong(System.getenv().getOrDefault("FUZZ_SEED", "1"));
        long started = System.nanoTime();
        long steps = 0;
        for (long seed = first; seed < first + runs; seed++) {
            Random p = new Random(seed * 0x9E3779B97F4A7C15L);
            int replicas = 3 + p.nextInt(3);
            int n = forceSameGap ? 450 + p.nextInt(51) : 50 + p.nextInt(451);
            boolean sameGap = forceSameGap || p.nextInt(4) == 0;
            steps += n;
            Generated g = generate(seed, replicas, n, sameGap, forceSameGap);
            String why = problem(g, seed);
            if (why != null) {
                List<Op> minimal = minimize(g.ops());
                StringBuilder ops = new StringBuilder();
                for (Op o : minimal) ops.append(Canonical.encode(o.toJson())).append('\n');
                fail(name + " failure at seed " + seed + ": " + why + "; minimal failing op set (" + minimal.size() + " of " + g.ops().size() + " ops):\n" + ops);
            }
        }
        System.out.printf("%s: %d seeds from %d, %d steps, %.1fs%n", name, runs, first, steps, (System.nanoTime() - started) / 1e9);
    }

    static int runs() {
        return Integer.parseInt(System.getenv().getOrDefault("FUZZ_RUNS", "500"));
    }

    @Test
    void everyReplicaConvergesToByteIdenticalState() {
        fuzz("fuzz", runs(), false);
    }

    @Test
    void convergesWhileKeysAreRebalancedUnderSameGapPressure() {
        fuzz("rebalance-fuzz", Math.max(50, runs() / 10), true);
    }
}
