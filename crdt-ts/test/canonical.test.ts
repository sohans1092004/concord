import fc from "fast-check";
import { describe, expect, it } from "vitest";
import { canonicalize, compareBytes, compareUtf8, utf8 } from "../src/canonical.js";

describe("canonical JSON (SPEC §9.2.2)", () => {
  it("sorts keys, drops whitespace, writes integers plainly", () => {
    expect(canonicalize({ b: 1, a: [true, null, -3], c: { z: "x", y: 0 } })).toBe(
      '{"a":[true,null,-3],"b":1,"c":{"y":0,"z":"x"}}',
    );
    expect(canonicalize(-0)).toBe("0");
  });

  it("escapes only quote, backslash and control characters", () => {
    expect(canonicalize('"\\\b\f\n\r\t\u0000\u001f\u007f/é😀 ')).toBe(
      '"\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f\u007f/é😀 "',
    );
  });

  it("sorts keys by UTF-16 code units (RFC 8785 §3.2.3), not code points", () => {
    // U+1F600 is D83D DE00 in UTF-16, so it sorts before U+FF61 here; in UTF-8 it would sort after.
    expect(canonicalize({ "｡": 2, "😀": 1 })).toBe('{"😀":1,"｡":2}');
  });

  it("rejects non-integers and lone surrogates", () => {
    expect(() => canonicalize(1.5)).toThrow();
    expect(() => canonicalize(2 ** 53)).toThrow();
    expect(() => canonicalize(Number.NaN)).toThrow();
    expect(() => canonicalize("\ud800")).toThrow();
    expect(() => canonicalize({ "\udc00": 1 })).toThrow();
  });

  it("agrees with JSON.parse on every string", () => {
    fc.assert(fc.property(fc.string({ unit: "grapheme" }), (s) => {
      expect(JSON.parse(canonicalize(s))).toBe(s);
    }));
  });

  it("utf8 matches the platform encoder", () => {
    fc.assert(
      fc.property(fc.string({ unit: "binary" }), (s) => {
        expect(Array.from(utf8(s))).toEqual(Array.from(new TextEncoder().encode(s)));
      }),
    );
  });

  it("compareUtf8 orders strings exactly as their unsigned UTF-8 bytes", () => {
    fc.assert(
      fc.property(fc.string({ unit: "binary" }), fc.string({ unit: "binary" }), (a, b) => {
        expect(compareUtf8(a, b)).toBe(compareBytes(utf8(a), utf8(b)));
      }),
    );
    expect(compareUtf8("￿", "😀")).toBe(-1); // UTF-16 code-unit order would say +1
  });
});
