package dev.concord.crdt.cli;

import dev.concord.crdt.Board;
import dev.concord.crdt.Canonical;
import dev.concord.crdt.Json;
import dev.concord.crdt.Op;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Line protocol for the differential harness (SPEC §9.2). One JSON request per stdin line, one
 * JSON response per stdout line:
 *
 * <pre>
 * {"cmd":"run","ops":[...],"initial_state"?:S,"server_time_ms"?:n}
 *     → {"collisions":n,"rejected":{op_id:code},"state_sha256":h,"view_sha256":h}
 * {"cmd":"validate","op":X,"server_time_ms"?:n} → {"ok":true} | {"ok":false,"code":c}
 * {"cmd":"canonical","value":X}                 → {"hex":h} | {"error":msg}
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) continue;
            Map<String, Object> res;
            try {
                res = handle((Map<?, ?>) Json.parse(line));
            } catch (RuntimeException e) {
                res = new LinkedHashMap<>();
                res.put("error", String.valueOf(e.getMessage()));
            }
            out.write(Canonical.encode(res));
            out.write('\n');
        }
        out.flush();
    }

    static Map<String, Object> handle(Map<?, ?> req) {
        Map<String, Object> res = new LinkedHashMap<>();
        Long server = req.get("server_time_ms") instanceof Long l ? l : null;
        switch ((String) req.get("cmd")) {
            case "run" -> {
                Board b = req.containsKey("initial_state") ? Board.decode(req.get("initial_state")) : Board.empty();
                Map<String, Object> rejected = new LinkedHashMap<>();
                long collisions = 0;
                for (Object raw : (List<?>) req.get("ops")) {
                    Op.Check c = check(raw, server);
                    if (!c.ok()) rejected.put(String.valueOf(((Map<?, ?>) raw).get("op_id")), c.code());
                    else collisions += b.apply(Op.parse(raw));
                }
                res.put("collisions", collisions);
                res.put("rejected", rejected);
                res.put("state_sha256", sha256(b.canonical()));
                res.put("view_sha256", sha256(Canonical.encode(b.view())));
            }
            case "validate" -> {
                Op.Check c = check(req.get("op"), server);
                res.put("ok", c.ok());
                if (!c.ok()) res.put("code", c.code());
            }
            case "canonical" -> {
                try {
                    res.put("hex", HexFormat.of().formatHex(Canonical.utf8(Canonical.encode(req.get("value")))));
                } catch (IllegalArgumentException e) {
                    res.put("error", e.getMessage());
                }
            }
            default -> throw new IllegalArgumentException("unknown cmd " + req.get("cmd"));
        }
        return res;
    }

    private static Op.Check check(Object raw, Long server) {
        Op.Check c = Op.checkShape(raw);
        return c.ok() && server != null ? Op.checkClock(Op.parse(raw), server) : c;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
