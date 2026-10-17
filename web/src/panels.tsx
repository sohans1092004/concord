// Side panels: Trash, Unsent edits (SPEC §7.4), History (§1.1), Members, Report a bug.

import type { SyncClient } from "@concord/client-core";
import { useEffect, useState, type ReactNode } from "react";
import { api, ApiError, type HistoryEntry, type Me, type MemberRow } from "./api";
import { describeOp, downloadJson } from "./describe";
import { clientLog, useClientVersion } from "./useBoard";

function Panel({ title, onClose, children, testId }: { title: string; onClose: () => void; children: ReactNode; testId: string }) {
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [onClose]);
  return (
    <aside className="panel" role="dialog" aria-label={title} data-testid={testId}>
      <header>
        <h2>{title}</h2>
        <button onClick={onClose} aria-label={`Close ${title}`}>✕</button>
      </header>
      <div className="panel-body">{children}</div>
    </aside>
  );
}

/** Titles of everything this replica has seen, live or deleted, for describing ops. */
function titlesOf(client: SyncClient): (id: string) => string | undefined {
  const t = new Map<string, string>();
  for (const l of client.view().lists) {
    if ("title" in l) t.set(l.id, String(l.title ?? ""));
    for (const c of l.cards) t.set(c.id, String(c.title ?? ""));
  }
  for (const x of client.trash()) t.set(x.id, x.title);
  return (id) => t.get(id);
}

export function TrashPanel({ client, canEdit, onClose }: { client: SyncClient; canEdit: boolean; onClose: () => void }) {
  useClientVersion(client);
  const items = client.trash();
  return (
    <Panel title="Trash" onClose={onClose} testId="trash">
      {items.length === 0 && <p className="muted">Nothing deleted on this board.</p>}
      <ul className="rows">
        {items.map((x) => (
          <li key={x.id} data-testid="trash-item">
            <span>{x.kind === "list" ? "List" : "Card"}: {x.title || "(untitled)"}</span>
            {canEdit && <button onClick={() => (x.kind === "list" ? client.restoreList(x.id) : client.restoreCard(x.id))}>Restore</button>}
          </li>
        ))}
      </ul>
    </Panel>
  );
}

/** §7.4: every edit the server refused for good, readable and exportable. Never dropped. */
export function UnsentPanel({ client, boardId, onClose }: { client: SyncClient; boardId: string; onClose: () => void }) {
  useClientVersion(client);
  const unsent = client.unsentEdits();
  const titles = titlesOf(client);
  const why = (code: string) =>
    code === "UNAUTHORIZED" ? "You no longer had access to this board when it was sent."
    : code === "INVALID_OP" ? "The server could not accept it (a bug in Concord; please report it)."
    : code;
  return (
    <Panel title="Unsent edits" onClose={onClose} testId="unsent">
      <p>These edits could not be saved to the board. They are kept here, on this device, so nothing you typed is lost.</p>
      {unsent.length === 0 && <p className="muted">None.</p>}
      <ul className="rows">
        {unsent.map((u) => (
          <li key={u.op.op_id} data-testid="unsent-item">
            <div>You {describeOp(u.op as unknown as Record<string, unknown>, titles)}</div>
            <div className="muted small">{new Date(u.at).toLocaleString()}: {why(u.code)}{u.detail ? ` (${u.detail})` : ""}</div>
          </li>
        ))}
      </ul>
      {unsent.length > 0 && <button onClick={() => downloadJson(`concord-unsent-${boardId}.json`, unsent)}>Export as JSON</button>}
    </Panel>
  );
}

export function HistoryPanel({ client, boardId, onClose }: { client: SyncClient; boardId: string; onClose: () => void }) {
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [next, setNext] = useState<number | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const load = (before?: number) =>
    api.history(boardId, before).then((r) => (setEntries((e) => [...e, ...r.entries]), setNext(r.next_before_version))).catch((e: unknown) => setError(String(e)));
  useEffect(() => void load(), [boardId]); // eslint-disable-line react-hooks/exhaustive-deps
  const titles = titlesOf(client);
  return (
    <Panel title="History (last 30 days)" onClose={onClose} testId="history">
      {error && <p className="error">History needs a connection: {error}</p>}
      <ol className="rows">
        {entries.map((e) => (
          <li key={e.version} data-testid="history-item">
            <div><b>{e.user_name ?? "Concord (reordering)"}</b> {describeOp(e.op, titles)}</div>
            <div className="muted small">{new Date(e.committed_at).toLocaleString()}</div>
          </li>
        ))}
      </ol>
      {next !== null && next !== undefined && <button onClick={() => void load(next)}>Older</button>}
    </Panel>
  );
}

export function MembersPanel({ boardId, me, onClose }: { boardId: string; me: Me; onClose: () => void }) {
  const [rows, setRows] = useState<MemberRow[]>([]);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("editor");
  const [msg, setMsg] = useState<string | null>(null);
  const refresh = () => api.members(boardId).then(setRows).catch((e: unknown) => setMsg(String(e)));
  useEffect(() => void refresh(), [boardId]); // eslint-disable-line react-hooks/exhaustive-deps
  const amOwner = rows.some((r) => r.user_id === me.id && r.role === "owner");
  const act = (p: Promise<unknown>) => p.then(() => (setMsg(null), refresh())).catch((e: unknown) => setMsg(e instanceof ApiError ? e.message : String(e)));
  return (
    <Panel title="Members" onClose={onClose} testId="members">
      <ul className="rows">
        {rows.map((r) => (
          <li key={r.user_id}>
            <span>{r.name} <span className="muted small">{r.email}</span></span>
            {amOwner ? (
              <span>
                <select aria-label={`Role of ${r.name}`} value={r.role} onChange={(e) => act(api.setRole(boardId, r.user_id, e.target.value))}>
                  <option value="owner">owner</option><option value="editor">editor</option><option value="viewer">viewer</option>
                </select>
                <button onClick={() => act(api.removeMember(boardId, r.user_id))}>Remove</button>
              </span>
            ) : <span className="muted">{r.role}</span>}
          </li>
        ))}
      </ul>
      {amOwner && (
        <form onSubmit={(e) => (e.preventDefault(), act(api.addMember(boardId, email, role)), setEmail(""))}>
          <input type="email" required placeholder="Email of an existing user" aria-label="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <select aria-label="Role" value={role} onChange={(e) => setRole(e.target.value)}>
            <option value="editor">editor</option><option value="viewer">viewer</option><option value="owner">owner</option>
          </select>
          <button>Add</button>
        </form>
      )}
      {msg && <p className="error" role="alert">{msg}</p>}
    </Panel>
  );
}

/**
 * The trial's bug report (Phase 3). The tester sees exactly what will be attached and can
 * untick any of it; the server redacts secrets from all of it again before storing.
 */
export function BugReportPanel({ client, boardId, onClose }: { client: SyncClient; boardId: string; onClose: () => void }) {
  const items: Record<string, unknown> = {
    app: { build: import.meta.env.VITE_BUILD ?? "dev", url: location.pathname + location.hash, user_agent: navigator.userAgent },
    connection: { status: client.status, confirmed_version: client.confirmedVersion, pending_edits: client.pendingOps().length, replica_id: client.replica },
    unsent_edits: client.unsentEdits(),
    recent_events: clientLog.slice(-200).map((e) => ({ ...e, at: Math.round(e.at) })),
  };
  const [include, setInclude] = useState<Record<string, boolean>>(Object.fromEntries(Object.keys(items).map((k) => [k, true])));
  const [text, setText] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const send = () => {
    const diagnostics = Object.fromEntries(Object.entries(items).filter(([k]) => include[k]));
    api.bugReport(boardId, text, diagnostics).then((r) => setDone(r.id)).catch((e: unknown) => setError(String(e)));
  };
  return (
    <Panel title="Report a bug" onClose={onClose} testId="bug-report">
      {done ? <p>Thank you. Report {done.slice(0, 8)} was sent.</p> : (
        <>
          <label>What happened, and what did you expect?
            <textarea rows={5} value={text} maxLength={10_000} onChange={(e) => setText(e.target.value)} aria-label="What happened" />
          </label>
          <p>This will be attached. Untick anything you do not want to send:</p>
          {Object.entries(items).map(([k, v]) => (
            <div key={k} className="diag-item" data-testid="diag-item">
              <label className="inline"><input type="checkbox" checked={include[k] ?? false} onChange={(e) => setInclude({ ...include, [k]: e.target.checked })} /> {k.replace(/_/g, " ")}</label>
              <details>
                <summary>Show what is sent</summary>
                <pre className="diag">{JSON.stringify(v, null, 2)}</pre>
              </details>
            </div>
          ))}
          <button disabled={!text.trim()} onClick={send}>Send report</button>
          {error && <p className="error" role="alert">Could not send (are you offline?): {error}</p>}
        </>
      )}
    </Panel>
  );
}
