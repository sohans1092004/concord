// Fractional ordering keys (SPEC §3.7, §4.2 step 5).
//
// A credited port of David Greenspan's fractional-indexing algorithm:
//   https://observablehq.com/@dgreensp/implementing-fractional-indexing
// as implemented in rocicorp/fractional-indexing (npm `fractional-indexing`):
//   https://github.com/rocicorp/fractional-indexing
// Upstream is released under CC0-1.0 (public-domain dedication), so no notice is
// legally required; this attribution is given because the algorithm is theirs.
// Everything else in this package is hand-written.

export const DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
export const MAX_KEY_LENGTH = 64;
const SMALLEST_INTEGER = "A" + "0".repeat(26);
const BASE62 = /^[0-9A-Za-z]+$/;

function integerLength(head: string): number {
  if (head >= "a" && head <= "z") return head.charCodeAt(0) - 97 + 2;
  if (head >= "A" && head <= "Z") return 90 - head.charCodeAt(0) + 2;
  throw new Error(`keys: invalid head character in ${JSON.stringify(head)}`);
}

function integerPart(key: string): string {
  const n = integerLength(key[0] ?? "");
  if (n > key.length) throw new Error(`keys: invalid key ${key}`);
  return key.slice(0, n);
}

/** Greenspan's full key check plus the §4.2 length and alphabet limits. */
export function validateKey(key: unknown): key is string {
  if (typeof key !== "string" || key.length < 1 || key.length > MAX_KEY_LENGTH || !BASE62.test(key)) return false;
  if (key === SMALLEST_INTEGER) return false;
  const head = key[0]!;
  if (!((head >= "a" && head <= "z") || (head >= "A" && head <= "Z"))) return false;
  const n = integerLength(head);
  if (n > key.length) return false;
  return !key.slice(n).endsWith("0");
}
