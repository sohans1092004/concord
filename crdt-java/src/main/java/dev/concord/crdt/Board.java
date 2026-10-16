package dev.concord.crdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Board state: two OR-Sets (lists, cards) and one LWW map (SPEC §3, §4.4). View: §3.6, §9.2.4. */
public final class Board {
    private static final List<String> CARD_FIELDS = List.of("title", "description", "assignee", "labels");

    private final OrSet lists;
    private final OrSet cards;
    private final Lww registers;

    private Board(OrSet lists, OrSet cards, Lww registers) {
        this.lists = lists;
        this.cards = cards;
        this.registers = registers;
    }

    public static Board empty() {
        return new Board(new OrSet(), new OrSet(), new Lww());
    }

    /** Applies a validated op. Returns the number of LWW tie-breaks that fired. */
    public int apply(Op op) {
        Lww r = registers;
        return switch (op) {
            case Op.AddList o -> {
                lists.add(o.listId(), o.opId());
                yield r.set(o.listId(), "title", o.title(), o.hlc()) + r.set(o.listId(), "placement", Op.placement(null, o.positionKey()), o.hlc());
            }
            case Op.AddCard o -> {
                cards.add(o.cardId(), o.opId());
                yield r.set(o.cardId(), "title", o.title(), o.hlc()) + r.set(o.cardId(), "placement", Op.placement(o.listId(), o.positionKey()), o.hlc());
            }
            case Op.SetField o -> r.set(o.entityId(), o.field(), o.value(), o.hlc());
            case Op.SetPlacement o -> r.set(o.entityId(), "placement", Op.placement(o.listId(), o.positionKey()), o.hlc());
            case Op.DeleteList o -> {
                lists.remove(o.listId(), o.observedTags());
                yield 0;
            }
            case Op.DeleteCard o -> {
                cards.remove(o.cardId(), o.observedTags());
                yield 0;
            }
            case Op.RestoreList o -> {
                lists.add(o.listId(), o.opId());
                yield 0;
            }
            case Op.RestoreCard o -> {
                cards.add(o.cardId(), o.opId());
                yield 0;
            }
        };
    }

    public record Merged(Board board, int collisions) {}

    public static Merged merge(Board a, Board b) {
        Board out = a.copy();
        return new Merged(out, out.mergeFrom(b));
    }

    /** In-place merge; returns the collision count. */
    public int mergeFrom(Board other) {
        lists.mergeFrom(other.lists);
        cards.mergeFrom(other.cards);
        return registers.mergeFrom(other.registers);
    }

    public Board copy() {
        return new Board(lists.copy(), cards.copy(), registers.copy());
    }

    /** Live tags of a list or card: the observed_tags a DELETE from this replica carries. */
    public List<String> liveTags(boolean list, String id) {
        return (list ? lists : cards).liveTags(id);
    }

    private String positionKey(String id) {
        Lww.Register p = registers.get(id, "placement");
        return p == null ? null : (String) ((Map<?, ?>) p.value()).get("position_key");
    }

    private static final Comparator<Map<String, Object>> BY_KEY_THEN_ID = (x, y) -> {
        int c = Canonical.compareUtf8((String) x.get("position_key"), (String) y.get("position_key"));
        return c != 0 ? c : Canonical.compareUtf8((String) x.get("id"), (String) y.get("id"));
    };

    @SuppressWarnings("unchecked")
    public Map<String, Object> view() {
        Map<String, Map<String, Object>> rendered = new HashMap<>();
        for (String id : lists.ids()) {
            String key = positionKey(id);
            if (key == null || !lists.contains(id)) continue;
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", id);
            l.put("position_key", key);
            Lww.Register title = registers.get(id, "title");
            if (title != null) l.put("title", title.value());
            l.put("cards", new ArrayList<Map<String, Object>>());
            rendered.put(id, l);
        }
        List<Map<String, Object>> orphaned = new ArrayList<>();
        for (String id : cards.ids()) {
            String key = positionKey(id);
            if (key == null || !cards.contains(id)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("position_key", key);
            for (String f : CARD_FIELDS) {
                Lww.Register reg = registers.get(id, f);
                if (reg != null) c.put(f, reg.value());
            }
            Object listId = ((Map<?, ?>) registers.get(id, "placement").value()).get("list_id");
            Map<String, Object> home = listId == null ? null : rendered.get(listId);
            (home != null ? (List<Map<String, Object>>) home.get("cards") : orphaned).add(c);
        }
        List<Map<String, Object>> out = new ArrayList<>(rendered.values());
        out.sort(BY_KEY_THEN_ID);
        for (Map<String, Object> l : out) ((List<Map<String, Object>>) l.get("cards")).sort(BY_KEY_THEN_ID);
        if (!orphaned.isEmpty()) {
            orphaned.sort(BY_KEY_THEN_ID);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", Op.NIL_UUID);
            o.put("cards", orphaned);
            out.add(o);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("lists", out);
        return v;
    }

    public Map<String, Object> encode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cards", cards.encode());
        m.put("lists", lists.encode());
        m.put("registers", registers.encode());
        return m;
    }

    /** The canonical encoding whose SHA-256 is the state hash. */
    public String canonical() {
        return Canonical.encode(encode());
    }

    public static Board decode(Object x) {
        if (!(x instanceof Map<?, ?> m) || !new TreeSet<>(m.keySet()).equals(new TreeSet<>(List.of("cards", "lists", "registers"))))
            throw new IllegalArgumentException("board: members must be cards, lists, registers");
        Lww registers = Lww.decode(m.get("registers"));
        for (Object entity : ((Map<?, ?>) m.get("registers")).keySet()) {
            Lww.Register p = registers.get((String) entity, "placement");
            if (p == null) continue;
            boolean ok = p.value() instanceof Map<?, ?> v
                    && v.get("position_key") instanceof String
                    && v.keySet().stream().allMatch(k -> k.equals("position_key") || (k.equals("list_id") && Hlc.isUuid(v.get("list_id"))));
            if (!ok) throw new IllegalArgumentException("board: " + entity + ".placement has the wrong shape");
        }
        return new Board(OrSet.decode(m.get("lists")), OrSet.decode(m.get("cards")), registers);
    }
}
