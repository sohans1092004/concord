package dev.concord.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Prometheus text format, as Prometheus parses it: names, labels, cumulative buckets. */
class PromTest {
    @Test
    void rendersCountersGaugesAndCumulativeHistograms() {
        Prom p = new Prom("concord_test");
        p.inc("ops", "board_id", "b1");
        p.add("ops", 2, "board_id", "b1");
        p.inc("ops", "board_id", "b\"2");
        p.gauge("connections", () -> 7);
        for (double v : List.of(3.0, 40.0, 40.0, 9_000.0, 20_000.0)) p.observe("latency_ms", Prom.MS, v);
        p.inc("gone", "board_id", "b3");
        p.remove("gone", "board_id", "b3");
        String text = p.render();
        for (String line : List.of(
                "# TYPE concord_test_ops_total counter",
                "concord_test_ops_total{board_id=\"b1\"} 3",
                "concord_test_ops_total{board_id=\"b\\\"2\"} 1",
                "# TYPE concord_test_connections gauge",
                "concord_test_connections 7",
                "# TYPE concord_test_latency_ms histogram",
                "concord_test_latency_ms_bucket{le=\"2\"} 0",
                "concord_test_latency_ms_bucket{le=\"5\"} 1",
                "concord_test_latency_ms_bucket{le=\"50\"} 3",
                "concord_test_latency_ms_bucket{le=\"10000\"} 4",
                "concord_test_latency_ms_bucket{le=\"+Inf\"} 5",
                "concord_test_latency_ms_sum 29083",
                "concord_test_latency_ms_count 5"))
            assertTrue(text.contains(line + "\n"), () -> "missing \"" + line + "\" in:\n" + text);
        assertTrue(!text.contains("gone"), "a removed series is not rendered");
    }
}
