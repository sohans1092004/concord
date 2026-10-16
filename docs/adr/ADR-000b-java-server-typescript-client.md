# ADR-000b: Java 21 server, TypeScript client, a conformance suite between them

Status: Accepted, confirmed before Phase 1 (SPEC §11.1). Date: 2026-10-16.

## Context

The owner of a board runs merge on the server; every client runs the same merge locally
for optimistic and offline editing (SPEC §2, §7). If the two disagree on a single byte, a
client's view silently differs from the server's and the snapshot hash check (SPEC §6.2)
fails. The server's threading story (one virtual thread per board actor, SPEC §2.2) and
its fencing and group-commit design argue for the JVM. The client is a browser, so it is
TypeScript.

## Decision

Write the CRDT core twice, `crdt-java` (Java 21, Gradle, no frameworks) and `crdt-ts`
(TypeScript, strict), and prove them identical with three mechanisms:

1. **Canonical encoding.** RFC 8785 JSON restricted to integers, with fixed escapes; every
   state has exactly one byte string and SHA-256 (SPEC §9.2.2–9.2.3).
2. **Golden vectors.** 55 hand-written cases in `conformance/vectors/`, each with the
   expected view, state and hash. Hashes are computed by a third implementation (Python's
   JSON encoder) from the hand-written expected state, never by either core.
3. **Differential fuzz.** The TypeScript fuzzer drives the Java core through a line
   protocol CLI and compares state hashes, view hashes, validation verdicts and canonical
   bytes (`conformance/runner/`).

## Alternatives

- **TypeScript everywhere** (the spec's fallback). One implementation, no drift; the
  server becomes Node's event loop. Rejected while time allows, because the Java server is
  where the distributed-systems signal is, but kept as the fallback.
- **Run the TypeScript core inside the JVM** (GraalJS). One implementation, but merge on
  the hot path would cross a language boundary per op, and debugging spans two runtimes.
- **Compile one core to both** (Kotlin Multiplatform, Rust + WASM + JNI). Removes drift at
  the cost of a toolchain far heavier than the 1,000 lines it would save.

## Consequences

- Every rule in the spec has to be stated precisely enough for two implementations to
  agree, which flushed out real gaps: string ordering (UTF-16 vs UTF-8 bytes vs
  `java.util.UUID`'s signed order), what counts as an integer (`1.0` is an integer in
  JavaScript, so the Java parser reads numbers as doubles and applies JavaScript's safe-
  integer rule), code-point vs UTF-16 lengths, and NFC.
- Known residual risk: NFC data comes from each platform's Unicode tables (Java 21 vs the
  ICU in Node). For characters added in newer Unicode versions the two may disagree on
  whether a label is NFC. That only affects validation (a client might accept a label the
  server rejects), never convergence.
- Every change to the core lands twice, and CI fails until both sides agree.
