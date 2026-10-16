package dev.concord.crdt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observed-remove set of entity IDs (SPEC §3.2). Encoding: SPEC §9.2.3. An ID is present while
 * it has an add-tag that is not tombstoned. Tombstones are a flat, permanent set of tags.
 */
public final class OrSet {
    private final Map<String, Set<String>> adds = new HashMap<>();
    private final Set<String> tombs = new HashSet<>();

    public void add(String id, String tag) {
        adds.computeIfAbsent(id, k -> new HashSet<>()).add(tag);
    }

    /** Tombstones exactly the observed tags. */
    public void remove(Iterable<String> observed) {
        for (String t : observed) tombs.add(t);
    }

    public boolean contains(String id) {
        Set<String> tags = adds.get(id);
        if (tags == null) return false;
        for (String t : tags) if (!tombs.contains(t)) return true;
        return false;
    }

    /** The live tags of id: what a DELETE from this replica observes. */
    public List<String> liveTags(String id) {
        List<String> out = new ArrayList<>();
        for (String t : adds.getOrDefault(id, Set.of())) if (!tombs.contains(t)) out.add(t);
        out.sort(Canonical::compareUtf8);
        return out;
    }

    /** Every ID with at least one add-tag, live or not. */
    public Set<String> ids() {
        return adds.keySet();
    }

    public static OrSet merge(OrSet a, OrSet b) {
        OrSet out = a.copy();
        out.mergeFrom(b);
        return out;
    }

    /** In-place union. */
    public void mergeFrom(OrSet other) {
        for (var e : other.adds.entrySet()) adds.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        tombs.addAll(other.tombs);
    }

    public OrSet copy() {
        OrSet out = new OrSet();
        out.mergeFrom(this);
        return out;
    }

    public Map<String, Object> encode() {
        Map<String, Object> a = new LinkedHashMap<>();
        for (var e : adds.entrySet()) {
            List<String> tags = new ArrayList<>(e.getValue());
            tags.sort(Canonical::compareUtf8);
            a.put(e.getKey(), tags);
        }
        List<String> t = new ArrayList<>(tombs);
        t.sort(Canonical::compareUtf8);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adds", a);
        m.put("tombstones", t);
        return m;
    }

    public static OrSet decode(Object x) {
        if (!(x instanceof Map<?, ?> m) || m.size() != 2 || !m.containsKey("adds") || !m.containsKey("tombstones"))
            throw new IllegalArgumentException("orset: members must be adds, tombstones");
        OrSet out = new OrSet();
        if (!(m.get("adds") instanceof Map<?, ?> adds)) throw new IllegalArgumentException("orset: adds is not an object");
        for (var e : adds.entrySet()) {
            if (!Hlc.isUuid(e.getKey())) throw new IllegalArgumentException("orset: bad id " + e.getKey());
            if (!(e.getValue() instanceof List<?> tags) || tags.isEmpty())
                throw new IllegalArgumentException("orset: adds." + e.getKey() + " must be a non-empty array");
            for (Object t : tags) {
                if (!Hlc.isUuid(t)) throw new IllegalArgumentException("orset: bad tag under " + e.getKey());
                out.add((String) e.getKey(), (String) t);
            }
        }
        if (!(m.get("tombstones") instanceof List<?> tombs)) throw new IllegalArgumentException("orset: tombstones is not an array");
        for (Object t : tombs) {
            if (!Hlc.isUuid(t)) throw new IllegalArgumentException("orset: bad tombstone");
            out.tombs.add((String) t);
        }
        return out;
    }
}
