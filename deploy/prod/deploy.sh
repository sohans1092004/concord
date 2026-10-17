#!/bin/bash
# Deploy a published version, or roll back to one, without losing an edit (Phase 4a step 6):
#   deploy/prod/deploy.sh <full commit SHA>      # CI published its images (ci.yml, "publish")
# Nothing is built here: the VM only pulls.
#
# 1. Take a backup now, with the running version, and stop if it fails.
# 2. Pull the new version's images. If they do not exist, stop: .env still names the old one.
# 3. Migrations: forward-only. Migrate applies only schema versions above the database's; a
#    migration must keep the previous code working (add, never rename or drop), since the old
#    Session instances run against the new schema until they are replaced, and so that a
#    rollback to the previous version still works.
# 4. Replace session1, then session2, one at a time: on SIGTERM an instance stops taking ops,
#    flushes, snapshots and releases its leases (SPEC §2.4), and the other instance claims its
#    boards. Clients' in-flight ops are retried from their queues.
# 5. Replace the gateway: on SIGTERM it sends RECONNECT_ELSEWHERE to every client, which
#    reconnects (with backoff) to the new one and resends its queue (SPEC §8 row 15).
# 6. The rest (the backup job; Caddy only if its config changed).
# Rollback is the same command with the previous SHA, which this script prints at the end.
set -euo pipefail
cd "$(dirname "$0")"
step() { echo "== $(date -u +%T) $*"; }
tag=${1:?usage: deploy.sh <full commit SHA>   (the running one: $(grep ^CONCORD_TAG= .env | cut -d= -f2))}
[[ "$tag" =~ ^[0-9a-f]{40}$ ]] || { echo "the tag must be a full 40-character commit SHA, never \"latest\": $tag" >&2; exit 2; }
prev=$(grep ^CONCORD_TAG= .env | cut -d= -f2)

step "backup before deploying (running version ${prev:0:12})"
docker compose exec -T backup /app/backup/backup.sh

step "pull ${tag:0:12}"
CONCORD_TAG=$tag docker compose pull --quiet
sed -i "s/^CONCORD_TAG=.*/CONCORD_TAG=$tag/" .env # from here on, compose runs the new version

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

step "the rest (backup; caddy and redis only if their config changed)"
docker compose up -d --wait

step "done: running ${tag:0:12}"
docker compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'
curl -fsS "https://$(grep ^CONCORD_DOMAIN= .env | cut -d= -f2)/healthz" && echo " <- public /healthz"
echo "roll back with: deploy/prod/deploy.sh $prev"
