// Ops in words, for Unsent edits (SPEC §7.4) and History (§1.1).

type AnyOp = Record<string, unknown>;

const short = (s: unknown) => {
  const t = String(s ?? "");
  return t.length > 60 ? `${t.slice(0, 57)}…` : t;
};

/** `titles` maps entity IDs to titles the reader can recognise, when known. */
export function describeOp(op: AnyOp, titles: (id: string) => string | undefined = () => undefined): string {
  const name = (id: unknown) => {
    const t = titles(String(id));
    return t ? `“${short(t)}”` : "an item";
  };
  switch (op.type) {
    case "ADD_LIST":
      return `added list “${short(op.title)}”`;
    case "ADD_CARD":
      return `added card “${short(op.title)}”${titles(String((op.placement as AnyOp)?.list_id)) ? ` to ${name((op.placement as AnyOp).list_id)}` : ""}`;
    case "SET_FIELD": {
      const v = op.value;
      const shown = Array.isArray(v) ? (v.length ? v.join(", ") : "no labels") : v === null ? "nobody" : `“${short(v)}”`;
      return `set the ${String(op.field)} of ${name(op.entity_id)} to ${shown}`;
    }
    case "SET_PLACEMENT": {
      const p = op.placement as AnyOp;
      return p.list_id ? `moved ${name(op.entity_id)} to ${name(p.list_id)}` : `reordered list ${name(op.entity_id)}`;
    }
    case "DELETE_LIST":
      return `deleted list ${name(op.list_id)}`;
    case "DELETE_CARD":
      return `deleted card ${name(op.card_id)}`;
    case "RESTORE_LIST":
      return `restored list ${name(op.list_id)}`;
    case "RESTORE_CARD":
      return `restored card ${name(op.card_id)}`;
    default:
      return `made a change (${String(op.type)})`;
  }
}

/** Saves `data` as a JSON file the user can keep (Unsent edits export). */
export function downloadJson(filename: string, data: unknown): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
