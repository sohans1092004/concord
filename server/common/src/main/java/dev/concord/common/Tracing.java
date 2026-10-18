package dev.concord.common;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Traces (SPEC §9.5): one trace per edit, client send → gateway → owner → commit → broadcast →
 * peer render. The trace ID is the op's op_id (a UUID is 128 bits), so nothing new travels on the
 * wire: every service finds the trace from the op it already handles. Every span is a child of
 * the edit's root, the sender's client.send (reported through /api/telemetry), whose span ID is
 * the op_id's lower 64 bits. Off, and free, unless CONCORD_OTLP_ENDPOINT is set: the trial VM
 * runs without it.
 */
public final class Tracing {
    private static volatile Tracer tracer;
    private static volatile boolean enabled;
    private static SdkTracerProvider provider;
    /** {traceId, spanId} for the next span this thread starts: the edit's root. */
    private static final ThreadLocal<String[]> forced = new ThreadLocal<>();

    private Tracing() {}

    public static synchronized void init(String service, String otlpEndpoint) {
        if (otlpEndpoint == null || otlpEndpoint.isBlank() || enabled) return;
        IdGenerator random = IdGenerator.random();
        IdGenerator ids = new IdGenerator() {
            @Override
            public String generateSpanId() {
                String[] f = forced.get();
                return f != null ? f[1] : random.generateSpanId();
            }

            @Override
            public String generateTraceId() {
                String[] f = forced.get();
                return f != null ? f[0] : random.generateTraceId();
            }
        };
        provider = SdkTracerProvider.builder()
                .setIdGenerator(ids)
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), service))))
                .addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint).build()).build())
                .build();
        tracer = provider.get("dev.concord");
        enabled = true;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static long nowNanos() {
        Instant i = Instant.now();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    /** The op's trace ID: its op_id without dashes, or null if it is not a UUID. */
    public static String traceId(String opId) {
        if (opId == null) return null;
        String t = opId.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return t.length() == 32 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0) && !t.equals("0".repeat(32)) ? t : null;
    }

    /** The edit's root span ID: the trace ID's lower 64 bits (never all zero). */
    static String rootSpanId(String traceId) {
        String s = traceId.substring(16);
        return s.equals("0".repeat(16)) ? "0000000000000001" : s;
    }

    /** A span of the op's trace, a child of the edit's root; times in epoch nanoseconds. */
    public static void span(String opId, String name, long startNanos, long endNanos, String... attributes) {
        if (!enabled) return;
        String t = traceId(opId);
        if (t == null) return;
        SpanContext root = SpanContext.createFromRemoteParent(t, rootSpanId(t), TraceFlags.getSampled(), TraceState.getDefault());
        finish(tracer.spanBuilder(name).setParent(Context.root().with(Span.wrap(root))), opId, startNanos, endNanos, attributes);
    }

    /** The edit's root span (the sender's client.send), whose ID every other span names as parent. */
    public static void root(String opId, String name, long startNanos, long endNanos, String... attributes) {
        if (!enabled) return;
        String t = traceId(opId);
        if (t == null) return;
        forced.set(new String[] {t, rootSpanId(t)});
        try {
            finish(tracer.spanBuilder(name).setNoParent(), opId, startNanos, endNanos, attributes);
        } finally {
            forced.remove();
        }
    }

    private static void finish(SpanBuilder b, String opId, long startNanos, long endNanos, String... attributes) {
        Span s = b.setStartTimestamp(startNanos, TimeUnit.NANOSECONDS).startSpan();
        s.setAttribute("op_id", opId);
        for (int i = 0; i + 1 < attributes.length; i += 2) s.setAttribute(attributes[i], attributes[i + 1]);
        s.end(Math.max(endNanos, startNanos), TimeUnit.NANOSECONDS);
    }

    /** Flushes what is buffered (a test, or shutdown). */
    public static void flush() {
        SdkTracerProvider p = provider;
        if (p != null) p.forceFlush().join(10, TimeUnit.SECONDS);
    }
}
