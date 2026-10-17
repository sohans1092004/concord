#!/bin/bash
# One backup (Phase 4a step 5): pg_dump and its manifest from one Postgres snapshot, encrypted
# with age to AGE_RECIPIENT (the private key is never on this machine), uploaded with rclone to
# BACKUP_REMOTE, then every backup older than BACKUP_KEEP_DAYS deleted. Run by schedule.sh, or
# by hand:  docker compose exec backup /app/backup/backup.sh
set -euo pipefail
case "$AGE_RECIPIENT$BACKUP_REMOTE" in *CHANGE_ME*) echo "AGE_RECIPIENT / BACKUP_REMOTE are not set in .env" >&2; exit 2 ;; esac
ts=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
start=$(date +%s)
java $JAVA_OPTS -cp '/app/session/lib/*' dev.concord.session.Backup dump "$work/concord.dump" "$work/manifest.json" >"$work/summary.json"
tar -C "$work" -cf - concord.dump manifest.json | age -r "$AGE_RECIPIENT" -o "$work/concord-$ts.tar.age"
bytes=$(stat -c %s "$work/concord-$ts.tar.age")
rclone mkdir "${BACKUP_REMOTE%%/*}" 2>/dev/null || true # creates the bucket if allowed and missing (the rehearsal's MinIO)
rclone copyto --s3-no-check-bucket "$work/concord-$ts.tar.age" "$BACKUP_REMOTE/concord-$ts.tar.age"
# --b2-hard-delete: B2 keeps old versions by default, so a plain delete would only hide them.
rclone delete --b2-hard-delete --min-age "${BACKUP_KEEP_DAYS}d" --include 'concord-*.tar.age' "$BACKUP_REMOTE"
kept=$(rclone lsf --include 'concord-*.tar.age' "$BACKUP_REMOTE" | wc -l)
printf '{"event":"backup_done","at":"%s","object":"concord-%s.tar.age","bytes":%s,"seconds":%s,"kept":%s,"summary":%s}\n' \
  "$(date -u +%FT%TZ)" "$ts" "$bytes" "$(( $(date +%s) - start ))" "$kept" "$(cat "$work/summary.json")" | tee /state/last-backup.json
# A heartbeat for an external check (healthchecks.io, free): it emails when one is missed.
[ -n "${BACKUP_PING_URL:-}" ] && curl -fsS -m 10 "$BACKUP_PING_URL" >/dev/null || true
