"""Remove-the-guard checks: each safety guard is removed, the test that protects it must fail,
then the file is restored from git and the test must pass again.

Usage (from server/): python tools/guard_checks.py [name ...]
Needs JAVA_HOME (JDK 21) and Docker. Prints one result line per check; exits 1 on any surprise.
"""
import html
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ACTOR = "session/src/main/java/dev/concord/session/BoardActor.java"
STORE = "session/src/main/java/dev/concord/session/Store.java"
OWNERSHIP = "session/src/main/java/dev/concord/session/Ownership.java"

# name: (what is removed, [(file, old, new)], test filter, must the test fail?)
CHECKS = {
    "fence-check": (
        "the fence condition in the commit UPDATE (SPEC §6.1 step 3.2)",
        [(STORE, "WHERE id = ? AND fence = ? RETURNING version", "WHERE id = ? AND ? >= 0 RETURNING version")],
        "dev.concord.session.FencingTest.staleOwnerCannotCommitAfterANewOwnerClaims", True),
    "old-claim-order": (
        "claim step 3 (fence written before load), with v3's `fence <= :fence` commit condition",
        [(OWNERSHIP, "if (store.raiseFence(board, fence)) return fence;", "if (fence > 0) return fence;"),
         (STORE, "\"UPDATE boards SET version = version + ? WHERE id = ? AND fence = ? RETURNING version\"",
          "\"UPDATE boards SET version = version + ?, fence = GREATEST(fence, \" + fence + \") WHERE id = ? AND fence <= ? RETURNING version\"")],
        "dev.concord.session.FencingTest.staleOwnerCannotCommitAfterANewOwnerClaims", True),
    "apply-after-commit": (
        "apply-after-commit: ops are applied to memory before the transaction (v2's order)",
        [(ACTOR, "        long started = d.clock().getAsLong();\n        Store.Committed c;",
          "        for (Store.Pending p : ops) board.apply(p.op());\n        long started = d.clock().getAsLong();\n        Store.Committed c;")],
        "dev.concord.session.WritePathTest.noAckBeforeCommit", True),
    "dedup-backstop": (
        "commit step 3.1, dropping op_ids already in board_ops (the backstop behind the LRU)",
        [(STORE, "Map<String, Long> dups = storedVersions(c, board, ops.stream().map(p -> p.op().opId()).toList());",
          "Map<String, Long> dups = new HashMap<>();")],
        "dev.concord.session.WritePathTest.dedupReturnsTheOriginalAckAndNeverRebroadcasts", True),
    "dedup-lru": (
        "the LRU lookup (SPEC §4.3 step 1)",
        [(ACTOR, "            Long acked = lru.get(opId);", "            Long acked = null;")],
        "dev.concord.session.WritePathTest.lruAnswersResendsWithoutTheDatabase", True),
}


def gradle(test):
    cmd = [str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")), ":session:test", "--tests", test, "-q"]
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode == 0, failure_message(test)


def failure_message(test):
    """The first failure message JUnit recorded for the test class, shortened."""
    cls = test.rsplit(".", 1)[0]
    xml = ROOT / "session/build/test-results/test" / f"TEST-{cls}.xml"
    if not xml.exists():
        return ""
    m = re.search(r'<failure message="([^"]*)"', xml.read_text(encoding="utf-8"))
    return html.unescape(m.group(1))[:200] if m else ""


def main(names):
    surprises = 0
    for name in names or CHECKS:
        what, edits, test, must_fail = CHECKS[name]
        files = {}
        try:
            for f, old, new in edits:
                p = ROOT / f
                s = files.setdefault(f, p.read_text(encoding="utf-8"))
                cur = p.read_text(encoding="utf-8")
                assert cur.count(old) == 1, f"{name}: guard not found in {f}: {old!r}"
                p.write_text(cur.replace(old, new), encoding="utf-8", newline="\n")
            ok_removed, why = gradle(test)
        finally:
            for f, s in files.items():
                (ROOT / f).write_text(s, encoding="utf-8", newline="\n")
        ok_restored, _ = gradle(test)
        expected = (not ok_removed) if must_fail else ok_removed
        if not expected or not ok_restored:
            surprises += 1
        print(f"{name}: removed {what}\n  test {test.split('.')[-1]}: "
              f"{'PASSED' if ok_removed else 'FAILED'} without the guard"
              f"{' (' + why + ')' if why and not ok_removed else ''}; "
              f"{'PASSED' if ok_restored else 'FAILED'} with it restored"
              f"{'' if expected else '   <-- UNEXPECTED'}", flush=True)
    sys.exit(1 if surprises else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
