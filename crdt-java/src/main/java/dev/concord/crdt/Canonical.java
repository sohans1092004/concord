package dev.concord.crdt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Canonical JSON (SPEC §9.2.2): RFC 8785 restricted to integers. Hand-written; no library. */
public final class Canonical {
    private Canonical() {}

    public static String encode(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof Boolean bool) b.append(bool ? "true" : "false");
        else if (v instanceof Long || v instanceof Integer) {
            long n = ((Number) v).longValue();
            if (Math.abs(n) > Json.MAX_SAFE_INTEGER) throw new IllegalArgumentException("canonical: not a safe integer: " + n);
            b.append(n);
        } else if (v instanceof String str) writeString(b, str);
        else if (v instanceof List<?> list) {
            b.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) b.append(',');
                write(b, list.get(i));
            }
            b.append(']');
        } else if (v instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) keys.add((String) k);
            keys.sort(null); // String.compareTo: UTF-16 code units, RFC 8785 §3.2.3
            b.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) b.append(',');
                writeString(b, keys.get(i));
                b.append(':');
                write(b, map.get(keys.get(i)));
            }
            b.append('}');
        } else throw new IllegalArgumentException("canonical: unsupported value " + v.getClass().getSimpleName() + " " + v);
    }

    private static void writeString(StringBuilder b, String s) {
        if (!isWellFormed(s)) throw new IllegalArgumentException("canonical: string is not well-formed Unicode");
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** True if every surrogate is part of a pair. */
    public static boolean isWellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                i++;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    /** Unsigned byte order. Used for the LWW tie-break (SPEC §3.4). */
    public static int compareBytes(byte[] a, byte[] b) {
        return Integer.signum(Arrays.compareUnsigned(a, b));
    }

    /**
     * Orders strings as their UTF-8 bytes would, without allocating: for well-formed strings,
     * code point order equals UTF-8 byte order. Never {@code String.compareTo}, which is UTF-16.
     */
    public static int compareUtf8(String a, String b) {
        if (a.equals(b)) return 0;
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return ca < cb ? -1 : 1;
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return i < a.length() ? 1 : j < b.length() ? -1 : 0;
    }
}
