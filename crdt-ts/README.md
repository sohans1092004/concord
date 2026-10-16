# @concord/crdt

Concord's CRDT engine: an OR-Set for which lists and cards exist, last-writer-wins
registers for their attributes (including an atomic placement), fractional ordering keys
and a hybrid logical clock. Pure: no I/O, no host APIs beyond `crypto.randomUUID` in
`op.newOpId`. Byte-identical to the Java core in the same repository.

```ts
import { Board, op, keys, hlc } from "@concord/crdt";

const board = Board.empty();
let clock = hlc.zero(replicaId);
clock = hlc.tick(clock, Date.now());
const add = { op_id: op.newOpId(), board_id, hlc: clock, replica_id: replicaId,
              type: "ADD_LIST", list_id, placement: { position_key: keys.keyBetween(null, null) }, title: "To Do" };
if (op.validateShape(add)) board.apply(add);   // returns the LWW collision count
board.view();                                   // lists and cards in order, plus Orphaned
board.canonical();                              // RFC 8785 bytes; SHA-256 is the state hash
```

Rules, encodings and test strategy: see `docs/SPEC.md` and the repository README.
`npm test` runs the unit and property tests, the 54 conformance vectors (bundled in
`vectors/`) and 500 permutation-fuzz seeds (`FUZZ_RUNS` to change).
