package dev.concord.session;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters from SPEC §9.5; exported as JSON on GET /metrics (tests, drills, summary.sh) and, with
 * the histograms and labelled series in {@link #prom}, as Prometheus text on GET /metrics/prometheus.
 */
public final class Metrics {
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    /** Histograms, per-board series and gauges. */
    public final dev.concord.common.Prom prom = new dev.concord.common.Prom("concord_session");

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

    /** Prometheus text: every counter (rejected_CODE as one family by code; *_peak_bytes as gauges), then {@link #prom}. */
    public String prometheus() {
        StringBuilder b = new StringBuilder();
        new TreeMap<>(counters).forEach((k, v) -> {
            if (k.startsWith("rejected_"))
                b.append("concord_session_rejected_total{code=\"").append(k.substring(9)).append("\"} ").append(v.get()).append('\n');
            else if (k.endsWith("_peak_bytes")) b.append("concord_session_").append(k).append(' ').append(v.get()).append('\n');
            else b.append("concord_session_").append(k).append("_total ").append(v.get()).append('\n');
        });
        return b.append(prom.render()).toString();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
