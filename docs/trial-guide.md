# Concord trial: a guide for testers

Thank you for trying Concord. It is a shared board, like Trello: lists, and cards in them,
that several people edit at once. Its special trick is that it **keeps working without
internet** and merges everyone's changes when you are back, with no "which version do you
want?" questions. Please use it for real for two weeks, and tell us whenever something
surprises you.

## Getting started

1. Open the link you were sent. Choose **Create an account** and enter your email, the name
   others should see, a password (8 characters or more) and the invite code, if you were
   given one.
2. **Create board**, or wait for someone to add you to theirs. Board owners add people under
   **Members**, using the email they signed up with, as *editor* (can change things) or
   *viewer* (can only look).
3. Add lists with **+ Add a list**, and cards with **+ Add a card** under a list.

## Everyday use

- **Open a card** by clicking it, or by focusing it and pressing Enter. You can edit its
  title, description, assignee and labels (comma-separated). Changes save when you leave the
  field.
- **Move cards** by dragging them. Without a mouse: focus a card, press Space, use the arrow
  keys, then Space again. Or use **Move** inside an open card.
- **Delete** a card or list with its delete button. Nothing is gone for good: **Trash** lists
  everything deleted, with **Restore**. Cards whose list was deleted wait in **Orphaned**
  until the list is restored or you drag them out.
- **History** shows who changed what in the last 30 days.
- The round initials at the top are the people looking at the board right now; an eye on a
  card shows who has it open.

## Offline

You can keep working with no connection: on a train, a plane, in a basement. The bar at the
top says **Offline** and counts your edits that are waiting. They are kept on this device,
even if you close the tab or reload the page, and are sent by themselves when you are back
online. If someone else moved the same card while you were away, the later move wins and
the card is marked **moved by {name}** for a moment, so it never jumps silently.

If an edit can never be saved (for example, the owner removed you from the board while you
were offline), it is not thrown away: it appears under **unsent** at the top, where you can
read it and **Export as JSON**.

## What we would like you to try

- Edit the same board as a friend at the same time, from two devices.
- Turn off Wi-Fi, make many changes (move, delete, restore, rename), reload the page, then
  turn Wi-Fi back on. Do both devices end up showing the same board?
- Open the board in two tabs, and close one while offline.
- Anything you would normally use a board for.

## Reporting a bug

Press **Report a bug** on the board. Describe what you did, what you expected and what
happened. The form shows **exactly** what will be attached (the connection status, edits
waiting, unsent edits and the app's recent events); untick anything you prefer not to send.
Your password and sign-in are never included. Reports go straight to us; we read every one.

If the app does not open at all, email the person who invited you, with the time and what
you saw.

## Known limits

- Descriptions are saved whole: if two people edit the same description at the same moment,
  the later save wins (both versions are in **History**).
- Two people adding several cards into the same spot at once can see them interleave.
- Use a current Chrome, Edge, Firefox or Safari. Private windows may not keep offline edits.
