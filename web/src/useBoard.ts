// One SyncClient per open board, stored in IndexedDB (SPEC §7.1). React reads client-core's
// view; it never keeps board state of its own.

import { IndexedDbStorage, SyncClient } from "@concord/client-core";
import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { api, wsUrl } from "./api";

/** The last 200 client-core log events, for bug reports (they never contain a ticket). */
export const clientLog: { at: number; event: string; data: Record<string, unknown> }[] = [];

export interface OpenBoard {
  client: SyncClient;
  storage: IndexedDbStorage;
}

declare global {
  interface Window {
    /** For Playwright and for bug reports: the open board's client. Read-only use. */
    concord?: OpenBoard;
  }
}

export function useBoard(boardId: string): { board: OpenBoard | null; error: string | null } {
  const [board, setBoard] = useState<OpenBoard | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let closed = false;
    let opened: OpenBoard | null = null;
    (async () => {
      const storage = await IndexedDbStorage.open(boardId);
      const client = await SyncClient.open({
        urls: [wsUrl()],
        boardId,
        storage,
        ticket: () => api.ticket(),
        log: (event, data) => {
          clientLog.push({ at: Date.now(), event, data });
          if (clientLog.length > 200) clientLog.shift();
        },
        // SPEC 9.5: sync latency and this client's trace spans, best effort (a refused or failed
        // report is dropped; nothing retries it).
        telemetry: (report) => {
          void fetch("/api/telemetry", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(report) }).catch(() => {});
        },
      });
      opened = { client, storage };
      if (closed) {
        client.close();
        storage.close();
        return;
      }
      window.concord = opened;
      setBoard(opened);
    })().catch((e: unknown) => setError(String(e)));
    return () => {
      closed = true;
      if (opened) {
        opened.client.close();
        opened.storage.close();
        if (window.concord === opened) delete window.concord;
      }
    };
  }, [boardId]);
  return { board, error };
}

/** Re-renders on every client-core change; returns a counter that changes with it. */
export function useClientVersion(client: SyncClient | null): number {
  // Stable per client: a new subscribe function would make React re-subscribe on every render.
  const subscribe = useCallback((f: () => void) => (client ? client.onChange(() => {
    tick.n++;
    perf.changeAt ??= performance.now(); // the first change since the last commit
    f();
  }) : () => {}), [client]);
  return useSyncExternalStore(subscribe, () => tick.n);
}
const tick = { n: 0 };

/**
 * SPEC §1.2 (render < 16ms per update): from a client-core change to React's DOM commit, as a
 * `concord:update` performance measure. The board calls updateCommitted() in a layout effect.
 */
const perf: { changeAt: number | null } = { changeAt: null };
export function updateCommitted(): void {
  if (perf.changeAt === null) return;
  performance.measure("concord:update", { start: perf.changeAt, end: performance.now() });
  perf.changeAt = null;
}
