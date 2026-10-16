package dev.concord.crdt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fractional ordering keys (SPEC §3.7, §4.2 step 5).
 *
 * <p>A credited port of David Greenspan's fractional-indexing algorithm:
 * https://observablehq.com/@dgreensp/implementing-fractional-indexing, as implemented in
 * rocicorp/fractional-indexing (https://github.com/rocicorp/fractional-indexing). Upstream is
 * released under CC0-1.0 (public-domain dedication), so no notice is legally required; this
 * attribution is given because the algorithm is theirs. Unlike upstream, reversed bounds throw
 * instead of being swapped. Everything else in this package is hand-written.
 */
public final class Keys {
    public static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final int MAX_KEY_LENGTH = 64;
    private static final String SMALLEST_INTEGER = "A" + "0".repeat(26);

    private Keys() {}

    private static boolean isBase62(String s) {
        for (int i = 0; i < s.length(); i++) if (DIGITS.indexOf(s.charAt(i)) < 0) return false;
        return true;
    }

    private static int integerLength(char head) {
        if (head >= 'a' && head <= 'z') return head - 'a' + 2;
        if (head >= 'A' && head <= 'Z') return 'Z' - head + 2;
        throw new IllegalArgumentException("keys: invalid head character " + head);
    }

    private static String integerPart(String key) {
        if (key.isEmpty()) throw new IllegalArgumentException("keys: empty key");
        int n = integerLength(key.charAt(0));
        if (n > key.length()) throw new IllegalArgumentException("keys: invalid key " + key);
        return key.substring(0, n);
    }

    /** Greenspan's full key check plus the §4.2 length and alphabet limits. */
    public static boolean validateKey(Object x) {
        if (!(x instanceof String key) || key.isEmpty() || key.length() > MAX_KEY_LENGTH || !isBase62(key)) return false;
        if (key.equals(SMALLEST_INTEGER)) return false;
        char head = key.charAt(0);
        if (!((head >= 'a' && head <= 'z') || (head >= 'A' && head <= 'Z'))) return false;
        int n = integerLength(head);
        return n <= key.length() && !key.substring(n).endsWith("0");
    }

    private static String midpoint(String a, String b) {
        if (b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException("keys: " + a + " >= " + b);
        if (a.endsWith("0") || (b != null && b.endsWith("0"))) throw new IllegalArgumentException("keys: trailing zero");
        if (b != null) {
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : '0') == b.charAt(n)) n++;
            if (n > 0) return b.substring(0, n) + midpoint(a.substring(Math.min(n, a.length())), b.substring(n));
        }
        int digitA = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int digitB = b != null ? DIGITS.indexOf(b.charAt(0)) : DIGITS.length();
        if (digitB - digitA > 1) return String.valueOf(DIGITS.charAt((digitA + digitB + 1) >> 1)); // Math.round(mean)
        if (b != null && b.length() > 1) return b.substring(0, 1);
        return DIGITS.charAt(digitA) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    private static String incrementInteger(String x) {
        char head = x.charAt(0);
        char[] digs = x.substring(1).toCharArray();
        boolean carry = true;
        for (int i = digs.length - 1; carry && i >= 0; i--) {
            int d = DIGITS.indexOf(digs[i]) + 1;
            if (d == DIGITS.length()) digs[i] = '0';
            else {
                digs[i] = DIGITS.charAt(d);
                carry = false;
            }
        }
        String rest = new String(digs);
        if (!carry) return head + rest;
        if (head == 'Z') return "a0";
        if (head == 'z') return null;
        char h = (char) (head + 1);
        return h + (h > 'a' ? rest + "0" : rest.substring(0, rest.length() - 1));
    }

    private static String decrementInteger(String x) {
        char head = x.charAt(0);
        char[] digs = x.substring(1).toCharArray();
        boolean borrow = true;
        for (int i = digs.length - 1; borrow && i >= 0; i--) {
            int d = DIGITS.indexOf(digs[i]) - 1;
            if (d == -1) digs[i] = 'z';
            else {
                digs[i] = DIGITS.charAt(d);
                borrow = false;
            }
        }
        String rest = new String(digs);
        if (!borrow) return head + rest;
        if (head == 'a') return "Zz";
        if (head == 'A') return null;
        char h = (char) (head - 1);
        return h + (h < 'Z' ? rest + "z" : rest.substring(0, rest.length() - 1));
    }

    private static void requireKey(String k) {
        if (k.equals(SMALLEST_INTEGER)) throw new IllegalArgumentException("keys: invalid key " + k);
        String i = integerPart(k);
        if (!isBase62(k) || k.substring(i.length()).endsWith("0")) throw new IllegalArgumentException("keys: invalid key " + k);
    }

    /** A key strictly between a and b; null is the open start or end. Throws unless a &lt; b. */
    public static String keyBetween(String a, String b) {
        if (a != null) requireKey(a);
        if (b != null) requireKey(b);
        if (a != null && b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException("keys: " + a + " >= " + b);
        if (a == null) {
            if (b == null) return "a0";
            String ib = integerPart(b);
            String fb = b.substring(ib.length());
            if (ib.equals(SMALLEST_INTEGER)) return ib + midpoint("", fb);
            if (ib.compareTo(b) < 0) return ib;
            String res = decrementInteger(ib);
            if (res == null) throw new IllegalArgumentException("keys: cannot decrement any more");
            return res;
        }
        if (b == null) {
            String ia = integerPart(a);
            String i = incrementInteger(ia);
            return i == null ? ia + midpoint(a.substring(ia.length()), null) : i;
        }
        String ia = integerPart(a), fa = a.substring(ia.length());
        String ib = integerPart(b), fb = b.substring(ib.length());
        if (ia.equals(ib)) return ia + midpoint(fa, fb);
        String i = incrementInteger(ia);
        if (i == null) throw new IllegalArgumentException("keys: cannot increment any more");
        return i.compareTo(b) < 0 ? i : ia + midpoint(fa, null);
    }

    /** n ascending keys strictly between a and b, spread evenly. */
    public static List<String> keysBetween(String a, String b, int n) {
        if (n == 0) return new ArrayList<>();
        if (n == 1) return new ArrayList<>(List.of(keyBetween(a, b)));
        List<String> out = new ArrayList<>();
        if (b == null) {
            out.add(keyBetween(a, null));
            while (out.size() < n) out.add(keyBetween(out.get(out.size() - 1), null));
            return out;
        }
        if (a == null) {
            out.add(keyBetween(null, b));
            while (out.size() < n) out.add(keyBetween(null, out.get(out.size() - 1)));
            Collections.reverse(out);
            return out;
        }
        int mid = n / 2;
        String c = keyBetween(a, b);
        out.addAll(keysBetween(a, c, mid));
        out.add(c);
        out.addAll(keysBetween(c, b, n - mid - 1));
        return out;
    }

    /** Fresh, short keys for a list of keys.size() entries, in the same order. */
    public static List<String> rebalance(List<String> keys) {
        return keysBetween(null, null, keys.size());
    }

    /** Re-key writes to issue first (entry at index gets key), then the new entry's key. */
    public record InsertPlan(List<Rekey> rekeys, String key) {}

    public record Rekey(int index, String key) {}

    /**
     * Keys to insert a new entry at index of sorted (keys in render order), SPEC §3.7. If the
     * neighbours share a key, every entry from index on that shares it is re-keyed first.
     */
    public static InsertPlan planInsert(List<String> sorted, int index) {
        if (index < 0 || index > sorted.size()) throw new IndexOutOfBoundsException("keys: index out of range");
        String lower = index > 0 ? sorted.get(index - 1) : null;
        String upper = index < sorted.size() ? sorted.get(index) : null;
        if (lower == null || upper == null || !lower.equals(upper)) return new InsertPlan(List.of(), keyBetween(lower, upper));
        int j = index;
        while (j < sorted.size() && sorted.get(j).equals(lower)) j++;
        List<String> fresh = keysBetween(lower, j < sorted.size() ? sorted.get(j) : null, j - index);
        List<Rekey> rekeys = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) rekeys.add(new Rekey(index + i, fresh.get(i)));
        return new InsertPlan(List.copyOf(rekeys), keyBetween(lower, fresh.get(0)));
    }
}
