package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class KeysTest {
    private static int intLen(char h) {
        return h >= 'a' ? h - 'a' + 2 : 'Z' - h + 2;
    }

    /** Any valid key of at most 40 characters. */
    @Provide
    Arbitrary<String> anyKey() {
        Arbitrary<Character> digit = Arbitraries.of(Keys.DIGITS.chars().mapToObj(c -> (char) c).toList());
        List<Character> heads = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) if (intLen(c) <= 8) heads.add(c);
        for (char c = 'a'; c <= 'z'; c++) if (intLen(c) <= 8) heads.add(c);
        return Arbitraries.of(heads).flatMap(h -> Combinators.combine(
                        digit.list().ofSize(intLen(h) - 1),
                        digit.list().ofMaxSize(30))
                .as((i, f) -> {
                    StringBuilder b = new StringBuilder().append(h);
                    i.forEach(b::append);
                    StringBuilder fr = new StringBuilder();
                    f.forEach(fr::append);
                    return b + fr.toString().replaceAll("0+$", "");
                }))
                .filter(Keys::validateKey);
    }

    @Provide
    Arbitrary<Tuple.Tuple2<String, String>> pair() {
        return Combinators.combine(anyKey(), anyKey()).as((a, b) -> a.compareTo(b) < 0 ? Tuple.of(a, b) : Tuple.of(b, a))
                .filter(t -> !t.get1().equals(t.get2()));
    }

    @Property(tries = 2000)
    void keyBetweenIsStrictlyBetweenAndValid(@ForAll("pair") Tuple.Tuple2<String, String> p) {
        String k = Keys.keyBetween(p.get1(), p.get2());
        assertTrue(p.get1().compareTo(k) < 0 && k.compareTo(p.get2()) < 0, k);
        assertTrue(Keys.validateKey(k), k);
        assertEquals(k, Keys.keyBetween(p.get1(), p.get2())); // deterministic
    }

    @Property
    void nullBoundsWork(@ForAll("anyKey") String k) {
        String before = Keys.keyBetween(null, k), after = Keys.keyBetween(k, null);
        assertTrue(before.compareTo(k) < 0 && k.compareTo(after) < 0);
        assertTrue(Keys.validateKey(before) && Keys.validateKey(after));
    }

    @Test
    void throwsUnlessALessThanBAndOnInvalidKeys() {
        assertThrows(IllegalArgumentException.class, () -> Keys.keyBetween("a1", "a1"));
        assertThrows(IllegalArgumentException.class, () -> Keys.keyBetween("a2", "a1"));
        assertThrows(IllegalArgumentException.class, () -> Keys.keyBetween("a10", null));
        assertThrows(IllegalArgumentException.class, () -> Keys.keyBetween("b0", null));
    }

    @Test
    void followsGreenspansReferenceOutputs() {
        // rocicorp/fractional-indexing src/test.js
        String[][] table = {
            {null, null, "a0"}, {null, "a0", "Zz"}, {null, "Zz", "Zy"}, {"a0", null, "a1"}, {"a1", null, "a2"},
            {"a0", "a1", "a0V"}, {"a1", "a2", "a1V"}, {"a0V", "a1", "a0l"}, {"Zz", "a0", "ZzV"}, {"Zz", "a1", "a0"},
            {null, "Y00", "Xzzz"}, {"bzz", null, "c000"}, {"a0", "a0V", "a0G"}, {"a0", "a0G", "a08"},
            {"b125", "b129", "b127"}, {"a0", "a1V", "a1"}, {"Zz", "a01", "a0"}, {null, "a0V", "a0"}, {null, "b999", "b99"},
            {null, "A000000000000000000000000001", "A000000000000000000000000000V"},
            {"zzzzzzzzzzzzzzzzzzzzzzzzzzy", null, "zzzzzzzzzzzzzzzzzzzzzzzzzzz"},
            {"zzzzzzzzzzzzzzzzzzzzzzzzzzz", null, "zzzzzzzzzzzzzzzzzzzzzzzzzzzV"},
        };
        for (String[] row : table) assertEquals(row[2], Keys.keyBetween(row[0], row[1]), row[0] + ".." + row[1]);
        for (String[] bad : new String[][] {{null, "A00000000000000000000000000"}, {"a00", null}, {"a00", "a1"}, {"0", "1"}, {"a1", "a0"}})
            assertThrows(IllegalArgumentException.class, () -> Keys.keyBetween(bad[0], bad[1]), bad[0] + ".." + bad[1]);
    }

    @Property
    void keysBetweenGivesNAscendingValidKeysInsideTheBounds(
            @ForAll("anyKey") String x, @ForAll("anyKey") String y, @ForAll @IntRange(min = 0, max = 3) int bounds, @ForAll @IntRange(min = 0, max = 30) int n) {
        String a = (bounds & 1) == 0 ? null : (x.compareTo(y) <= 0 ? x : y);
        String b = (bounds & 2) == 0 ? null : (x.compareTo(y) <= 0 ? y : x);
        Assume.that(a == null || b == null || a.compareTo(b) < 0);
        List<String> ks = Keys.keysBetween(a, b, n);
        assertEquals(n, ks.size());
        for (int i = 0; i < ks.size(); i++) {
            String k = ks.get(i);
            assertTrue(Keys.validateKey(k));
            if (i > 0) assertTrue(ks.get(i - 1).compareTo(k) < 0);
            if (a != null) assertTrue(a.compareTo(k) < 0);
            if (b != null) assertTrue(k.compareTo(b) < 0);
        }
    }

    @Test
    void rebalanceAndAppendsStayShort() {
        List<String> ks = Keys.rebalance(java.util.Collections.nCopies(2000, "a" + "V".repeat(45)));
        assertEquals(2000, ks.size());
        for (int i = 1; i < ks.size(); i++) assertTrue(ks.get(i - 1).compareTo(ks.get(i)) < 0);
        assertTrue(ks.stream().mapToInt(String::length).max().orElse(0) <= 4);
        String k = Keys.keyBetween(null, null);
        int longest = k.length();
        for (int i = 0; i < 2000; i++) longest = Math.max(longest, (k = Keys.keyBetween(k, null)).length());
        assertTrue(longest <= 4);
    }

    record Entry(String key, String id) {}

    static final Comparator<Entry> ORDER = (x, y) -> {
        int c = Canonical.compareUtf8(x.key(), y.key());
        return c != 0 ? c : Canonical.compareUtf8(x.id(), y.id());
    };

    @Property(tries = 2000)
    void planInsertPlacesTheNewEntryExactlyAtTheIndex(
            @ForAll("entries") List<Entry> raw, @ForAll @IntRange(min = 0, max = 100) int at) {
        List<Entry> sorted = new ArrayList<>(raw);
        sorted.sort(ORDER);
        int index = at % (sorted.size() + 1);
        Keys.InsertPlan plan = Keys.planInsert(sorted.stream().map(Entry::key).toList(), index);
        List<Entry> after = new ArrayList<>(sorted);
        for (Keys.Rekey r : plan.rekeys()) after.set(r.index(), new Entry(r.key(), after.get(r.index()).id()));
        assertTrue(Keys.validateKey(plan.key()));
        String newId = UUID.randomUUID().toString();
        after.add(new Entry(plan.key(), newId));
        after.sort(ORDER);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < index; i++) expected.add(sorted.get(i).id());
        expected.add(newId);
        for (int i = index; i < sorted.size(); i++) expected.add(sorted.get(i).id());
        assertEquals(expected, after.stream().map(Entry::id).toList());
    }

    @Provide
    Arbitrary<List<Entry>> entries() {
        return Combinators.combine(Arbitraries.of("a0", "a1", "a1V", "a2"), Arbitraries.create(() -> UUID.randomUUID().toString()))
                .as(Entry::new).list().ofMaxSize(8);
    }

    @Test
    void planInsertReKeysEveryUpperNeighbourSharingTheKey() {
        assertEquals(List.of(2, 3), Keys.planInsert(List.of("a0", "a1", "a1", "a1", "a2"), 2).rekeys().stream().map(Keys.Rekey::index).toList());
        assertEquals(List.of(), Keys.planInsert(List.of("a0", "a1"), 1).rekeys());
    }
}
