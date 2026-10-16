# ADR-002: One atomic placement register, not separate list and position fields

Status: Accepted (SPEC §3.5). Date: 2026-10-16.

## Context

A card's location is two facts: which list it is in, and where in that list. v1 stored them
as independent LWW fields (`current_list_id` and a position). Two concurrent moves of one
card then race on two registers separately:

- Replica A moves card X to *Doing* at position `p1`.
- Replica B, offline, moves X to *Done* at position `p2`.

If A wins the list register and B wins the position register, every replica converges on
*Doing* at `p2`: a placement that no user ever chose, and whose key was computed against
*Done*'s neighbours, so X can land anywhere in *Doing*. Convergence holds; intent does not.

## Decision

Placement is one LWW register holding the whole value: `{list_id, position_key}` for a card,
`{position_key}` for a list. Create, move between lists and reorder all write a complete
placement with one HLC (`ADD_*` or `SET_PLACEMENT`), and `SET_FIELD` may not write it.
Rendering a list is "live cards whose placement names this list, sorted by
`(position_key, id)`"; there is no separate sequence CRDT.

## Alternatives

- **Separate registers, as in v1.** Produces the mixed-winner placement above.
- **A sequence CRDT per list plus a list register.** A move becomes remove-from-one-sequence
  plus insert-into-another, which can duplicate a card or lose it unless the two halves are
  made atomic, which is this decision with more machinery.
- **Duplicate the card into both lists and ask the user.** Contradicts "never asked to pick
  a version" (SPEC §1).

## Consequences

- No merge can mix one writer's list with another writer's position. Concurrent moves of
  one card resolve to exactly one writer's choice (vectors 005, 006, 029, 030).
- The losing client sees its move undone. SPEC §3.5 requires a visible "moved by {name}"
  transition rather than a silent jump; that is Phase 3 UI work.
- A placement can name a list that is deleted or not yet seen on this replica. That case is
  defined by the Orphaned rule (ADR-003, SPEC §3.6), and a card given a list-form placement
  also lands in Orphaned (vector 032).
- Re-keying and rebalancing are ordinary placement writes, so they converge like any other
  op and need no special merge logic.
