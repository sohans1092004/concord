package dev.concord.crdt;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.UUID;

/** Op types and shape validation (SPEC §4.1, §4.2 steps 1, 3 and 5). */
public sealed interface Op {
    String NIL_UUID = "00000000-0000-0000-0000-000000000000";
    int MAX_TITLE = 200;
    int MAX_DESCRIPTION = 10_000;
    int MAX_LABELS = 20;
    int MAX_LABEL = 50;
    long MAX_SKEW_MS = 60_000;

    String opId();

    String boardId();

    Hlc hlc();

    String replicaId();

    record AddList(String opId, String boardId, Hlc hlc, String replicaId, String listId, String positionKey, String title) implements Op {}

    record AddCard(String opId, String boardId, Hlc hlc, String replicaId, String cardId, String listId, String positionKey, String title) implements Op {}

    /** {@code value} is a String, null (assignee) or {@code List<String>} (labels). */
    record SetField(String opId, String boardId, Hlc hlc, String replicaId, String entityId, String field, Object value) implements Op {}

    /** {@code listId} is null for the list form of placement. */
    record SetPlacement(String opId, String boardId, Hlc hlc, String replicaId, String entityId, String listId, String positionKey) implements Op {}

    record DeleteList(String opId, String boardId, Hlc hlc, String replicaId, String listId, List<String> observedTags) implements Op {}

    record DeleteCard(String opId, String boardId, Hlc hlc, String replicaId, String cardId, List<String> observedTags) implements Op {}

    record RestoreList(String opId, String boardId, Hlc hlc, String replicaId, String listId) implements Op {}

    record RestoreCard(String opId, String boardId, Hlc hlc, String replicaId, String cardId) implements Op {}

    /** Result of a check. {@code code} is INVALID_OP or CLOCK_SKEW when not ok. */
    record Check(boolean ok, String code, String reason) {
        static final Check OK = new Check(true, null, null);

        static Check invalid(String reason) {
            return new Check(false, "INVALID_OP", reason);
        }
    }

    Map<String, List<String>> MEMBERS = Map.of(
            "ADD_LIST", List.of("list_id", "placement", "title"),
            "ADD_CARD", List.of("card_id", "placement", "title"),
            "SET_FIELD", List.of("entity_id", "field", "value"),
            "SET_PLACEMENT", List.of("entity_id", "placement"),
            "DELETE_LIST", List.of("list_id", "observed_tags"),
            "DELETE_CARD", List.of("card_id", "observed_tags"),
            "RESTORE_LIST", List.of("list_id"),
            "RESTORE_CARD", List.of("card_id"));

    /** §4.2 steps 1 and 5 on parsed JSON. Pure; no state needed. */
    static boolean validateShape(Object x) {
        return checkShape(x).ok();
    }

    static Check checkShape(Object x) {
        String err = Validation.shapeError(x);
        return err == null ? Check.OK : Check.invalid(err);
    }

    /** Parses JSON that passes {@link #checkShape}; throws IllegalArgumentException otherwise. */
    static Op parse(Object x) {
        Check c = checkShape(x);
        if (!c.ok()) throw new IllegalArgumentException("op: " + c.reason());
        Map<?, ?> m = (Map<?, ?>) x;
        String id = (String) m.get("op_id"), board = (String) m.get("board_id"), rep = (String) m.get("replica_id");
        Hlc hlc = Hlc.decode(m.get("hlc"));
        Map<?, ?> p = m.get("placement") instanceof Map<?, ?> pm ? pm : Map.of();
        return switch ((String) m.get("type")) {
            case "ADD_LIST" -> new AddList(id, board, hlc, rep, (String) m.get("list_id"), (String) p.get("position_key"), (String) m.get("title"));
            case "ADD_CARD" -> new AddCard(id, board, hlc, rep, (String) m.get("card_id"), (String) p.get("list_id"), (String) p.get("position_key"), (String) m.get("title"));
            case "SET_FIELD" -> new SetField(id, board, hlc, rep, (String) m.get("entity_id"), (String) m.get("field"), m.get("value"));
            case "SET_PLACEMENT" -> new SetPlacement(id, board, hlc, rep, (String) m.get("entity_id"), (String) p.get("list_id"), (String) p.get("position_key"));
            case "DELETE_LIST" -> new DeleteList(id, board, hlc, rep, (String) m.get("list_id"), strings(m.get("observed_tags")));
            case "DELETE_CARD" -> new DeleteCard(id, board, hlc, rep, (String) m.get("card_id"), strings(m.get("observed_tags")));
            case "RESTORE_LIST" -> new RestoreList(id, board, hlc, rep, (String) m.get("list_id"));
            case "RESTORE_CARD" -> new RestoreCard(id, board, hlc, rep, (String) m.get("card_id"));
            default -> throw new IllegalStateException();
        };
    }

    private static List<String> strings(Object x) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) x) out.add((String) o);
        return List.copyOf(out);
    }

    /** §3.1 skew guard: reject iff physical_ms > server_time_ms + 60000 (strict). */
    static Check checkClock(Op op, long serverTimeMs) {
        return op.hlc().physicalMs() > serverTimeMs + MAX_SKEW_MS
                ? new Check(false, "CLOCK_SKEW", "physical_ms is more than " + MAX_SKEW_MS + "ms ahead of server time")
                : Check.OK;
    }

    /** A fresh UUID v4 from the JDK's CSPRNG. */
    static String newOpId() {
        return UUID.randomUUID().toString();
    }

    /** Placement as stored in the LWW register: {position_key} or {list_id, position_key}. */
    static Map<String, Object> placement(String listId, String positionKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (listId != null) m.put("list_id", listId);
        m.put("position_key", positionKey);
        return m;
    }

    /** The op as §4.1 JSON. */
    default Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op_id", opId());
        m.put("board_id", boardId());
        m.put("hlc", hlc().encode());
        m.put("replica_id", replicaId());
        switch (this) {
            case AddList o -> { m.put("type", "ADD_LIST"); m.put("list_id", o.listId()); m.put("placement", placement(null, o.positionKey())); m.put("title", o.title()); }
            case AddCard o -> { m.put("type", "ADD_CARD"); m.put("card_id", o.cardId()); m.put("placement", placement(o.listId(), o.positionKey())); m.put("title", o.title()); }
            case SetField o -> { m.put("type", "SET_FIELD"); m.put("entity_id", o.entityId()); m.put("field", o.field()); m.put("value", o.value()); }
            case SetPlacement o -> { m.put("type", "SET_PLACEMENT"); m.put("entity_id", o.entityId()); m.put("placement", placement(o.listId(), o.positionKey())); }
            case DeleteList o -> { m.put("type", "DELETE_LIST"); m.put("list_id", o.listId()); m.put("observed_tags", o.observedTags()); }
            case DeleteCard o -> { m.put("type", "DELETE_CARD"); m.put("card_id", o.cardId()); m.put("observed_tags", o.observedTags()); }
            case RestoreList o -> { m.put("type", "RESTORE_LIST"); m.put("list_id", o.listId()); }
            case RestoreCard o -> { m.put("type", "RESTORE_CARD"); m.put("card_id", o.cardId()); }
        }
        return m;
    }

    /** The validation rules; kept out of the public surface. */
    final class Validation {
        private static final List<String> BASE = List.of("board_id", "hlc", "op_id", "replica_id", "type");

        private Validation() {}

        static String shapeError(Object x) {
            if (!(x instanceof Map<?, ?> m)) return "op is not an object";
            if (!(m.get("type") instanceof String type) || !MEMBERS.containsKey(type)) return "unknown type " + m.get("type");
            List<String> expected = new ArrayList<>(BASE);
            expected.addAll(MEMBERS.get(type));
            String e = exactMembers(m, expected);
            if (e != null) return e;
            for (String name : List.of("op_id", "board_id", "replica_id")) if ((e = checkId(m.get(name), name)) != null) return e;
            Hlc hlc = Hlc.decode(m.get("hlc"));
            if (hlc == null) return "hlc is not a valid HLC";
            if (!hlc.replicaId().equals(m.get("replica_id"))) return "hlc.replica_id differs from replica_id";
            return switch (type) {
                case "ADD_LIST" -> first(checkId(m.get("list_id"), "list_id"), checkPlacement(m.get("placement"), "list"), fieldValue("title", m.get("title")));
                case "ADD_CARD" -> first(checkId(m.get("card_id"), "card_id"), checkPlacement(m.get("placement"), "card"), fieldValue("title", m.get("title")));
                case "SET_FIELD" -> first(checkId(m.get("entity_id"), "entity_id"), fieldValue(m.get("field"), m.get("value")));
                case "SET_PLACEMENT" -> first(checkId(m.get("entity_id"), "entity_id"), checkPlacement(m.get("placement"), "either"));
                case "DELETE_LIST", "DELETE_CARD" -> first(checkId(m.get(type.equals("DELETE_LIST") ? "list_id" : "card_id"), "id"), tags(m.get("observed_tags")));
                case "RESTORE_LIST" -> checkId(m.get("list_id"), "list_id");
                case "RESTORE_CARD" -> checkId(m.get("card_id"), "card_id");
                default -> throw new IllegalStateException();
            };
        }

        private static String first(String... errs) {
            for (String e : errs) if (e != null) return e;
            return null;
        }

        private static String exactMembers(Map<?, ?> m, List<String> expected) {
            for (Object k : m.keySet()) if (!expected.contains(k)) return "unexpected member " + k;
            for (String k : expected) if (!m.containsKey(k)) return "missing member " + k;
            return null;
        }

        /** Lowercase canonical UUID, never the nil UUID (reserved for Orphaned). */
        private static String checkId(Object v, String name) {
            if (!Hlc.isUuid(v)) return name + " is not a lowercase canonical UUID";
            if (NIL_UUID.equals(v)) return name + " is the reserved nil UUID";
            return null;
        }

        private static String checkPlacement(Object x, String form) {
            if (!(x instanceof Map<?, ?> p)) return "placement is not an object";
            boolean isCard = p.containsKey("list_id");
            if ((form.equals("list") && isCard) || (form.equals("card") && !isCard)) return "placement must be the " + form + " form";
            String e = exactMembers(p, isCard ? List.of("list_id", "position_key") : List.of("position_key"));
            if (e != null) return "placement: " + e;
            if (isCard && (e = checkId(p.get("list_id"), "list_id")) != null) return "placement." + e;
            if (!Keys.validateKey(p.get("position_key"))) return "placement.position_key is not a valid key";
            return null;
        }

        private static boolean goodString(Object x, int max) {
            // No U+0000: Postgres JSONB cannot store it (SPEC §4.2).
            return x instanceof String s && Canonical.isWellFormed(s) && s.indexOf(0) < 0 && s.codePointCount(0, s.length()) <= max;
        }

        private static String fieldValue(Object field, Object value) {
            if (!(field instanceof String f)) return "field is not settable";
            return switch (f) {
                case "title" -> goodString(value, MAX_TITLE) ? null : "title is not a string of at most " + MAX_TITLE + " code points";
                case "description" -> goodString(value, MAX_DESCRIPTION) ? null : "description is not a string of at most " + MAX_DESCRIPTION + " code points";
                case "assignee" -> value == null ? null : checkId(value, "assignee");
                case "labels" -> labels(value);
                default -> "field " + f + " is not settable";
            };
        }

        private static String labels(Object x) {
            if (!(x instanceof List<?> list)) return "labels is not an array";
            if (list.size() > MAX_LABELS) return "more than " + MAX_LABELS + " labels";
            for (int i = 0; i < list.size(); i++) {
                Object l = list.get(i);
                if (!goodString(l, MAX_LABEL)) return "label " + i + " is not a string of at most " + MAX_LABEL + " code points";
                if (!Normalizer.isNormalized((String) l, Normalizer.Form.NFC)) return "label " + i + " is not NFC";
                if (i > 0 && Canonical.compareUtf8((String) list.get(i - 1), (String) l) >= 0) return "labels are not strictly ascending";
            }
            return null;
        }

        private static String tags(Object x) {
            if (!(x instanceof List<?> list)) return "observed_tags is not an array";
            for (Object t : list) {
                String e = checkId(t, "tag");
                if (e != null) return e;
            }
            return null;
        }

    }
}
