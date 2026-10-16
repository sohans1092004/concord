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
 * adds[id] minus tombstones[id] is non-empty. Tombstones are per entity, so a delete naming X
 * only ever tombstones (X, tag) pairs, and are permanent.
 */
public final class OrSet {
    private final Map<String, Set<String>> adds = new HashMap<>();
    private final Map<String, Set<String>> tombs = new HashMap<>();

    public void add(String id, String tag) {
        put(adds, id, tag);
    }

    /** Tombstones exactly the (id, tag) pairs observed. */
    public void remove(String id, Iterable<String> observed) {
        for (String t : observed) put(tombs, id, t);
    }

    public boolean contains(String id) {
        return !liveTags(id).isEmpty();
    }

    /** The live tags of id: what a DELETE from this replica observes. */
    public List<String> liveTags(String id) {
        Set<String> dead = tombs.getOrDefault(id, Set.of());
        List<String> out = new ArrayList<>();
        for (String t : adds.getOrDefault(id, Set.of())) if (!dead.contains(t)) out.add(t);
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
        for (var e : other.adds.entrySet()) for (String t : e.getValue()) put(adds, e.getKey(), t);
        for (var e : other.tombs.entrySet()) for (String t : e.getValue()) put(tombs, e.getKey(), t);
    }

    public OrSet copy() {
        OrSet out = new OrSet();
        out.mergeFrom(this);
        return out;
    }

    public Map<String, Object> encode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adds", encodeTags(adds));
        m.put("tombstones", encodeTags(tombs));
        return m;
    }

    public static OrSet decode(Object x) {
        if (!(x instanceof Map<?, ?> m) || m.size() != 2 || !m.containsKey("adds") || !m.containsKey("tombstones"))
            throw new IllegalArgumentException("orset: members must be adds, tombstones");
        OrSet out = new OrSet();
        decodeTags(m.get("adds"), "adds", out.adds);
        decodeTags(m.get("tombstones"), "tombstones", out.tombs);
        return out;
    }

    private static void put(Map<String, Set<String>> m, String id, String tag) {
        m.computeIfAbsent(id, k -> new HashSet<>()).add(tag);
    }

    /** {id: [tags]} with sorted tags; an entity with no tags has no entry. */
    private static Map<String, Object> encodeTags(Map<String, Set<String>> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            List<String> tags = new ArrayList<>(e.getValue());
            tags.sort(Canonical::compareUtf8);
            out.put(e.getKey(), tags);
        }
        return out;
    }

    private static void decodeTags(Object x, String name, Map<String, Set<String>> into) {
        if (!(x instanceof Map<?, ?> tagMap)) throw new IllegalArgumentException("orset: " + name + " is not an object");
        for (var e : tagMap.entrySet()) {
            if (!Hlc.isUuid(e.getKey())) throw new IllegalArgumentException("orset: bad id " + e.getKey());
            if (!(e.getValue() instanceof List<?> tags) || tags.isEmpty())
                throw new IllegalArgumentException("orset: " + name + "." + e.getKey() + " must be a non-empty array");
            for (Object t : tags) {
                if (!Hlc.isUuid(t)) throw new IllegalArgumentException("orset: bad tag under " + name + "." + e.getKey());
                put(into, (String) e.getKey(), (String) t);
            }
        }
    }
}
