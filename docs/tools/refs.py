"""Builds the cited documents and checks every reference in them.

  python docs/tools/refs.py build   # docs/src/*.md -> docs/*.md (references resolved, diagrams split out)
  python docs/tools/refs.py check   # every file:line, commit and test name in docs/*.md resolves

In a source file, [[alias#anchor]] cites the first line of the file that contains `anchor`
(build fails if it is missing or not unique), and [[alias#anchor..end]] a range ending at the
first line after it that contains `end`. Each renders as a link `File.ext:N` to that line, and
docs/refs.json records the anchor, so `check` can tell when the code has moved under a citation.
A ```mermaid block becomes docs/diagrams/<doc>-<n>.mmd, rendered to SVG by render.mjs.
"""
import json
import pathlib
import re
import subprocess
import sys
import textwrap

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
SRC = DOCS / "src"
DIAGRAMS = DOCS / "diagrams"
REFS = DOCS / "refs.json"
OUTPUTS = ["how-concord-was-built.md", "interview-guide.md"]

J = "src/main/java/dev/concord"
ALIASES = {
    "actor": f"server/session/{J}/session/BoardActor.java",
    "store": f"server/session/{J}/session/Store.java",
    "ownership": f"server/session/{J}/session/Ownership.java",
    "boards": f"server/session/{J}/session/Boards.java",
    "membership": f"server/session/{J}/session/Membership.java",
    "publisher": f"server/session/{J}/session/Publisher.java",
    "internal": f"server/session/{J}/session/InternalServer.java",
    "sessionmain": f"server/session/{J}/session/SessionMain.java",
    "registry": f"server/session/{J}/session/Registry.java",
    "backup": f"server/session/{J}/session/Backup.java",
    "clientconn": f"server/gateway/{J}/gateway/ClientConn.java",
    "router": f"server/gateway/{J}/gateway/Router.java",
    "fanout": f"server/gateway/{J}/gateway/Fanout.java",
    "gatewaymain": f"server/gateway/{J}/gateway/GatewayMain.java",
    "link": f"server/gateway/{J}/gateway/Link.java",
    "presence": f"server/gateway/{J}/gateway/Presence.java",
    "api": f"server/gateway/{J}/gateway/Api.java",
    "accounts": f"server/gateway/{J}/gateway/Accounts.java",
    "retention": f"server/gateway/{J}/gateway/Retention.java",
    "bucket": f"server/gateway/{J}/gateway/TokenBucket.java",
    "rediskeys": f"server/common/{J}/common/RedisKeys.java",
    "rendezvous": f"server/common/{J}/common/Rendezvous.java",
    "secrets": f"server/common/{J}/common/Secrets.java",
    "passwords": f"server/common/{J}/common/Passwords.java",
    "outbox": f"server/common/{J}/common/Outbox.java",
    "config": f"server/common/{J}/common/Config.java",
    "migrate": f"server/common/{J}/common/Migrate.java",
    "tracing": f"server/common/{J}/common/Tracing.java",
    "prom": f"server/common/{J}/common/Prom.java",
    "redact": f"server/common/{J}/common/RedactingJsonEncoder.java",
    "schema": "server/common/src/main/resources/schema.sql",
    "hlc": "crdt-ts/src/hlc.ts",
    "orset": "crdt-ts/src/orset.ts",
    "lww": "crdt-ts/src/lww.ts",
    "keys": "crdt-ts/src/keys.ts",
    "op": "crdt-ts/src/op.ts",
    "board": "crdt-ts/src/board.ts",
    "canonical": "crdt-ts/src/canonical.ts",
    "jboard": "crdt-java/src/main/java/dev/concord/crdt/Board.java",
    "jhlc": "crdt-java/src/main/java/dev/concord/crdt/Hlc.java",
    "jkeys": "crdt-java/src/main/java/dev/concord/crdt/Keys.java",
    "client": "client-core/src/client.ts",
    "idb": "client-core/src/idb.ts",
    "storage": "client-core/src/storage.ts",
    "boardpage": "web/src/BoardPage.tsx",
    "useboard": "web/src/useBoard.ts",
    "sim": "crdt-ts/test/fuzz/sim.ts",
    "fuzz": "crdt-ts/test/fuzz.test.ts",
    "guards": "server/tools/guard_checks.py",
    "spec": "docs/SPEC.md",
    "ci": ".github/workflows/ci.yml",
    "nightly": ".github/workflows/nightly.yml",
    "prodcompose": "deploy/prod/compose.yaml",
    "deploysh": "deploy/prod/deploy.sh",
    "caddyfile": "deploy/prod/caddy/Caddyfile",
    "runbook": "docs/runbook.md",
    "drills": "client-core/test/drills/drills.test.ts",
    "load": "client-core/test/load/load.test.ts",
    "perfspec": "web/e2e/perf.spec.ts",
}

REF = re.compile(r"\[\[([^\]#]+)#([^\]]+?)\]\]")
LINK = re.compile(r"\[`([^`]+):(\d+)(?:-(\d+))?`\]\(((?:\.\./)+[^)#]+)#L(\d+)(?:-L(\d+))?\)")


def path_of(alias: str) -> str:
    return ALIASES.get(alias.strip(), alias.strip())


def find(lines: list[str], anchor: str, start: int, rel: str) -> int:
    hits = [i for i in range(start, len(lines)) if anchor in lines[i]]
    if not hits:
        raise SystemExit(f"anchor not found in {rel}: {anchor!r}")
    if start == 0 and len(hits) > 1:
        raise SystemExit(f"anchor not unique in {rel} ({len(hits)} lines): {anchor!r}")
    return hits[0]


def wrap_label(line: str, width: int = 28) -> str:
    """A sequence-diagram message or note, its text wrapped with <br/>."""
    arrow = any(a in line for a in ("->>", "-->>", "-x"))
    if not (arrow or line.strip().startswith("Note")) or ": " not in line:
        return line
    head, text = line.split(": ", 1)
    return head + ": " + "<br/>".join(textwrap.wrap(text, width)) if len(text) > width else line


def build() -> None:
    DIAGRAMS.mkdir(exist_ok=True)
    refs: dict[str, dict] = {}
    for name in OUTPUTS:
        parts = SRC / name[:-3]  # a long document is a directory of parts, joined in name order
        if not parts.is_dir() and not (SRC / name).exists():
            continue
        text = "\n".join(p.read_text(encoding="utf-8") for p in sorted(parts.glob("*.md"))) if parts.is_dir() \
            else (SRC / name).read_text(encoding="utf-8")
        cache: dict[str, list[str]] = {}

        def resolve(m: re.Match) -> str:
            rel = path_of(m.group(1))
            f = ROOT / rel
            if not f.is_file():
                raise SystemExit(f"no such file: {rel}")
            lines = cache.setdefault(rel, f.read_text(encoding="utf-8").splitlines())
            anchor, _, end = m.group(2).partition("..")
            a = find(lines, anchor, 0, rel)
            b = find(lines, end, a + 1, rel) if end else a
            label = f"{pathlib.PurePosixPath(rel).name}:{a + 1}" + (f"-{b + 1}" if end else "")
            target = f"../{rel}#L{a + 1}" + (f"-L{b + 1}" if end else "")
            refs[f"{rel}#L{a + 1}"] = {"anchor": anchor}
            if end:
                refs[f"{rel}#L{b + 1}"] = {"anchor": end}
            return f"[`{label}`]({target})"

        text = REF.sub(resolve, text)
        n = 0

        def diagram(m: re.Match) -> str:
            nonlocal n
            n += 1
            stem = f"{name[:-3]}-{n}"
            src = m.group(2).strip()
            if src.startswith("sequenceDiagram"):  # long labels make a wide diagram, unreadable on a page
                src = "\n".join(wrap_label(line) for line in src.splitlines())
            (DIAGRAMS / f"{stem}.mmd").write_text(src + "\n", encoding="utf-8", newline="\n")
            return f"![{m.group(1) or stem}](diagrams/{stem}.svg)"

        text = re.sub(r"```mermaid(?: title=\"([^\"]*)\")?\n(.*?)```", diagram, text, flags=re.S)
        (DOCS / name).write_text(text, encoding="utf-8", newline="\n")
        print(f"built docs/{name}: {n} diagrams")
    REFS.write_text(json.dumps(refs, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def check() -> None:
    refs = json.loads(REFS.read_text(encoding="utf-8")) if REFS.exists() else {}
    files = lines = shas = tests = 0
    bad: list[str] = []
    known_tests = set()
    for f in list((ROOT / "server").rglob("src/test/**/*.java")):
        cls = f.stem
        for m in re.finditer(r"void ([a-zA-Z0-9_]+)\(", f.read_text(encoding="utf-8")):
            known_tests.add(f"{cls}.{m.group(1)}")
    for name in OUTPUTS:
        if not (DOCS / name).exists():
            continue
        text = (DOCS / name).read_text(encoding="utf-8")
        for m in LINK.finditer(text):
            rel = m.group(4).lstrip("./")
            rel = re.sub(r"^(\.\./)+", "", m.group(4))
            f = ROOT / rel
            files += 1
            if not f.is_file():
                bad.append(f"{name}: missing file {rel}")
                continue
            src = f.read_text(encoding="utf-8").splitlines()
            for line in [int(m.group(5))] + ([int(m.group(6))] if m.group(6) else []):
                lines += 1
                if line > len(src) or not src[line - 1].strip():
                    bad.append(f"{name}: {rel}:{line} is past the end or blank")
                    continue
                want = refs.get(f"{rel}#L{line}")
                if want and want["anchor"] not in src[line - 1]:
                    bad.append(f"{name}: {rel}:{line} no longer contains {want['anchor']!r}")
                elif not want:
                    bad.append(f"{name}: {rel}:{line} has no recorded anchor (cited by hand?)")
        # Hand-written references outside links (a repo path with a slash): the file and lines must exist.
        plain = LINK.sub("", text)
        for m in re.finditer(r"([\w.-]+(?:/[\w.-]+)+\.(?:md|ts|tsx|java|sql|yml|yaml|py|txt|sh)):(\d+)(?:-(\d+))?", plain):
            f = ROOT / m.group(1)
            files += 1
            if not f.is_file():
                bad.append(f"{name}: missing file {m.group(1)}")
                continue
            n = len(f.read_text(encoding="utf-8").splitlines())
            for line in [int(m.group(2))] + ([int(m.group(3))] if m.group(3) else []):
                lines += 1
                if line > n:
                    bad.append(f"{name}: {m.group(1)}:{line} is past the end ({n} lines)")
        for m in re.finditer(r"`([0-9a-f]{7,40})`", text):
            shas += 1
            r = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", m.group(1) + "^{commit}"], capture_output=True)
            if r.returncode != 0:
                bad.append(f"{name}: commit {m.group(1)} not found")
        for m in re.finditer(r"`([A-Z][A-Za-z]+Test)\.([a-z][A-Za-z0-9]+)`", text):
            tests += 1
            if f"{m.group(1)}.{m.group(2)}" not in known_tests:
                bad.append(f"{name}: test {m.group(1)}.{m.group(2)} not found")
    print(f"checked {files} file references ({lines} line numbers), {shas} commit SHAs, {tests} test names")
    for b in bad:
        print("  FAIL", b)
    print("all references resolve" if not bad else f"{len(bad)} problems")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    {"build": build, "check": check}[sys.argv[1] if len(sys.argv) > 1 else "check"]()
