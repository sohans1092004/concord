// The board (SPEC §1.1, §3.6): lists and cards, drag to reorder and move, with the keyboard as
// well as the mouse (@dnd-kit). Every change goes through client-core; this file only renders
// client-core's view and turns user intents into client-core calls.

import {
  closestCorners, DndContext, KeyboardSensor, PointerSensor, useDroppable, useSensor, useSensors, type DragEndEvent,
} from "@dnd-kit/core";
import {
  horizontalListSortingStrategy, SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import type { CardView, ListView, View } from "@concord/crdt";
import type { Presence, SyncClient } from "@concord/client-core";
import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Me } from "./api";
import { CardDialog } from "./CardDialog";
import { BugReportPanel, HistoryPanel, MembersPanel, TrashPanel, UnsentPanel } from "./panels";
import { updateCommitted, useBoard, useClientVersion } from "./useBoard";

export const ORPHANED = "00000000-0000-0000-0000-000000000000";
type Panel = "trash" | "unsent" | "history" | "members" | "bug" | null;

export function BoardPage({ boardId, title, me }: { boardId: string; title: string; me: Me }) {
  const { board, error } = useBoard(boardId);
  if (error) return <p className="error">Could not open this board here: {error}</p>;
  if (!board) return <p className="muted">Opening the board…</p>;
  return <Board client={board.client} boardId={boardId} title={title} me={me} adopted={board.storage.adopted.reduce((n, a) => n + a.pending, 0)} />;
}

/**
 * The page. It does not subscribe to client-core itself: the parts that show board data do
 * (Header, Lists, the dialog and panels), so an update never re-renders the drag-and-drop
 * context, which would re-render every one of up to 2,000 sortable cards (measured: §10.1).
 */
function Board({ client, boardId, title, me, adopted }: { client: SyncClient; boardId: string; title: string; me: Me; adopted: number }) {
  const [open, setOpen] = useState<string | null>(null);
  const [panel, setPanel] = useState<Panel>(null);
  const canEdit = () => client.boardRole === "owner" || client.boardRole === "editor";
  usePresence(client, open);
  const close = useCallback(() => setPanel(null), []);
  return (
    <div className="board">
      <Header client={client} title={title} me={me} adopted={adopted} onPanel={setPanel} />
      <DndArea client={client} me={me} onOpen={setOpen} />
      {open && <CardDialog client={client} cardId={open} onClose={() => setOpen(null)} canEdit={canEdit()} />}
      {panel === "trash" && <TrashPanel client={client} canEdit={canEdit()} onClose={close} />}
      {panel === "unsent" && <UnsentPanel client={client} boardId={boardId} onClose={close} />}
      {panel === "history" && <HistoryPanel client={client} boardId={boardId} onClose={close} />}
      {panel === "members" && <MembersPanel boardId={boardId} me={me} onClose={close} />}
      {panel === "bug" && <BugReportPanel client={client} boardId={boardId} onClose={close} />}
    </div>
  );
}

function Header({ client, title, me, adopted, onPanel }: { client: SyncClient; title: string; me: Me; adopted: number; onPanel: (p: Panel) => void }) {
  useClientVersion(client);
  const members = client.members();
  const presence = client.presence();
  return (
    <>
      <header className="board-header">
        <a href="#/">← Boards</a>
        <h1>{title}</h1>
        <StatusBar client={client} onUnsent={() => onPanel("unsent")} adopted={adopted} />
        <div className="who" aria-label="On this board now">
          {[...presence].filter(([u]) => u !== me.id).map(([u, p]) => (
            <span key={u} className={`avatar ${p.state}`} title={`${members.get(u)?.name ?? "someone"}${p.cardId ? " (has a card open)" : ""}${p.state === "idle" ? ", idle" : ""}`}>
              {initials(members.get(u)?.name)}
            </span>
          ))}
        </div>
        <nav className="tools">
          <button onClick={() => onPanel("trash")}>Trash</button>
          <button onClick={() => onPanel("history")}>History</button>
          <button onClick={() => onPanel("members")}>Members</button>
          <button onClick={() => onPanel("bug")}>Report a bug</button>
        </nav>
      </header>
      {client.boardRole === "viewer" && <p className="banner">You can view this board but not edit it.</p>}
    </>
  );
}

/** The drag-and-drop context: props never change, so it renders once; Lists below subscribes. */
const DndArea = memo(function DndArea({ client, me, onOpen }: { client: SyncClient; me: Me; onOpen: (card: string) => void }) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    // Space picks up and drops; arrows move; Escape cancels. Enter is left free to open a card.
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
      keyboardCodes: { start: ["Space"], cancel: ["Escape"], end: ["Space", "Enter"] },
    }),
  );
  // Reads the view at drop time, so it never needs to change.
  const onDragEnd = useCallback(({ active, over }: DragEndEvent) => {
    if (!over || !(client.boardRole === "owner" || client.boardRole === "editor")) return;
    const view = client.view();
    const lists = view.lists.filter((l) => l.id !== ORPHANED);
    const listOf = (card: string) => view.lists.find((l) => l.cards.some((c) => c.id === card))?.id;
    const a = String(active.id), o = String(over.id);
    if (a.startsWith("list:")) {
      const id = a.slice(5);
      const target = o.startsWith("card:") ? listOf(o.slice(5)) : o.slice(5);
      const to = lists.findIndex((l) => l.id === target);
      if (to < 0 || lists[to]!.id === id) return;
      client.moveList(id, to);
      return;
    }
    const card = a.slice(5);
    let listId: string | undefined, index: number;
    if (o.startsWith("card:")) {
      listId = listOf(o.slice(5));
      index = (view.lists.find((l) => l.id === listId)?.cards ?? []).findIndex((c) => c.id === o.slice(5));
    } else {
      listId = o.slice(5);
      index = (view.lists.find((l) => l.id === listId)?.cards ?? []).filter((c) => c.id !== card).length;
    }
    if (!listId || listId === ORPHANED || index < 0) return; // nothing can be moved into Orphaned (§3.6)
    const from = listOf(card);
    const fromIndex = (view.lists.find((l) => l.id === from)?.cards ?? []).findIndex((c) => c.id === card);
    if (from === listId && fromIndex === index) return;
    client.moveCard(card, listId, index);
  }, [client]);
  return (
    <DndContext sensors={sensors} collisionDetection={closestCorners} onDragEnd={onDragEnd}>
      <Lists client={client} me={me} onOpen={onOpen} />
    </DndContext>
  );
});

function Lists({ client, me, onOpen }: { client: SyncClient; me: Me; onOpen: (card: string) => void }) {
  useClientVersion(client);
  useLayoutEffect(updateCommitted);
  const view = useStableView(client.view());
  const members = client.members();
  const movedBy = client.movedBy();
  const presence = client.presence();
  const canEdit = client.boardRole === "owner" || client.boardRole === "editor";

  // A "moved by" mark expires on its own; re-render when the next one does.
  const [, setNow] = useState(0);
  useEffect(() => {
    if (movedBy.size === 0) return;
    const t = setTimeout(() => setNow(Date.now()), 500);
    return () => clearTimeout(t);
  });

  const lists = useMemo(() => view.lists.filter((l) => l.id !== ORPHANED), [view]);
  const orphaned = view.lists.find((l) => l.id === ORPHANED);
  const listIds = lists.map((l) => `list:${l.id}`).join();
  const items = useMemo(() => (listIds ? listIds.split(",") : []), [listIds]);
  // Per-card marks ("moved by", who has it open), stable while their content is, so lists memoise.
  const marksKey = JSON.stringify([[...movedBy].map(([c, m]) => [c, members.get(m.userId ?? "")?.name ?? "someone"]),
    [...presence].filter(([u, p]) => u !== me.id && p.cardId).map(([u, p]) => [p.cardId, members.get(u)?.name ?? "someone"])]);
  const marks = useMemo(() => {
    const [moved, viewers] = JSON.parse(marksKey) as [[string, string][], [string, string][]];
    const m = new Map<string, { movedBy: string | null; viewers: string }>();
    const get = (c: string) => m.get(c) ?? (m.set(c, { movedBy: null, viewers: "" }), m.get(c)!);
    for (const [c, name] of moved) get(c).movedBy = name;
    for (const [c, name] of viewers) get(c).viewers = get(c).viewers ? `${get(c).viewers}, ${name}` : name;
    return m;
  }, [marksKey]);

  return (
    <SortableContext items={items} strategy={horizontalListSortingStrategy}>
      <div className="lists">
        {lists.map((l) => (
          <ListColumn key={l.id} list={l} client={client} canEdit={canEdit} marks={marks} onOpen={onOpen} />
        ))}
        {orphaned && <ListColumn list={orphaned} client={client} canEdit={canEdit} marks={marks} onOpen={onOpen} />}
        {canEdit && <AddList client={client} />}
      </div>
    </SortableContext>
  );
}

/** §7.5 presence: the open card, and active or idle (after 60s without input). */
function usePresence(client: SyncClient, open: string | null) {
  const last = useRef(Date.now());
  const [idle, setIdle] = useState(false);
  useEffect(() => {
    const seen = () => {
      last.current = Date.now();
      setIdle(false);
    };
    const events = ["pointerdown", "keydown", "pointermove", "wheel"] as const;
    for (const e of events) window.addEventListener(e, seen, { passive: true });
    const t = setInterval(() => setIdle(Date.now() - last.current > 60_000), 5_000);
    return () => {
      for (const e of events) window.removeEventListener(e, seen);
      clearInterval(t);
    };
  }, []);
  useEffect(() => client.setPresence(open, idle ? "idle" : "active"), [client, open, idle]);
}

/** §7.4, §10: a clear offline indicator, the pending-edits count, reconnect without user action. */
function StatusBar({ client, onUnsent, adopted }: { client: SyncClient; onUnsent: () => void; adopted: number }) {
  const pending = client.pendingOps().length;
  const unsent = client.unsentEdits().length;
  const s = client.status;
  const label =
    s === "joined" ? (pending ? "Saving…" : "All changes saved")
    : s === "unauthorized" ? "You no longer have access to this board"
    : "Offline: your edits are kept on this device and sent when you reconnect";
  return (
    <div className={`status ${s === "joined" ? "online" : "offline"}`} role="status" aria-live="polite" data-testid="status" data-status={s}>
      <span className="dot" aria-hidden="true" />
      <span>{label}</span>
      {pending > 0 && <span className="pill" data-testid="pending">{pending} pending</span>}
      {unsent > 0 && <button className="pill warn" onClick={onUnsent} data-testid="unsent-count">{unsent} unsent</button>}
      {adopted > 0 && <span className="pill" title="Edits from a tab that was closed before they were sent">+{adopted} from a closed tab</span>}
    </div>
  );
}

function AddList({ client }: { client: SyncClient }) {
  const [t, setT] = useState("");
  return (
    <form className="add-list" onSubmit={(e) => (e.preventDefault(), t.trim() && (client.addList(t.trim()), setT("")))}>
      <input aria-label="New list title" placeholder="+ Add a list" value={t} maxLength={200} onChange={(e) => setT(e.target.value)} />
    </form>
  );
}

type Marks = ReadonlyMap<string, { movedBy: string | null; viewers: string }>;

interface ListProps {
  list: ListView;
  client: SyncClient;
  canEdit: boolean;
  marks: Marks;
  onOpen: (card: string) => void;
}

/** Memoised: re-renders only when its list (kept stable by useStableView) or the marks change. */
const ListColumn = memo(function ListColumn({ list, client, canEdit, marks, onOpen }: ListProps) {
  const isOrphaned = list.id === ORPHANED;
  const items = useMemo(() => list.cards.map((c) => `card:${c.id}`), [list.cards]);
  const sortable = useSortable({ id: `list:${list.id}`, disabled: isOrphaned || !canEdit });
  const drop = useDroppable({ id: `drop:${list.id}`, disabled: isOrphaned });
  const [renaming, setRenaming] = useState(false);
  const title = isOrphaned ? "Orphaned" : String((list as { title?: unknown }).title ?? "");
  const style = { transform: CSS.Translate.toString(sortable.transform), transition: sortable.transition };
  return (
    <section ref={sortable.setNodeRef} style={style} className={`list${isOrphaned ? " orphaned" : ""}`} aria-label={`List ${title}`} data-testid="list" data-list-id={list.id}>
      <header className="list-header">
        {renaming ? (
          <input autoFocus defaultValue={title} aria-label="List title" maxLength={200}
            onBlur={(e) => (setRenaming(false), e.target.value.trim() && e.target.value !== title && client.setField(list.id, "title", e.target.value.trim()))}
            onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()} />
        ) : (
          // The title is the list's drag handle (pointer or Space + arrows), so the buttons beside it keep their own names.
          <h2 {...(isOrphaned || !canEdit ? {} : { ...sortable.attributes, ...sortable.listeners, "aria-label": `Move list ${title}` })}
            onDoubleClick={() => canEdit && !isOrphaned && setRenaming(true)}>{title}</h2>
        )}
        {isOrphaned && <p className="muted small">Cards whose list was deleted. Restore the list from Trash, or drag a card out.</p>}
        {canEdit && !isOrphaned && (
          <span className="list-actions">
            <button aria-label={`Rename list ${title}`} onClick={() => setRenaming(true)}>✎</button>
            <button aria-label={`Delete list ${title}`} onClick={() => client.deleteList(list.id)}>🗑</button>
          </span>
        )}
      </header>
      <SortableContext items={items} strategy={verticalListSortingStrategy}>
        <ol ref={drop.setNodeRef} className="cards">
          {list.cards.map((c) => (
            <CardItem key={c.id} card={c} canEdit={canEdit} onOpen={onOpen}
              movedBy={marks.get(c.id)?.movedBy ?? null} viewers={marks.get(c.id)?.viewers ?? ""} />
          ))}
        </ol>
      </SortableContext>
      {canEdit && !isOrphaned && <AddCard client={client} listId={list.id} />}
    </section>
  );
});

function AddCard({ client, listId }: { client: SyncClient; listId: string }) {
  const [t, setT] = useState("");
  return (
    <form className="add-card" onSubmit={(e) => (e.preventDefault(), t.trim() && (client.addCard(listId, t.trim()), setT("")))}>
      <input aria-label="New card title" placeholder="+ Add a card" value={t} maxLength={200} onChange={(e) => setT(e.target.value)} />
    </form>
  );
}

interface CardProps {
  card: CardView;
  canEdit: boolean;
  onOpen: (card: string) => void;
  movedBy: string | null;
  viewers: string;
}

/** One card. Memoised on what it shows, so an update re-renders only the cards it changed. */
const CardItem = memo(function CardItem({ card, canEdit, onOpen, movedBy, viewers }: CardProps) {
  const s = useSortable({ id: `card:${card.id}`, disabled: !canEdit });
  const style = { transform: CSS.Translate.toString(s.transform), transition: s.transition };
  const labels = (card.labels as string[] | undefined) ?? [];
  // A viewer cannot drag, but can still open a card: never mark it disabled.
  const handle = canEdit ? { ...s.attributes, ...s.listeners } : { role: "button", tabIndex: 0 };
  return (
    <li ref={s.setNodeRef} style={style} {...handle}
      className={`card${movedBy ? " moved" : ""}${s.isDragging ? " dragging" : ""}`}
      data-testid="card" data-card-id={card.id}
      onClick={() => onOpen(card.id)}
      onKeyDown={(e) => {
        s.listeners?.onKeyDown?.(e);
        if (e.key === "Enter" && !e.defaultPrevented) {
          e.preventDefault(); // else the Enter's keypress lands on the opened dialog's close button
          onOpen(card.id);
        }
      }}
      aria-label={`Card ${String(card.title ?? "")}`}>
      <span className="card-title">{String(card.title ?? "")}</span>
      {labels.length > 0 && <span className="labels">{labels.map((l) => <span key={l} className="label">{l}</span>)}</span>}
      {movedBy && <span className="moved-by" role="status" data-testid="moved-by">moved by {movedBy}</span>}
      {viewers && <span className="viewers" title={`Open for ${viewers}`}>👁 {viewers}</span>}
    </li>
  );
});

function sameCard(a: CardView, b: CardView): boolean {
  return a.id === b.id && a.position_key === b.position_key && a.title === b.title && a.description === b.description
    && a.assignee === b.assignee && sameLabels(a.labels, b.labels);
}

function sameLabels(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
  return a.every((x, i) => x === b[i]);
}

/**
 * client-core rebuilds the view as new objects; keep the previous object for every card and list
 * whose content is unchanged, so memoised components skip them. O(cards) shallow compares.
 */
function useStableView(next: View): View {
  const prev = useRef<View | null>(null);
  const before = prev.current;
  const oldLists = new Map<string, ListView>();
  for (const l of before?.lists ?? []) oldLists.set(l.id, l);
  // Cards that moved between lists are found here; built only if some list needs it.
  let oldCards: Map<string, CardView> | null = null;
  const findOld = (id: string): CardView | undefined => {
    if (!oldCards) {
      oldCards = new Map();
      for (const l of before?.lists ?? []) for (const c of l.cards) oldCards.set(c.id, c);
    }
    return oldCards.get(id);
  };
  let same = before !== null && before.lists.length === next.lists.length;
  const lists = next.lists.map((l, i) => {
    const p = oldLists.get(l.id);
    let listSame = p !== undefined && p.cards.length === l.cards.length && (p as { title?: unknown }).title === (l as { title?: unknown }).title
      && (p as { position_key?: string }).position_key === (l as { position_key?: string }).position_key;
    const cards = l.cards.map((c, k) => {
      const at = p?.cards[k];
      const o = at !== undefined && at.id === c.id ? at : findOld(c.id); // positional fast path
      const keep = o !== undefined && sameCard(o, c) ? o : c;
      if (keep !== at) listSame = false;
      return keep;
    });
    const out = listSame ? p! : { ...l, cards };
    if (out !== before?.lists[i]) same = false;
    return out;
  });
  const view = same ? before! : { lists };
  prev.current = view;
  return view;
}

export function initials(name: string | undefined): string {
  return (name ?? "?").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => [...w][0]!.toUpperCase()).join("");
}

export type { Presence };
