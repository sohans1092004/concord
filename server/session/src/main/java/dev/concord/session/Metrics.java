package dev.concord.session;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Counters from SPEC §9.5 that Phase 2 needs to see; exported as JSON on GET /metrics. */
public final class Metrics {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void inc(String name) {
        add(name, 1);
    }

    public void add(String name, long n) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(n);
    }

    /** A counter used as a high-water mark (see Outbox). */
    public AtomicLong peak(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicLong());
    }

    public long get(String name) {
        AtomicLong a = counters.get(name);
        return a == null ? 0 : a.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
