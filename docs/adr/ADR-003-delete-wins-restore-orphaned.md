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

- **Existence is an OR-Set of tags.** A tag is the `op_id` of the `ADD_*` or `RESTORE_*` that
  made it. `DELETE_*` tombstones exactly the tags its replica observed. Tombstones are kept
  for the life of the board, so a delete delivered before its add still wins (vector 012).
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
- **Cascade: deleting a list deletes its cards.** A write that must be merged; it races
  with concurrent moves into the list and turns an undo of the list into many undos.
- **Hide the cards of a deleted list.** v2's choice; the cards look lost.

## Consequences

- No edit is ever discarded, only hidden while its entity is deleted.
- Every replica computes the same Orphaned contents from the same state, so no op is needed.
  The fuzz generator deletes, restores and moves into deleted lists constantly; across 200
  sampled scenarios, 148 ended with a non-empty Orphaned list.
- Tombstones grow without bound. At 2,000 cards that is IDs only (SPEC §3.2).
- Open question for Phase 2: tombstones are a flat set, so a buggy or malicious
  `DELETE_CARD` for card X that lists a tag belonging to card Y deletes Y. Convergence is
  unaffected; the owner should check that `observed_tags` belong to the named entity.
