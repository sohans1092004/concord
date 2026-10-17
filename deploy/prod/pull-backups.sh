#!/bin/bash
# A second copy of the backups, on your laptop (Phase 4a step 5; needed if the bucket is in the
# same Oracle account as the VM). Run weekly, e.g. Windows Task Scheduler running Git Bash with
#   bash ~/concord/deploy/prod/pull-backups.sh
# Needs rclone on the laptop and the same REMOTE settings as the VM's .env, in
# ~/.concord-backup.env (only the RCLONE_CONFIG_REMOTE_* lines and BACKUP_REMOTE).
# Downloads the newest backup into ~/concord-backups, keeping the last 8. Backups stay
# encrypted; restore one with deploy/prod/restore.sh <your age key> <file>.
set -euo pipefail
set -a; . ~/.concord-backup.env; set +a
dest=~/concord-backups
mkdir -p "$dest"
latest=$(rclone lsf --include 'concord-*.tar.age' "$BACKUP_REMOTE" | sort | tail -1)
[ -n "$latest" ] || { echo "no backups in $BACKUP_REMOTE" >&2; exit 1; }
rclone copyto "$BACKUP_REMOTE/$latest" "$dest/$latest"
ls -1 "$dest"/concord-*.tar.age | sort | head -n -8 | xargs -r rm --
echo "have $(ls "$dest" | wc -l) backups in $dest; newest $latest"
