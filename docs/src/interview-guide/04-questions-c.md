
### 3.11 Testing

**Q50. How do you test convergence?**
- *Answer.* A permutation fuzzer simulates 3–5 replicas with skewed clocks making 50–500 ops each from partial views, then delivers all ops in 8 random orders with about 20% duplicates. Every replica must end byte-identical, with no LWW tie-break firing on valid ops ([[fuzz#const RUNS = Number(process.env.FUZZ_RUNS ?? 500);]]). It runs 500 seeds per commit and 100,000 nightly. The op mix leans on the hard cases: one "hot" card takes most moves, deletes and restores ([[sim#if (kind < 6) {..} else {]]).
- *Follow-up.* "How do you know the fuzzer can find bugs?" Three bugs were injected (arrival-order LWW, a delete forgetting unseen tags, an HLC ignoring its counter); each was caught within 200 runs (ADR-000).
- *Trap.* "We have high coverage, so it's correct." Coverage says nothing about orders.

**Q51. What happens when the fuzzer finds a failure?**
- *Answer.*
  1. fast-check shrinks the scenario.
  2. Delta debugging cuts it to a minimal op set ([[sim#export function minimize(]]).
  3. Both are written to `.fuzz-failures/` with the seed, a candidate for a new conformance vector. `FUZZ_SEED` replays a run.
- *Follow-up.* "Did that happen?" The rebalance property found that the generator never rebalanced *list* keys, which once grew to 65 characters (ADR-001).
- *Trap.* Not knowing what a seed buys you: replay.

**Q52. What's a remove-the-guard check?**
- *Answer.* For each safety guard, a script swaps the guarded code for code that compiles without it, runs the named test and requires it to **fail**, then restores the file and requires it to pass ([[guards#"fence-check": (]]). There are 42, at unit, browser and drill level. Two tests could not fail on the first run: `FencingTest` (the new owner committed first, so the window was skipped) and the LRU test (the primary key hid the LRU's removal) (incident 015).
- *Follow-up.* "How is that different from mutation testing?" Mutation testing mutates everything at random; this targets the lines I already know matter, and records the failure message as evidence.
- *Trap.* Commenting a guard out and calling a compile error "the test failed".

**Q53. How do the drills check themselves?**
- *Answer.* Each drill fails unless every op any client saw ACKed is in `board_ops`, every client and a fresh one end on one SHA-256 state hash, and nothing is in Unsent edits ([[drills#run("kill-owner")("kill-owner"]]). Each also logs the counter that proves its path ran, such as `stale_owner_fence_rejections`.
- *Follow-up.* "What did a drill find?" The kill-owner drill found valid edits landing in Unsent after a crash: per-op retry timers (incident 012).
- *Trap.* A drill that only checks "the service came back".

**Q54. Server coverage?**
- *Answer.* Not measured: JaCoCo is configured for `crdt-java` only. The cores are measured: TS 94.62% of branches, Java 87.37% excluding the CLI **[laptop]**. The server's evidence is 61 integration tests on real infrastructure plus the guard checks.
- *Follow-up.* "Would you add it?" Yes, cheaply, but I'd trust the guard checks more: they prove the tests detect the bug, not just that lines ran.
- *Trap.* Inventing a number.

### 3.12 Performance

**Q55. What's your sync latency?**
- *Answer.* p50 27 ms and p95 32 ms with 2 editors; p95 42 ms with 30 **[laptop]**. From India to the Sydney host: p95 315–354 ms, of which the server is about 17 ms; the rest is a ~300 ms round trip **[host]**. The target, p95 < 200 ms, is met by the server and missed end to end from far away.
- *Follow-up.* "Fix?" Host near the users. No server change needed.
- *Trap.* Quoting the laptop number as user-facing latency.

**Q56. How did you pick the commit window?**
- *Answer.* Measured 0, 5, 15 and 30 ms with 30 editors on one board. 5 ms batched as well as 15 (2.55 against 2.53 ops per commit) at 11 ms less median latency; 0 ms committed every op alone; 30 ms only added latency **[laptop]**. Changed from 15 to 5 (ADR-005, amendment 32).
- *Follow-up.* "Is one run per setting enough?" It's thin: 60 s and about 52,000 samples each. I'd rerun before betting much on the 5-against-15 batching tie.
- *Trap.* Claiming the window matters at 300 boards; there, each board commits alone anyway.

**Q57. Tell me about the render performance work.**
- *Answer.* 2,000 cards, 10 editors: p50 was 56.6 ms, with every update over 16 ms. Three causes (`2dfb84c`):
  1. The drag-and-drop context re-rendered every sortable card on each update.
  2. Unchanged objects were rebuilt, so memoised components re-rendered.
  3. The store subscription was unstable.

  Fixes: memoise the DnD area, `useStableView`, a stable subscription ([[boardpage#function useStableView(next: View)]]). After: p50 4.7 ms. On today's idle rerun about 1 update in 300 still takes 16–26 ms, so the strict target is missed, and it's not profiled.
- *Follow-up.* "Why not virtualise the list?" A choice I'd test next; the tail may be GC or layout, not render count.
- *Trap.* Saying the target is met.

**Q58. What's the throughput of one connection?**
- *Answer.* 21.6 ops/s with one op in flight, capped by the 20 ops/s per-connection rate limit plus a burst of 50 (50 + 20 × 30 = 650; 649 sent) **[laptop]**. A person never comes close; a reconnecting client with a backlog does, which is why the client paces itself.
- *Follow-up.* "Would pipelining help?" Only above the rate limit; per-connection sequence numbers would be needed to keep order.
- *Trap.* Blaming the round trip.

### 3.13 Security

**Q59. How are passwords and sessions stored?**
- *Answer.* Passwords use argon2id with OWASP's minimum (19 MiB, 2 passes, 1 lane), in the PHC string format so parameters can rise later ([[passwords#static final int MEMORY_KIB = 19 * 1024]]). Sessions are 32 random bytes; Postgres stores only their SHA-256, so a leaked table can't be replayed. The cookie is `HttpOnly; SameSite=Strict; Secure`, 30 days.
- *Follow-up.* "Timing attacks on sign-in?" An unknown email is checked against a dummy hash, so timing doesn't reveal which emails exist ([[api#boolean ok = Passwords.verify(String.valueOf(b.get("password")), hash) && !rows.isEmpty();]]).
- *Trap.* bcrypt "because it's standard" without parameters.

**Q60. CSRF and cross-site WebSocket hijacking?**
- *Answer.* Every state-changing REST call must be JSON from an allowed Origin, and a request carrying the cookie with no Origin is refused ([[api#private void sameOrigin(Context ctx)]]). WebSockets need the Origin allowlist plus a single-use ticket. The CSP allows only same-origin scripts and `wss://` to the host.
- *Follow-up.* "Rate limits behind a proxy?" X-Forwarded-For is believed only from Caddy's own address, re-resolved every 30 s (amendment 23).
- *Trap.* Trusting X-Forwarded-For from anyone.

**Q61. What protects the internal gateway-to-owner socket?**
- *Answer.* A shared-secret header, compared in constant time; production refuses a secret under 32 bytes or the dev one ([[internal#app.wsBeforeUpgrade("/internal"]], [[config#public Config checkPublic()]]). Only Caddy publishes ports.
- *Follow-up.* "Why not mTLS?" One VM on a private Docker network; a secret plus no published ports is proportionate. mTLS would be next on a real network.
- *Trap.* "It's internal, so it's safe."

**Q62. Do secrets reach the logs?**
- *Answer.* No. A redacting JSON encoder scrubs `ticket=`, `access_token=`, the session cookie and `Bearer` values from every log line, and bug-report diagnostics pass the same redactor ([[redact#static final Pattern SECRET]]). `GatewayLogTest` checks it; guard checks `log-redaction-ticket` and `bug-report-redaction`.
- *Follow-up.* "And `/metrics`?" Exposed publicly once on the trial host (counters including board IDs, no secrets); now 404 from outside (incident 019).
- *Trap.* Not owning the `/metrics` incident.

### 3.14 Operations

**Q63. How do you deploy without losing edits?**
- *Answer.* `deploy.sh <sha>`:
  1. refuse unless the checkout matches the SHA;
  2. back up;
  3. pull the images CI built after every test passed;
  4. migrate forward-only;
  5. replace session1, session2, then the gateway, each after the previous is healthy;
  6. reload Caddy;
  7. check `/healthz` and that `/metrics` is closed from outside;
  8. print the rollback.

  Three live deploys under editing lost nothing **[host]**.
- *Follow-up.* "Why pull, never build, on the VM?" The VM has 2 GB, a build measured 1,072 MiB, and pulling runs exactly what CI tested.
- *Trap.* `latest` tags.

**Q64. How do you know a backup works?**
- *Answer.* `pg_dump` and a manifest of every board's state hash come from one exported snapshot. `verify` restores into a throwaway Postgres and replays each board from its oldest kept snapshot against the manifest. A rehearsal restore printed `RESTORE VERIFIED` in 19.9 s **[laptop]**. Honest gap: a restore of a *live* backup after the `restore.sh` fix isn't recorded.
- *Follow-up.* "Where's the key?" The age private key is never on the server.
- *Trap.* "We have nightly dumps": untested backups.

### 3.15 Why not X?

**Q65. Why not Yjs or Automerge? Why not OT?**
- *Answer.* Yjs and Automerge are general JSON/text CRDTs whose defaults aren't our product rules: delete-wins, the Orphaned list, one atomic placement. Neither has a Java implementation, so the server would run JavaScript or Rust through a bridge. Concord needs no text CRDT, since descriptions are last-writer-wins whole (ADR-000). OT (operational transformation) needs a central transform server and transform functions for every pair of operations, and handles offline divergence poorly; CRDTs merge without coordination.
- *Follow-up.* "Would you use Yjs for descriptions?" If character-level editing became a requirement, yes, for that field only.
- *Trap.* Dismissing libraries as "not invented here": it was a semantics decision.

**Q66. Why not Kafka, Raft, a StatefulSet, merge-on-read, or JWTs?**
- *Answer.*
  - **Kafka:** Postgres already stores every op with its version, so Pub/Sub plus gap repair is enough and faster (ADR-006).
  - **Raft:** Concord needs one writer per board, not a replicated log. Postgres is the durable log, and a lease plus a Postgres fence gives single-writer safety without running consensus.
  - **A StatefulSet:** the ownership of boards moves at runtime by lease, not by pod identity, and v3 dropped Kubernetes for Phase 4 (v3 change 12).
  - **Merge-on-read:** every op would load the board, and two requests would race unless Postgres row locks serialised them, which moves the actor into the database (ADR-004).
  - **JWTs:** can't be revoked without a lookup, and they ended up in URLs; hashed sessions plus single-use tickets are simpler (amendment 14).
- *Follow-up.* "When would Raft make sense?" Many replicas of the *owner* with automatic failover in milliseconds, instead of a 15 s lease.
- *Trap.* Saying the lease is a consensus protocol. It isn't; the Postgres fence is what makes it safe.
