# Incident 017: CI hung on arm64, in the log-capture helper

**Date:** 2026-10-17 to 2026-10-18 · **Type:** test-infrastructure bug (deadlock) · **Environment:** GitHub Actions, `ubuntu-24.04-arm`, server test suite · **Status:** fixed in `d940470`

## Summary

The arm64 CI job (server tests plus the production images for the AWS Graviton host) hung twice in `:gateway:test` (`92844f3`, `790a65b`), until GitHub's job timeout killed it with no reports.
- A temporary hunt workflow ran the suite 12 times on arm64 without a hang.
- The third hang came with a thread dump, which showed a deadlock in **a test helper**: the log-capturing appender in `GatewayLogTest`.

## What broke

CI on arm64, intermittently: the job hung instead of failing, so it said nothing about why.

## Impact

- No production impact: production does not use the test appender.
- CI could not be trusted on the architecture the trial host runs, and publishing images waits for that job.

## Detection

1. Two hangs with no output.
2. `1be66a0` added diagnostics to CI:
   - per-test logging;
   - a thread dump of any test JVM still running after 8 minutes;
   - a 15-minute Gradle test timeout so the job fails with reports.
3. A temporary `arm64-hunt` workflow ran 6 gateway-only and 6 full-suite runs: **12 of 12 passed**. It was removed (`7c6ad1b`), and the diagnostics were kept.
4. The next hang, on `a176a2a`, came with the dump: `GatewayLogTest.tokensNeverReachTheLogs`, with the test thread, two housekeepers and a heartbeat all BLOCKED on the Capture appender's monitor. No platform thread held it, so a **virtual thread** did, inside `append`.

## Root cause

- **Formatting under a lock.** The test's Capture appender extended Logback's `AppenderBase`, whose `doAppend` is `synchronized`. Inside it the appender formatted the message, which calls arbitrary `toString()`s, and encoded it, with every logger at TRACE.
  - A virtual thread could block or park inside that monitor and hold it while every other logging thread waited.
- **Production does not do this.** Logback's `OutputStreamAppender` encodes first, without a lock, and locks only around the write (checked with `javap` on logback-core 1.5.18).
- **Why the hang never failed:** the JUnit timeout interrupts the test thread, and an interrupt cannot reach a thread blocked on a monitor. So the 5-minute timeout never fired.

## Fix

`d940470`:
- **The appender:** Capture extends `UnsynchronizedAppenderBase` and does what production does. It formats and encodes with no lock, uses synchronized lists for the adds, and takes snapshots for the reads.
- **Timeouts:** the JUnit default timeout runs in a separate thread, so it fires even when the test thread is blocked on a monitor.
- **Diagnostics:** the CI watchdog's dump includes virtual threads (`jcmd Thread.dump_to_file`).

## Verification

- Every arm64 CI run since has passed, including every commit published to the trial host.
- Guard check `log-redaction-ticket` still fails without the redaction and passes with it: the test still tests what it should.

## What I learned

- A hang is a failure that withholds its evidence. The first fix was to make it fail loudly, with a thread dump, and only then look for the bug.
- Twelve green runs proved nothing about an intermittent deadlock. The diagnostics did.
- Test helpers run on the same threads as the code under test: a `synchronized` in a helper can deadlock virtual threads just as well as one in production.
