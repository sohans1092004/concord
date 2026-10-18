// Every worked example in how-concord-was-built.md section 4, computed by the real crdt-ts code.
//   cd crdt-ts && npx vite-node ../docs/tools/examples.ts
import { Board } from "../../crdt-ts/src/board.ts";
import { tick, receive, compare } from "../../crdt-ts/src/hlc.ts";
import { keyBetween, planInsert, keysBetween } from "../../crdt-ts/src/keys.ts";
import { pick } from "../../crdt-ts/src/lww.ts";

const A = "a0000000-0000-4000-8000-00000000000a", B = "b0000000-0000-4000-8000-00000000000b";
const BOARD = "b0a4d000-0000-4000-8000-000000000001";
const L = "11000000-0000-4000-8000-000000000001", L2 = "11000000-0000-4000-8000-000000000002";
const X = "cc000000-0000-4000-8000-000000000001";
const id = (n: number) => `0e000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const h = (p: number, c: number, r: string) => ({ physical_ms: p, counter: c, replica_id: r });
const op = (n: number, r: string, hl: ReturnType<typeof h>, body: object) => ({ op_id: id(n), board_id: BOARD, hlc: hl, replica_id: r, ...body }) as any;

console.log("HLC");
let a = h(1000, 0, A);
console.log(" A tick at wall 1000 ->", JSON.stringify(tick(a, 1000)));
const remote = h(1005, 2, A);
console.log(" B (last 990/0) receives A's 1005/2 at wall 995 ->", JSON.stringify(receive(h(990, 0, B), remote, 995)));
console.log(" compare 1000/1/A vs 1000/1/B:", compare(h(1000, 1, A), h(1000, 1, B)));

console.log("KEYS");
console.log(" between null,null:", keyBetween(null, null));
console.log(" after a0:", keyBetween("a0", null), " after a1:", keyBetween("a1", null));
console.log(" between a0,a1:", keyBetween("a0", "a1"));
console.log(" between a0,a0V:", keyBetween("a0", "a0V"));
console.log(" planInsert([a0,a1,a1,a2], 2):", JSON.stringify(planInsert(["a0", "a1", "a1", "a2"], 2)));
console.log(" keysBetween(null,null,4):", keysBetween(null, null, 4));

console.log("LWW tie");
console.log(" pick equal hlc, x vs y:", JSON.stringify(pick({ value: "x", hlc: h(1000, 0, A) }, { value: "y", hlc: h(1000, 0, A) })));

function run(name: string, ops: any[], orders: number[][]) {
  const hashes = orders.map((o) => {
    const b = Board.empty();
    for (const i of o) b.apply(ops[i]);
    return { order: o.join(","), view: JSON.stringify(b.view()), state: b.canonical() };
  });
  console.log(name);
  for (const x of hashes) console.log("  order", x.order, "view", x.view);
  console.log("  identical states:", new Set(hashes.map((x) => x.state)).size === 1);
}

const addL = op(1, A, h(1000, 0, A), { type: "ADD_LIST", list_id: L, placement: { position_key: "a0" }, title: "To Do" });
const addX = op(2, A, h(1001, 0, A), { type: "ADD_CARD", card_id: X, placement: { list_id: L, position_key: "a0" }, title: "Pay rent" });
// delete vs concurrent restore
const delX = op(3, B, h(1002, 0, B), { type: "DELETE_CARD", card_id: X, observed_tags: [id(2)] });
const restX = op(4, A, h(1003, 0, A), { type: "RESTORE_CARD", card_id: X });
run("DELETE (B, observed t2) vs concurrent RESTORE (A, new tag t4)", [addL, addX, delX, restX], [[0, 1, 2, 3], [0, 1, 3, 2]]);
// delete wins over edit
const edit = op(5, A, h(1004, 0, A), { type: "SET_FIELD", entity_id: X, field: "title", value: "Pay rent today" });
run("DELETE (B) vs concurrent edit (A)", [addL, addX, delX, edit], [[0, 1, 2, 3], [0, 1, 3, 2]]);
// delete before add arrives
run("DELETE arrives before its ADD", [addL, addX, delX], [[0, 1, 2], [0, 2, 1]]);
// concurrent moves: atomic placement
const addL2 = op(6, A, h(1000, 1, A), { type: "ADD_LIST", list_id: L2, placement: { position_key: "a1" }, title: "Done" });
const mvA = op(7, A, h(1010, 0, A), { type: "SET_PLACEMENT", entity_id: X, placement: { list_id: L, position_key: "a1" } });
const mvB = op(8, B, h(1011, 0, B), { type: "SET_PLACEMENT", entity_id: X, placement: { list_id: L2, position_key: "a0" } });
run("concurrent moves of X", [addL, addL2, addX, mvA, mvB], [[0, 1, 2, 3, 4], [0, 1, 2, 4, 3]]);
// orphaned
const delL = op(9, B, h(1020, 0, B), { type: "DELETE_LIST", list_id: L, observed_tags: [id(1)] });
const Y = "cc000000-0000-4000-8000-000000000002";
const addY = op(10, A, h(1021, 0, A), { type: "ADD_CARD", card_id: Y, placement: { list_id: L, position_key: "a2" }, title: "Call bank" });
run("DELETE_LIST (B) vs concurrent ADD_CARD into it (A)", [addL, addX, delL, addY], [[0, 1, 2, 3], [0, 1, 3, 2]]);
// equal keys from concurrent appends
const Z = "cc000000-0000-4000-8000-000000000003";
const apA = op(11, A, h(1030, 0, A), { type: "ADD_CARD", card_id: Y, placement: { list_id: L, position_key: "a1" }, title: "From A" });
const apB = op(12, B, h(1030, 0, B), { type: "ADD_CARD", card_id: Z, placement: { list_id: L, position_key: "a1" }, title: "From B" });
run("concurrent appends pick the same key a1", [addL, addX, apA, apB], [[0, 1, 2, 3], [0, 1, 3, 2]]);
