# I deleted my safety checks one at a time. Two of my tests didn't notice.

*Draft.*

Concord is a collaborative kanban board I built: several people edit one board at once, work offline for up to a day, and never lose an edit. Its central promise is narrow and strict. When the server sends a client an `OP_ACK`, the edit is in Postgres, and it will never disappear. The client drops an acknowledged edit from its queue for good, so an ACK that turns out to be wrong is an edit nobody holds any more.

A promise like that rests on a handful of lines of code. One condition in a SQL `WHERE` clause. A lookup in a cache. A check on a lease. Each of those lines has a test that is supposed to protect it. This post is about how I found out whether those tests actually did, by deleting the lines and watching what happened. Two of them didn't notice.

One caveat up front: everything here ran on one laptop and in CI. It shows what the tests could and couldn't detect, not how a production cluster behaves.

## The guard that matters most

Each board has exactly one **owner**: a server process that orders its edits, checks them and commits them. Ownership comes from a lease in Redis, and leases are not enough on their own. A process can freeze for 20 seconds (a long GC pause, a `docker pause`, a VM migration), lose its lease while frozen, and wake up still believing it owns the board. If it can still write, two owners commit edits to the same board, and the new owner's memory never sees what the stale one wrote.

So every commit carries a **fence**, a number that goes up with every change of owner, and Postgres checks it in the same transaction that stores the edits:

```sql
UPDATE boards SET version = version + ? WHERE id = ? AND fence = ? RETURNING version
```

Zero rows back means a newer owner exists: the stale owner drops the board and stores nothing. That one condition, `AND fence = ?`, is the difference between "a frozen server is harmless" and "a frozen server silently loses edits".

There is a test for it, `FencingTest`. Owner A takes an edit into its batch and pauses just before committing. Its lease expires, owner B claims the board, and A wakes up and tries to commit. The test requires A to be refused and B's state to hold every acknowledged edit.

The test was green. That told me the code and the test agreed. It didn't tell me the test could tell the difference between code with the guard and code without it.

## Remove the guard, run the test

The idea is old and simple, a hand-made cousin of mutation testing. For each guard:

1. Delete it, or replace it with something that always passes.
2. Run the test that is supposed to protect it. **It must fail.**
3. Restore the file and run the test again. **It must pass.**

I wrote it as a Python script that edits the source, runs the named test, and restores the file from git. Each check is a line of data:

```python
"fence-check": (
    "the fence condition in the commit UPDATE (SPEC §6.1 step 3.2)",
    [(STORE, "WHERE id = ? AND fence = ? RETURNING version",
             "WHERE id = ? AND ? >= 0 RETURNING version")],
    "dev.concord.session.FencingTest.staleOwnerCannotCommitAfterANewOwnerClaims", True),
```

The replacement keeps the same number of parameters, so the code still compiles and runs: it just stops checking. The script prints one line per check, and the results go into the repository next to the code:

```
fence-check: removed the fence condition in the commit UPDATE (SPEC §6.1 step 3.2)
  test staleOwnerCannotCommitAfterANewOwnerClaims: FAILED without the guard
  (AssertionFailedError: stale owner ACKed: {type=OP_ACK, ...}); PASSED with it restored
```

That one worked. Without the fence, the stale owner ACKed an edit the new owner never applied, which is exactly the bug the guard exists to prevent. Then I ran the others.

## The first test that couldn't fail

The specification I started from had a different claim order. A new owner loaded the board first and wrote its fence to Postgres lazily, at its first commit. Between loading and that first commit, Postgres still held the old owner's fence, so a stale owner could commit in that window and its edit would be missing from the new owner's memory. I changed the order before writing any code: write the fence to Postgres *before* loading anything.

The guard check for that put the old order back. `FencingTest` should have failed. **It passed.**

The test was wrong, not the code. In my test, owner B committed an edit of its own before A woke up. That commit raised the fence in Postgres, so A was fenced out even under the old order. The dangerous window is *before* the new owner's first commit, and my test had stepped straight past it. It asserted the right outcome, at a moment when every version of the code produced that outcome.

The fix was to make B claim and load, but not commit, and only then wake A. With the old order restored, the test now fails with `stale owner ACKed`; with the real order, it passes. The test now pins the exact interleaving that breaks the old design. Nothing in the green-tick version had said that it didn't.

## The second one

Duplicate edits are normal: a client that loses its connection resends everything it hasn't seen acknowledged. The owner answers a duplicate with the original ACK from an in-memory cache of the last 10,000 op IDs. I removed that lookup. **Every test still passed.**

They passed because the database's primary key also catches a duplicate. Removing the cache only moved the work one layer down. So what was the cache *for*? Answering resends when the database can't, for instance during a Postgres outage, when clients reconnect and resend their queues. No test exercised that.

The new test cuts the owner off from Postgres (through Toxiproxy) and sends a resend. Only the cache can answer it. Without the cache the test gets `RETRY_LATER` instead of the original ACK, and fails, as it should.

Both broken tests were correct about *what* they asserted. They were wrong about *when* and *which layer*: the interleaving, and which mechanism actually answered. In both cases the fix was the same: drive the system onto the one path where only the guard can save you.

## It isn't only unit tests

Some guards are only exercised by a failure drill against the whole stack: real gateways, real Session instances, Postgres, Redis, killed and paused with Docker. The script can do those too. It rebuilds the server image with the guard removed, runs the drill, then restores and rebuilds.

That's how I found a drill that couldn't reach its own guard. The pause-owner drill freezes the owner past its lease and resumes it. It always passed, with zero commits from the stale owner, but its own numbers said `stale_owner_fence_rejections: 0`. The fence was never tested. The stale owner's lease renewal ran first, saw the new owner, and dropped the board before it tried to commit. Correct behaviour, wrong path.

So I added a variant that also cuts the frozen owner off from Redis, so its renewal can't save it and only the Postgres fence can. Then that one was flaky: one run in three had no fence rejection. While the owner was frozen, clients resent their edits to the new owner, and when the old one woke, everything in its queue was already stored. A batch of nothing but duplicates commits nothing, so it never needs the fence. The fix was a probe edit sent straight to the frozen owner that nobody else could commit. Now every run has a fence rejection. And the guard check proves the drill: remove the fence condition, and the stale owner ACKs the probe.

A drill that passes without ever exercising its guard is worse than no drill, because it looks like evidence. Each drill now logs the counter that proves its path ran, and a guard check proves it fails without the guard.

## Retries can hide the bug

One more pattern turned up more than once: **a retry can hide the bug it recovers from.**

After Redis is wiped, a board's fence counter is gone. The rebuild rule is that the next fence must start above the one Postgres has seen. My first version of the drill checked that the next claim got `boards.fence + 1`. But a claim that draws a fence at or below Postgres's value is refused and retries, and the retry increments again. A counter that had restarted at 1 would still end at the right number, one retry later.

The check now counts retries and requires zero. With the floor removed from the counter script, the drill fails with `without a retry: expected 1 to be +0`. The same thought, "the retry made it right, so the test can't see it was wrong", shaped several later checks.

## What 42 checks found

By the end there were 42. Each is in the repository with the line it removes, the test that must fail, and the recorded result, failing without its guard and passing with it. They cover:
- the core write path, the fence, dedup and apply-after-commit;
- clock handling, Redis-wipe recovery and idle unloading;
- deleted boards, op retention, backups and log redaction;
- presence, the client's offline queue, telemetry limits, trace propagation, and the client pacing itself to the server's rate limit.

Most of them failed on the first try, which is the boring, good outcome. The ones that didn't were worth the whole exercise:

- **Two unit tests that couldn't fail:** the fencing test, which stepped past its window, and the dedup test, where another layer did the work.
- **A drill that never reached its guard,** and another that passed by luck through a retry.

None of these were bugs in the code. Every one was a bug in the *evidence*, and evidence is what lets you change the code later without being afraid of it.

## What I'd do again

**Write the check when you write the guard.** "Removing this line must break this named test" is a better spec for a test than "test the fencing": it says which path the test has to reach, before the test exists to get it wrong.

**Make the replacement compile and run.** Commenting a guard out and getting a compile error proves nothing. The replacement has to keep the program running with the guard's effect gone: `fence = ?` becomes `? >= 0`, `if (!bucket.take(...))` becomes `if (false)`.

**Record the result next to the code.** "It failed without the guard" is a claim; the failure message is evidence. `stale owner ACKed: {type=OP_ACK, ...}` tells the next reader exactly what the guard stops.

**Prefer the failure that names the property.** The best checks fail with the system's promise in the message: "stale owner ACKed", "every ACKed op is in board_ops", "without a retry". When one fails, you know what broke without reading the test.

**It's cheap.** The whole suite is a 330-line script and a list. Unit-level checks take seconds each; the drill-level ones rebuild an image and take minutes. A full run fits in a coffee break, and I only run the relevant ones when a guard changes.

## What it doesn't do

It doesn't find guards I never wrote. Each check starts from a line I already knew was important. The bugs that come from a missing guard, like a board deleted while its owner had it loaded, or a client that resent its whole queue every time the server said "slow down", were found by running the system: on a live host, and by measuring at the extremes the spec names. Remove-the-guard checks make the tests honest about the guards you have. Drills, measurements and real users find the guards you don't.

That's the split I'd keep. Tests prove the known invariants, and the guard checks prove the tests. Everything else, you go and break on purpose.

*Concord's code, the 42 checks and their recorded results are at github.com/sohans1092004/concord (`server/tools/guard_checks.py`, `guard_checks.out`); the postmortems mentioned here are in `production/incident-reports/` (003, 006, 015).*
