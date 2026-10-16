package dev.concord.gateway;

/**
 * Per-connection rate limit (SPEC §9.4, amendment 7): 20 ops/s, burst 50. In the gateway's
 * memory, because a connection lives on exactly one gateway and must keep working while Redis
 * is down. Used only from that connection's message handler.
 */
final class TokenBucket {
    private final double ratePerMs;
    private final double burst;
    private double tokens;
    private long last;

    TokenBucket(double perSecond, double burst, long nowMs) {
        this.ratePerMs = perSecond / 1000.0;
        this.burst = burst;
        this.tokens = burst;
        this.last = nowMs;
    }

    synchronized boolean take(long nowMs) {
        tokens = Math.min(burst, tokens + (nowMs - last) * ratePerMs);
        last = nowMs;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
