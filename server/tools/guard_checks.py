"""Remove-the-guard checks: each safety guard is removed, the test that protects it must fail,
then the file is restored from git and the test must pass again.

Usage (from server/): python tools/guard_checks.py [name ...]
Needs JAVA_HOME (JDK 21), Docker and Node 22; drill checks also need the compose stack up. Prints one result line per check; exits 1 on any surprise.
"""
import html
import os
import pathlib
import re
import shutil
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
    "retake-fence-check": (
        "the boards.fence check before re-taking a missing lease (amendment 13)",
        [(OWNERSHIP, "if (current == null || current != fence) return 0;", "if (current == null) return 0;")],
        "dev.concord.session.FencingTest.staleOwnerDoesNotRetakeAWipedLease", True),
    # Drill checks: the Session image is rebuilt with the guard removed and the drill run
    # against the compose stack (deploy/docker-compose.yml must be up), then restored.
    "fence-counter-rebuild": (
        "the boards.fence floor in the fence Lua script (SPEC §6.2 step 1): after a wipe, INCR restarts at 1",
        [(OWNERSHIP, "            if v <= floor then", "            if false then")],
        "drill:wipe-redis", True),
    "fence-check-e2e": (
        "the fence condition in the commit UPDATE, end to end with the stale owner cut off from Redis",
        [(STORE, "WHERE id = ? AND fence = ? RETURNING version", "WHERE id = ? AND ? >= 0 RETURNING version")],
        "drill:pause-owner-no-redis", True),
}

REPO = ROOT.parent
COMPOSE = ["docker", "compose", "-f", str(REPO / "deploy/docker-compose.yml")]


def run_check(test):
    return drill(test.split(":", 1)[1]) if test.startswith("drill:") else gradle(test)


def drill(name):
    """Rebuilds and restarts the Session instances from the working tree, then runs the drill."""
    subprocess.run(COMPOSE + ["up", "-d", "--build", "session1", "session2", "session3"], check=True, capture_output=True)
    for port in (7001, 7002, 7003):
        for _ in range(120):
            if subprocess.run(["curl", "-fs", f"localhost:{port}/health"], capture_output=True).returncode == 0:
                break
            subprocess.run(["python", "-c", "import time; time.sleep(1)"])
    npx = shutil.which("npx") or "npx"
    r = subprocess.run([npx, "vitest", "run", "test/drills"], cwd=REPO / "client-core", env={**os.environ, "DRILL": name},
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = re.sub(r"\x1b\[[0-9;]*m", "", r.stdout + r.stderr)
    why = next((l.strip() for l in out.splitlines() if "AssertionError" in l), "")
    return r.returncode == 0, why[:200]


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
            ok_removed, why = run_check(test)
        finally:
            for f, s in files.items():
                (ROOT / f).write_text(s, encoding="utf-8", newline="\n")
        ok_restored, _ = run_check(test)
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
