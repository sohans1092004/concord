package dev.concord.common;

import dev.concord.crdt.Canonical;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire messages. Client frames are SPEC §7.5. Internal frames (gateway ↔ owner, one WebSocket
 * per instance pair, SPEC §2.3 step 4) wrap them:
 *
 * <pre>
 * gateway → owner  {"k":"OP"|"JOIN"|"SYNC_REQUEST", "req":n, "board_id", "user_id", "op"?, "since_version"?}
 *                  {"k":"POLL", "req":n, "board_id", "since_version"}   1s fallback, SPEC §6.4
 *                  {"k":"ENSURE", "req":n, "board_id"}                   claim if unowned
 * owner → gateway  {"k":"R", "req":n, "msg":{...a §7.5 server message, or NOT_OWNER...}}
 * </pre>
 *
 * Everything is encoded canonically (§9.2.2), so servers only ever parse server-produced JSON
 * after the gateway's first parse of a client frame.
 */
public final class Msg {
    private Msg() {}

    public static final String CLOCK_SKEW = "CLOCK_SKEW", RETRY_LATER = "RETRY_LATER", UNAVAILABLE = "UNAVAILABLE",
            INVALID_OP = "INVALID_OP", UNAUTHORIZED = "UNAUTHORIZED", NOT_OWNER = "NOT_OWNER";

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String encode(Map<String, Object> m) {
        return Canonical.encode(m);
    }

    public static Map<String, Object> error(String code, String opId, String detail) {
        // A detail can quote client input; never let it break the canonical encoder.
        String safe = detail == null || Canonical.isWellFormed(detail) ? detail : "(detail not well-formed Unicode)";
        return obj("type", "ERROR", "code", code, "op_id", opId, "detail", safe);
    }

    public static Map<String, Object> clockSkew(String opId, String detail, long serverTimeMs) {
        Map<String, Object> m = error(CLOCK_SKEW, opId, detail);
        m.put("server_time_ms", serverTimeMs);
        return m;
    }

    public static Map<String, Object> ack(String opId, long version) {
        return obj("type", "OP_ACK", "op_id", opId, "version", version);
    }

    /** SPEC §7.5: a committed op, its version and the user who made it. */
    public static Map<String, Object> broadcast(Map<String, Object> op, long version, String userId) {
        return obj("type", "OP_BROADCAST", "op", op, "version", version, "user_id", userId);
    }

    public static Map<String, Object> boardVersion(long version) {
        return obj("type", "BOARD_VERSION", "version", version);
    }

    /** JOINED or SYNC: exactly one of ops (a list of {op, version, user_id}) and snapshot is set. */
    public static Map<String, Object> catchUp(String type, List<Map<String, Object>> ops, Object snapshot, long version) {
        Map<String, Object> m = obj("type", type);
        if (snapshot != null) m.put("snapshot", snapshot);
        else m.put("ops", ops);
        m.put("version", version);
        return m;
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
