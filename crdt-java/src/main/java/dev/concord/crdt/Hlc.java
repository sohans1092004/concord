package dev.concord.crdt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hybrid logical clock (SPEC §3.1, encoding §9.2.1). Kulkarni et al., 2014. Pure: callers pass
 * physical time in. {@code replicaId} is compared as UTF-8 bytes, never as {@code java.util.UUID}.
 */
public record Hlc(long physicalMs, long counter, String replicaId) implements Comparable<Hlc> {
    public static final long MAX_COUNTER = Integer.MAX_VALUE;
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public Hlc {
        if (counter > MAX_COUNTER) throw new ArithmeticException("hlc: counter overflow");
    }

    public static boolean isUuid(Object x) {
        return x instanceof String s && UUID.matcher(s).matches();
    }

    public static Hlc zero(String replicaId) {
        return new Hlc(0, 0, replicaId);
    }

    @Override
    public int compareTo(Hlc o) {
        if (physicalMs != o.physicalMs) return physicalMs < o.physicalMs ? -1 : 1;
        if (counter != o.counter) return counter < o.counter ? -1 : 1;
        return Canonical.compareUtf8(replicaId, o.replicaId);
    }

    /** Next timestamp for a local event. Strictly greater than this. */
    public Hlc tick(long nowMs) {
        long pt = Math.max(physicalMs, nowMs);
        return new Hlc(pt, pt == physicalMs ? counter + 1 : 0, replicaId);
    }

    /** Next timestamp after seeing {@code remote}. Strictly greater than both. */
    public Hlc receive(Hlc remote, long nowMs) {
        long pt = Math.max(Math.max(physicalMs, remote.physicalMs), nowMs);
        long c;
        if (pt == physicalMs && pt == remote.physicalMs) c = Math.max(counter, remote.counter) + 1;
        else if (pt == physicalMs) c = counter + 1;
        else if (pt == remote.physicalMs) c = remote.counter + 1;
        else c = 0;
        return new Hlc(pt, c, replicaId);
    }

    public Map<String, Object> encode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counter", counter);
        m.put("physical_ms", physicalMs);
        m.put("replica_id", replicaId);
        return m;
    }

    /** Parses the §9.2.1 object; null if it is not exactly that shape. */
    public static Hlc decode(Object x) {
        if (!(x instanceof Map<?, ?> m) || m.size() != 3) return null;
        if (!(m.get("physical_ms") instanceof Long pm) || pm < 0) return null;
        if (!(m.get("counter") instanceof Long c) || c < 0 || c > MAX_COUNTER) return null;
        if (!isUuid(m.get("replica_id"))) return null;
        return new Hlc(pm, c, (String) m.get("replica_id"));
    }
}
