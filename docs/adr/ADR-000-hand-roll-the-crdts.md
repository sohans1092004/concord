# ADR-000: Hand-roll the CRDTs; no Automerge or Yjs

Status: Accepted (SPEC §11, decided before Phase 1). Date: 2026-10-16.

## Context

Concord needs exactly two replicated structures: an observed-remove set for which lists
and cards exist, and last-writer-wins registers for their attributes, including an atomic
placement (SPEC §3). It does not need collaborative text: descriptions are LWW as a whole
(SPEC §1.3). The project's core claim is that the engine is *proven* convergent, and that
the server (Java) and client (TypeScript) compute byte-identical state.

## Decision

Write both CRDTs, the HLC and the board composition by hand, in TypeScript (`crdt-ts/`) and
Java (`crdt-java/`). The only borrowed algorithm is the fractional-indexing key generator,
which is a credited port (ADR-001); everything else is written for this project.

## Alternatives

- **Automerge.** A general JSON CRDT with history. It solves text, lists and maps we do not
  need, and its document format and merge semantics would be ours to explain but not ours to
  control: "delete wins over a concurrent edit" (SPEC §3.3) and the Orphaned list (§3.6) are
  product rules, not Automerge's defaults. There is no Java implementation, so the server
  would have to run JavaScript or Rust through a bridge.
- **Yjs.** Excellent for text and sequences; same mismatch on semantics, and its server
  story is JavaScript. Its Java port is unofficial.
- **A library for primitives plus our own composition.** Saves the least code (the OR-Set
  and LWW register are each under 150 lines) while keeping the cross-language problem.

## Consequences

- Correctness is on us, so the test suite is the product: merge-law property tests per
  module, a permutation fuzz with 500 seeds per commit and 100,000 nightly, 54 hand-written
  conformance vectors, and a differential fuzz between the two cores (SPEC §9). The fuzzers
  were themselves tested by injecting three bugs (arrival-order LWW, a delete that forgets
  unseen tags, an HLC that ignores its counter); each was caught within 200 runs.
- Two implementations must stay identical. The canonical encoding (SPEC §9.2.2) and the
  differential harness make any drift a failing test rather than a production surprise.
- The engine is small enough to read in an afternoon, which is what makes the claim "I can
  explain every line of the merge" true.
- Anything beyond the two CRDTs (rich text, undo) would be a new decision, not a
  configuration change.
