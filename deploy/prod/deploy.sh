#!/bin/bash
# Deploy a new version during the trial (Phase 4a step 6), without losing an edit:
#   cd ~/concord && git pull && deploy/prod/deploy.sh
#
# 1. Build the new images (the old containers keep serving meanwhile). The Java services are
#    always replaced (--force-recreate), even when unchanged, so every deploy takes the same path.
# 2. Take a backup now, and stop if it fails.
# 3. Migrations: forward-only. Migrate applies only schema versions above the database's; a
#    migration must keep the previous code working (add, never rename or drop), since the old
#    Session instances run against the new schema until they are replaced.
# 4. Replace session1, then session2, one at a time: on SIGTERM an instance stops taking ops,
#    flushes, snapshots and releases its leases (SPEC §2.4), and the other instance claims its
#    boards. Clients' in-flight ops are retried from their queues.
# 5. Replace the gateway: on SIGTERM it sends RECONNECT_ELSEWHERE to every client, which
#    reconnects (with backoff) to the new one and resends its queue (SPEC §8 row 15).
set -euo pipefail
cd "$(dirname "$0")"
step() { echo "== $(date -u +%T) $*"; }

step "build"
docker compose build

step "backup before deploying"
docker compose up -d --wait backup postgres
docker compose exec -T backup /app/backup/backup.sh

step "migrate (forward-only)"
docker compose run --rm migrate

for s in session1 session2; do
  step "replace $s"
  docker compose up -d --no-deps --wait --force-recreate "$s"
  # --wait returns once its healthcheck passes; give the other instance its boards back first.
  sleep 5
done

step "replace gateway"
docker compose up -d --no-deps --wait --force-recreate gateway

step "the rest (caddy, redis: only if their config changed)"
docker compose up -d --wait

step "done"
docker compose ps --format 'table {{.Service}}\t{{.Status}}'
curl -fsS "https://$(grep ^CONCORD_DOMAIN= .env | cut -d= -f2)/healthz" && echo " <- public /healthz"
