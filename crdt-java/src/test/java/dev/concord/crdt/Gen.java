package dev.concord.crdt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/** Shared jqwik arbitraries, mirroring crdt-ts/test/arb.ts: small pools so states overlap. */
final class Gen {
    private Gen() {}

    static String uuidN(String prefix, long n) {
        return String.format("%s-0000-4000-8000-%012x", prefix, n);
    }

    static final String BOARD = uuidN("b0a4d000", 1);

    static Arbitrary<String> replica() {
        return Arbitraries.integers().between(1, 4).map(n -> uuidN("a0000000", n));
    }

    static Arbitrary<Hlc> hlc() {
        return Combinators.combine(Arbitraries.integers().between(0, 6), Arbitraries.integers().between(0, 3), replica())
                .as((ms, c, r) -> new Hlc(1760000000000L + ms, c, r));
    }

    static Arbitrary<Hlc> wideHlc() {
        return Combinators.combine(
                        Arbitraries.longs().between(0, Json.MAX_SAFE_INTEGER),
                        Arbitraries.longs().between(0, Hlc.MAX_COUNTER - 1),
                        Arbitraries.create(java.util.UUID::randomUUID).map(Object::toString))
                .as(Hlc::new);
    }

    static Arbitrary<String> listId() { return Arbitraries.integers().between(1, 3).map(n -> uuidN("11000000", n)); }

    static Arbitrary<String> cardId() { return Arbitraries.integers().between(1, 4).map(n -> uuidN("cc000000", n)); }

    static Arbitrary<String> opId() { return Arbitraries.integers().between(1, 40).map(n -> uuidN("0e000000", n)); }

    static Arbitrary<String> key() { return Arbitraries.of("a0", "a1", "a1V", "a2", "Zz", "b00", "a0G", "a0V", "Zzz"); }

    /** Any well-formed Unicode string, astral planes and control characters included. */
    static Arbitrary<String> unicode(int maxLength) {
        return Arbitraries.oneOf(
                        Arbitraries.integers().between(0, 0x7f),
                        Arbitraries.integers().between(0, 0xffff),
                        Arbitraries.integers().between(0x10000, 0x10ffff))
                .filter(cp -> cp < 0xd800 || cp > 0xdfff)
                .list().ofMaxSize(maxLength)
                .map(cps -> {
                    StringBuilder b = new StringBuilder();
                    for (int cp : cps) b.appendCodePoint(cp);
                    return b.toString();
                });
    }

    private static Map<String, Object> base(String opId, Hlc hlc, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op_id", opId);
        m.put("board_id", BOARD);
        m.put("hlc", hlc.encode());
        m.put("replica_id", hlc.replicaId());
        m.put("type", type);
        return m;
    }

    private static Map<String, Object> with(Map<String, Object> m, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** JSON of any op that passes validateShape. */
    static Arbitrary<Map<String, Object>> opJson() {
        Arbitrary<Object> fieldValue = Arbitraries.oneOf(
                Arbitraries.of("title").flatMap(f -> Arbitraries.of("x", "y", "z").map(v -> List.of(f, v))),
                Arbitraries.of("description").flatMap(f -> Arbitraries.of("", "d1", "d2").map(v -> List.of(f, v))),
                Arbitraries.of("assignee").flatMap(f -> Arbitraries.of(0, 1).map(v -> { List<Object> l = new ArrayList<>(); l.add(f); l.add(v == 0 ? null : uuidN("ee000000", 1)); return l; })),
                Arbitraries.of("labels").flatMap(f -> Arbitraries.subsetOf("bug", "p1", "ux", "émoji").map(s -> {
                    List<String> labels = new ArrayList<>(s);
                    labels.sort(Canonical::compareUtf8);
                    return List.of(f, labels);
                })));
        return Combinators.combine(opId(), hlc(), Arbitraries.integers().between(0, 8)).flatAs((id, h, kind) -> switch (kind) {
            case 0 -> Combinators.combine(listId(), key(), Arbitraries.of("To Do", "Done"))
                    .as((l, k, t) -> with(base(id, h, "ADD_LIST"), "list_id", l, "placement", Op.placement(null, k), "title", t));
            case 1 -> Combinators.combine(cardId(), listId(), key(), Arbitraries.of("x", "y"))
                    .as((c, l, k, t) -> with(base(id, h, "ADD_CARD"), "card_id", c, "placement", Op.placement(l, k), "title", t));
            case 2 -> Combinators.combine(Arbitraries.oneOf(cardId(), listId()), fieldValue)
                    .as((e, fv) -> { List<?> p = (List<?>) fv; return with(base(id, h, "SET_FIELD"), "entity_id", e, "field", p.get(0), "value", p.get(1)); });
            case 3 -> Combinators.combine(cardId(), listId(), key())
                    .as((c, l, k) -> with(base(id, h, "SET_PLACEMENT"), "entity_id", c, "placement", Op.placement(l, k)));
            case 4 -> Combinators.combine(listId(), key())
                    .as((l, k) -> with(base(id, h, "SET_PLACEMENT"), "entity_id", l, "placement", Op.placement(null, k)));
            case 5 -> Combinators.combine(listId(), opId().list().ofMaxSize(3))
                    .as((l, t) -> with(base(id, h, "DELETE_LIST"), "list_id", l, "observed_tags", t));
            case 6 -> Combinators.combine(cardId(), opId().list().ofMaxSize(3))
                    .as((c, t) -> with(base(id, h, "DELETE_CARD"), "card_id", c, "observed_tags", t));
            case 7 -> listId().map(l -> with(base(id, h, "RESTORE_LIST"), "list_id", l));
            default -> cardId().map(c -> with(base(id, h, "RESTORE_CARD"), "card_id", c));
        });
    }

    static Arbitrary<Op> op() {
        return opJson().map(Op::parse);
    }

    static Board build(List<Op> ops) {
        Board b = Board.empty();
        for (Op o : ops) b.apply(o);
        return b;
    }

    static Arbitrary<Board> board() {
        return op().list().ofMaxSize(25).map(Gen::build);
    }
}
