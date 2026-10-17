// A card, opened (SPEC §1.1): title, description, assignee, labels, move, delete. Every field
// is one SET_FIELD through client-core; labels go through normalizeLabels (sorted, unique, NFC).

import { normalizeLabels, type SyncClient } from "@concord/client-core";
import type { CardView } from "@concord/crdt";
import { useEffect, useRef, useState } from "react";
import { ORPHANED } from "./BoardPage";
import { useClientVersion } from "./useBoard";

export function CardDialog({ client, cardId, onClose, canEdit }: { client: SyncClient; cardId: string; onClose: () => void; canEdit: boolean }) {
  useClientVersion(client); // the card may change while it is open (another editor, a move)
  const view = client.view();
  const list = view.lists.find((l) => l.cards.some((c) => c.id === cardId));
  const card = list?.cards.find((c) => c.id === cardId) as CardView | undefined;
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  if (!card || !list) return null;
  const members = [...client.members()];
  const lists = view.lists.filter((l) => l.id !== ORPHANED);
  return (
    <dialog ref={dialog} className="card-dialog" onClose={onClose} aria-label={`Card ${String(card.title ?? "")}`} data-testid="card-dialog">
      <form method="dialog" className="dialog-close"><button aria-label="Close">✕</button></form>
      <Field label="Title" value={String(card.title ?? "")} disabled={!canEdit} max={200} autoFocus
        onSave={(v) => v.trim() && client.setField(cardId, "title", v.trim())} />
      <Field label="Description" value={String(card.description ?? "")} disabled={!canEdit} max={10_000} multiline
        onSave={(v) => client.setField(cardId, "description", v)} />
      <label>
        Assignee
        <select aria-label="Assignee" disabled={!canEdit} value={String(card.assignee ?? "")} onChange={(e) => client.setField(cardId, "assignee", e.target.value || null)}>
          <option value="">Nobody</option>
          {members.map(([id, m]) => <option key={id} value={id}>{m.name}</option>)}
        </select>
      </label>
      <Labels value={(card.labels as string[] | undefined) ?? []} disabled={!canEdit} onSave={(l) => client.setField(cardId, "labels", l)} />
      {canEdit && (
        <fieldset className="move">
          <legend>Move (keyboard-friendly)</legend>
          <MoveControls client={client} cardId={cardId} fromList={list.id} lists={lists.map((l) => ({ id: l.id, title: String((l as { title?: unknown }).title ?? ""), size: l.cards.length }))} />
        </fieldset>
      )}
      {canEdit && <button className="danger" onClick={() => (client.deleteCard(cardId), onClose())}>Delete card</button>}
    </dialog>
  );
}

function Field({ label, value, onSave, disabled, max, multiline, autoFocus }: { label: string; value: string; onSave: (v: string) => void; disabled: boolean; max: number; multiline?: boolean; autoFocus?: boolean }) {
  const [draft, setDraft] = useState(value);
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    if (!editing) setDraft(value); // someone else changed it while we were not typing
  }, [value, editing]);
  const save = () => {
    setEditing(false);
    if (draft !== value) onSave(draft);
  };
  // Closing the dialog with Escape unmounts the field without a blur: save what was typed.
  const latest = useRef({ draft, value, editing, onSave });
  latest.current = { draft, value, editing, onSave };
  useEffect(() => () => {
    const l = latest.current;
    if (l.editing && l.draft !== l.value) l.onSave(l.draft);
  }, []);
  const props = {
    value: draft, disabled, maxLength: max, "aria-label": label, autoFocus: autoFocus ?? false,
    onFocus: () => setEditing(true), onBlur: save,
    onChange: (e: { target: { value: string } }) => setDraft(e.target.value),
  };
  return (
    <label>
      {label}
      {multiline ? <textarea rows={6} {...props} /> : <input {...props} onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />}
    </label>
  );
}

function Labels({ value, onSave, disabled }: { value: string[]; onSave: (l: string[]) => void; disabled: boolean }) {
  const [draft, setDraft] = useState(value.join(", "));
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setDraft(value.join(", ")), [value]);
  const pending = useRef({ draft, value, save: (_: string) => {} });
  useEffect(() => () => {
    if (pending.current.draft !== pending.current.value.join(", ")) pending.current.save(pending.current.draft);
  }, []);
  const save = (text = draft) => {
    try {
      const next = normalizeLabels(text.split(","));
      setError(null);
      if (JSON.stringify(next) !== JSON.stringify(value)) onSave(next);
      setDraft(next.join(", "));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e)); // kept in the box: nothing typed is lost
    }
  };
  pending.current = { draft, value, save };
  return (
    <label>
      Labels (comma-separated)
      <input value={draft} disabled={disabled} aria-label="Labels" onChange={(e) => setDraft(e.target.value)} onBlur={() => save()}
        onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} aria-invalid={error !== null} />
      {error && <span className="error" role="alert">{error}</span>}
    </label>
  );
}

function MoveControls({ client, cardId, fromList, lists }: { client: SyncClient; cardId: string; fromList: string; lists: { id: string; title: string; size: number }[] }) {
  const [to, setTo] = useState(lists.some((l) => l.id === fromList) ? fromList : lists[0]?.id ?? "");
  const target = lists.find((l) => l.id === to);
  const max = target ? (to === fromList ? target.size : target.size + 1) : 1;
  const [pos, setPos] = useState(1);
  return (
    <>
      <label>List <select aria-label="Move to list" value={to} onChange={(e) => setTo(e.target.value)}>{lists.map((l) => <option key={l.id} value={l.id}>{l.title}</option>)}</select></label>
      <label>Position <input aria-label="Position" type="number" min={1} max={max} value={Math.min(pos, max)} onChange={(e) => setPos(Number(e.target.value))} /></label>
      <button type="button" disabled={!target} onClick={() => client.moveCard(cardId, to, Math.max(0, Math.min(pos, max) - 1))}>Move</button>
    </>
  );
}
