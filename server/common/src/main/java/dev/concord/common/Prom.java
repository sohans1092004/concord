package dev.concord.common;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.LongSupplier;

/**
 * Prometheus metrics (SPEC §9.5) in the text exposition format, by hand: counters, gauges and
 * fixed-bucket histograms, with labels as alternating key/value strings. Served at
 * /metrics/prometheus on every service; scraped only from inside the Docker network.
 */
public final class Prom {
    /** Latency buckets, ms. */
    public static final double[] MS = {1, 2, 5, 10, 25, 50, 100, 200, 300, 500, 1_000, 2_500, 5_000, 10_000};
    /** Count buckets (group-commit batch size). */
    public static final double[] COUNT = {1, 2, 5, 10, 20, 50, 100, 200, 500};
    /** Size buckets, bytes (snapshots). */
    public static final double[] BYTES = {1_000, 10_000, 100_000, 1_000_000, 10_000_000};

    private final String prefix;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();
    private final Map<String, Hist> hists = new ConcurrentHashMap<>();

    private static final class Hist {
        final double[] bounds;
        final AtomicLong[] counts;
        final DoubleAdder sum = new DoubleAdder();
        final AtomicLong count = new AtomicLong();

        Hist(double[] bounds) {
            this.bounds = bounds;
            counts = new AtomicLong[bounds.length];
            for (int i = 0; i < bounds.length; i++) counts[i] = new AtomicLong();
        }
    }

    public Prom(String prefix) {
        this.prefix = prefix;
    }

    public void inc(String name, String... labels) {
        add(name, 1, labels);
    }

    public void add(String name, long n, String... labels) {
        counters.computeIfAbsent(key(name, labels), k -> new AtomicLong()).addAndGet(n);
    }

    /** Drops one labelled series (a board this instance no longer serves). */
    public void remove(String name, String... labels) {
        counters.remove(key(name, labels));
    }

    public void gauge(String name, LongSupplier value) {
        gauges.put(key(name), value);
    }

    public void observe(String name, double[] buckets, double value) {
        Hist h = hists.computeIfAbsent(key(name), k -> new Hist(buckets));
        for (int i = 0; i < h.bounds.length; i++) if (value <= h.bounds[i]) h.counts[i].incrementAndGet();
        h.sum.add(value);
        h.count.incrementAndGet();
    }

    private String key(String name, String... labels) {
        StringBuilder b = new StringBuilder(prefix).append('_').append(name);
        if (labels.length > 0) {
            b.append('{');
            for (int i = 0; i + 1 < labels.length; i += 2) {
                if (i > 0) b.append(',');
                b.append(labels[i]).append("=\"").append(labels[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            b.append('}');
        }
        return b.toString();
    }

    private static String family(String key) {
        int brace = key.indexOf('{');
        return brace < 0 ? key : key.substring(0, brace);
    }

    /** The text exposition format: counters get _total, histograms _bucket/_sum/_count. */
    public String render() {
        StringBuilder out = new StringBuilder();
        Map<String, Map<String, Long>> byFamily = new TreeMap<>();
        counters.forEach((k, v) -> byFamily.computeIfAbsent(family(k), f -> new TreeMap<>()).put(k, v.get()));
        byFamily.forEach((f, series) -> {
            out.append("# TYPE ").append(f).append("_total counter\n");
            series.forEach((k, v) -> {
                int brace = k.indexOf('{');
                String name = brace < 0 ? k + "_total" : k.substring(0, brace) + "_total" + k.substring(brace);
                out.append(name).append(' ').append(v).append('\n');
            });
        });
        new TreeMap<>(gauges).forEach((k, g) -> out.append("# TYPE ").append(k).append(" gauge\n").append(k).append(' ').append(g.getAsLong()).append('\n'));
        new TreeMap<>(hists).forEach((k, h) -> {
            out.append("# TYPE ").append(k).append(" histogram\n");
            for (int i = 0; i < h.bounds.length; i++)
                out.append(k).append("_bucket{le=\"").append(fmt(h.bounds[i])).append("\"} ").append(h.counts[i].get()).append('\n');
            out.append(k).append("_bucket{le=\"+Inf\"} ").append(h.count.get()).append('\n');
            out.append(k).append("_sum ").append(fmt(h.sum.sum())).append('\n');
            out.append(k).append("_count ").append(h.count.get()).append('\n');
        });
        return out.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
