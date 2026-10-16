package dev.concord.common;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;

/**
 * A bounded, non-blocking sender for one WebSocket (amendment 9). Jetty queues async writes
 * without limit, so a peer that stops reading would grow this process's memory forever. Here,
 * bytes accepted but not yet written are counted; once a send would exceed the limit, the
 * peer is told {@code lastWords}, the connection is closed, and nothing more is queued. If
 * the peer does not complete the close within 1s, it is cut.
 */
public final class Outbox {
    private static final ScheduledExecutorService CUTTER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("outbox-cutter").daemon().factory());

    private final Session session;
    private final long limit;
    private final String lastWords;
    private final Runnable onOverflow;
    private final AtomicLong outstanding = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong peak;

    /** {@code peak} records the largest backlog seen, for metrics and tests (may be shared). */
    public Outbox(Session session, long limitBytes, String lastWords, Runnable onOverflow, AtomicLong peak) {
        this.session = session;
        this.limit = limitBytes;
        this.lastWords = lastWords;
        this.onOverflow = onOverflow;
        this.peak = peak;
    }

    /** Queues one text frame; returns false if the connection is (now) closed instead. */
    public boolean send(String text) {
        if (closed.get() || !session.isOpen()) return false;
        long size = text.getBytes(StandardCharsets.UTF_8).length;
        long now = outstanding.addAndGet(size);
        if (now > limit) {
            outstanding.addAndGet(-size);
            overflow();
            return false;
        }
        peak.accumulateAndGet(now, Math::max);
        try {
            session.getRemote().sendString(text, new WriteCallback() {
                @Override
                public void writeSuccess() {
                    outstanding.addAndGet(-size);
                }

                @Override
                public void writeFailed(Throwable x) {
                    outstanding.addAndGet(-size);
                }
            });
            return true;
        } catch (RuntimeException e) {
            outstanding.addAndGet(-size);
            return false;
        }
    }

    public long outstanding() {
        return outstanding.get();
    }

    private void overflow() {
        if (!closed.compareAndSet(false, true)) return;
        onOverflow.run();
        try {
            if (lastWords != null) session.getRemote().sendString(lastWords, WriteCallback.NOOP);
            session.close(1013, "slow consumer: reconnect elsewhere");
        } catch (RuntimeException e) {
            // already going
        }
        // Not conditional on isOpen(): a session reports closed as soon as the close starts, but a
        // peer that never reads never finishes it, and its backlog would stay queued.
        CUTTER.schedule(() -> {
            try {
                session.disconnect();
            } catch (RuntimeException e) {
                // already gone
            }
        }, 1, TimeUnit.SECONDS);
    }
}
