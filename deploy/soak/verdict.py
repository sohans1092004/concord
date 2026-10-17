"""Soak verdict (Phase 4a step 1): is each JVM's heap after a full GC flat across the second hour?

Usage: python deploy/soak/verdict.py deploy/soak/<run>.jsonl [from_minute]

Flat means: over the samples from minute 60 on (or from_minute, for a shorter run whose board
state is constant by then), the least-squares slope of the used heap after
a full GC is at most max(2 MB/hour, 5% of that JVM's mean heap per hour). A leak shows as a
steady climb; GC noise does not. Prints a table and exits 1 if any JVM is not flat.
"""
import json
import sys

rows = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
# JVM keys are the integer heap samples: deploy-* (dev stack) or <project>-gateway-1 etc. (prod)
jvms = [k for k, v in rows[0].items() if isinstance(v, int) and k not in ("minute", "edits", "connections", "slow", "errors")]
start = int(sys.argv[2]) if len(sys.argv) > 2 else 60
late = [r for r in rows if r["minute"] >= start]
if len(late) < 3:
    sys.exit(f"only {len(late)} samples from minute {start} on; the soak must run at least {start + 10} minutes")

ok = True
print(f"{len(rows)} samples, minutes {rows[0]['minute']}..{rows[-1]['minute']}; window (minute {start} on): {len(late)} samples")
print(f"{'JVM':22} {'first MB':>9} {'h2 start':>9} {'h2 end':>9} {'h2 min':>8} {'h2 max':>8} {'slope MB/h':>11} {'limit':>7}  verdict")
for j in jvms:
    xs = [r["minute"] / 60 for r in late]
    ys = [r[j] / 1024 for r in late]
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    limit = max(2.0, 0.05 * my)
    flat = slope <= limit
    ok &= flat
    print(f"{j:22} {rows[0][j] / 1024:9.1f} {ys[0]:9.1f} {ys[-1]:9.1f} {min(ys):8.1f} {max(ys):8.1f} {slope:11.2f} {limit:7.1f}  {'flat' if flat else 'GROWING'}")
last = rows[-1]
print("load:", {k: last[k] for k in ("edits", "connections", "slow", "errors")})
print("container memory at the end:", last["mem"])
sys.exit(0 if ok else 1)
