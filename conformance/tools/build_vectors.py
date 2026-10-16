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


# ---- vectors 011-050 (added after both cores existed; still hand-derived) ---

def base_board():
    """L1 'To Do' at a0 with card C1 'Card' at a0: ops 1-2, and its state pieces."""
    return ([add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
             add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Card")],
            {L(1): lreg("To Do", "a0", H(0, 0, 1))},
            creg("Card", L(1), "a0", H(1, 0, 1)))


ops, lregs, c1 = base_board()
vector(
    "011-delete-that-observed-the-restore-wins", "§3.2",
    "Replica 3 saw the restore before deleting, so its delete carries the restore's tag "
    "and the card is gone. Contrast with 004.",
    ops + [del_card(3, H(2, 0, 1), C(1), [O(2)]),
           restore_card(4, H(3, 0, 2), C(1)),
           del_card(5, H(4, 0, 3), C(1), [O(4)])],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2), O(4)]}, card_tombs=[O(2), O(4)],
          registers={**lregs, C(1): c1}),
)

vector(
    "012-delete-arrives-before-its-add", "§3.2",
    "The delete is delivered first. Its tombstone is kept even though the tag is not yet "
    "known, so the add that arrives later is already cancelled.",
    [del_card(3, H(2, 0, 2), C(1), [O(2)])] + ops,
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, card_tombs=[O(2)],
          registers={**lregs, C(1): c1}),
)

vector(
    "013-edit-arrives-before-its-add", "§3.4",
    "A rename with a higher HLC is delivered before the card's ADD. Once the add arrives "
    "the card shows the rename, not the original title.",
    [set_field(3, H(5, 0, 2), C(1), "title", "Renamed")] + ops,
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Renamed")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("Renamed", H(5, 0, 2))}}),
)

STATE_014 = state(cards={C(1): [O(2)]},
                  registers={C(1): creg("Waiting", L(1), "a0", H(1, 0, 1))})

vector(
    "014-card-of-a-never-seen-list-shows-in-orphaned", "§3.6",
    "The card's ADD arrives before its list's ADD. The list has never been seen, so the "
    "card shows in Orphaned.",
    [add_card(2, H(1, 0, 1), C(1), L(1), "a0", "Waiting")],
    view(orphaned([vcard(C(1), "a0", "Waiting")])),
    STATE_014,
)

vector(
    "015-never-seen-list-arrives-and-takes-its-cards-back", "§3.6",
    "Starting from 014's state, the list's ADD arrives. Its card returns to it with no "
    "write to the card.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Waiting")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     C(1): creg("Waiting", L(1), "a0", H(1, 0, 1))}),
    initial_state=STATE_014,
)

vector(
    "016-restored-card-in-a-deleted-list-shows-in-orphaned", "§3.6",
    "A deleted card in a deleted list is hidden. Restoring the card, but not its list, "
    "brings it back in Orphaned.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_list(2, H(1, 0, 1), L(2), "a1", "Doing"),
     add_card(3, H(2, 0, 1), C(1), L(1), "a0", "c"),
     del_card(4, H(3, 0, 1), C(1), [O(3)]),
     del_list(5, H(4, 0, 1), L(1), [O(1)]),
     restore_card(6, H(5, 0, 2), C(1))],
    view(vlist(L(2), "a1", "Doing", []), orphaned([vcard(C(1), "a0", "c")])),
    state(lists={L(1): [O(1)], L(2): [O(2)]}, list_tombs=[O(1)],
          cards={C(1): [O(3), O(6)]}, card_tombs=[O(3)],
          registers={L(1): lreg("To Do", "a0", H(0, 0, 1)),
                     L(2): lreg("Doing", "a1", H(1, 0, 1)),
                     C(1): creg("c", L(1), "a0", H(2, 0, 1))}),
)

vector(
    "017-move-a-card-out-of-orphaned", "§3.6",
    "From 008's state, moving the orphaned card into a live list is an ordinary "
    "SET_PLACEMENT; Orphaned disappears because it is empty.",
    [move(8, H(7, 0, 1), C(1), L(2), "a1")],
    view(vlist(L(2), "a1", "Doing", [vcard(C(3), "a0", "unaffected"), vcard(C(1), "a1", "lives on")])),
    {**STATE_008, "registers": {**STATE_008["registers"],
                                C(1): {"title": reg("lives on", H(2, 0, 1)),
                                       "placement": reg({"list_id": L(2), "position_key": "a1"}, H(7, 0, 1))}}},
    initial_state=STATE_008,
)

vector(
    "018-orphaned-sorts-by-key-then-id", "§3.6 §3.7",
    "Cards from two deleted lists share Orphaned and sort by (position_key, id) like any "
    "list: Zz before a0, then the two a0 cards by ID.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "A"),
     add_list(2, H(1, 0, 1), L(2), "a1", "B"),
     add_card(3, H(2, 0, 1), C(2), L(1), "a0", "c2"),
     add_card(4, H(3, 0, 1), C(1), L(2), "a0", "c1"),
     add_card(5, H(4, 0, 1), C(3), L(2), "Zz", "c3"),
     del_list(6, H(5, 0, 1), L(1), [O(1)]),
     del_list(7, H(6, 0, 1), L(2), [O(2)])],
    view(orphaned([vcard(C(3), "Zz", "c3"), vcard(C(1), "a0", "c1"), vcard(C(2), "a0", "c2")])),
    state(lists={L(1): [O(1)], L(2): [O(2)]}, list_tombs=[O(1), O(2)],
          cards={C(1): [O(4)], C(2): [O(3)], C(3): [O(5)]},
          registers={L(1): lreg("A", "a0", H(0, 0, 1)), L(2): lreg("B", "a1", H(1, 0, 1)),
                     C(1): creg("c1", L(2), "a0", H(3, 0, 1)),
                     C(2): creg("c2", L(1), "a0", H(2, 0, 1)),
                     C(3): creg("c3", L(2), "Zz", H(4, 0, 1))}),
)

vector(
    "019-orphaned-hidden-when-empty", "§3.6",
    "A deleted list whose only card is also deleted leaves nothing to show: no Orphaned "
    "entry, an empty view.",
    ops + [del_card(3, H(2, 0, 1), C(1), [O(2)]), del_list(4, H(3, 0, 1), L(1), [O(1)])],
    view(),
    state(lists={L(1): [O(1)]}, list_tombs=[O(1)], cards={C(1): [O(2)]}, card_tombs=[O(2)],
          registers={**lregs, C(1): c1}),
)

vector(
    "020-list-restore-concurrent-with-delete", "§3.2",
    "Lists use the same OR-Set rule as cards: a restore that a later delete never "
    "observed keeps the list alive.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     del_list(2, H(1, 0, 1), L(1), [O(1)]),
     restore_list(3, H(2, 0, 2), L(1)),
     del_list(4, H(3, 0, 3), L(1), [O(1)])],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1), O(3)]}, list_tombs=[O(1)], registers=lregs),
)

vector(
    "021-concurrent-list-renames-higher-hlc-wins", "§3.4",
    "Two concurrent renames of one list: physical_ms 5 beats physical_ms 4 even though "
    "the loser has counter 9.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     set_field(2, H(5, 0, 2), L(1), "title", "Backlog"),
     set_field(3, H(4, 9, 3), L(1), "title", "Later")],
    view(vlist(L(1), "a0", "Backlog", [])),
    state(lists={L(1): [O(1)]},
          registers={L(1): {"title": reg("Backlog", H(5, 0, 2)),
                            "placement": reg({"position_key": "a0"}, H(0, 0, 1))}}),
)

vector(
    "022-concurrent-edits-to-different-fields-all-survive", "§3.4",
    "Title, assignee and labels edited concurrently by three replicas: each field is its "
    "own register, so all three survive.",
    ops + [set_field(3, H(5, 0, 1), C(1), "title", "Renamed"),
           set_field(4, H(5, 0, 2), C(1), "assignee", P(1)),
           set_field(5, H(4, 0, 3), C(1), "labels", ["bug", "p1"])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Renamed", assignee=P(1), labels=["bug", "p1"])])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("Renamed", H(5, 0, 1)),
                                     "assignee": reg(P(1), H(5, 0, 2)),
                                     "labels": reg(["bug", "p1"], H(4, 0, 3))}}),
)

vector(
    "023-counter-beats-replica-id", "§3.1",
    "Same physical_ms: counter 1 from replica ...01 beats counter 0 from replica ...04.",
    ops + [set_field(3, H(9, 1, 1), C(1), "title", "counter 1"),
           set_field(4, H(9, 0, 4), C(1), "title", "counter 0")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "counter 1")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("counter 1", H(9, 1, 1))}}),
)

vector(
    "024-physical-ms-beats-counter", "§3.1",
    "physical_ms 10 with counter 0 beats physical_ms 9 with counter 500.",
    ops + [set_field(3, H(10, 0, 1), C(1), "title", "later ms"),
           set_field(4, H(9, 500, 2), C(1), "title", "high counter")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "later ms")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("later ms", H(10, 0, 1))}}),
)

vector(
    "025-lww-collision-tie-breaks-on-unsigned-bytes", "§3.4",
    "A buggy client reuses one HLC for two different titles. The defensive tie-break "
    "keeps the greater canonical encoding as unsigned UTF-8 bytes: \"😀\" (22 F0 ...) beats "
    "\"｡\" (22 EF ...), though UTF-16 order would pick ｡. One collision is reported.",
    ops + [set_field(3, H(5, 0, 2), C(1), "title", "｡"),
           set_field(4, H(5, 0, 2), C(1), "title", "😀")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "😀")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("😀", H(5, 0, 2))}}),
    expected_collisions=1,
)

vector(
    "026-lww-collision-on-labels", "§3.4",
    "Equal HLCs on labels: [\"b\"] encodes greater than [\"a\"] and wins. A third write "
    "of the winning value is not a collision, so exactly one is reported.",
    ops + [set_field(3, H(5, 0, 2), C(1), "labels", ["a"]),
           set_field(4, H(5, 0, 2), C(1), "labels", ["b"]),
           set_field(5, H(5, 0, 2), C(1), "labels", ["b"])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card", labels=["b"])])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "labels": reg(["b"], H(5, 0, 2))}}),
    expected_collisions=1,
)

vector(
    "027-duplicate-ops-are-no-ops", "§4.3",
    "The same ADD_CARD and DELETE_CARD are each applied twice. Re-applying an op is a "
    "no-op in the algebra, so the state equals a single application.",
    ops + [ops[1], del_card(3, H(2, 0, 1), C(1), [O(2)]), del_card(3, H(2, 0, 1), C(1), [O(2)])],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, card_tombs=[O(2)],
          registers={**lregs, C(1): c1}),
)

vector(
    "028-assignee-cleared-and-empty-description-are-shown", "§3.4 §9.2.4",
    "Unassigning writes null, which the view shows as \"assignee\": null; an empty "
    "description is shown as \"\". A field is present whenever its register exists.",
    ops + [set_field(3, H(2, 0, 1), C(1), "assignee", P(1)),
           set_field(4, H(3, 0, 2), C(1), "assignee", None),
           set_field(5, H(4, 0, 1), C(1), "description", "")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card", description="", assignee=None)])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "assignee": reg(None, H(3, 0, 2)),
                                     "description": reg("", H(4, 0, 1))}}),
)

vector(
    "029-concurrent-reorders-higher-hlc-wins", "§3.5",
    "Replica 1 moves C1 below C2 (a2); replica 2 concurrently moves it to the top (Zz) "
    "with a higher HLC. C1 ends on top.",
    ops + [add_card(3, H(2, 0, 1), C(2), L(1), "a1", "Second"),
           move(4, H(10, 0, 1), C(1), L(1), "a2"),
           move(5, H(11, 0, 2), C(1), L(1), "Zz")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "Zz", "Card"), vcard(C(2), "a1", "Second")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)], C(2): [O(3)]},
          registers={**lregs,
                     C(1): {**c1, "placement": reg({"list_id": L(1), "position_key": "Zz"}, H(11, 0, 2))},
                     C(2): creg("Second", L(1), "a1", H(2, 0, 1))}),
)

vector(
    "030-reorder-concurrent-with-move-to-another-list", "§3.5",
    "A reorder within To Do (higher HLC) and a move to Doing race. The reorder's "
    "placement wins whole: the card stays in To Do at a5.",
    ops + [add_list(3, H(2, 0, 1), L(2), "a1", "Doing"),
           move(4, H(12, 0, 1), C(1), L(1), "a5"),
           move(5, H(11, 0, 2), C(1), L(2), "a0")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a5", "Card")]), vlist(L(2), "a1", "Doing", [])),
    state(lists={L(1): [O(1)], L(2): [O(3)]}, cards={C(1): [O(2)]},
          registers={**lregs, L(2): lreg("Doing", "a1", H(2, 0, 1)),
                     C(1): {**c1, "placement": reg({"list_id": L(1), "position_key": "a5"}, H(12, 0, 1))}}),
)

vector(
    "031-list-reorder", "§3.5",
    "SET_PLACEMENT with the list form moves To Do after Doing.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     add_list(2, H(1, 0, 1), L(2), "a1", "Doing"),
     move_list(3, H(5, 0, 2), L(1), "a2")],
    view(vlist(L(2), "a1", "Doing", []), vlist(L(1), "a2", "To Do", [])),
    state(lists={L(1): [O(1)], L(2): [O(2)]},
          registers={L(1): {"title": reg("To Do", H(0, 0, 1)),
                            "placement": reg({"position_key": "a2"}, H(5, 0, 2))},
                     L(2): lreg("Doing", "a1", H(1, 0, 1))}),
)

vector(
    "032-card-given-a-list-form-placement-is-orphaned", "§3.6",
    "SET_PLACEMENT accepts either placement form by shape. A card given the list form "
    "(no list_id) has no home list, so it shows in Orphaned.",
    ops + [move_list(3, H(5, 0, 2), C(1), "a3")],
    view(vlist(L(1), "a0", "To Do", []), orphaned([vcard(C(1), "a3", "Card")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "placement": reg({"position_key": "a3"}, H(5, 0, 2))}}),
)

vector(
    "033-edit-of-a-never-added-card-is-not-shown", "§3.6",
    "A register write for a card with no add-tag is stored but not rendered.",
    [ops[0], set_field(2, H(1, 0, 2), C(1), "title", "ghost")],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, registers={**lregs, C(1): {"title": reg("ghost", H(1, 0, 2))}}),
)

vector(
    "034-live-card-without-placement-is-not-shown", "§3.6",
    "A restore delivered before the card's ADD makes the card live, but it has no "
    "placement register yet, so it is not rendered.",
    [ops[0], restore_card(2, H(1, 0, 2), C(1))],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, registers=lregs),
)

vector(
    "035-placement-without-add-tag-is-not-shown", "§3.6",
    "A move delivered before the card's ADD writes a placement for a card that is not "
    "live; nothing is rendered.",
    [ops[0], move(2, H(1, 0, 2), C(1), L(1), "a0")],
    view(vlist(L(1), "a0", "To Do", [])),
    state(lists={L(1): [O(1)]},
          registers={**lregs, C(1): {"placement": reg({"list_id": L(1), "position_key": "a0"}, H(1, 0, 2))}}),
)

vector(
    "036-list-without-title-renders-without-title", "§3.6 §9.2.4",
    "A list made live by a restore and placed by SET_PLACEMENT before its ADD arrives is "
    "rendered with no title member.",
    [restore_list(1, H(0, 0, 1), L(1)), move_list(2, H(1, 0, 1), L(1), "a0")],
    view({"id": L(1), "position_key": "a0", "cards": []}),
    state(lists={L(1): [O(1)]}, registers={L(1): {"placement": reg({"position_key": "a0"}, H(1, 0, 1))}}),
)

vector(
    "037-ids-must-be-lowercase-canonical-uuids", "§4.2",
    "An uppercase card_id and a braced list_id are INVALID_OP; nothing they carry is "
    "applied.",
    ops + [{**add_card(3, H(2, 0, 1), C(2), L(1), "a1", "upper"), "card_id": C(2).upper()},
           add_card(4, H(3, 0, 1), C(3), "{" + L(1) + "}", "a1", "braced")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, registers={**lregs, C(1): c1}),
    expected_rejected={O(3): "INVALID_OP", O(4): "INVALID_OP"},
)

vector(
    "038-nil-uuid-is-reserved-for-orphaned", "§3.6 §4.2",
    "No op may name the nil UUID: adding a card to it, moving a card into it, creating a "
    "list with it and deleting it are all INVALID_OP.",
    [ops[0],
     add_card(2, H(1, 0, 1), C(1), NIL, "a0", "into orphaned"),
     add_card(3, H(2, 0, 1), C(1), L(1), "a0", "ok"),
     move(4, H(3, 0, 1), C(1), NIL, "a1"),
     add_list(5, H(4, 0, 1), NIL, "a1", "orphaned"),
     del_list(6, H(5, 0, 1), NIL, [])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "ok")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(3)]},
          registers={**lregs, C(1): creg("ok", L(1), "a0", H(2, 0, 1))}),
    expected_rejected={O(2): "INVALID_OP", O(4): "INVALID_OP", O(5): "INVALID_OP", O(6): "INVALID_OP"},
)

vector(
    "039-labels-sorted-unique-nfc-at-most-50", "§4.2",
    "Unsorted, NFD, 51-code-point and duplicate labels are INVALID_OP. U+FF61 then U+1F600 "
    "is accepted: that is ascending UTF-8 byte order (EF... < F0...), though UTF-16 order "
    "says the opposite.",
    ops + [set_field(3, H(2, 0, 1), C(1), "labels", ["b", "a"]),
           set_field(4, H(3, 0, 1), C(1), "labels", ["é"]),
           set_field(5, H(4, 0, 1), C(1), "labels", ["x" * 51]),
           set_field(6, H(5, 0, 1), C(1), "labels", ["a", "a"]),
           set_field(7, H(6, 0, 1), C(1), "labels", ["｡", "😀"])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card", labels=["｡", "😀"])])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "labels": reg(["｡", "😀"], H(6, 0, 1))}}),
    expected_rejected={O(3): "INVALID_OP", O(4): "INVALID_OP", O(5): "INVALID_OP", O(6): "INVALID_OP"},
)

vector(
    "040-title-length-counts-code-points", "§4.2",
    "200 emoji (400 UTF-16 units) is a valid title; 201 ASCII characters is not.",
    ops + [set_field(3, H(2, 0, 1), C(1), "title", "😀" * 200),
           set_field(4, H(3, 0, 1), C(1), "title", "x" * 201)],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "😀" * 200)])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "title": reg("😀" * 200, H(2, 0, 1))}}),
    expected_rejected={O(4): "INVALID_OP"},
)

vector(
    "041-position-keys-pass-greenspans-check", "§3.7 §4.2",
    "a00 and a0V0 (fraction ends in 0), b0 (integer part too short), A+26 zeros (reserved "
    "minimum) and a 65-character key are INVALID_OP; Zz and a 64-character key are valid.",
    [ops[0],
     add_card(2, H(1, 0, 1), C(1), L(1), "a00", "x"),
     add_card(3, H(2, 0, 1), C(2), L(1), "b0", "x"),
     add_card(4, H(3, 0, 1), C(3), L(1), "a0V0", "x"),
     add_card(5, H(4, 0, 1), C(4), L(1), "A" + "0" * 26, "x"),
     add_card(6, H(5, 0, 1), C(5), L(1), "Zz", "top"),
     add_card(7, H(6, 0, 1), C(6), L(1), "a" + "V" * 63, "64 chars"),
     add_card(8, H(7, 0, 1), C(7), L(1), "a" + "V" * 64, "65 chars")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(5), "Zz", "top"), vcard(C(6), "a" + "V" * 63, "64 chars")])),
    state(lists={L(1): [O(1)]}, cards={C(5): [O(6)], C(6): [O(7)]},
          registers={**lregs, C(5): creg("top", L(1), "Zz", H(5, 0, 1)),
                     C(6): creg("64 chars", L(1), "a" + "V" * 63, H(6, 0, 1))}),
    expected_rejected={O(2): "INVALID_OP", O(3): "INVALID_OP", O(4): "INVALID_OP",
                       O(5): "INVALID_OP", O(8): "INVALID_OP"},
)

vector(
    "042-exact-members-and-matching-replica", "§4.1 §4.2",
    "An unknown member, a replica_id that disagrees with hlc.replica_id, SET_FIELD of "
    "placement, and an ADD_CARD with the list form of placement are all INVALID_OP.",
    ops + [{**add_card(3, H(2, 0, 1), C(2), L(1), "a1", "x"), "extra": 1},
           {**add_card(4, H(3, 0, 1), C(3), L(1), "a1", "x"), "replica_id": R(2)},
           set_field(5, H(4, 0, 1), C(1), "placement", {"list_id": L(1), "position_key": "a1"}),
           {**add_card(6, H(5, 0, 1), C(4), L(1), "a1", "x"), "placement": {"position_key": "a1"}},
           add_card(7, H(6, 0, 1), C(5), L(1), "a1", "valid")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card"), vcard(C(5), "a1", "valid")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)], C(5): [O(7)]},
          registers={**lregs, C(1): c1, C(5): creg("valid", L(1), "a1", H(6, 0, 1))}),
    expected_rejected={O(3): "INVALID_OP", O(4): "INVALID_OP", O(5): "INVALID_OP", O(6): "INVALID_OP"},
)

vector(
    "043-clock-skew-rejected-list-leaves-its-card-orphaned", "§3.1 §3.6",
    "Server time T0. The ADD_LIST is 60,001ms ahead and rejected; the ADD_CARD is exactly "
    "60,000ms ahead (counter 7) and accepted, so the card names a list this replica never "
    "saw and shows in Orphaned.",
    [add_list(1, H(60001, 0, 1), L(1), "a0", "To Do"),
     add_card(2, H(60000, 7, 2), C(1), L(1), "a0", "survivor")],
    view(orphaned([vcard(C(1), "a0", "survivor")])),
    state(cards={C(1): [O(2)]}, registers={C(1): creg("survivor", L(1), "a0", H(60000, 7, 2))}),
    server_time_ms=T0,
    expected_rejected={O(1): "CLOCK_SKEW"},
)

vector(
    "044-delete-with-no-observed-tags-is-a-no-op", "§3.2",
    "A DELETE_CARD that observed no tags tombstones nothing; the card stays.",
    ops + [del_card(3, H(2, 0, 2), C(1), [])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]}, registers={**lregs, C(1): c1}),
)

vector(
    "045-three-equal-keys-are-rekeyed-before-an-insert", "§3.7",
    "Three replicas append at once and all pick a1. To insert between the first and second "
    "a1 card, planInsert re-keys the two upper ones to a2, a3 and inserts at a1V.",
    ops + [add_card(3, H(2, 0, 1), C(2), L(1), "a1", "c2"),
           add_card(4, H(2, 0, 2), C(3), L(1), "a1", "c3"),
           add_card(5, H(2, 0, 3), C(4), L(1), "a1", "c4"),
           move(6, H(10, 0, 1), C(3), L(1), "a2"),
           move(7, H(10, 1, 1), C(4), L(1), "a3"),
           add_card(8, H(10, 2, 1), C(5), L(1), "a1V", "inserted")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card"), vcard(C(2), "a1", "c2"),
                                     vcard(C(5), "a1V", "inserted"), vcard(C(3), "a2", "c3"),
                                     vcard(C(4), "a3", "c4")])),
    state(lists={L(1): [O(1)]},
          cards={C(1): [O(2)], C(2): [O(3)], C(3): [O(4)], C(4): [O(5)], C(5): [O(8)]},
          registers={**lregs, C(1): c1,
                     C(2): creg("c2", L(1), "a1", H(2, 0, 1)),
                     C(3): {"title": reg("c3", H(2, 0, 2)),
                            "placement": reg({"list_id": L(1), "position_key": "a2"}, H(10, 0, 1))},
                     C(4): {"title": reg("c4", H(2, 0, 3)),
                            "placement": reg({"list_id": L(1), "position_key": "a3"}, H(10, 1, 1))},
                     C(5): creg("inserted", L(1), "a1V", H(10, 2, 1))}),
)

vector(
    "046-insert-concurrent-with-rebalance-moves-the-insert", "§3.7",
    "Known anomaly: replica 3 rebalances Zz, ZzV to a0, a1 while replica 2 concurrently "
    "inserts ZzG between the old keys. The insert now sorts above both: deterministic on "
    "every replica, but not where its author put it.",
    [ops[0],
     add_card(2, H(1, 0, 1), C(1), L(1), "Zz", "first"),
     add_card(3, H(2, 0, 1), C(2), L(1), "ZzV", "second"),
     move(4, H(10, 0, 3), C(1), L(1), "a0"),
     move(5, H(10, 1, 3), C(2), L(1), "a1"),
     add_card(6, H(10, 0, 2), C(3), L(1), "ZzG", "between")],
    view(vlist(L(1), "a0", "To Do", [vcard(C(3), "ZzG", "between"), vcard(C(1), "a0", "first"),
                                     vcard(C(2), "a1", "second")])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)], C(2): [O(3)], C(3): [O(6)]},
          registers={**lregs,
                     C(1): {"title": reg("first", H(1, 0, 1)),
                            "placement": reg({"list_id": L(1), "position_key": "a0"}, H(10, 0, 3))},
                     C(2): {"title": reg("second", H(2, 0, 1)),
                            "placement": reg({"list_id": L(1), "position_key": "a1"}, H(10, 1, 3))},
                     C(3): creg("between", L(1), "ZzG", H(10, 0, 2))}),
)

vector(
    "047-move-into-a-deleted-list-shows-in-orphaned", "§3.6",
    "Replica 1 moves the card into Doing without having seen replica 2 delete Doing. The "
    "card is live and its list is not, so it shows in Orphaned.",
    ops + [add_list(3, H(2, 0, 1), L(2), "a1", "Doing"),
           del_list(4, H(3, 0, 2), L(2), [O(3)]),
           move(5, H(4, 0, 1), C(1), L(2), "a0")],
    view(vlist(L(1), "a0", "To Do", []), orphaned([vcard(C(1), "a0", "Card")])),
    state(lists={L(1): [O(1)], L(2): [O(3)]}, list_tombs=[O(3)], cards={C(1): [O(2)]},
          registers={**lregs, L(2): lreg("Doing", "a1", H(2, 0, 1)),
                     C(1): {**c1, "placement": reg({"list_id": L(2), "position_key": "a0"}, H(4, 0, 1))}}),
)

vector(
    "048-restore-list-concurrent-with-moving-its-card-out", "§3.6",
    "To Do is deleted. Replica 2 moves its card to Doing while replica 3 restores To Do. "
    "Both apply: To Do comes back empty and the card stays in Doing.",
    ops + [add_list(3, H(2, 0, 1), L(2), "a1", "Doing"),
           del_list(4, H(3, 0, 1), L(1), [O(1)]),
           move(5, H(5, 0, 2), C(1), L(2), "a0"),
           restore_list(6, H(6, 0, 3), L(1))],
    view(vlist(L(1), "a0", "To Do", []), vlist(L(2), "a1", "Doing", [vcard(C(1), "a0", "Card")])),
    state(lists={L(1): [O(1), O(6)], L(2): [O(3)]}, list_tombs=[O(1)], cards={C(1): [O(2)]},
          registers={**lregs, L(2): lreg("Doing", "a1", H(2, 0, 1)),
                     C(1): {**c1, "placement": reg({"list_id": L(2), "position_key": "a0"}, H(5, 0, 2))}}),
)

vector(
    "049-list-renamed-while-deleted-then-restored", "§3.3",
    "Delete wins over edit for lists too: the rename made while To Do was deleted is "
    "hidden, then shown once the list is restored.",
    [add_list(1, H(0, 0, 1), L(1), "a0", "To Do"),
     del_list(2, H(1, 0, 1), L(1), [O(1)]),
     set_field(3, H(2, 0, 2), L(1), "title", "Renamed while deleted"),
     restore_list(4, H(3, 0, 1), L(1))],
    view(vlist(L(1), "a0", "Renamed while deleted", [])),
    state(lists={L(1): [O(1), O(4)]}, list_tombs=[O(1)],
          registers={L(1): {"title": reg("Renamed while deleted", H(2, 0, 2)),
                            "placement": reg({"position_key": "a0"}, H(0, 0, 1))}}),
)

TRICKY = "tab\t nul\u0000 quote\" back\\ é 😀   del\u007f \u001f"

vector(
    "050-canonical-escaping-in-the-state-hash", "§9.2.2",
    "Strings with a tab, NUL, quote, backslash, é, an emoji, U+2028, DEL and U+001F. The "
    "canonical encoding escapes only the quote, backslash and characters below U+0020 "
    "(\\t, \\u0000, \\u001f); everything else is raw UTF-8, so the hash pins the exact bytes.",
    ops + [set_field(3, H(2, 0, 1), C(1), "description", TRICKY),
           set_field(4, H(3, 0, 1), C(1), "labels", ["é", "😀"])],
    view(vlist(L(1), "a0", "To Do", [vcard(C(1), "a0", "Card", description=TRICKY, labels=["é", "😀"])])),
    state(lists={L(1): [O(1)]}, cards={C(1): [O(2)]},
          registers={**lregs, C(1): {**c1, "description": reg(TRICKY, H(2, 0, 1)),
                                     "labels": reg(["é", "😀"], H(3, 0, 1))}}),
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
