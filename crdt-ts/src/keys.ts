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

function midpoint(a: string, b: string | null): string {
  if (b !== null && a >= b) throw new Error(`keys: ${a} >= ${b}`);
  if (a.endsWith("0") || (b !== null && b.endsWith("0"))) throw new Error("keys: trailing zero");
  if (b !== null) {
    // Strip the longest common prefix, padding `a` with zeros as we go.
    let n = 0;
    while ((a[n] ?? "0") === b[n]) n++;
    if (n > 0) return b.slice(0, n) + midpoint(a.slice(n), b.slice(n));
  }
  const digitA = a ? DIGITS.indexOf(a[0]!) : 0;
  const digitB = b !== null ? DIGITS.indexOf(b[0]!) : DIGITS.length;
  if (digitB - digitA > 1) return DIGITS[(digitA + digitB + 1) >> 1]!; // Math.round(mean), integers only
  // The first digits are consecutive.
  if (b !== null && b.length > 1) return b.slice(0, 1);
  return DIGITS[digitA]! + midpoint(a.slice(1), null);
}

function incrementInteger(x: string): string | null {
  const head = x[0]!;
  const digs = x.slice(1).split("");
  let carry = true;
  for (let i = digs.length - 1; carry && i >= 0; i--) {
    const d = DIGITS.indexOf(digs[i]!) + 1;
    if (d === DIGITS.length) digs[i] = "0";
    else {
      digs[i] = DIGITS[d]!;
      carry = false;
    }
  }
  if (!carry) return head + digs.join("");
  if (head === "Z") return "a0";
  if (head === "z") return null;
  const h = String.fromCharCode(head.charCodeAt(0) + 1);
  if (h > "a") digs.push("0");
  else digs.pop();
  return h + digs.join("");
}

function decrementInteger(x: string): string | null {
  const head = x[0]!;
  const digs = x.slice(1).split("");
  let borrow = true;
  for (let i = digs.length - 1; borrow && i >= 0; i--) {
    const d = DIGITS.indexOf(digs[i]!) - 1;
    if (d === -1) digs[i] = "z";
    else {
      digs[i] = DIGITS[d]!;
      borrow = false;
    }
  }
  if (!borrow) return head + digs.join("");
  if (head === "a") return "Zz";
  if (head === "A") return null;
  const h = String.fromCharCode(head.charCodeAt(0) - 1);
  if (h < "Z") digs.push("z");
  else digs.pop();
  return h + digs.join("");
}

function requireKey(k: string): void {
  if (k === SMALLEST_INTEGER) throw new Error(`keys: invalid key ${k}`);
  const i = integerPart(k);
  if (!BASE62.test(k)) throw new Error(`keys: invalid key ${k}`);
  if (k.slice(i.length).endsWith("0")) throw new Error(`keys: invalid key ${k}`);
}

/**
 * A key strictly between `a` and `b`; `null` is the open start or end.
 * Throws unless a < b (SPEC §3.7). Deterministic: equal inputs give equal keys.
 * Does not cap the result at MAX_KEY_LENGTH; rebalancing (SPEC §3.7) keeps keys short.
 */
export function keyBetween(a: string | null, b: string | null): string {
  if (a !== null) requireKey(a);
  if (b !== null) requireKey(b);
  if (a !== null && b !== null && a >= b) throw new Error(`keys: ${a} >= ${b}`);
  if (a === null) {
    if (b === null) return "a0";
    const ib = integerPart(b);
    const fb = b.slice(ib.length);
    if (ib === SMALLEST_INTEGER) return ib + midpoint("", fb);
    if (ib < b) return ib;
    const res = decrementInteger(ib);
    if (res === null) throw new Error("keys: cannot decrement any more");
    return res;
  }
  if (b === null) {
    const ia = integerPart(a);
    const i = incrementInteger(ia);
    return i === null ? ia + midpoint(a.slice(ia.length), null) : i;
  }
  const ia = integerPart(a);
  const fa = a.slice(ia.length);
  const ib = integerPart(b);
  const fb = b.slice(ib.length);
  if (ia === ib) return ia + midpoint(fa, fb);
  const i = incrementInteger(ia);
  if (i === null) throw new Error("keys: cannot increment any more");
  return i < b ? i : ia + midpoint(fa, null);
}

/** `n` ascending keys strictly between `a` and `b`, spread evenly. */
export function keysBetween(a: string | null, b: string | null, n: number): string[] {
  if (n === 0) return [];
  if (n === 1) return [keyBetween(a, b)];
  if (b === null) {
    const out = [keyBetween(a, null)];
    while (out.length < n) out.push(keyBetween(out[out.length - 1]!, null));
    return out;
  }
  if (a === null) {
    const out = [keyBetween(null, b)];
    while (out.length < n) out.push(keyBetween(null, out[out.length - 1]!));
    return out.reverse();
  }
  const mid = Math.floor(n / 2);
  const c = keyBetween(a, b);
  return [...keysBetween(a, c, mid), c, ...keysBetween(c, b, n - mid - 1)];
}

/** Fresh, short keys for a list of `keys.length` entries, in the same order. */
export function rebalance(keys: readonly string[]): string[] {
  return keysBetween(null, null, keys.length);
}

export interface InsertPlan {
  /** Re-key writes to issue first: the entry at `index` (render order) gets `key`. */
  readonly rekeys: readonly { readonly index: number; readonly key: string }[];
  /** The key for the new entry, which then renders at position `index`. */
  readonly key: string;
}

/**
 * Keys to insert a new entry at `index` of `sorted`, the keys in render order
 * (SPEC §3.7). If the neighbours share a key, every entry from `index` on that shares
 * it is re-keyed first, so the new entry lands exactly at `index` whatever its ID.
 */
export function planInsert(sorted: readonly string[], index: number): InsertPlan {
  if (index < 0 || index > sorted.length) throw new RangeError("keys: index out of range");
  const lower = sorted[index - 1] ?? null;
  const upper = sorted[index] ?? null;
  if (lower === null || upper === null || lower !== upper) return { rekeys: [], key: keyBetween(lower, upper) };
  let j = index;
  while (j < sorted.length && sorted[j] === lower) j++;
  const fresh = keysBetween(lower, sorted[j] ?? null, j - index);
  return { rekeys: fresh.map((key, i) => ({ index: index + i, key })), key: keyBetween(lower, fresh[0]!) };
}
