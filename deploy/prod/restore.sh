#!/bin/bash
# Restore a backup and verify it (Phase 4a step 5). Run from deploy/prod on any machine with
# Docker and .env present (the storage settings, and CONCORD_TAG: the images it pulls).
#
#   ./restore.sh <age-identity-file> [latest|<backup-file.tar.age>] [--into-prod]
#
# Default: the backup goes into a fresh, throwaway Postgres and is verified there (row counts,
# and every board's hash two ways, against the manifest made with the dump); nothing of the
# running stack is touched. Use this to prove a backup is good.
#
# --into-prod: replace the production database with the backup (a dead machine, a disaster).
# The gateway, the Session instances and the backup job are stopped first and started again
# after; the restored database is verified before anything starts.
set -euo pipefail
cd "$(dirname "$0")"
identity=${1:?usage: restore.sh <age-identity-file> [latest|<file>] [--into-prod]}
which=${2:-latest}
mode=${3:-}
[ -f "$identity" ] || { echo "no identity file $identity" >&2; exit 2; }
work=$(mktemp -d "$PWD/restore-work.XXXX")
chmod 777 "$work"
cleanup() { docker rm -f concord-restore-pg >/dev/null 2>&1 || true; docker network rm concord-restore >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT
bk() { docker compose run --rm --no-deps -T -v "$work:/work" backup "$@"; }

# 1. The backup file.
if [ "$which" = latest ]; then
  name=$(bk bash -c 'rclone lsf --include "concord-*.tar.age" "$BACKUP_REMOTE" | sort | tail -1' | tr -d '\r')
  [ -n "$name" ] || { echo "no backups in the bucket" >&2; exit 1; }
  echo "restoring $name (the latest in the bucket)"
  bk bash -c "rclone copyto \"\$BACKUP_REMOTE/$name\" /work/backup.tar.age"
else
  cp "$which" "$work/backup.tar.age"
  name=$(basename "$which")
fi

# 2. Decrypt and unpack: concord.dump and manifest.json.
# The key goes in on stdin, into a private file in the throwaway container: a mounted key file
# need not be readable by the container's user (age-keygen writes mode 600, and a key made by
# root, or by the VM's ubuntu user, is then unreadable to uid 10001: "permission denied").
bk bash -c 'set -o pipefail; umask 077; cat > /tmp/identity; age -d -i /tmp/identity /work/backup.tar.age | tar -C /work -xf -' < "$identity"
echo "manifest: $(head -c 300 "$work/manifest.json")..."

verify() { # $1: network, $2: JDBC URL, $3: user, $4: password
  docker run --rm --network "$1" -v "$work:/work" -e CONCORD_ENV=dev -e CONCORD_DB_URL="$2" -e CONCORD_DB_USER="$3" \
    -e CONCORD_DB_PASSWORD="$4" -e JAVA_OPTS=-Xmx256m "ghcr.io/sohans1092004/concord-server:$(grep ^CONCORD_TAG= .env | cut -d= -f2)" \
    java -cp '/app/session/lib/*' dev.concord.session.Backup verify /work/manifest.json
}

if [ "$mode" != "--into-prod" ]; then
  # 3. A fresh Postgres, the restore, the check.
  docker network create concord-restore >/dev/null
  docker run -d --name concord-restore-pg --network concord-restore -e POSTGRES_PASSWORD=restore -e POSTGRES_DB=concord \
    -v "$work:/work" postgres:16-alpine >/dev/null
  until docker exec concord-restore-pg pg_isready -U postgres -d concord >/dev/null 2>&1; do sleep 1; done
  sleep 2 # the image's init restarts the server once
  until docker exec concord-restore-pg pg_isready -U postgres -d concord >/dev/null 2>&1; do sleep 1; done
  start=$(date +%s)
  docker exec concord-restore-pg pg_restore -U postgres -d concord --no-owner /work/concord.dump
  echo "pg_restore took $(( $(date +%s) - start ))s"
  verify concord-restore jdbc:postgresql://concord-restore-pg:5432/concord postgres restore
  echo "RESTORE VERIFIED: $name"
  exit 0
fi

# --into-prod
read -r -p "This REPLACES the production database with $name. Type the board count from the manifest to go on: " answer
expected=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["boards"]))' "$work/manifest.json")
[ "$answer" = "$expected" ] || { echo "not confirmed" >&2; exit 1; }
docker compose stop gateway session1 session2 backup
docker compose up -d --wait postgres redis
docker compose exec -T postgres psql -U concord -d postgres -c "DROP DATABASE IF EXISTS concord WITH (FORCE)" -c "CREATE DATABASE concord OWNER concord"
docker compose cp "$work/concord.dump" postgres:/tmp/concord.dump
docker compose exec -T postgres pg_restore -U concord -d concord --no-owner /tmp/concord.dump
docker compose exec -T postgres rm /tmp/concord.dump
docker compose exec -T redis redis-cli FLUSHALL >/dev/null # leases and fences rebuild from Postgres (§5.2)
net=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(docker compose ps -q postgres)")
set -a; . ./.env; set +a
verify "$net" jdbc:postgresql://postgres:5432/concord concord "$POSTGRES_PASSWORD"
docker compose up -d --wait
echo "PRODUCTION RESTORED AND VERIFIED: $name"
