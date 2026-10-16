// Op types and shape validation (SPEC §4.1, §4.2 steps 1, 3 and 5).

import { compareUtf8 } from "./canonical.js";
import { decode as decodeHlc, isUuid, type Hlc } from "./hlc.js";
import { validateKey } from "./keys.js";

export const NIL_UUID = "00000000-0000-0000-0000-000000000000";
export const MAX_TITLE = 200;
export const MAX_DESCRIPTION = 10_000;
export const MAX_LABELS = 20;
export const MAX_LABEL = 50;
export const MAX_SKEW_MS = 60_000;

export type ListPlacement = { readonly position_key: string };
export type CardPlacement = { readonly list_id: string; readonly position_key: string };
export type Placement = ListPlacement | CardPlacement;
export type Field = "title" | "description" | "assignee" | "labels";
export type FieldValue = string | null | readonly string[];

interface Base {
  readonly op_id: string;
  readonly board_id: string;
  readonly hlc: Hlc;
  readonly replica_id: string;
}

export type Op =
  | (Base & { readonly type: "ADD_LIST"; readonly list_id: string; readonly placement: ListPlacement; readonly title: string })
  | (Base & { readonly type: "ADD_CARD"; readonly card_id: string; readonly placement: CardPlacement; readonly title: string })
  | (Base & { readonly type: "SET_FIELD"; readonly entity_id: string; readonly field: Field; readonly value: FieldValue })
  | (Base & { readonly type: "SET_PLACEMENT"; readonly entity_id: string; readonly placement: Placement })
  | (Base & { readonly type: "DELETE_LIST"; readonly list_id: string; readonly observed_tags: readonly string[] })
  | (Base & { readonly type: "DELETE_CARD"; readonly card_id: string; readonly observed_tags: readonly string[] })
  | (Base & { readonly type: "RESTORE_LIST"; readonly list_id: string })
  | (Base & { readonly type: "RESTORE_CARD"; readonly card_id: string });

export type OpType = Op["type"];

export type Check = { readonly ok: true } | { readonly ok: false; readonly code: "INVALID_OP" | "CLOCK_SKEW"; readonly reason: string };

const OK: Check = { ok: true };
const invalid = (reason: string): Check => ({ ok: false, code: "INVALID_OP", reason });

const BASE = ["board_id", "hlc", "op_id", "replica_id", "type"];
const MEMBERS: Record<OpType, readonly string[]> = {
  ADD_LIST: ["list_id", "placement", "title"],
  ADD_CARD: ["card_id", "placement", "title"],
  SET_FIELD: ["entity_id", "field", "value"],
  SET_PLACEMENT: ["entity_id", "placement"],
  DELETE_LIST: ["list_id", "observed_tags"],
  DELETE_CARD: ["card_id", "observed_tags"],
  RESTORE_LIST: ["list_id"],
  RESTORE_CARD: ["card_id"],
};

const isObject = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const codePoints = (s: string): number => {
  let n = 0;
  for (const _ of s) n++;
  return n;
};
// No U+0000: Postgres JSONB cannot store it (SPEC §4.2).
const NUL = String.fromCharCode(0);
const goodString = (x: unknown, max: number): x is string =>
  typeof x === "string" && x.isWellFormed() && !x.includes(NUL) && codePoints(x) <= max;

function exactMembers(o: Record<string, unknown>, expected: readonly string[]): string | null {
  const keys = Object.keys(o);
  for (const k of keys) if (!expected.includes(k)) return `unexpected member ${k}`;
  for (const k of expected) if (!(k in o)) return `missing member ${k}`;
  return null;
}

/** An ID field: lowercase canonical UUID, never the nil UUID (reserved for Orphaned). */
function checkId(o: Record<string, unknown>, name: string): string | null {
  const v = o[name];
  if (!isUuid(v)) return `${name} is not a lowercase canonical UUID`;
  if (v === NIL_UUID) return `${name} is the reserved nil UUID`;
  return null;
}

function checkPlacement(x: unknown, form: "list" | "card" | "either"): string | null {
  if (!isObject(x)) return "placement is not an object";
  const isCard = "list_id" in x;
  if ((form === "list" && isCard) || (form === "card" && !isCard)) return `placement must be the ${form} form`;
  const err = exactMembers(x, isCard ? ["list_id", "position_key"] : ["position_key"]);
  if (err) return `placement: ${err}`;
  if (isCard) {
    const idErr = checkId(x, "list_id");
    if (idErr) return `placement.${idErr}`;
  }
  if (!validateKey(x.position_key)) return "placement.position_key is not a valid key";
  return null;
}

function checkLabels(x: unknown): string | null {
  if (!Array.isArray(x)) return "labels is not an array";
  if (x.length > MAX_LABELS) return `more than ${MAX_LABELS} labels`;
  for (let i = 0; i < x.length; i++) {
    const l: unknown = x[i];
    if (!goodString(l, MAX_LABEL)) return `label ${i} is not a string of at most ${MAX_LABEL} code points`;
    if (l.normalize("NFC") !== l) return `label ${i} is not NFC`;
    if (i > 0 && compareUtf8(x[i - 1] as string, l) >= 0) return "labels are not strictly ascending";
  }
  return null;
}

function checkFieldValue(field: unknown, value: unknown): string | null {
  switch (field) {
    case "title":
      return goodString(value, MAX_TITLE) ? null : `title is not a string of at most ${MAX_TITLE} code points`;
    case "description":
      return goodString(value, MAX_DESCRIPTION) ? null : `description is not a string of at most ${MAX_DESCRIPTION} code points`;
    case "assignee":
      return value === null ? null : checkId({ assignee: value }, "assignee");
    case "labels":
      return checkLabels(value);
    default:
      return `field ${JSON.stringify(field)} is not settable`;
  }
}

/** §4.2 steps 1 and 5: is `x` exactly one well-formed op? Pure; no state needed. */
export function validateShape(x: unknown): x is Op {
  return checkShape(x).ok;
}

export function checkShape(x: unknown): Check {
  if (!isObject(x)) return invalid("op is not an object");
  const type = x.type;
  if (typeof type !== "string" || !Object.hasOwn(MEMBERS, type)) return invalid(`unknown type ${JSON.stringify(type)}`);
  const members = MEMBERS[type as OpType];
  const err = exactMembers(x, [...BASE, ...members]);
  if (err) return invalid(err);
  for (const name of ["op_id", "board_id", "replica_id"]) {
    const e = checkId(x, name);
    if (e) return invalid(e);
  }
  const hlc = decodeHlc(x.hlc);
  if (!hlc) return invalid("hlc is not a valid HLC");
  if (hlc.replica_id !== x.replica_id) return invalid("hlc.replica_id differs from replica_id");

  let e: string | null = null;
  switch (type as OpType) {
    case "ADD_LIST":
      e = checkId(x, "list_id") ?? checkPlacement(x.placement, "list") ?? checkFieldValue("title", x.title);
      break;
    case "ADD_CARD":
      e = checkId(x, "card_id") ?? checkPlacement(x.placement, "card") ?? checkFieldValue("title", x.title);
      break;
    case "SET_FIELD":
      e = checkId(x, "entity_id") ?? checkFieldValue(x.field, x.value);
      break;
    case "SET_PLACEMENT":
      e = checkId(x, "entity_id") ?? checkPlacement(x.placement, "either");
      break;
    case "DELETE_LIST":
    case "DELETE_CARD": {
      e = checkId(x, type === "DELETE_LIST" ? "list_id" : "card_id");
      const tags = x.observed_tags;
      if (!e && !Array.isArray(tags)) e = "observed_tags is not an array";
      if (!e) for (const t of tags as unknown[]) if ((e = checkId({ tag: t }, "tag"))) break;
      break;
    }
    case "RESTORE_LIST":
      e = checkId(x, "list_id");
      break;
    case "RESTORE_CARD":
      e = checkId(x, "card_id");
      break;
  }
  return e ? invalid(e) : OK;
}

/** §3.1 skew guard: reject iff physical_ms > server_time_ms + 60000 (strict). */
export function checkClock(op: Op, serverTimeMs: number): Check {
  return op.hlc.physical_ms > serverTimeMs + MAX_SKEW_MS
    ? { ok: false, code: "CLOCK_SKEW", reason: `physical_ms is more than ${MAX_SKEW_MS}ms ahead of server time` }
    : OK;
}

/** A fresh UUID v4 from the host's CSPRNG (browsers and Node 20+ both provide it). */
export function newOpId(): string {
  return (globalThis as unknown as { crypto: { randomUUID(): string } }).crypto.randomUUID();
}
