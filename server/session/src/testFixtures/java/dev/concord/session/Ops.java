package dev.concord.session;

import dev.concord.crdt.Hlc;
import dev.concord.crdt.Op;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds valid op JSON for one replica, ticking its HLC like a client would. */
public final class Ops {
    public final String board;
    public final String replica = UUID.randomUUID().toString();
    private Hlc hlc = Hlc.zero(replica);

    public Ops(String board) {
        this.board = board;
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    private Map<String, Object> base(String type, String opId, long now) {
        hlc = hlc.tick(now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op_id", opId);
        m.put("board_id", board);
        m.put("hlc", hlc.encode());
        m.put("replica_id", replica);
        m.put("type", type);
        return m;
    }

    private Map<String, Object> base(String type) {
        return base(type, uuid(), System.currentTimeMillis());
    }

    public Map<String, Object> addList(String listId, String key, String title) {
        Map<String, Object> m = base("ADD_LIST");
        m.put("list_id", listId);
        m.put("placement", Op.placement(null, key));
        m.put("title", title);
        return m;
    }

    public Map<String, Object> addCard(String cardId, String listId, String key, String title) {
        Map<String, Object> m = base("ADD_CARD");
        m.put("card_id", cardId);
        m.put("placement", Op.placement(listId, key));
        m.put("title", title);
        return m;
    }

    public Map<String, Object> setField(String entity, String field, Object value) {
        Map<String, Object> m = base("SET_FIELD");
        m.put("entity_id", entity);
        m.put("field", field);
        m.put("value", value);
        return m;
    }

    public Map<String, Object> move(String card, String listId, String key) {
        Map<String, Object> m = base("SET_PLACEMENT");
        m.put("entity_id", card);
        m.put("placement", Op.placement(listId, key));
        return m;
    }

    public Map<String, Object> deleteCard(String card, List<String> tags) {
        Map<String, Object> m = base("DELETE_CARD");
        m.put("card_id", card);
        m.put("observed_tags", tags);
        return m;
    }

    public Map<String, Object> restoreCard(String card) {
        Map<String, Object> m = base("RESTORE_CARD");
        m.put("card_id", card);
        return m;
    }

    /** An op stamped at {@code physicalMs}, for clock-skew tests. */
    public Map<String, Object> addListAt(long physicalMs, String listId) {
        Map<String, Object> m = base("ADD_LIST", uuid(), physicalMs);
        m.put("list_id", listId);
        m.put("placement", Op.placement(null, "a0"));
        m.put("title", "skewed");
        return m;
    }
}
