#!/bin/bash
# The backup container's main loop: one backup a day at BACKUP_HOUR_UTC:15, forever.
# A failed backup is logged and retried at the next run; /state/last-backup.json says how the
# last one went (read by deploy/prod/summary.sh).
set -u
while true; do
  now=$(date -u +%s)
  next=$(date -u -d "today ${BACKUP_HOUR_UTC}:15" +%s)
  [ "$next" -le "$now" ] && next=$(date -u -d "tomorrow ${BACKUP_HOUR_UTC}:15" +%s)
  echo "{\"event\":\"backup_scheduled\",\"at\":\"$(date -u -d @"$next" +%FT%TZ)\"}"
  sleep $((next - now))
  /app/backup/backup.sh || echo "{\"event\":\"backup_failed\",\"exit\":$?}"
done
