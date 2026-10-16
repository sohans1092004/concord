"""Prints branch/line coverage as a markdown table (CI appends it to the job summary).

Usage: python coverage_summary.py ts    crdt-ts/coverage/coverage-summary.json
       python coverage_summary.py java  crdt-java/build/reports/jacoco/test/jacocoTestReport.xml
"""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET


def row(name, branch, line):
    def cell(c):
        covered, total = c
        return f"{100 * covered / total:.2f}% ({covered}/{total})" if total else "-"
    return f"| {name} | {cell(branch)} | {cell(line)} |"


def ts(path):
    data = json.loads(pathlib.Path(path).read_text())
    for name, d in data.items():
        label = "**total**" if name == "total" else pathlib.Path(name).name
        yield row(label, (d["branches"]["covered"], d["branches"]["total"]),
                  (d["lines"]["covered"], d["lines"]["total"]))


def java(path):
    root = ET.parse(path).getroot()

    def counts(e, kind):
        c = next((x for x in e.findall("counter") if x.get("type") == kind), None)
        return (0, 0) if c is None else (int(c.get("covered")), int(c.get("covered")) + int(c.get("missed")))

    core_b, core_l = [0, 0], [0, 0]
    for pkg in root.findall("package"):
        for f in pkg.findall("sourcefile"):
            b, l = counts(f, "BRANCH"), counts(f, "LINE")
            core = not pkg.get("name").endswith("/cli")
            if core:
                core_b = [core_b[0] + b[0], core_b[1] + b[1]]
                core_l = [core_l[0] + l[0], core_l[1] + l[1]]
            yield row(f.get("name") + ("" if core else " (CLI, exercised by the differential run)"), b, l)
    yield row("**core total** (excluding CLI)", core_b, core_l)
    yield row("**total**", counts(root, "BRANCH"), counts(root, "LINE"))


if __name__ == "__main__":
    kind, path = sys.argv[1], sys.argv[2]
    print(f"### {kind} coverage\n\n| File | Branches | Lines |\n|---|---|---|")
    for r in (ts if kind == "ts" else java)(path):
        print(r)
