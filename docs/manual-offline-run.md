# One-hour offline run, by hand (SPEC §10 Phase 3 exit)

A person does this with two real browsers; nothing is scripted and no developer tools are
opened. Record the result in the table at the end (and attach anything odd as a bug report
from the app). The scripted, real-time hour is recorded separately in
`docs/offline-hour-scripted.md` and is labelled as scripted.

## Setup (10 minutes)

1. Stack up: `docker compose -f deploy/docker-compose.yml up -d --build`, then
   `cd web && npx vite build && npx vite preview` (the app at http://localhost:4173), or the
   trial deployment.
2. Two browsers, **A** and **B**, ideally on two machines (B a laptop that can lose Wi-Fi).
   Sign up two accounts. A creates a board "Offline hour", adds B as editor.
3. On A, make three lists and about ten cards. Wait until both show **All changes saved**.
4. Write down the start time.

## The hour

| Minute | On B (offline) | On A (online) |
|---|---|---|
| 0 | Turn Wi-Fi off (not the browser's offline switch). Within 15s the bar says **Offline**. | |
| 0–15 | Add 15 cards across lists; rename 5; add labels to 5; set assignees on 3. | Rename two cards B will also rename. |
| 15 | Reload the page. The board opens, all edits still there, same pending count. | Move a card B will also move (remember which). |
| 15–30 | Drag 10 cards between lists, including the one A moved; reorder a list. | Delete one card; add 5 cards. |
| 30 | Close the tab. Open the board again in a new tab (still offline). The pending count is unchanged, possibly shown as "+N from a closed tab". | |
| 30–45 | Delete 5 cards, restore 2 from **Trash**; delete one list (its cards go to **Orphaned**). | Edit a description of a card in the list B deletes. |
| 45–60 | Restore the deleted list; 10 more mixed edits. Note the pending count. | Keep editing lightly. |
| 60 | Turn Wi-Fi on. Do not touch anything. | |

## Checks after reconnecting

- [ ] B reconnects by itself within about a minute; the bar returns to **All changes saved**;
  the pending count goes to 0.
- [ ] **unsent** is not shown on either browser.
- [ ] A and B show the same lists, in the same order, with the same cards in the same order
  (compare list by list; screenshots of both help).
- [ ] The card both moved shows **moved by {name}** briefly on the browser whose move lost.
- [ ] Every card B added offline is present on A.
- [ ] **History** on A shows B's offline edits, attributed to B.
- [ ] Orphaned cards returned to the restored list.

## Result

| Field | Value |
|---|---|
| Date and start time | |
| Tester | |
| Browsers and machines | |
| Offline duration (minutes) | |
| Edits made on B offline (pending count before reconnecting) | |
| Time from Wi-Fi on to "All changes saved" | |
| Both boards identical? | |
| Unsent edits | |
| Anything odd (with bug report IDs) | |
