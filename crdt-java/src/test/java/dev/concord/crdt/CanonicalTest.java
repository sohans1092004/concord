package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class CanonicalTest {
    @Provide Arbitrary<String> text() { return Gen.unicode(40); }

    @Test
    void sortsKeysDropsWhitespaceWritesIntegersPlainly() {
        Object v = Json.parse("{ \"b\": 1, \"a\": [true, null, -3], \"c\": {\"z\": \"x\", \"y\": 0} }");
        assertEquals("{\"a\":[true,null,-3],\"b\":1,\"c\":{\"y\":0,\"z\":\"x\"}}", Canonical.encode(v));
        assertEquals("0", Canonical.encode(Json.parse("-0")));
    }

    @Test
    void escapesOnlyQuoteBackslashAndControlCharacters() {
        String s = "\"\\\b\f\n\r\t\u0000\u001f\u007f/é😀 ";
        assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f\u007f/é😀 \"", Canonical.encode(s));
    }

    @Test
    void sortsKeysByUtf16CodeUnits() {
        // U+1F600 is D83D DE00 in UTF-16, so it sorts before U+FF61 (RFC 8785 §3.2.3).
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("｡", 2L);
        m.put("😀", 1L);
        assertEquals("{\"😀\":1,\"｡\":2}", Canonical.encode(m));
    }

    @Test
    void numbersFollowJavaScript() {
        // JSON.parse reads every literal as a double; integers are those that are safe.
        assertEquals(1L, Json.parse("1.0"));
        assertEquals(1000L, Json.parse("1e3"));
        assertEquals(0.5, Json.parse("0.5"));
        assertEquals(Json.MAX_SAFE_INTEGER, Json.parse("9007199254740991"));
        assertEquals(9007199254740992.0, Json.parse("9007199254740993")); // rounds like JS, then is unsafe
    }

    @Test
    void rejectsNonIntegersLoneSurrogatesAndBadJson() {
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode(1.5));
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode(1L << 53));
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> Canonical.encode(Map.of("\udc00", 1L)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"raw\ttab\""));
    }

    @Property(tries = 2000)
    void parseOfEncodeIsIdentity(@ForAll("text") String s) {
        assertEquals(s, Json.parse(Canonical.encode(s)));
        assertEquals(List.of(s), Json.parse(Canonical.encode(List.of(s))));
    }

    @Property(tries = 2000)
    void compareUtf8OrdersAsUnsignedUtf8Bytes(@ForAll("text") String a, @ForAll("text") String b) {
        int expected = Integer.signum(Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, Canonical.compareUtf8(a, b));
    }

    @Test
    void compareUtf8DisagreesWithStringCompareToOutsideTheBmp() {
        assertEquals(-1, Canonical.compareUtf8("￿", "😀"));
        assertEquals(1, Integer.signum("￿".compareTo("😀")));
    }
}
