#!/bin/bash
# The daily summary (Phase 4a step 7): the last 24 hours at a glance.
#   deploy/prod/summary.sh            # print it
#   crontab: 0 7 * * * ~/concord/deploy/prod/summary.sh >> ~/concord-summary.log 2>&1
set -uo pipefail
cd "$(dirname "$0")"
since=24h
echo "== Concord summary, $(date -u +%FT%TZ), last $since"

echo "-- containers"
docker compose ps --format 'table {{.Service}}\t{{.Status}}'

echo "-- memory and CPU now"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'
for s in gateway session1 session2; do
  printf '%-9s heap: ' "$s"
  docker compose exec -T "$s" jcmd 1 GC.heap_info 2>/dev/null \
    | sed -nE 's/^ *(def new generation|tenured generation|garbage-first heap) +total ([0-9]+K), used ([0-9]+K).*/\1: total \2, used \3/p' \
    | sed -e 's/^def new generation/young/' -e 's/^tenured generation/old/' -e 's/^garbage-first heap/heap/' \
    | paste -sd ';' - | sed 's/;/;  /g' || echo "?"
done
free -m | sed -n 1,2p

echo "-- disk"
df -h / | tail -1

echo "-- errors and warnings in the logs"
for s in gateway session1 session2 backup; do
  e=$(docker compose logs --since "$since" --no-color "$s" 2>/dev/null | grep -c '"level":"ERROR"')
  w=$(docker compose logs --since "$since" --no-color "$s" 2>/dev/null | grep -c '"level":"WARN"')
  printf '%-9s errors %s, warnings %s\n' "$s" "$e" "$w"
done
echo "fence rejections (stale owners refused): $(docker compose logs --since "$since" --no-color session1 session2 2>/dev/null | grep -c 'fenced out by a newer owner')"
echo "commit failures: $(docker compose logs --since "$since" --no-color session1 session2 2>/dev/null | grep -c 'commit failed')"
echo "slow consumers cut: $(docker compose logs --since "$since" --no-color gateway 2>/dev/null | grep -c 'slow consumer')"

echo "-- metrics since each service started (SPEC 9.5; /metrics/prometheus, inside the network only)"
# p50/p95 from a Prometheus histogram's cumulative buckets: the first bucket bound reaching the share.
quantiles() {
  awk -v m="$1" '$1 ~ "^"m"_bucket" { split($1, a, "\""); le[n] = a[2]; c[n++] = $2 } $1 == m"_count" { t = $2 }
    END { if (!t) { print "no samples"; exit } for (i = 0; i < n; i++) { if (!p50 && c[i] >= 0.5 * t) p50 = le[i]; if (!p95 && c[i] >= 0.95 * t) p95 = le[i] }
          printf "%d samples, p50 <= %s ms, p95 <= %s ms\n", t, p50, p95 }'
}
g=$(docker compose exec -T gateway curl -fs localhost:8080/metrics/prometheus 2>/dev/null)
echo "sync latency (client-reported): $(printf '%s\n' "$g" | quantiles concord_gateway_sync_latency_ms)"
printf '%s\n' "$g" | grep -E '^concord_gateway_(connections|connections_opened_total|slow_consumer_closes_total) ' | sed 's/^concord_gateway_/  /'
for s in session1 session2; do
  m=$(docker compose exec -T "$s" curl -fs localhost:7000/metrics/prometheus 2>/dev/null)
  echo "$s: commit $(printf '%s\n' "$m" | quantiles concord_session_commit_ms)"
  printf '%s\n' "$m" | grep -E '^concord_session_(committed_ops|duplicates|claims|fence_rejections|commit_failures|sync_requests|idle_unloads|boards_deleted)_total |^concord_session_(rejected_total|boards_loaded)' | sed 's/^concord_session_/  /'
done

echo "-- people and data"
docker compose exec -T postgres psql -U concord -d concord -At -F ' ' -c "
  SELECT 'users', count(*) FROM users UNION ALL
  SELECT 'boards', count(*) FROM boards UNION ALL
  SELECT 'ops in the last 24h', count(*) FROM board_ops WHERE committed_at > now() - interval '24 hours' UNION ALL
  SELECT 'sign-ins in the last 24h', count(*) FROM sessions WHERE created_at > now() - interval '24 hours' UNION ALL
  SELECT 'bug reports in the last 24h', count(*) FROM bug_reports WHERE created_at > now() - interval '24 hours' UNION ALL
  SELECT 'unsent edits in those reports', coalesce(sum(jsonb_array_length(coalesce(diagnostics->'unsent_edits', '[]'::jsonb))), 0)
    FROM bug_reports WHERE created_at > now() - interval '24 hours'"

echo "-- last backup"
docker compose exec -T backup cat /state/last-backup.json 2>/dev/null || echo "none yet"
