"""Expands the hand-written conformance vectors below into conformance/vectors/*.json.

Every expected value here is written by hand. This script only:
  - expands readable aliases (L(1), C(2), O(3), ...) into UUIDs,
  - sorts tag arrays (canonical state requires sorted tags),
  - hashes the hand-written expected_state with Python's JSON encoder, which for
    ASCII keys and integer-only data produces RFC 8785 bytes (SPEC §9.2.2).

Usage: python build_vectors.py          write vectors
       python build_vectors.py --check  fail if the committed files are stale
"""
import hashlib
import json
import pathlib
import sys

OUT = pathlib.Path(__file__).resolve().parent.parent / "vectors"

T0 = 1760000000000
BOARD = "b0a4d000-0000-4000-8000-000000000001"
NIL = "00000000-0000-0000-0000-000000000000"


def _uuid(prefix, n):
    return f"{prefix}-0000-4000-8000-{n:012x}"


def R(n): return _uuid("a0000000", n)   # replica
def L(n): return _uuid("11000000", n)   # list
def C(n): return _uuid("cc000000", n)   # card
def O(n): return _uuid("0e000000", n)   # op id (= add-tag)
def P(n): return _uuid("ee000000", n)   # person (assignee)


def H(ms, counter, replica):
    """HLC at T0 + ms."""
    return {"counter": counter, "physical_ms": T0 + ms, "replica_id": R(replica)}


# ---- ops -------------------------------------------------------------------

def op(n, typ, hlc, **fields):
    return {"op_id": O(n), "board_id": BOARD, "hlc": hlc,
            "replica_id": hlc["replica_id"], "type": typ, **fields}


def add_list(n, hlc, lid, key, title):
    return op(n, "ADD_LIST", hlc, list_id=lid, placement={"position_key": key}, title=title)


def add_card(n, hlc, cid, lid, key, title):
    return op(n, "ADD_CARD", hlc, card_id=cid,
              placement={"list_id": lid, "position_key": key}, title=title)


def set_field(n, hlc, eid, field, value):
    return op(n, "SET_FIELD", hlc, entity_id=eid, field=field, value=value)


def move(n, hlc, cid, lid, key):
    return op(n, "SET_PLACEMENT", hlc, entity_id=cid,
              placement={"list_id": lid, "position_key": key})


def move_list(n, hlc, lid, key):
    return op(n, "SET_PLACEMENT", hlc, entity_id=lid, placement={"position_key": key})


def del_list(n, hlc, lid, tags):
    return op(n, "DELETE_LIST", hlc, list_id=lid, observed_tags=tags)


def del_card(n, hlc, cid, tags):
    return op(n, "DELETE_CARD", hlc, card_id=cid, observed_tags=tags)


def restore_list(n, hlc, lid):
    return op(n, "RESTORE_LIST", hlc, list_id=lid)


def restore_card(n, hlc, cid):
    return op(n, "RESTORE_CARD", hlc, card_id=cid)


# ---- states and views ------------------------------------------------------

def reg(value, hlc):
    return {"hlc": hlc, "value": value}


def lreg(title, key, hlc):
    """Registers an ADD_LIST writes."""
    return {"title": reg(title, hlc), "placement": reg({"position_key": key}, hlc)}


def creg(title, lid, key, hlc):
    """Registers an ADD_CARD writes."""
    return {"title": reg(title, hlc),
            "placement": reg({"list_id": lid, "position_key": key}, hlc)}


def state(lists=None, list_tombs=(), cards=None, card_tombs=(), registers=None):
    return {"cards": {"adds": cards or {}, "tombstones": list(card_tombs)},
            "lists": {"adds": lists or {}, "tombstones": list(list_tombs)},
            "registers": registers or {}}


EMPTY = state()


def vlist(lid, key, title, cards):
    return {"id": lid, "position_key": key, "title": title, "cards": cards}


def vcard(cid, key, title, **extra):
    return {"id": cid, "position_key": key, "title": title, **extra}


def orphaned(cards):
    return {"id": NIL, "cards": cards}


def view(*lists):
    return {"lists": list(lists)}


# ---- vectors ---------------------------------------------------------------

VECTORS = []


def vector(name, rule, description, ops, expected_view, expected_state,
           initial_state=EMPTY, server_time_ms=None, expected_rejected=None,
           expected_collisions=0):
    v = {"name": name, "rule": rule, "description": description,
         "initial_state": initial_state}
    if server_time_ms is not None:
        v["server_time_ms"] = server_time_ms
    v["ops"] = ops
    v["expected_rejected"] = expected_rejected or {}
    v["expected_collisions"] = expected_collisions
    v["expected_view"] = expected_view
    v["expected_state"] = expected_state
    VECTORS.append(v)


vector(
    "001-add-list-and-card", "§3.2 §3.5",
    "One list, one card. The baseline every other vector builds on.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Write spec")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Write spec")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): creg("Write spec", L(1), "a0", H(1, 0, 1))}),
)

vector(
    "002-delete-wins-over-concurrent-edit", "§3.3",
    "Replica 2 deletes the card while replica 1 concurrently renames it with a higher "
    "HLC. The edit adds no tag, so the card stays deleted; the edit is kept in the "
    "register, only hidden.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Draft"),
     del_card(3, H(5, 0, 2), C(1), [O(2)]),
     set_field(4, H(9, 0, 1), C(1), "title", "Draft v2")],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, card_tombs=[O(2)],
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): {"title": reg("Draft v2", H(9, 0, 1)),
                            "placement": reg({"list_id": L(1), "position_key": "a0"},
                                             H(1, 0, 1))}}),
)

vector(
    "003-restore-shows-edits-made-while-deleted", "§3.3",
    "A card is deleted, edited while deleted, then restored. The restored card shows "
    "the edit made after the delete.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Draft"),
     del_card(3, H(2, 0, 1), C(1), [O(2)]),
     set_field(4, H(3, 0, 2), C(1), "description", "edited after delete"),
     restore_card(5, H(4, 0, 1), C(1))],
    view(vlist(L(1), "a0", "To Do",
               [vcard(C(1), "a0", "Draft", description="edited after delete")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2), O(5)]}, card_tombs=[O(2)],
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): {**creg("Draft", L(1), "a0", H(1, 0, 1)),
                            "description": reg("edited after delete", H(3, 0, 2))}}),
)

vector(
    "004-restore-concurrent-with-delete", "§3.2",
    "Replica 1 deletes the card; replica 2 sees that and restores it with a new tag "
    "(op 4). Replica 3, which never saw the restore, deletes the card again with only "
    "the original tag observed and a higher HLC. The restore's tag was never observed by "
    "that delete, so the card survives. A two-phase set would lose it.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Keep me"),
     del_card(3, H(2, 0, 1), C(1), [O(2)]),
     restore_card(4, H(3, 0, 2), C(1)),
     del_card(5, H(4, 0, 3), C(1), [O(2)])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Keep me")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2), O(4)]}, card_tombs=[O(2)],
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): creg("Keep me", L(1), "a0", H(1, 0, 1))}),
)

vector(
    "005-concurrent-moves-to-different-lists", "§3.5",
    "Replica 2 moves the card to Doing at key a0; replica 3, offline, moves it to Done "
    "at key Zz with a higher HLC. The higher placement wins whole: Done at Zz. No mix of "
    "one writer's list with the other's key.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_list(2, H(1, 0, 1), L(2), "a1", "Doing"),
     add_list(3, H(2, 0, 1), L(3), "a2", "Done"),
     add_card(4, H(3, 0, 1), C(1), L(1), "a0", "Card X"),
     move(5, H(10, 0, 2), C(1), L(2), "a0"),
     move(6, H(12, 0, 3), C(1), L(3), "Zz")],
    view(vlist(L(1), "a0", "To Do", []),
         vlist(L(2), "a1", "Doing", []),
         vlist(L(3), "a2", "Done", [vcard(C(1), "Zz", "Card X")])),
    state(lists={L(1): [O(1)], L(2): [O(2)], L(3): [O(3)]}, cards={C(1): [O(4)]},
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     L(2): lreg("Doing", "a1", H(1, 0, 1)),
                     L(3): lreg("Done", "a2", H(2, 0, 1)),
                     C(1): {"title": reg("Card X", H(3, 0, 1)),
                            "placement": reg({"list_id": L(3), "position_key": "Zz"},
                                             H(12, 0, 3))}}),
)

vector(
    "006-concurrent-moves-replica-id-tiebreak", "§3.1 §3.5",
    "Two moves with the same physical_ms and counter. The HLC falls through to "
    "replica_id: replica ...03 > ...02, so the move to Doing wins even though it is "
    "listed first.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_list(2, H(0, 1, 1), L(2), "a1", "Doing"),
     add_card(3, H(0, 2, 1), C(1), L(1), "a0", "Card X"),
     move(4, H(20, 0, 3), C(1), L(2), "a0"),
     move(5, H(20, 0, 2), C(1), L(1), "a1")],
    view(vlist(L(1), "a0", "To Do", []),
         vlist(L(2), "a1", "Doing", [vcard(C(1), "a0", "Card X")])),
    state(lists={L(1): [O(1)], L(2): [O(2)]}, cards={C(1): [O(3)]},
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     L(2): lreg("Doing", "a1", H(0, 1, 1)),
                     C(1): {"title": reg("Card X", H(0, 2, 1)),
                            "placement": reg({"list_id": L(2), "position_key": "a0"},
                                             H(20, 0, 3))}}),
)

vector(
    "007-equal-keys-tie-break-by-entity-id", "§3.7",
    "Two replicas append to the same list at once and pick the same key a1; they sort "
    "by card ID (...01 before ...02), not by HLC or arrival. Two lists sharing key a0 "
    "sort by list ID the same way, though the higher ID was created first.",
    [add_list(1, H(0, 0, 1), L(2), "a0", "To Do"),
     add_card(2, H(5, 0, 1), C(2), L(2), "a1", "from replica 1"),
     add_card(3, H(5, 0, 2), C(1), L(2), "a1", "from replica 2"),
     add_card(4, H(6, 0, 3), C(3), L(2), "a0", "first"),
     add_list(5, H(7, 0, 2), L(1), "a0", "Also a0")],
    view(vlist(L(1), "a0", "Also a0", []),
         vlist(L(2), "a0", "To Do", [vcard(C(3), "a0", "first"),
                                     vcard(C(1), "a1", "from replica 2"),
                                     vcard(C(2), "a1", "from replica 1")])),
    state(lists={L(1): [O(5)], L(2): [O(1)]},
          cards={C(1): [O(3)], C(2): [O(2)], C(3): [O(4)]},
          registers={L(1): lreg("Also a0", "a0", H(7, 0, 2)),
                     L(2): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): creg("from replica 2", L(2), "a1", H(5, 0, 2)),
                     C(2): creg("from replica 1", L(2), "a1", H(5, 0, 1)),
                     C(3): creg("first", L(2), "a0", H(6, 0, 3))}),
)

STATE_008 = state(
    lists={L(1): [O(1)], L(2): [O(2)]}, list_tombs=[O(1)],
    cards={C(1): [O(3)], C(2): [O(4)], C(3): [O(5)]}, card_tombs=[O(4)],
    registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
               L(2): lreg("Doing", "a1", H(1, 0, 1)),
               C(1): creg("lives on", L(1), "a0", H(2, 0, 1)),
               C(2): creg("deleted too", L(1), "a1", H(3, 0, 1)),
               C(3): creg("unaffected", L(2), "a0", H(4, 0, 1))})

vector(
    "008-orphaned-list", "§3.6",
    "List To Do is deleted while it holds a live card and a deleted card. The live card "
    "shows in Orphaned (nil UUID, last); the deleted card shows nowhere; the other "
    "list is unaffected.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_list(2, H(1, 0, 1), L(2), "a1", "Doing"),
     add_card(3, H(2, 0, 1), C(1), L(1), "a0", "lives on"),
     add_card(4, H(3, 0, 1), C(2), L(1), "a1", "deleted too"),
     add_card(5, H(4, 0, 1), C(3), L(2), "a0", "unaffected"),
     del_card(6, H(5, 0, 1), C(2), [O(4)]),
     del_list(7, H(6, 0, 2), L(1), [O(1)])],
    view(vlist(L(2), "a1", "Doing", [vcard(C(3), "a0", "unaffected")]),
         orphaned([vcard(C(1), "a0", "lives on")])),
    STATE_008,
)

vector(
    "009-restore-list-returns-cards", "§3.6",
    "Starting from 008's end state, restoring the list returns its live card with no "
    "write to the card; Orphaned disappears because it is empty. The deleted card stays "
    "hidden.",
    [restore_list(8, H(7, 0, 3), L(1))],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "lives on")]),
         vlist(L(2), "a1", "Doing", [vcard(C(3), "a0", "unaffected")])),
    {**STATE_008, "lists": {"adds": {L(1): [O(1), O(8)], L(2): [O(2)]},
                            "tombstones": [O(1)]}},
    initial_state=STATE_008,
)

vector(
    "010-clock-skew-guard-boundary", "§3.1",
    "Server time is T0+100000. An op exactly 60,000ms ahead is accepted; one 60,001ms "
    "ahead is rejected with CLOCK_SKEW; an op 100s in the past is accepted, because age "
    "is never a reason to reject.",
    [add_list(1, H(160000, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(160001, 0, 2), C(1), L(1), "a0", "too far ahead"),
     add_card(3, H(0, 0, 3), C(2), L(1), "a0", "old op")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(2), "a0", "old op")])),
    state(lists={L(1): [O(1)]}, cards={C(2): [O(3)]},
          registers={L(1): lreg("To Do", "a0", H(160000, 0, 1)),
                     C(2): creg("old op", L(1), "a0", H(0, 0, 3))}),
    server_time_ms=T0 + 100000,
    expected_rejected={O(2): "CLOCK_SKEW"},
)


# ---- output ----------------------------------------------------------------

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sort_tags(s):
    for kind in ("cards", "lists"):
        s[kind]["tombstones"] = sorted(set(s[kind]["tombstones"]))
        s[kind]["adds"] = {k: sorted(set(v)) for k, v in s[kind]["adds"].items()}
    return s


def render(v):
    v = json.loads(json.dumps(v))  # deep copy; shared states stay untouched
    sort_tags(v["initial_state"])
    sort_tags(v["expected_state"])
    v["expected_state_sha256"] = hashlib.sha256(
        canonical(v["expected_state"]).encode("utf-8")).hexdigest()
    return json.dumps(v, indent=2, ensure_ascii=False) + "\n"


def main():
    check = "--check" in sys.argv
    names = [v["name"] for v in VECTORS]
    assert len(names) == len(set(names)), "duplicate vector name"
    stale = []
    for v in VECTORS:
        path = OUT / f"{v['name']}.json"
        text = render(v)
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path.name)
        else:
            OUT.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
    if check and stale:
        sys.exit("stale vectors: " + ", ".join(stale))
    print(f"{len(VECTORS)} vectors {'checked' if check else 'written'}")


if __name__ == "__main__":
    main()
