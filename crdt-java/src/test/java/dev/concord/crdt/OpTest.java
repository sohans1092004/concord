package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class OpTest {
    static final String R1 = Gen.uuidN("a0000000", 1);
    static final String CARD = Gen.uuidN("cc000000", 1);
    static final Hlc H = new Hlc(1760000000000L, 0, R1);

    static Map<String, Object> base(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op_id", Gen.uuidN("0e000000", 1));
        m.put("board_id", Gen.BOARD);
        m.put("hlc", H.encode());
        m.put("replica_id", R1);
        m.put("type", type);
        return m;
    }

    static Map<String, Object> addCard() {
        Map<String, Object> m = base("ADD_CARD");
        m.put("card_id", CARD);
        m.put("placement", Op.placement(Gen.uuidN("11000000", 1), "a0"));
        m.put("title", "t");
        return m;
    }

    static Map<String, Object> setField(String field, Object value) {
        Map<String, Object> m = base("SET_FIELD");
        m.put("entity_id", CARD);
        m.put("field", field);
        m.put("value", value);
        return m;
    }

    static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }

    static List<String> labels(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(String.format("l%02d", i));
        return out;
    }

    @Provide Arbitrary<Map<String, Object>> ops() { return Gen.opJson(); }

    @Property
    void acceptsEveryGeneratedOpAndItsJsonRoundTrip(@ForAll("ops") Map<String, Object> op) {
        assertEquals(Op.Check.OK, Op.checkShape(op));
        Object reparsed = Json.parse(Canonical.encode(op));
        assertTrue(Op.validateShape(reparsed));
        assertEquals(Canonical.encode(op), Canonical.encode(Op.parse(reparsed).toJson()));
    }

    @Property
    void rejectsAnyOpWithAMemberRemovedOrAnUnknownMemberAdded(@ForAll("ops") Map<String, Object> op, @ForAll @IntRange(min = 0, max = 100) int i) {
        List<String> keys = new ArrayList<>(op.keySet());
        Map<String, Object> without = new LinkedHashMap<>(op);
        without.remove(keys.get(i % keys.size()));
        assertFalse(Op.validateShape(without));
        assertFalse(Op.validateShape(with(op, "extra", 1L)));
    }

    @Test
    void rejectsIdsThatAreNotLowercaseCanonicalUuidsAndTheNilUuid() {
        assertTrue(Op.validateShape(addCard()));
        for (Object bad : List.of(CARD.toUpperCase(), "{" + CARD + "}", CARD.replace("-", ""), Op.NIL_UUID, "", 7L)) {
            assertFalse(Op.validateShape(with(addCard(), "card_id", bad)));
            assertFalse(Op.validateShape(with(addCard(), "op_id", bad)));
        }
        assertFalse(Op.validateShape(with(addCard(), "placement", Op.placement(Op.NIL_UUID, "a0"))));
        Map<String, Object> move = with(with(base("SET_PLACEMENT"), "entity_id", CARD), "placement", Op.placement(Op.NIL_UUID, "a0"));
        assertFalse(Op.validateShape(move));
        Map<String, Object> del = with(with(base("DELETE_CARD"), "card_id", CARD), "observed_tags", List.of(Gen.uuidN("0E000000", 1)));
        assertFalse(Op.validateShape(del));
        assertFalse(Op.validateShape(setField("assignee", Op.NIL_UUID)));
    }

    @Test
    void rejectsAReplicaIdThatDisagreesWithTheHlcAndNonIntegers() {
        assertFalse(Op.validateShape(with(addCard(), "replica_id", Gen.uuidN("a0000000", 2))));
        assertFalse(Op.validateShape(with(addCard(), "hlc", with(H.encode(), "counter", 0.5))));
        assertFalse(Op.validateShape(with(addCard(), "hlc", with(H.encode(), "physical_ms", 9007199254740992.0))));
        assertTrue(Op.validateShape(Json.parse(Canonical.encode(addCard()).replace("\"counter\":0", "\"counter\":0.0"))));
    }

    @Test
    void enforcesTitleAndDescriptionLimitsInCodePoints() {
        assertTrue(Op.validateShape(with(addCard(), "title", "😀".repeat(200))));
        assertFalse(Op.validateShape(with(addCard(), "title", "x".repeat(201))));
        assertFalse(Op.validateShape(with(addCard(), "title", "\ud800")));
        assertTrue(Op.validateShape(setField("description", "d".repeat(10_000))));
        assertFalse(Op.validateShape(setField("description", "d".repeat(10_001))));
    }

    @Test
    void enforcesLabels() {
        assertTrue(Op.validateShape(setField("labels", List.of())));
        assertTrue(Op.validateShape(setField("labels", List.of("a", "b"))));
        assertFalse(Op.validateShape(setField("labels", List.of("b", "a"))));
        assertFalse(Op.validateShape(setField("labels", List.of("a", "a"))));
        assertTrue(Op.validateShape(setField("labels", labels(20))));
        assertFalse(Op.validateShape(setField("labels", labels(21))));
        assertTrue(Op.validateShape(setField("labels", List.of("x".repeat(50)))));
        assertFalse(Op.validateShape(setField("labels", List.of("x".repeat(51)))));
        assertTrue(Op.validateShape(setField("labels", List.of("é"))));
        assertFalse(Op.validateShape(setField("labels", List.of("é"))));
        // Byte order, not UTF-16 order: U+FF61 (EF BD A1) before U+1F600 (F0 9F 98 80).
        assertTrue(Op.validateShape(setField("labels", List.of("｡", "😀"))));
        assertFalse(Op.validateShape(setField("labels", List.of("😀", "｡"))));
    }

    @Test
    void rejectsPlacementInSetFieldAndTheWrongPlacementForm() {
        assertFalse(Op.validateShape(setField("placement", "a0")));
        assertFalse(Op.validateShape(with(addCard(), "placement", Op.placement(null, "a0"))));
        Map<String, Object> list = with(with(with(base("ADD_LIST"), "list_id", Gen.uuidN("11000000", 1)),
                "placement", Op.placement(Gen.uuidN("11000000", 2), "a0")), "title", "t");
        assertFalse(Op.validateShape(list));
    }

    @Test
    void appliesGreenspansFullKeyCheck() {
        for (String good : List.of("a0", "a1", "Zz", "a0V", "b00", "b00V", "A" + "0".repeat(25) + "1"))
            assertTrue(Op.validateShape(with(addCard(), "placement", Op.placement(Gen.uuidN("11000000", 1), good))), good);
        for (String bad : List.of("", "a", "b0", "a0V0", "a00", "0a", "a0-", "a0é", "A" + "0".repeat(26), "a" + "V".repeat(64)))
            assertFalse(Op.validateShape(with(addCard(), "placement", Op.placement(Gen.uuidN("11000000", 1), bad))), bad);
    }

    @Property
    void checkClockRejectsIffMoreThan60sAhead(@ForAll @LongRange(min = 0, max = 1L << 50) long server, @ForAll @LongRange(min = -200_000, max = 200_000) long delta) {
        long pm = Math.max(0, server + delta);
        Op op = Op.parse(with(addCard(), "hlc", new Hlc(pm, 0, R1).encode()));
        assertEquals(pm <= server + 60_000, Op.checkClock(op, server).ok());
    }

    @Test
    void checkClockBoundary() {
        Op at = Op.parse(with(addCard(), "hlc", new Hlc(1_060_000, 0, R1).encode()));
        Op past = Op.parse(with(addCard(), "hlc", new Hlc(1_060_001, 0, R1).encode()));
        Op old = Op.parse(with(addCard(), "hlc", new Hlc(0, 0, R1).encode()));
        assertTrue(Op.checkClock(at, 1_000_000).ok());
        assertEquals("CLOCK_SKEW", Op.checkClock(past, 1_000_000).code());
        assertTrue(Op.checkClock(old, 1_000_000).ok());
    }

    @Test
    void newOpIdMakesLowercaseCanonicalUuidV4s() {
        String id = Op.newOpId();
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), id);
        assertNotEquals(id, Op.newOpId());
    }
}
