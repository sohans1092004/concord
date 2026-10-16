package dev.concord.crdt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the fuzz generator, as crdt-ts/test/fuzz/coverage.ts does: over the 500 seeds CI runs,
 * each hard case must occur at least 50 times. Two ops are concurrent when the later one's
 * replica had not seen the earlier one.
 */
class CoverageTest {
    static final String MOVES = "concurrent moves of the same card", DEL_EDIT = "delete vs concurrent edit",
            DEL_RESTORE = "delete vs concurrent restore", EQUAL_KEYS = "concurrent equal-key inserts";

    static String entity(Op op) {
        return switch (op) {
            case Op.AddList o -> o.listId();
            case Op.AddCard o -> o.cardId();
            case Op.SetField o -> o.entityId();
            case Op.SetPlacement o -> o.entityId();
            case Op.DeleteList o -> o.listId();
            case Op.DeleteCard o -> o.cardId();
            case Op.RestoreList o -> o.listId();
            case Op.RestoreCard o -> o.cardId();
        };
    }

    static String slot(Op op) {
        return switch (op) {
            case Op.AddList o -> "lists/" + o.positionKey();
            case Op.AddCard o -> o.listId() + "/" + o.positionKey();
            case Op.SetPlacement o -> (o.listId() == null ? "lists" : o.listId()) + "/" + o.positionKey();
            default -> null;
        };
    }

    static boolean isDelete(Op op) {
        return (op instanceof Op.DeleteCard d && !d.observedTags().isEmpty()) || (op instanceof Op.DeleteList l && !l.observedTags().isEmpty());
    }

    static boolean isRestore(Op op) {
        return op instanceof Op.RestoreCard || op instanceof Op.RestoreList;
    }

    static String classify(Op a, Op b) {
        if (entity(a).equals(entity(b))) {
            if (a instanceof Op.SetPlacement x && b instanceof Op.SetPlacement && x.listId() != null) return MOVES;
            if ((isDelete(a) && b instanceof Op.SetField) || (isDelete(b) && a instanceof Op.SetField)) return DEL_EDIT;
            if ((isDelete(a) && isRestore(b)) || (isDelete(b) && isRestore(a))) return DEL_RESTORE;
            return null;
        }
        String sa = slot(a);
        return sa != null && sa.equals(slot(b)) ? EQUAL_KEYS : null;
    }

    @Test
    void everyHardCaseOccursAtLeast50TimesIn500Seeds() {
        Map<String, long[]> counts = new LinkedHashMap<>(); // case -> {pairs, scenarios}
        for (String c : List.of(MOVES, DEL_EDIT, DEL_RESTORE, EQUAL_KEYS)) counts.put(c, new long[2]);
        for (long seed = 1; seed <= 500; seed++) {
            List<Op> ops = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            FuzzTest.scenario(seed, false, (op, index, known) -> {
                for (int i = 0; i < index; i++) {
                    if (known.contains(i)) continue;
                    String c = classify(ops.get(i), op);
                    if (c != null) {
                        counts.get(c)[0]++;
                        seen.add(c);
                    }
                }
                ops.add(op);
            });
            for (String c : seen) counts.get(c)[1]++;
        }
        counts.forEach((c, n) -> System.out.printf("coverage: %s: %d pairs in %d/500 scenarios%n", c, n[0], n[1]));
        counts.forEach((c, n) -> assertTrue(n[0] >= 50, c + ": " + n[0]));
    }
}
