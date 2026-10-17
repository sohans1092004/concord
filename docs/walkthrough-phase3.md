# Walkthrough: one click, from the screen to IndexedDB, to the wire, to another browser

This follows one user action, typing a card title and pressing Enter, through the browser
that makes it and the browser that sees it. It is written for someone who did not write the
code: each step names the file and line, so you can read the code in the order the edit
travels. The server's half (gateway, owner, Postgres, Redis) is covered step by step in
`docs/walkthrough-phase2.md`; here it is summarised in section 4. The rules each step
implements are in `docs/SPEC.md`, cited as §.

```
browser A                                                     browser B
 React form ─▶ client-core submit ─▶ view (instant)            view ◀─ React re-render
                │                                                ▲
                ├─▶ IndexedDB (pending queue)                    │ client-core: confirmed op
                └─▶ WebSocket ─▶ gateway ─▶ owner ─▶ Postgres ─▶ Redis ─▶ gateway ─▶ WebSocket
                         ◀── OP_ACK ◀──┘
```

Three layers in the browser, and only one of them knows anything about syncing:

- **`web/`** (React): renders client-core's view and turns clicks into client-core calls. It
  keeps no board state of its own.
- **`client-core/`** (`SyncClient`): the confirmed replica, the pending queue, the view,
  the connection, every rejection path (§7).
- **`crdt-ts/`** (the engine): `Board.apply`, `Board.view`, keys, the HLC. Pure functions,
  no I/O, proven against the Java engine by the conformance suite (§9.2).

## 0. Before the click: opening the board

1. The board page opens a `SyncClient` for the board, `web/src/useBoard.ts:30-35`:
   - storage is `IndexedDbStorage.open(boardId)`;
   - the WebSocket ticket comes from `api.ticket()`, a `POST /api/ws-ticket` made with the
     signed-in session cookie (§9.4).
2. `IndexedDbStorage.open`, `client-core/src/idb.ts:87`, claims this tab's replica (§7.1):
   - the replica ID remembered in `sessionStorage` (so a reload resumes the same replica);
   - the tab holds a Web Lock on that ID for its lifetime, `idb.ts:92`;
   - if another live tab holds that lock (a duplicated tab), this tab makes a new replica ID.
3. `load()`, `idb.ts:103`, reads this replica's saved state and adopts the queues of tabs
   that were closed before their edits were sent. It takes each one's lock first,
   `idb.ts:109`, so two tabs never adopt the same queue.
4. `SyncClient.connect` fetches a ticket, `client-core/src/client.ts:399`, and opens
   `ws://…/ws?ticket=…`, `client.ts:408`. The gateway uses the ticket up in one SQL
   statement before upgrading the connection:
   - `server/gateway/src/main/java/dev/concord/gateway/GatewayMain.java:74-79`;
   - `Api.consumeTicket`, `Api.java:234`.
5. The client sends `JOIN`; the reply `JOINED` carries the board (ops since the client's
   version, or a snapshot), the member names, and the server time used to correct the
   client's clock (§3.1, §7.5).

## 1. The click (browser A)

6. The "+ Add a card" box is a form, `web/src/BoardPage.tsx:281`. On submit it calls
   `client.addCard(listId, title)`. That is all React does.
7. `addCard`, `client-core/src/client.ts:320`, picks a position key for the end of the list
   with `keys.planInsert`. If the neighbours share a key (two people appended at once), it
   first re-keys the upper ones with ordinary `SET_PLACEMENT` ops (§3.7, rule B). Then it
   calls `submit`.
8. `submit`, `client.ts:297`, makes the op (§7.2):
   - it ticks the hybrid logical clock;
   - it gives the op a fresh `op_id`;
   - it checks the op with the engine's `validateShape`, so a bug fails loudly here rather
     than on the server.
9. The op goes on the pending queue, `client.ts:302`, and is applied to the view board at
   once, `client.ts:303`. The view is "confirmed replica + ops waiting for a gap to fill +
   my pending ops", merged. Merging is commutative and idempotent, so applying an op twice
   (here, and again when the server confirms it) changes nothing.

## 2. Into IndexedDB (browser A)

10. `submit` marks the queue dirty, `client.ts:304`, and `changed()`, `client.ts:776`, saves
    at once through `saveNow`, `client.ts:784` → `this.storage.save`, `client.ts:798`.
    Changes only to the confirmed replica (other people's ops) are saved within a second
    instead: losing one just means catching up from an older version.
11. `IndexedDbStorage.save`, `idb.ts:139`, writes this tab's record in one transaction. From
    here the edit survives a reload, a closed tab or a crash. If the tab is closed before
    the edit is sent, the next tab to open the board adopts it (step 3).
12. `changed()` then notifies subscribers: `emit`, `client.ts:809`.

## 3. On the screen, and on the wire (browser A)

13. React is subscribed through `useClientVersion`, `web/src/useBoard.ts:63`
    (`useSyncExternalStore`). Only the components that show board data subscribe; the
    drag-and-drop context does not, so an update never re-renders all 2,000 sortable cards.
    `Lists`, `BoardPage.tsx:131`, reads `client.view()`. `useStableView`,
    `BoardPage.tsx:134`, keeps the previous object for every unchanged list and card, so
    the memoised `ListColumn` (`BoardPage.tsx:237`) and `CardItem` (`BoardPage.tsx:296`)
    re-render only what changed.
14. The new card is on screen before any network traffic. Each update's time from
    client-core's change to React's DOM commit is recorded as the `concord:update`
    performance measure, `useBoard.ts:79` (§10.1 measurement).
15. `sendOp`, `client.ts:485`, sends `{"type":"OP","op":{…}}` if joined. If offline, the op
    simply waits in the queue: after the next `JOINED` the whole queue is resent in order,
    and the server's dedup by `op_id` makes resending safe (§4.3, §7.3).
    - A connection that hears nothing for 15s is treated as dead. The owner sends
      `BOARD_VERSION` every 5s, so silence means a dropped network that left the socket
      open.
    - The browser's `offline` event drops the socket at once (§7.3, amendment 18).

## 4. Through the server (summary of `walkthrough-phase2.md`)

16. The gateway parses the frame once, rate-limits it, and forwards it in order to the
    board's owner:
    - `ClientConn.onMessage`, `server/gateway/src/main/java/dev/concord/gateway/ClientConn.java:90`;
    - `op()`, `ClientConn.java:170-182`.

    The `user_id` it adds comes from the ticket, never from the client's JSON.
17. The owner's board actor dedups and validates the op, `admit`,
    `server/session/src/main/java/dev/concord/session/BoardActor.java:245`. It
    group-commits it to Postgres under its fence, `flush`, `BoardActor.java:361`. Only
    after the commit does it apply the op and reply `OP_ACK`. It then publishes
    `{op, version, user_id}` on Redis, `applyCommitted`, `BoardActor.java:458-469`.
18. Every gateway with a client on the board relays that broadcast,
    `Fanout.deliver`, `server/gateway/src/main/java/dev/concord/gateway/Fanout.java:88-97`.

## 5. The acknowledgement (browser A)

19. `OP_ACK {op_id, version}` arrives, `client.ts:529` → `acked`, `client.ts:597`:
    - the op leaves the pending queue;
    - it enters the confirmed replica at its version, `confirmedOp` (`client.ts:576`) →
      `drain` (`client.ts:588`) → `applyConfirmed` (`client.ts:568`).

    The view does not change, because the op was already in it. The queue change is saved
    at once (step 10).
20. If the server had refused the op for good (`INVALID_OP`, or `UNAUTHORIZED` after access
    was revoked), the op would move to Unsent edits instead, and the view would be rebuilt
    without it. Nothing typed is dropped: the Unsent edits panel shows it in words and can
    export it as JSON, `web/src/panels.tsx` (§7.4).

## 6. On the other browser (browser B)

21. B receives `OP_BROADCAST {op, version, user_id}`, `client.ts:518`. If its version is the
    next one, the op is applied to B's confirmed replica and view. If a version is missing,
    B holds the op and asks for the gap with `SYNC_REQUEST` before applying anything newer
    (§6.4).
22. B's `changed()` emits; B's `Lists` re-renders with the new card; only that list's
    column re-renders, because `useStableView` reused every other list and card object.
23. If B had concurrently moved the same card elsewhere and lost (A's move had the higher
    HLC, §3.5), `checkMoves`, `client.ts:745`, marks the card. B then shows
    "moved by {A's name}" for a few seconds instead of a silent jump. A's name comes from
    the member map in `JOINED`, keyed by the `user_id` on the op (amendment 15).

## Where to check each claim

| Claim | Test |
|---|---|
| Two browsers, one offline for 200+ edits with a reload, converge with nothing unsent | `web/e2e/offline.spec.ts`, "exit criterion" |
| A reload while offline loses nothing | `web/e2e/offline.spec.ts`, "a reload mid-offline" |
| A duplicated tab gets its own replica; a closed tab's queue is adopted exactly once | `web/e2e/offline.spec.ts` |
| The loser of a concurrent move sees "moved by {name}" | `web/e2e/moved.spec.ts` |
| Each update renders in under 16ms at 2,000 cards and 10 editors | `web/e2e/perf.spec.ts` |
| The incremental view always equals a rebuild | `client-core/test/unit/features.test.ts` |
| A ticket works once, within 30s | `server/gateway/src/test/java/dev/concord/gateway/ApiTest.java` |
