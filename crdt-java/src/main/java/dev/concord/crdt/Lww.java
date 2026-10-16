package dev.concord.crdt;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Last-writer-wins registers, one per (entity, field) (SPEC §3.4). Encoding: §9.2.3. */
public final class Lww {
    public record Register(Object value, Hlc hlc) {}

    /** The surviving register and whether the defensive tie-break fired. */
    public record Pick(Register winner, boolean collision) {}

    private final Map<String, Map<String, Register>> regs = new HashMap<>();

    /**
     * Higher HLC wins. Equal HLCs with different values keep the greater canonical encoding,
     * compared as unsigned UTF-8 bytes (Arrays.compareUnsigned), and report a collision.
     */
    public static Pick pick(Register a, Register b) {
        int c = a.hlc().compareTo(b.hlc());
        if (c != 0) return new Pick(c > 0 ? a : b, false);
        if (Objects.equals(a.value(), b.value())) return new Pick(a, false);
        int v = Canonical.compareBytes(Canonical.utf8(Canonical.encode(a.value())), Canonical.utf8(Canonical.encode(b.value())));
        return new Pick(v >= 0 ? a : b, v != 0);
    }

    public Register get(String entity, String field) {
        Map<String, Register> f = regs.get(entity);
        return f == null ? null : f.get(field);
    }

    public Map<String, Register> fields(String entity) {
        return regs.getOrDefault(entity, Map.of());
    }

    /** Writes if hlc wins. Returns 1 if the tie-break fired, else 0. */
    public int set(String entity, String field, Object value, Hlc hlc) {
        return put(entity, field, new Register(value, hlc));
    }

    private int put(String entity, String field, Register r) {
        Map<String, Register> f = regs.computeIfAbsent(entity, k -> new HashMap<>());
        Register cur = f.get(field);
        if (cur == null) {
            f.put(field, r);
            return 0;
        }
        Pick p = pick(cur, r);
        if (p.winner() != cur) f.put(field, p.winner());
        return p.collision() ? 1 : 0;
    }

    /** Merged copy plus the number of registers whose tie-break fired. */
    public record Merged(Lww lww, int collisions) {}

    public static Merged merge(Lww a, Lww b) {
        Lww out = a.copy();
        return new Merged(out, out.mergeFrom(b));
    }

    /** In-place merge; returns the collision count. */
    public int mergeFrom(Lww other) {
        int collisions = 0;
        for (var e : other.regs.entrySet()) for (var f : e.getValue().entrySet()) collisions += put(e.getKey(), f.getKey(), f.getValue());
        return collisions;
    }

    public Lww copy() {
        Lww out = new Lww();
        for (var e : regs.entrySet()) out.regs.put(e.getKey(), new HashMap<>(e.getValue())); // registers are immutable
        return out;
    }

    public Map<String, Object> encode() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : regs.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            for (var r : e.getValue().entrySet()) {
                Map<String, Object> reg = new LinkedHashMap<>();
                reg.put("hlc", r.getValue().hlc().encode());
                reg.put("value", r.getValue().value());
                f.put(r.getKey(), reg);
            }
            out.put(e.getKey(), f);
        }
        return out;
    }

    /** Structural decode. Field-specific value checks belong to the caller (board). */
    public static Lww decode(Object x) {
        if (!(x instanceof Map<?, ?> m)) throw new IllegalArgumentException("lww: not an object");
        Lww out = new Lww();
        for (var e : m.entrySet()) {
            String entity = (String) e.getKey();
            if (!Hlc.isUuid(entity)) throw new IllegalArgumentException("lww: bad entity " + entity);
            if (!(e.getValue() instanceof Map<?, ?> fields) || fields.isEmpty()) throw new IllegalArgumentException("lww: " + entity + " has no fields");
            for (var f : fields.entrySet()) {
                if (!(f.getValue() instanceof Map<?, ?> reg) || reg.size() != 2 || !reg.containsKey("value"))
                    throw new IllegalArgumentException("lww: " + entity + "." + f.getKey() + " must have exactly hlc and value");
                Hlc hlc = Hlc.decode(reg.get("hlc"));
                if (hlc == null) throw new IllegalArgumentException("lww: " + entity + "." + f.getKey() + ".hlc is invalid");
                Canonical.encode(reg.get("value")); // throws on non-integers, lone surrogates
                out.put(entity, (String) f.getKey(), new Register(reg.get("value"), hlc));
            }
        }
        return out;
    }
}
