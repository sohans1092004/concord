package dev.concord.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/**
 * Rendezvous (highest-random-weight) hashing of a board over live instances (SPEC §2.3 step 2).
 * Every gateway computes the same order from the same instance list, with no coordination.
 */
public final class Rendezvous {
    private Rendezvous() {}

    public record Instance(String id, String address) {
        /** Parses an {@code instances} member, {@code id@address}. */
        public static Instance parse(String member) {
            int at = member.indexOf('@');
            return new Instance(member.substring(0, at), member.substring(at + 1));
        }

        public String member() {
            return id + "@" + address;
        }
    }

    /** Instances in preference order for this board: the first is the preferred owner. */
    public static List<Instance> order(String board, List<Instance> live) {
        return live.stream()
                .sorted(Comparator.comparingLong((Instance i) -> weight(board, i.id())).reversed()
                        .thenComparing(Instance::id))
                .toList();
    }

    static long weight(String board, String instance) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((board + "|" + instance).getBytes(StandardCharsets.UTF_8));
            // Unsigned compare via flipping the sign bit, so the order matches the hash bytes.
            return ByteBuffer.wrap(h, 0, 8).getLong() ^ Long.MIN_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
