package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** SPEC §9.1: merge laws for orset, lww and board over random states. */
class MergeLawsTest {
    // ---- orset ----------------------------------------------------------------------------

    record SetAction(boolean add, String id, String tag, List<String> tags) {}

    @Provide
    Arbitrary<List<SetAction>> setActions() {
        Arbitrary<SetAction> add = Combinators.combine(Gen.cardId(), Gen.opId()).as((id, t) -> new SetAction(true, id, t, List.of()));
        Arbitrary<SetAction> rm = Combinators.combine(Gen.cardId(), Gen.opId().list().ofMaxSize(3)).as((id, ts) -> new SetAction(false, id, null, ts));
        return Arbitraries.oneOf(add, rm).list().ofMaxSize(20);
    }

    static OrSet orset(List<SetAction> xs) {
        OrSet s = new OrSet();
        for (SetAction a : xs) {
            if (a.add()) s.add(a.id(), a.tag());
            else s.remove(a.id(), a.tags());
        }
        return s;
    }

    static String enc(OrSet s) { return Canonical.encode(s.encode()); }

    @Property
    void orsetMergeIsCommutativeAssociativeIdempotent(
            @ForAll("setActions") List<SetAction> x, @ForAll("setActions") List<SetAction> y, @ForAll("setActions") List<SetAction> z) {
        OrSet a = orset(x), b = orset(y), c = orset(z);
        assertEquals(enc(OrSet.merge(a, b)), enc(OrSet.merge(b, a)));
        assertEquals(enc(OrSet.merge(OrSet.merge(a, b), c)), enc(OrSet.merge(a, OrSet.merge(b, c))));
        assertEquals(enc(OrSet.merge(a, a)), enc(a));
        String ea = enc(a);
        OrSet.merge(a, b);
        assertEquals(ea, enc(a)); // inputs untouched
    }

    @Property
    void orsetActionsCommute(@ForAll("setActions") List<SetAction> xs, @ForAll long seed) {
        List<SetAction> ys = new ArrayList<>(xs);
        Collections.shuffle(ys, new Random(seed));
        assertEquals(enc(orset(xs)), enc(orset(ys)));
    }

    @Test
    void orsetDeleteBeforeAddAndRestore() {
        OrSet s = new OrSet();
        String id = Gen.uuidN("cc000000", 1), t1 = Gen.uuidN("0e000000", 1), t2 = Gen.uuidN("0e000000", 2);
        s.remove(id, List.of(t1));
        s.add(id, t1);
        assertFalse(s.contains(id));
        s.add(id, t2);
        assertTrue(s.contains(id));
        assertEquals(List.of(t2), s.liveTags(id));
    }

    @Property
    void orsetRemoveNamingOneIdNeverChangesAnother(
            @ForAll("setActions") List<SetAction> xs, @ForAll("cardIds") String x, @ForAll("cardIds") String y) {
        Assume.that(!x.equals(y));
        OrSet s = orset(xs);
        boolean before = s.contains(y);
        s.remove(x, s.liveTags(y)); // even listing every live tag of y
        assertEquals(before, s.contains(y));
    }

    @Provide
    Arbitrary<String> cardIds() { return Gen.cardId(); }

    @Property
    void orsetDecodeRoundTrips(@ForAll("setActions") List<SetAction> xs) {
        OrSet s = orset(xs);
        assertEquals(enc(s), enc(OrSet.decode(Json.parse(enc(s)))));
    }

    @Test
    void orsetDecodeRejectsTheFlatTombstoneShape() {
        String c1 = "\"cc000000-0000-4000-8000-000000000001\"", t1 = "\"0e000000-0000-4000-8000-000000000001\"";
        assertThrows(IllegalArgumentException.class, () -> OrSet.decode(Json.parse("{\"adds\":{},\"tombstones\":[" + t1 + "]}")));
        assertThrows(IllegalArgumentException.class, () -> OrSet.decode(Json.parse("{\"adds\":{},\"tombstones\":{" + c1 + ":[]}}")));
        assertEquals(List.of(), OrSet.decode(Json.parse("{\"adds\":{},\"tombstones\":{" + c1 + ":[" + t1 + "]}}")).liveTags(c1.replace("\"", "")));
    }

    // ---- lww ------------------------------------------------------------------------------

    record Write(String entity, String field, Object value, Hlc hlc) {}

    @Provide
    Arbitrary<List<Write>> writes() {
        Arbitrary<Object> value = Arbitraries.oneOf(
                Arbitraries.of("a", "b", "é", "😀", "｡", List.of("x"), List.of("x", "y"), Op.placement("l", "a0")),
                Arbitraries.just((Object) null),
                Arbitraries.longs().between(-3, 3).map(v -> (Object) v));
        return Combinators.combine(Gen.cardId(), Arbitraries.of("title", "labels"), value, Gen.hlc()).as(Write::new).list().ofMaxSize(15);
    }

    static Lww lww(List<Write> ws) {
        Lww l = new Lww();
        for (Write w : ws) l.set(w.entity(), w.field(), w.value(), w.hlc());
        return l;
    }

    static String enc(Lww l) { return Canonical.encode(l.encode()); }

    @Property(tries = 1000)
    void lwwMergeIsCommutativeAssociativeIdempotentEvenWithCollisions(
            @ForAll("writes") List<Write> x, @ForAll("writes") List<Write> y, @ForAll("writes") List<Write> z) {
        Lww a = lww(x), b = lww(y), c = lww(z);
        assertEquals(enc(Lww.merge(a, b).lww()), enc(Lww.merge(b, a).lww()));
        assertEquals(enc(Lww.merge(Lww.merge(a, b).lww(), c).lww()), enc(Lww.merge(a, Lww.merge(b, c).lww()).lww()));
        assertEquals(enc(Lww.merge(a, a).lww()), enc(a));
        assertEquals(0, Lww.merge(a, a).collisions());
        assertEquals(Lww.merge(a, b).collisions(), Lww.merge(b, a).collisions());
    }

    @Property
    void lwwWritesCommute(@ForAll("writes") List<Write> xs, @ForAll long seed) {
        List<Write> ys = new ArrayList<>(xs);
        Collections.shuffle(ys, new Random(seed));
        assertEquals(enc(lww(xs)), enc(lww(ys)));
    }

    @Test
    void lwwHigherHlcWinsAndTieBreakUsesUnsignedBytes() {
        String e = Gen.uuidN("cc000000", 1), r = Gen.uuidN("a0000000", 1);
        Lww l = new Lww();
        assertEquals(0, l.set(e, "title", "old", new Hlc(1, 0, r)));
        assertEquals(0, l.set(e, "title", "new", new Hlc(2, 0, r)));
        assertEquals(0, l.set(e, "title", "older", new Hlc(0, 0, r)));
        assertEquals("new", l.get(e, "title").value());
        assertEquals(0, l.set(e, "title", "new", new Hlc(2, 0, r)));
        // EF BD A1 < F0 9F 98 80 as unsigned bytes; String.compareTo would say the opposite.
        assertEquals(0, l.set(e, "title", "｡", new Hlc(3, 0, r)));
        assertEquals(1, l.set(e, "title", "😀", new Hlc(3, 0, r)));
        assertEquals("😀", l.get(e, "title").value());
        assertEquals(1, l.set(e, "title", "｡", new Hlc(3, 0, r)));
        assertEquals("😀", l.get(e, "title").value());
    }

    @Property
    void lwwDecodeRoundTrips(@ForAll("writes") List<Write> xs) {
        Lww l = lww(xs);
        assertEquals(enc(l), enc(Lww.decode(Json.parse(enc(l)))));
    }

    // ---- board ----------------------------------------------------------------------------

    @Provide Arbitrary<List<Op>> ops() { return Gen.op().list().ofMaxSize(25); }

    @Property(tries = 500)
    void boardMergeIsCommutativeAssociativeIdempotent(@ForAll("ops") List<Op> x, @ForAll("ops") List<Op> y, @ForAll("ops") List<Op> z) {
        Board a = Gen.build(x), b = Gen.build(y), c = Gen.build(z);
        assertEquals(Board.merge(a, b).board().canonical(), Board.merge(b, a).board().canonical());
        assertEquals(Board.merge(Board.merge(a, b).board(), c).board().canonical(), Board.merge(a, Board.merge(b, c).board()).board().canonical());
        assertEquals(Board.merge(a, a).board().canonical(), a.canonical());
    }

    @Property
    void applyAgreesWithMergeOfSingleOpStates(@ForAll("ops") List<Op> ops) {
        Board merged = Board.empty();
        for (Op o : ops) merged = Board.merge(merged, Gen.build(List.of(o))).board();
        assertEquals(Gen.build(ops).canonical(), merged.canonical());
    }

    @Property
    void applyIsIdempotentAndOrderIndependent(@ForAll("ops") List<Op> xs, @ForAll long seed) {
        List<Op> ys = new ArrayList<>(xs);
        Collections.shuffle(ys, new Random(seed));
        ys.addAll(xs);
        assertEquals(Gen.build(xs).canonical(), Gen.build(ys).canonical());
    }

    @Property
    void viewIsAFunctionOfTheCanonicalState(@ForAll("ops") List<Op> ops) {
        Board b = Gen.build(ops);
        Board d = Board.decode(Json.parse(b.canonical()));
        assertEquals(b.canonical(), d.canonical());
        assertEquals(Canonical.encode(b.view()), Canonical.encode(d.view()));
    }

    @Test
    void boardDecodeRejectsMalformedState() {
        @SuppressWarnings("unchecked")
        Map<String, Object> empty = (Map<String, Object>) Json.parse(Board.empty().canonical());
        Map<String, Object> extra = new java.util.LinkedHashMap<>(empty);
        extra.put("extra", Map.of());
        assertThrows(IllegalArgumentException.class, () -> Board.decode(extra));
        Map<String, Object> bad = new java.util.LinkedHashMap<>(empty);
        bad.put("registers", Map.of(Gen.uuidN("cc000000", 1), Map.of("placement", Map.of("hlc", new Hlc(1, 0, Gen.uuidN("a0000000", 1)).encode(), "value", "a0"))));
        assertThrows(IllegalArgumentException.class, () -> Board.decode(bad));
    }
}
