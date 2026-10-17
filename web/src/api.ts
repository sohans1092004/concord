// The REST API (SPEC §2.1, §9.4). Same origin, so the HttpOnly session cookie goes with every
// call and the page never sees it. State-changing calls are JSON; the browser adds Origin.

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, credentials: "same-origin", headers: {} };
  if (body !== undefined) {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(body);
  }
  const r = await fetch(path, init);
  const text = await r.text();
  const json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  if (!r.ok) throw new ApiError(r.status, String(json.error ?? r.statusText));
  return json as T;
}

export interface Me {
  id: string;
  email: string;
  name: string;
}
export interface BoardRow {
  id: string;
  title: string;
  role: "owner" | "editor" | "viewer";
}
export interface MemberRow {
  user_id: string;
  name: string;
  email: string;
  role: "owner" | "editor" | "viewer";
}
export interface HistoryEntry {
  version: number;
  op_id: string;
  type: string;
  op: Record<string, unknown>;
  user_id: string;
  user_name: string | null;
  committed_at: string;
}

export const api = {
  me: () => call<Me>("GET", "/api/me"),
  signIn: (email: string, password: string) => call<Me>("POST", "/api/auth/sign-in", { email, password }),
  signUp: (email: string, name: string, password: string, invite_code?: string) =>
    call<Me>("POST", "/api/auth/sign-up", { email, name, password, ...(invite_code ? { invite_code } : {}) }),
  signOut: () => call("POST", "/api/auth/sign-out", {}),
  changePassword: (current: string, password: string) => call("POST", "/api/auth/password", { current, password }),
  /** SPEC §9.4: a single-use ticket, valid 30s, for one WebSocket connection. */
  ticket: async () => (await call<{ ticket: string }>("POST", "/api/ws-ticket", {})).ticket,
  boards: async () => (await call<{ boards: BoardRow[] }>("GET", "/api/boards")).boards,
  createBoard: (title: string) => call<BoardRow>("POST", "/api/boards", { title }),
  members: async (board: string) => (await call<{ members: MemberRow[] }>("GET", `/api/boards/${board}/members`)).members,
  addMember: (board: string, email: string, role: string) => call("POST", `/api/boards/${board}/members`, { email, role }),
  setRole: (board: string, user: string, role: string) => call("PATCH", `/api/boards/${board}/members/${user}`, { role }),
  removeMember: (board: string, user: string) => call("DELETE", `/api/boards/${board}/members/${user}`),
  history: (board: string, before?: number) =>
    call<{ entries: HistoryEntry[]; next_before_version: number | null }>("GET",
      `/api/boards/${board}/history?limit=50${before !== undefined ? `&before_version=${before}` : ""}`),
  bugReport: (board_id: string | null, description: string, diagnostics: Record<string, unknown>) =>
    call<{ id: string }>("POST", "/api/bug-reports", { board_id, description, diagnostics }),
};

/** The WebSocket URL on this origin (in dev, Vite proxies /ws to a gateway). */
export function wsUrl(): string {
  return `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`;
}
