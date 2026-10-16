// The Phase 2 drills (SPEC §8 rows 2–6), each self-checking. Run one with
//   DRILL=kill-owner npx vitest run test/drills
// or all of them with deploy/drills/run-all.sh. They leave the stack as they found it.
import { describe, expect, it } from "vitest";
import { compose, psql, redisCli, sleep } from "../e2e/stack.js";
import { boardVersion, drill, metricsOf, ownerOf, redisRoute, waitFor } from "./drill.js";

const only = process.env.DRILL;
const run = (name: string) => (only === undefined || only === name ? it : it.skip);

describe("drills", () => {
  /** §8 row 2, §10 exit: kill the owner mid-load; a new owner serves within 5s of lease expiry. */
  run("kill-owner")("kill-owner", async () => {
    await drill("kill-owner", {}, async (ctx) => {
      const old = ownerOf(ctx.board)!;
      const pttl = Number(redisCli("PTTL", `board:lease:${ctx.board}`));
      const killedAt = Date.now();
      compose("kill", "-s", "SIGKILL", old.instance);
      const leaseExpiresAt = killedAt + pttl;
      const next = await waitFor(() => {
        const o = ownerOf(ctx.board);
        return o && o.instance !== old.instance ? o : null;
      }, 60_000, "a new owner");
      const firstAck = await waitFor(() => ctx.acks().find((a) => a.at > next.at), 30_000, "an ACK from the new owner");
      ctx.log("killed", old.instance);
      ctx.log("new_owner", next.value.instance);
      ctx.log("fence", `${old.fence} -> ${next.value.fence}`);
      ctx.log("lease_remaining_at_kill_ms", pttl);
      ctx.log("new_owner_serving_after_lease_expiry_ms", next.at - leaseExpiresAt);
      ctx.log("first_ack_after_kill_ms", firstAck.at - killedAt);
      expect(next.at - leaseExpiresAt, "serving within 5s of lease expiry").toBeLessThanOrEqual(5_000);
      compose("start", old.instance); // leave the stack as we found it
    });
  });

  /** §8 row 3: pause the owner past its lease (Reliant incident 004), then resume it. */
  run("pause-owner")("pause-owner", async () => {
    await drill("pause-owner", { afterMs: 10_000 }, async (ctx) => {
      const old = ownerOf(ctx.board)!;
      const versionAtPause = boardVersion(ctx.board);
      const pausedAt = Date.now();
      compose("pause", old.instance);
      const next = await waitFor(() => {
        const o = ownerOf(ctx.board);
        return o && o.instance !== old.instance ? o : null;
      }, 60_000, "a new owner");
      await sleep(Math.max(0, 20_000 - (Date.now() - pausedAt))); // paused well past the 15s lease
      compose("unpause", old.instance);
      await sleep(8_000); // the stale owner wakes up with ops in its mailbox and tries to commit
      const staleCommits = Number(psql(`SELECT count(*) FROM board_ops WHERE board_id = '${ctx.board}' AND fence = ${old.fence} AND version > ${versionAtPause}`)[0]);
      ctx.log("paused", old.instance);
      ctx.log("paused_for_ms", Date.now() - pausedAt - 8_000);
      ctx.log("new_owner", next.value.instance);
      ctx.log("fence", `${old.fence} -> ${next.value.fence}`);
      ctx.log("new_owner_after_pause_ms", next.at - pausedAt);
      ctx.log("commits_by_stale_owner_after_pause", staleCommits);
      // How the stale owner stopped: fenced out by Postgres on commit, or its lease renewal
      // found another owner first. Either way it must not have committed.
      const port = 7000 + Number(old.instance.replace("session", ""));
      const metrics = (await (await fetch(`http://localhost:${port}/metrics`)).json()) as Record<string, number>;
      ctx.log("stale_owner_fence_rejections", metrics.fence_rejections ?? 0);
      expect(staleCommits, "zero commits from the stale owner").toBe(0);
    });
  });

  /**
   * §8 row 3, amendment 11: the paused owner also loses Redis, so on waking its lease renewal
   * cannot tell it it was replaced. It keeps serving the requests that queued up in its socket
   * while it was frozen; only the Postgres fence (§6.1 step 3.2) stops it. Its commit must be
   * rejected, it must log the fence rejection, and it must not ACK anything new.
   */
  run("pause-owner-no-redis")("pause-owner-no-redis", async () => {
    await drill("pause-owner-no-redis", { afterMs: 10_000 }, async (ctx) => {
      const old = ownerOf(ctx.board)!;
      const before = await metricsOf(old.instance);
      await redisRoute(old.instance, false); // its renewals and publishes now fail
      const pausedAt = Date.now();
      const since = new Date(pausedAt - 1_000).toISOString();
      compose("pause", old.instance);
      // Frozen from here: count what it has committed, which must never grow again.
      const ownRows = () => Number(psql(`SELECT count(*) FROM board_ops WHERE board_id = '${ctx.board}' AND fence = ${old.fence}`)[0]);
      const rowsAtPause = ownRows();
      const versionAtPause = boardVersion(ctx.board);
      try {
        const next = await waitFor(() => {
          const o = ownerOf(ctx.board);
          return o && o.instance !== old.instance ? o : null;
        }, 60_000, "a new owner");
        await sleep(Math.max(0, 20_000 - (Date.now() - pausedAt)));
        compose("unpause", old.instance); // wakes with requests queued, and still no Redis
        await sleep(8_000);
        const after = await metricsOf(old.instance);
        const logs = compose("logs", "--no-color", "--since", since, old.instance);
        const fencedLines = logs.split(/\r?\n/).filter((l) => l.includes("fenced out by a newer owner") && l.includes(ctx.board));
        const staleCommits = Number(psql(`SELECT count(*) FROM board_ops WHERE board_id = '${ctx.board}' AND fence = ${old.fence} AND version > ${versionAtPause}`)[0]);
        ctx.log("paused", old.instance);
        ctx.log("new_owner", `${next.value.instance} after ${next.at - pausedAt} ms`);
        ctx.log("fence", `${old.fence} -> ${next.value.fence}`);
        ctx.log("stale_owner_fence_rejections", (after.fence_rejections ?? 0) - (before.fence_rejections ?? 0));
        ctx.log("stale_owner_lease_renew_failures", (after.lease_renew_failures ?? 0) - (before.lease_renew_failures ?? 0));
        ctx.log("stale_owner_rows_at_pause_and_after", `${rowsAtPause} -> ${ownRows()}`);
        ctx.log("fenced_out_log_lines", fencedLines.length);
        ctx.log("commits_by_stale_owner_after_pause", staleCommits);
        expect((after.fence_rejections ?? 0) - (before.fence_rejections ?? 0), "rejected by the Postgres fence").toBeGreaterThan(0);
        expect(fencedLines.length, "the rejection is in its logs").toBeGreaterThan(0);
        // ACKs follow commits only (apply-after-commit, guard-checked), so no new row means no new ACK.
        expect(ownRows(), "no new commit, so no new ACK").toBe(rowsAtPause);
        expect(staleCommits).toBe(0);
      } finally {
        await redisRoute(old.instance, true);
      }
    });
  });

  /** §8 row 4: Postgres down. Nothing is ACKed; clients keep editing; all resumes after. */
  run("stop-postgres")("stop-postgres", async () => {
    await drill("stop-postgres", { afterMs: 10_000 }, async (ctx) => {
      compose("stop", "postgres");
      const stoppedAt = Date.now();
      await sleep(10_000);
      const acksDuring = ctx.acks().filter((a) => a.at > stoppedAt + 2_500).length; // after in-flight commits drain
      const pendingPeak = Math.max(...ctx.clients.map((x) => x.c.pendingOps().length));
      compose("start", "postgres");
      const startedAt = Date.now();
      const firstAck = await waitFor(() => ctx.acks().find((a) => a.at > startedAt), 60_000, "an ACK after Postgres returns");
      ctx.log("postgres_down_ms", startedAt - stoppedAt);
      ctx.log("acks_while_down", acksDuring);
      ctx.log("pending_peak_per_client", pendingPeak);
      ctx.log("first_ack_after_postgres_start_ms", firstAck.at - startedAt);
      expect(acksDuring, "no ACK without a commit").toBe(0);
      expect(pendingPeak).toBeGreaterThan(0);
    });
  });

  /** §8 row 5: Redis down. Owners keep serving (Postgres fences them); gateways poll owners. */
  run("stop-redis")("stop-redis", async () => {
    await drill("stop-redis", { afterMs: 10_000 }, async (ctx) => {
      const versionBefore = boardVersion(ctx.board);
      compose("stop", "redis");
      const stoppedAt = Date.now();
      await sleep(15_000);
      const acksDuring = ctx.acks().filter((a) => a.at > stoppedAt + 1_000).length;
      const versionDuring = boardVersion(ctx.board);
      // Did ops reach the *other* clients while Redis was down (polling fan-out)?
      const behind = ctx.clients.map((x) => versionDuring - (x.c.confirmedVersion ?? 0));
      compose("start", "redis");
      ctx.log("redis_down_ms", Date.now() - stoppedAt);
      ctx.log("acks_while_down", acksDuring);
      ctx.log("versions_committed_while_down", versionDuring - versionBefore);
      ctx.log("client_lag_in_versions_at_end_of_outage", behind);
      expect(acksDuring, "owners keep serving with Redis down").toBeGreaterThan(0);
      for (const lag of behind) expect(lag, "fan-out keeps working by polling").toBeLessThan(versionDuring - versionBefore);
    });
  });

  /** §8 row 6: Redis wiped. Leases are re-taken, fence counters rebuild from boards.fence. */
  run("wipe-redis")("wipe-redis", async () => {
    await drill("wipe-redis", { afterMs: 10_000 }, async (ctx) => {
      const before = ownerOf(ctx.board)!;
      const fenceBefore = Number(psql(`SELECT fence FROM boards WHERE id = '${ctx.board}'`)[0]);
      redisCli("FLUSHALL");
      const wipedAt = Date.now();
      const back = await waitFor(() => ownerOf(ctx.board), 30_000, "the owner key again");
      const fenceAfter = Number(psql(`SELECT fence FROM boards WHERE id = '${ctx.board}'`)[0]);
      ctx.log("owner_before", `${before.instance} fence ${before.fence}`);
      ctx.log("owner_after", `${back.value.instance} fence ${back.value.fence}`);
      ctx.log("owner_key_back_after_ms", back.at - wipedAt);
      ctx.log("boards_fence", `${fenceBefore} -> ${fenceAfter}`);
      expect(fenceAfter).toBeGreaterThanOrEqual(fenceBefore);
      expect(back.value.fence).toBeGreaterThanOrEqual(before.fence);
      // The wipe also erased board:fence:{id}. A gateway's claim watchdog may already have
      // claimed (and recreated the counter) before the owner's renewal re-took the lease;
      // either way, force a claim now: its fence must be above every fence Postgres has seen,
      // which an INCR on a wiped counter (starting again at 1) would not give.
      const floor = Number(psql(`SELECT fence FROM boards WHERE id = '${ctx.board}'`)[0]);
      ctx.log("boards_fence_before_forced_claim", floor);
      const retriesBefore = await Promise.all(["session1", "session2", "session3"].filter((s) => s !== back.value.instance).map(async (s) => [s, (await metricsOf(s)).claim_fence_retries ?? 0] as const));
      compose("kill", "-s", "SIGKILL", back.value.instance);
      const next = await waitFor(() => {
        const o = ownerOf(ctx.board);
        return o && o.instance !== back.value.instance ? o : null;
      }, 60_000, "an owner claimed after the wipe");
      ctx.log("claimed_after_wipe", `${next.value.instance} fence ${next.value.fence}`);
      ctx.log("fence_counter_after_claim", redisCli("GET", `board:fence:${ctx.board}`));
      expect(next.value.fence).toBe(floor + 1);
      // In one step: a claim that first drew a fence at or below boards.fence and had to retry
      // would also end at floor + 1 (the retry INCRs again), so check there was no retry.
      const retriesWas = new Map<string, number>(retriesBefore);
      const retries = ((await metricsOf(next.value.instance)).claim_fence_retries ?? 0) - (retriesWas.get(next.value.instance) ?? 0);
      ctx.log("claim_fence_retries", retries);
      expect(retries, "the counter rebuilt from boards.fence, without a retry").toBe(0);
      expect(Number(psql(`SELECT fence FROM boards WHERE id = '${ctx.board}'`)[0])).toBe(next.value.fence);
      compose("start", back.value.instance);
    });
  });
});
