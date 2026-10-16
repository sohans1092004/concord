package dev.concord.crdt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser. Values: {@code null}, {@link Boolean}, {@link Long}, {@link Double},
 * {@link String}, {@code List<Object>}, {@code Map<String, Object>}.
 *
 * <p>Numbers follow JavaScript so both cores accept the same inputs: every literal is read as
 * an IEEE double ({@code JSON.parse} does the same), and becomes a {@code Long} only if that
 * double is a safe integer (|n| ≤ 2^53 − 1). So {@code 1.0} and {@code 1e0} are the integer 1,
 * and {@code 0.5} or {@code 2^53} stay {@code Double}, which validation and encoding reject.
 */
public final class Json {
    public static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.error("trailing characters");
        return v;
    }

    private IllegalArgumentException error(String why) {
        return new IllegalArgumentException("json: " + why + " at " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("bad literal");
        i += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
            i++;
            ws();
            if (out.containsKey(k)) throw error("duplicate key " + k);
            out.put(k, value());
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return out;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return out;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw error("raw control character in string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("short \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        if (i < s.length() && s.charAt(i) == '0') i++;
        else if (!digits()) throw error("bad number");
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            if (!digits()) throw error("bad fraction");
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (!digits()) throw error("bad exponent");
        }
        double d = Double.parseDouble(s.substring(start, i));
        return isSafeInteger(d) ? (Object) (long) d : (Object) d;
    }

    private boolean digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return i > start;
    }

    static boolean isSafeInteger(double d) {
        return d == Math.rint(d) && Math.abs(d) <= MAX_SAFE_INTEGER;
    }
}
