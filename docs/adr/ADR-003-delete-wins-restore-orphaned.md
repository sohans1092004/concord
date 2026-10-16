# ADR-003: Delete wins over concurrent edits; restore via a new tag; Orphaned list

Status: Accepted (SPEC §3.2, §3.3, §3.6). Date: 2026-10-16.

## Context

Three concurrency cases decide what users see after a merge:

1. One user deletes a card while another edits it.
2. One user restores a deleted card while another deletes it (again).
3. One user deletes a list while its cards are live, or moves a card into it.

v1 said "add wins" for case 1, but an edit adds no OR-Set tag, so that rule could not be
implemented. v2 left the cards of a deleted list invisible, which loses edits from view.

## Decision

- **Existence is an OR-Set of tags, with per-entity tombstones.** A tag is the `op_id` of the
  `ADD_*` or `RESTORE_*` that made it. The state is `adds[id]` and `tombstones[id]`; an
  entity is live while `adds[id]` minus `tombstones[id]` is non-empty. A `DELETE_*` naming X
  tombstones exactly the (X, tag) pairs its replica observed, so it cannot touch any other
  entity whatever tags it lists (vectors 051, 052). Tombstones are kept for the life of the
  board, so a delete delivered before its add still wins (vectors 012, 053, 054).
- **Delete wins over a concurrent edit.** An edit writes its register but adds no tag, so it
  cannot revive a deleted entity. The edit is kept and shown if the entity is restored
  (vectors 002, 003, 049).
- **Restore adds a fresh tag**, so a restore concurrent with a delete survives, because that
  delete never observed the new tag (vectors 004, 020). A delete that did observe it wins
  (vector 011).
- **Orphaned is a derived view, not a write.** A live, placed card whose list is deleted,
  never seen on this replica, or absent from its placement is shown in a built-in Orphaned
  list: nil-UUID ID, shown last, only when non-empty, sorted like any list. Restoring the
  list or the list's `ADD_LIST` arriving returns the cards with no write; moving a card out is
  an ordinary placement write; no op may target the nil UUID (vectors 008, 009, 014–019, 038).

## Alternatives

- **Add wins over delete (edit revives).** Would need an edit to carry a tag, so deleting
  a card someone is typing in could never stick.
- **Two-phase set (no restore).** Simpler, but a deleted card could never come back.
- **One flat tombstone set of tags** (the first Phase 1 shape). Smaller, but a buggy or
  malicious `DELETE_CARD` for card X that listed a tag of card Y deleted Y. Convergence
  held, so no fuzz could catch it; it was found by reading the shape. Checking ownership of
  tags on the server would have fixed only the server path, so the shape was changed.
- **Cascade: deleting a list deletes its cards.** A write that must be merged; it races
  with concurrent moves into the list and turns an undo of the list into many undos.
- **Hide the cards of a deleted list.** v2's choice; the cards look lost.

## Consequences

- No edit is ever discarded, only hidden while its entity is deleted.
- Every replica computes the same Orphaned contents from the same state, so no op is needed.
  The fuzz generator deletes, restores and moves into deleted lists constantly; across 200
  sampled scenarios, 148 ended with a non-empty Orphaned list.
- Tombstones grow without bound. At 2,000 cards that is IDs only (SPEC §3.2). Keying them by
  entity adds one ID per deleted entity, not per tag.
- A bogus tag in `observed_tags` is harmless: it is stored under the named entity and
  matches nothing there.
