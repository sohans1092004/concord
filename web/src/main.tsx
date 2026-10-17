// Concord's web app: sign-in, my boards, a board. Hash routes, no router library:
//   #/            my boards (or sign-in)
//   #/b/{id}      a board

import { createRoot } from "react-dom/client";
import { useEffect, useState } from "react";
import { api, ApiError, type BoardRow, type Me } from "./api";
import { BoardPage } from "./BoardPage";
import "./styles.css";

function useHash(): string {
  const [h, setH] = useState(location.hash);
  useEffect(() => {
    const f = () => setH(location.hash);
    window.addEventListener("hashchange", f);
    return () => window.removeEventListener("hashchange", f);
  }, []);
  return h;
}

/** Who is signed in (no secret: the session is an HttpOnly cookie), so a reload or a new tab opens offline too. */
const ME_KEY = "concord:me";

function App() {
  const hash = useHash();
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  useEffect(() => {
    api.me().then((m) => (localStorage.setItem(ME_KEY, JSON.stringify(m)), setMe(m))).catch((e: unknown) => {
      const cached = localStorage.getItem(ME_KEY);
      // Offline: keep working as the user last signed in here (§7: offline for up to 24h).
      if (!(e instanceof ApiError) && cached) setMe(JSON.parse(cached) as Me);
      else setMe(null);
    });
  }, []);
  if (me === undefined) return <p className="muted">Loading…</p>;
  if (me === null) return <SignIn onIn={(m) => (localStorage.setItem(ME_KEY, JSON.stringify(m)), setMe(m))} />;
  const board = /^#\/b\/([0-9a-f-]{36})$/.exec(hash)?.[1];
  if (board) return <BoardPage key={board} boardId={board} title={boardTitle(board)} me={me} />;
  return <Boards me={me} onOut={() => (localStorage.removeItem(ME_KEY), setMe(null))} />;
}

const titles = new Map<string, string>();
function boardTitle(id: string): string {
  return titles.get(id) ?? localStorage.getItem(`concord:title:${id}`) ?? "Board";
}

function SignIn({ onIn }: { onIn: (m: Me) => void }) {
  const [mode, setMode] = useState<"in" | "up">("in");
  const [f, setF] = useState({ email: "", name: "", password: "", invite: "" });
  const [error, setError] = useState<string | null>(null);
  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    (mode === "in" ? api.signIn(f.email, f.password) : api.signUp(f.email, f.name, f.password, f.invite))
      .then(onIn).catch((x: unknown) => setError(x instanceof ApiError ? x.message : "Cannot reach Concord. Are you online?"));
  };
  const field = (k: keyof typeof f, label: string, type = "text") => (
    <label>{label}<input type={type} value={f[k]} required={k !== "invite"} onChange={(e) => setF({ ...f, [k]: e.target.value })} aria-label={label} /></label>
  );
  return (
    <main className="signin">
      <h1>Concord</h1>
      <form onSubmit={submit}>
        {field("email", "Email", "email")}
        {mode === "up" && field("name", "Your name (shown to others)")}
        {field("password", "Password", "password")}
        {mode === "up" && field("invite", "Invite code (if you were given one)")}
        <button>{mode === "in" ? "Sign in" : "Create account"}</button>
        {error && <p className="error" role="alert">{error}</p>}
      </form>
      <button className="link" onClick={() => (setMode(mode === "in" ? "up" : "in"), setError(null))}>
        {mode === "in" ? "New here? Create an account" : "Have an account? Sign in"}
      </button>
    </main>
  );
}

function Boards({ me, onOut }: { me: Me; onOut: () => void }) {
  const [boards, setBoards] = useState<BoardRow[] | null>(null);
  const [title, setTitle] = useState("");
  const [error, setError] = useState<string | null>(null);
  const refresh = () => api.boards().then((b) => {
    for (const x of b) (titles.set(x.id, x.title), localStorage.setItem(`concord:title:${x.id}`, x.title));
    setBoards(b);
  }).catch((e: unknown) => setError(String(e)));
  useEffect(() => void refresh(), []);
  return (
    <main className="boards">
      <header>
        <h1>Your boards</h1>
        <span className="muted">{me.name}</span>
        <button className="link" onClick={() => void api.signOut().finally(onOut)}>Sign out</button>
      </header>
      {error && <p className="error">{error}</p>}
      <ul className="rows">
        {boards?.map((b) => <li key={b.id}><a href={`#/b/${b.id}`}>{b.title}</a> <span className="muted small">{b.role}</span></li>)}
      </ul>
      <form onSubmit={(e) => (e.preventDefault(), title.trim() && api.createBoard(title.trim()).then((b) => (setTitle(""), refresh(), (location.hash = `#/b/${b.id}`))))}>
        <input placeholder="New board title" aria-label="New board title" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />
        <button>Create board</button>
      </form>
    </main>
  );
}

// The offline app shell (public/sw.js). Not in Vite's dev server, where it would cache dev modules.
if ("serviceWorker" in navigator && import.meta.env.PROD) void navigator.serviceWorker.register("/sw.js");

createRoot(document.getElementById("root")!).render(<App />);
