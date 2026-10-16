package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class HlcTest {
    @Provide Arbitrary<Hlc> wide() { return Gen.wideHlc().filter(h -> h.counter() < Hlc.MAX_COUNTER - 100); }

    @Provide Arbitrary<Hlc> narrow() { return Gen.hlc(); }

    @Property
    void tickIsStrictlyIncreasingEvenWhenTheWallClockGoesBackwards(
            @ForAll("wide") Hlc start, @ForAll @Size(max = 50) List<@LongRange(min = 0, max = 1L << 45) Long> nows) {
        Hlc h = start;
        for (long now : nows) {
            Hlc next = h.tick(now);
            assertEquals(1, next.compareTo(h));
            assertTrue(next.physicalMs() >= now);
            h = next;
        }
    }

    @Property
    void receiveExceedsBothClocks(@ForAll("wide") Hlc local, @ForAll("wide") Hlc remote, @ForAll @LongRange(min = 0, max = 1L << 45) long now) {
        Hlc r = local.receive(remote, now);
        assertEquals(1, r.compareTo(local));
        assertEquals(1, r.compareTo(remote));
        assertEquals(local.replicaId(), r.replicaId());
    }

    @Property
    void compareIsATotalOrder(@ForAll("narrow") Hlc a, @ForAll("narrow") Hlc b, @ForAll("narrow") Hlc c) {
        assertEquals(0, Integer.signum(a.compareTo(b)) + Integer.signum(b.compareTo(a)));
        if (a.compareTo(b) <= 0 && b.compareTo(c) <= 0) assertTrue(a.compareTo(c) <= 0);
        assertEquals(a.compareTo(b) == 0, a.equals(b));
    }

    @Test
    void ordersPhysicalThenCounterThenReplicaAsBytes() {
        String r1 = Gen.uuidN("a0000000", 1), r2 = Gen.uuidN("a0000000", 2);
        assertEquals(1, new Hlc(2, 0, r1).compareTo(new Hlc(1, 9, r2)));
        assertEquals(1, new Hlc(1, 1, r1).compareTo(new Hlc(1, 0, r2)));
        assertEquals(1, new Hlc(1, 0, r2).compareTo(new Hlc(1, 0, r1)));
        // java.util.UUID.compareTo uses signed longs, so f... (sign bit set) sorts before 1...;
        // byte order puts it after. The HLC must follow bytes.
        String hi = "f0000000-0000-4000-8000-000000000000", lo = "10000000-0000-4000-8000-000000000000";
        assertTrue(java.util.UUID.fromString(hi).compareTo(java.util.UUID.fromString(lo)) < 0);
        assertEquals(1, new Hlc(1, 0, hi).compareTo(new Hlc(1, 0, lo)));
    }

    @Property
    void encodeDecodeRoundTrips(@ForAll("wide") Hlc h) {
        assertEquals(h, Hlc.decode(Json.parse(Canonical.encode(h.encode()))));
    }

    @Test
    void decodeRejectsMalformedClocks() {
        Map<String, Object> ok = new Hlc(5, 0, Gen.uuidN("a0000000", 1)).encode();
        assertEquals(new Hlc(5, 0, Gen.uuidN("a0000000", 1)), Hlc.decode(ok));
        assertNull(Hlc.decode(with(ok, "counter", 1.5)));
        assertNull(Hlc.decode(with(ok, "counter", -1L)));
        assertNull(Hlc.decode(with(ok, "counter", 1L << 31)));
        assertNull(Hlc.decode(with(ok, "replica_id", Gen.uuidN("A0000000", 1))));
        assertNull(Hlc.decode(with(ok, "extra", 1L)));
        assertNull(Hlc.decode(List.of(1L, 2L, 3L)));
    }

    @Test
    void tickRefusesToOverflowTheCounter() {
        assertThrows(ArithmeticException.class, () -> new Hlc(10, Hlc.MAX_COUNTER, Gen.uuidN("a0000000", 1)).tick(5));
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put(k, v);
        return out;
    }
}
