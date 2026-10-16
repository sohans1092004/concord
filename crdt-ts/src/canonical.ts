// Canonical JSON (SPEC §9.2.2): RFC 8785 restricted to integers.

export type Json = null | boolean | number | string | readonly Json[] | { readonly [k: string]: Json };

const SHORT_ESCAPES: Record<number, string> = { 8: "\\b", 9: "\\t", 10: "\\n", 12: "\\f", 13: "\\r" };
const NEEDS_ESCAPE = /["\\\u0000-\u001f]/;

function encodeString(s: string): string {
  if (!s.isWellFormed()) throw new Error("canonical: string is not well-formed Unicode");
  if (!NEEDS_ESCAPE.test(s)) return `"${s}"`;
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 0x22) out += '\\"';
    else if (c === 0x5c) out += "\\\\";
    else if (c < 0x20) out += SHORT_ESCAPES[c] ?? "\\u00" + c.toString(16).padStart(2, "0");
    else out += s[i];
  }
  return out + '"';
}

export function canonicalize(value: Json): string {
  if (value === null) return "null";
  switch (typeof value) {
    case "boolean":
      return value ? "true" : "false";
    case "number":
      if (!Number.isSafeInteger(value)) throw new Error(`canonical: not a safe integer: ${value}`);
      return String(value === 0 ? 0 : value); // -0 encodes as 0
    case "string":
      return encodeString(value);
    case "object": {
      if (Array.isArray(value)) return "[" + value.map(canonicalize).join(",") + "]";
      const obj = value as { readonly [k: string]: Json };
      // Default sort compares UTF-16 code units, which is RFC 8785 §3.2.3 order.
      const keys = Object.keys(obj).sort();
      return "{" + keys.map((k) => encodeString(k) + ":" + canonicalize(obj[k] as Json)).join(",") + "}";
    }
    default:
      throw new Error(`canonical: unsupported type ${typeof value}`);
  }
}

/** UTF-8 bytes of a well-formed string. Written out so src/ needs no host APIs. */
export function utf8(s: string): Uint8Array {
  const out: number[] = [];
  for (const ch of s) {
    const cp = ch.codePointAt(0)!;
    if (cp < 0x80) out.push(cp);
    else if (cp < 0x800) out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    else if (cp < 0x10000) out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    else out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
  }
  return Uint8Array.from(out);
}

/** Unsigned byte comparison. Used for the LWW tie-break (SPEC §3.4). */
export function compareBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    if (a[i] !== b[i]) return a[i]! < b[i]! ? -1 : 1;
  }
  return a.length === b.length ? 0 : a.length < b.length ? -1 : 1;
}

/**
 * Orders strings as their UTF-8 bytes would, without allocating: for well-formed
 * strings, code point order equals UTF-8 byte order. Used for IDs, tags, keys, labels.
 */
export function compareUtf8(a: string, b: string): number {
  if (a === b) return 0;
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    const ca = a.codePointAt(i)!;
    const cb = b.codePointAt(j)!;
    if (ca !== cb) return ca < cb ? -1 : 1;
    i += ca > 0xffff ? 2 : 1;
    j += cb > 0xffff ? 2 : 1;
  }
  return i < a.length ? 1 : j < b.length ? -1 : 0;
}
