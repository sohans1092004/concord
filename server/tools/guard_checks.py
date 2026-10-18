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
API = "gateway/src/main/java/dev/concord/gateway/Api.java"
ACCOUNTS = "gateway/src/main/java/dev/concord/gateway/Accounts.java"
SESSIONMAIN = "session/src/main/java/dev/concord/session/SessionMain.java"
BACKUP = "session/src/main/java/dev/concord/session/Backup.java"
ENCODER = "common/src/main/java/dev/concord/common/RedactingJsonEncoder.java"
PRESENCE = "gateway/src/main/java/dev/concord/gateway/Presence.java"
CLIENTCONN = "gateway/src/main/java/dev/concord/gateway/ClientConn.java"
CLIENT = "../client-core/src/client.ts"
IDB = "../client-core/src/idb.ts"

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
    # Phase 3: sign-in, tickets and the REST API (gateway).
    "ticket-single-use": (
        "deleting the ticket as it is read (a plain SELECT instead), SPEC §9.4",
        [(API, "\"DELETE FROM ws_tickets WHERE token_hash = ? AND expires_at > now() RETURNING user_id\"",
          "\"SELECT user_id FROM ws_tickets WHERE token_hash = ? AND expires_at > now()\"")],
        "dev.concord.gateway.ApiTest.aTicketWorksOnceAndOnlyWithin30Seconds", True),
    "ticket-expiry": (
        "the 30s expiry check when a ticket is used",
        [(API, "WHERE token_hash = ? AND expires_at > now() RETURNING user_id", "WHERE token_hash = ? RETURNING user_id")],
        "dev.concord.gateway.ApiTest.aTicketWorksOnceAndOnlyWithin30Seconds", True),
    "rest-origin": (
        "the Origin checks on state-changing REST calls",
        [(API, "if (origin != null && !config.allowedOrigins().contains(origin)) throw new HttpResponseException(403, \"origin not allowed\");", ""),
         (API, "if (origin == null && ctx.cookie(COOKIE) != null) throw new HttpResponseException(403, \"origin required\");", "")],
        "dev.concord.gateway.ApiTest.stateChangingCallsRequireAnAllowedOrigin", True),
    "password-revokes-sessions": (
        "deleting the user's other sessions when the password changes",
        [(API, "\"DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?\"", "\"DELETE FROM sessions WHERE user_id = ? AND token_hash <> ? AND false\"")],
        "dev.concord.gateway.ApiTest.changingThePasswordEndsTheUsersOtherSessions", True),
    "members-owner-only": (
        "the owner check on adding a member",
        [(API, "if (!role(board, u).equals(\"owner\")) throw new HttpResponseException(403, \"only owners manage members\");\n        Map<?, ?> b = body(ctx);",
          "role(board, u);\n        Map<?, ?> b = body(ctx);")],
        "dev.concord.gateway.ApiTest.boardsMembersAndRoles", True),
    "last-owner": (
        "the rule that a board keeps at least one owner",
        [(API, "if (owners.equals(List.of(target)) && !\"owner\".equals(role))", "if (false)")],
        "dev.concord.gateway.ApiTest.boardsMembersAndRoles", True),
    "history-members-only": (
        "the membership check on the history endpoint",
        [(API, "        role(board, u);\n        long before", "        long before")],
        "dev.concord.gateway.ApiTest.boardsMembersAndRoles", True),
    "bug-report-redaction": (
        "redacting secrets from bug-report diagnostics and description",
        [(API, "redacted = RedactingJsonEncoder.redact(dev.concord.crdt.Canonical.encode(diag));", "redacted = dev.concord.crdt.Canonical.encode(diag);"),
         (API, "String description = RedactingJsonEncoder.redact(str(b, \"description\", 1, 10_000));", "String description = str(b, \"description\", 1, 10_000);")],
        "dev.concord.gateway.ApiTest.bugReportsAreStoredWithSecretsRedacted", True),
    "log-redaction-ticket": (
        "redacting ticket= and session values in log lines (only access_token= left)",
        [(ENCODER, "\"((?:ticket|access_token|concord_session)=|Bearer )", "\"((?:access_token)=)")],
        "dev.concord.gateway.GatewayLogTest.tokensNeverReachTheLogs", True),
    "trusted-proxy-only": (
        "believing X-Forwarded-For only from the trusted proxy's address (amendment 23)",
        [(API, "        if (!trustedProxyAddresses().contains(socket)) return socket;\n", "")],
        "dev.concord.gateway.ApiTest.forwardedForIsBelievedOnlyFromTheTrustedProxy", True),
    "backup-verify-replay": (
        "checking each board's full replay against the backup manifest (Phase 4a step 5)",
        [(BACKUP, "                if (!expected.equals(viaReplay)) problems.add(board + \": full replay \" + viaReplay + \", manifest \" + expected);\n", "")],
        "dev.concord.session.BackupTest.verifyPassesOnTheSameDataAndCatchesDamage", True),
    "idle-unload-not-reconciling": (
        "never unloading an idle board while it reconciles an unknown commit outcome (amendment 26)",
        [(ACTOR, "        if (state != State.SERVING) return; // never while reconciling: the board's state is not known yet\n", "")],
        "dev.concord.session.IdleUnloadTest.neverUnloadedWhileReconcilingAnUnknownCommitOutcome", True),
    "idle-unload-subscribers": (
        "keeping a board loaded while a gateway is subscribed to its channel (amendment 26)",
        [(ACTOR, "        if (d.subscribers().count(id) != 0) return; // someone has it open, or Redis cannot say\n", "")],
        "dev.concord.session.IdleUnloadTest.anIdleBoardIsUnloadedAndARejoinConverges", True),
    "board-deleted-commit": (
        "telling a deleted board from a newer owner when the commit's fence update matches nothing (amendment 27)",
        [(STORE, "                        if (deleted) throw new BoardDeleted(board);\n", "")],
        "dev.concord.session.BoardDeletedTest.anEditCommittingAfterTheDeletionIsRefusedNotAFenceAlert", True),
    "board-deleted-reconcile": (
        "ending reconciling when the board was deleted, instead of retrying forever (amendment 27)",
        [(ACTOR, """            } catch (Store.BoardDeleted e) {
                dropDeleted("found while reconciling"); // retrying would never end
                return;
""", "")],
        "dev.concord.session.BoardDeletedTest.reconcilingOnADeletedBoardEndsInsteadOfRetryingForever", True),
    "board-deleted-announced": (
        "dropping a loaded board when its deletion is announced (amendment 27)",
        [(SESSIONMAIN, "                    if (a != null) a.offer(new BoardActor.Deleted());\n", "")],
        "dev.concord.session.BoardDeletedTest.anAnnouncedDeletionDropsTheBoardAtOnceWithNoSnapshot", True),
    "delete-user-heir": (
        "making a remaining member owner when a deleted user was a shared board's last owner",
        [(ACCOUNTS, "                        exec(c, \"UPDATE board_members SET role = 'owner' WHERE board_id = ? AND user_id = ?\", b, UUID.fromString(heir));\n", "")],
        "dev.concord.gateway.ApiTest.deletingAnAccountRemovesItsDataAndKeepsSharedBoards", True),
    "presence-sweep": (
        "removing presence entries older than 30s in the sweep (SPEC 5.2)",
        [(PRESENCE, "                redis.zremrangebyscore(key(board), old);\n", "")],
        "dev.concord.gateway.PresenceTest.sweepRemovesStaleEntriesAndAnnouncesEachConnectionThatWent", True),
    "presence-joined-board": (
        "the check that a PRESENCE frame names the connection's joined board",
        [(CLIENTCONN, "if (!joined || b == null || !b.equals(f.get(\"board_id\"))) {", "if (!joined || b == null) {")],
        "dev.concord.gateway.PresenceTest.presenceFlowsWithTheGatewaysPostgresCutOffAndStaysOnItsBoard", True),
    # Phase 3, client: vitest (client-core) and Playwright (web) checks.
    "equal-key-rekey": (
        "re-keying equal upper neighbours before an insert (SPEC 3.7 rule B)",
        [(CLIENT, "    for (const r of plan.rekeys) this.submit(rekey(entries[r.index]!.id, r.key));\n", "")],
        "vitest:test/unit/features.test.ts", True),
    "silence-drop": (
        "dropping a joined connection after 15s of silence (amendment 18)",
        [(CLIENT, "if (this.now() - this.lastHeard > SILENCE_MS) this.dropConnection(\"silent\");", "void SILENCE_MS;")],
        "vitest:test/unit/features.test.ts", True),
    "offline-event": (
        "reacting to the browser's offline event (amendment 18)",
        [(CLIENT, "    g.addEventListener?.(\"offline\", this.onOffline);\n", "")],
        "vitest:test/unit/features.test.ts", True),
    "moved-by": (
        "detecting a lost concurrent move (SPEC 3.5): checkMoves() after each change",
        [(CLIENT, "  private changed(): void {\n    this.checkMoves();\n", "  private changed(): void {\n")],
        "pw:e2e/moved.spec.ts", True),
    "append-before-view": (
        "appending each edit durably before it is shown (SPEC 7.2, amendment 21): the edit then rests on the coalesced snapshot",
        [(CLIENT, "        const r = this.storage.append(op, seq);", "        const r = undefined as Promise<void> | void;")],
        "pw:e2e/offline.spec.ts:186", True),
    "presence-per-connection": (
        "keying presence by connection on the client, so one tab's 'left' removes only that tab (amendment 22)",
        [(CLIENT, "const conn = String(m.connection_id ?? m.user_id);", "const conn = String(m.user_id);")],
        "pw:e2e/board.spec.ts:193", True),
    "duplicate-tab-lock": (
        "taking the Web Lock for the remembered replica ID, so a duplicated tab makes its own (amendment 17)",
        [(IDB, "let lock = id ? await tryLock(lockName(boardId, id)) : null;", "let lock = id ? { release: () => {} } : null;")],
        "pw:e2e/offline.spec.ts:86", True),
    "adoption-lock": (
        "taking a saved replica's Web Lock before adopting it (SPEC 7.1)",
        [(IDB, "const lock = await tryLock(lockName(this.boardId, row.tabReplicaId));", "const lock = { release: () => {} };")],
        "pw:e2e/offline.spec.ts:114", True),
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
    kind, _, rest = test.partition(":")
    if kind == "drill":
        return drill(rest)
    if kind == "vitest":
        return node(REPO / "client-core", ["vitest", "run", rest])
    if kind == "pw":
        # The preview server serves the built app, so rebuild it from the edited source first.
        subprocess.run([shutil.which("npx") or "npx", "vite", "build"], cwd=REPO / "web", capture_output=True)
        return node(REPO / "web", ["playwright", "test", rest])
    return gradle(test)


def node(cwd, args):
    npx = shutil.which("npx") or "npx"
    r = subprocess.run([npx, *args], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = re.sub(r"\[[0-9;]*m", "", r.stdout + r.stderr)
    why = next((l.strip() for l in out.splitlines() if "AssertionError" in l or "Error:" in l), "")
    return r.returncode == 0, why[:200]


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


def module(test):
    return test.split(".")[2]  # dev.concord.<module>.Class.method


def gradle(test):
    cmd = [str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")), f":{module(test)}:test", "--tests", test, "-q"]
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode == 0, failure_message(test)


def failure_message(test):
    """The first failure message JUnit recorded for the test class, shortened."""
    cls = test.rsplit(".", 1)[0]
    xml = ROOT / module(test) / "build/test-results/test" / f"TEST-{cls}.xml"
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
        print(f"{name}: removed {what}\n  test {test if ':' in test else test.split('.')[-1]}: "
              f"{'PASSED' if ok_removed else 'FAILED'} without the guard"
              f"{' (' + why + ')' if why and not ok_removed else ''}; "
              f"{'PASSED' if ok_restored else 'FAILED'} with it restored"
              f"{'' if expected else '   <-- UNEXPECTED'}", flush=True)
    sys.exit(1 if surprises else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
