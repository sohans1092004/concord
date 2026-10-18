"""Writes grafana/dashboards/concord-overview.json: one panel per SPEC §9.5 metric.
Run after changing a panel:  python deploy/observability/make_dashboard.py"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "prometheus"}


def q(expr, legend):
    return {"datasource": DS, "expr": expr, "legendFormat": legend}


def ts(title, description, unit, *targets):
    return {"type": "timeseries", "title": title, "description": description, "datasource": DS,
            "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
            "targets": [dict(t, refId=chr(65 + i)) for i, t in enumerate(targets)]}


def quantiles(metric, by=""):
    grp = f" by (le{', ' + by if by else ''})"
    leg = f"{{{{{by}}}}} " if by else ""
    return [q(f"histogram_quantile({p}, sum(rate({metric}_bucket[1m])){grp})", f"{leg}p{int(p * 100)}") for p in (0.5, 0.95)]


panels = [
    ts("Sync latency (client-reported)", "Another client's edit, from its HLC time until applied here. Target: p95 < 200 ms (SPEC §10.1).",
       "ms", *quantiles("concord_gateway_sync_latency_ms")),
    ts("Commit latency", "The group commit's Postgres transaction, per batch.", "ms", *quantiles("concord_session_commit_ms")),
    ts("Group-commit batch size", "Ops per commit.", "short",
       q("sum(rate(concord_session_batch_size_sum[1m])) / sum(rate(concord_session_batch_size_count[1m]))", "mean ops per batch")),
    ts("Ops per second", "Committed ops, all boards and the ten busiest.", "ops",
       q("sum(rate(concord_session_committed_ops_total[1m]))", "all boards"),
       q("topk(10, sum by (board_id) (rate(concord_session_board_ops_total[1m])))", "{{board_id}}")),
    ts("Rejections by code", "Ops refused by the owner (SPEC §4.2).", "ops",
       q("sum by (code) (rate(concord_session_rejected_total[1m]))", "{{code}}"),
       q("sum(rate(concord_session_rejected_total[1m])) or vector(0)", "all codes")),
    ts("Duplicates and LWW ties", "Resends answered with the original ACK; last-writer-wins ties actually decided.", "ops",
       q("sum(rate(concord_session_duplicates_total[1m])) or vector(0)", "duplicates"),
       q("sum(rate(concord_session_lww_collisions_total[1m])) or vector(0)", "LWW ties decided")),
    ts("Ownership", "Claims and fence rejections (any fence rejection alerts).", "short",
       q("sum(increase(concord_session_claims_total[5m]))", "claims (5 min)"),
       q("sum(increase(concord_session_fence_rejections_total[5m]))", "fence rejections (5 min)"),
       q("sum(increase(concord_session_commit_failures_total[5m]))", "commit failures (5 min)")),
    ts("Version gaps", "Clients asking to resync after missing a broadcast (SYNC_REQUEST).", "ops",
       q("sum(rate(concord_session_sync_requests_total[1m]))", "gaps per second")),
    ts("Connections per gateway", "Open WebSocket connections, and how fast new ones open (a reconnect storm).", "short",
       q("concord_gateway_connections", "{{instance}}"),
       q("sum(rate(concord_gateway_connections_opened_total[1m]))", "opened per second")),
    ts("Snapshots", "Snapshot size and how long writing one takes.", "ms",
       *quantiles("concord_session_snapshot_ms"),
       q("histogram_quantile(0.95, sum(rate(concord_session_snapshot_bytes_bucket[5m])) by (le)) / 1000", "p95 size (KB)")),
    ts("Recovery time", "A claim: fence, lease, Postgres fence, snapshot + replay, announce.", "ms", *quantiles("concord_session_claim_ms")),
    ts("Boards loaded", "Per Session instance (idle boards unload after 10 minutes, amendment 26).", "short",
       q("concord_session_boards_loaded", "{{instance}}")),
]
for i, p in enumerate(panels):
    p["id"] = i + 1
    p["gridPos"] = {"x": (i % 2) * 12, "y": (i // 2) * 8, "w": 12, "h": 8}

dashboard = {"uid": "concord-overview", "title": "Concord overview", "tags": ["concord"], "timezone": "browser",
             "schemaVersion": 39, "version": 1, "refresh": "10s", "time": {"from": "now-30m", "to": "now"},
             "panels": panels, "templating": {"list": []}, "annotations": {"list": []}, "editable": True}
out = pathlib.Path(__file__).parent / "grafana" / "dashboards" / "concord-overview.json"
out.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(panels)} panels)")
