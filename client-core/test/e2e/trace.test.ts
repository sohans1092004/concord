// SPEC §9.5 traces, end to end, against the dev stack with tracing on:
//   CONCORD_OTLP_ENDPOINT=http://jaeger:4318/v1/traces docker compose -f deploy/docker-compose.yml --profile observability up -d --build
//   cd client-core && TRACE_TEST=1 npx vitest run test/e2e/trace.test.ts
// One edit by A (on gateway 1), seen by B (on gateway 2). Its trace, keyed by the op's op_id,
// must hold all seven spans in pipeline order: client.send → gateway.forward → owner.admit →
// owner.commit → owner.broadcast → gateway.deliver → peer.render.
import { describe, expect, it } from "vitest";
import type { Telemetry } from "../../src/index.js";
import { client, GATEWAYS, seed, sleep } from "./stack.js";

const JAEGER = process.env.JAEGER_URL ?? "http://localhost:16687";
const CHAIN = ["client.send", "gateway.forward", "owner.admit", "owner.commit", "owner.broadcast", "gateway.deliver", "peer.render"];

async function report(session: string, gateway: number, t: Telemetry): Promise<void> {
  const url = GATEWAYS[gateway]!.replace(/^ws/, "http").replace(/\/ws$/, "/api/telemetry");
  const r = await fetch(url, { method: "POST", headers: { Authorization: `Bearer ${session}`, "Content-Type": "application/json" }, body: JSON.stringify(t) });
  if (r.status !== 200) throw new Error(`telemetry: ${r.status} ${await r.text()}`);
}

interface JaegerSpan { operationName: string; startTime: number; duration: number; traceID: string }

async function trace(traceId: string): Promise<JaegerSpan[]> {
  const r = await fetch(`${JAEGER}/api/traces/${traceId}`);
  if (r.status !== 200) return [];
  const body = (await r.json()) as { data?: { spans: JaegerSpan[] }[] };
  return body.data?.[0]?.spans ?? [];
}

describe.skipIf(!process.env.TRACE_TEST)("traces (SPEC 9.5)", () => {
  it("follows one edit from the sender's click to the other client's screen", async () => {
    const seeded = seed(1);
    const [owner, editor] = seeded.users as [typeof seeded.users[0], typeof seeded.users[0]];
    const reports: Promise<void>[] = [];
    const a = await client(seeded.board, owner.session, 0, { telemetry: (t) => reports.push(report(owner.session, 0, t)) });
    const b = await client(seeded.board, editor.session, 1, { telemetry: (t) => reports.push(report(editor.session, 1, t)) });
    await a.c.until(() => a.c.status === "joined", 30_000);
    await b.c.until(() => b.c.status === "joined", 30_000);

    const list = a.c.addList("traced");
    const opId = a.c.pendingOps().at(-1)!.op_id;
    await b.c.until(() => b.c.view().lists.some((l) => l.id === list), 30_000);
    a.c.flushTelemetry();
    b.c.flushTelemetry();
    await Promise.all(reports);

    const traceId = opId.replace(/-/g, "");
    let spans: JaegerSpan[] = [];
    for (let i = 0; i < 60; i++) { // the services export in batches every few seconds
      spans = await trace(traceId);
      if (CHAIN.every((n) => spans.some((s) => s.operationName === n))) break;
      await sleep(1_000);
    }
    const names = new Set(spans.map((s) => s.operationName));
    expect([...names].sort(), `trace ${traceId}`).toEqual([...CHAIN].sort());
    expect(spans.every((s) => s.traceID === traceId)).toBe(true);
    const start = (n: string) => Math.min(...spans.filter((s) => s.operationName === n).map((s) => s.startTime));
    // Server spans share one clock: strictly in order. The client spans use the client's clock
    // corrected by the server's, so allow 20 ms either way (µs below).
    for (let i = 1; i < CHAIN.length; i++) {
      const slack = CHAIN[i - 1]!.startsWith("client") || CHAIN[i]!.startsWith("peer") ? 20_000 : 0;
      expect(start(CHAIN[i]!) + slack, `${CHAIN[i - 1]} starts before ${CHAIN[i]}`).toBeGreaterThanOrEqual(start(CHAIN[i - 1]!));
    }
    console.log(`TRACE ${traceId}: ${CHAIN.map((n) => `${n} +${((start(n) - start(CHAIN[0]!)) / 1000).toFixed(1)}ms`).join(", ")}`);
    a.c.close();
    b.c.close();
  }, 120_000);
});
