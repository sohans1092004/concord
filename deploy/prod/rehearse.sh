#!/bin/bash
# The Phase 4a rehearsal on this machine: the production stack, public through a Cloudflare
# quick tunnel, backups to a local MinIO bucket.
#   deploy/prod/rehearse.sh up       # build, start, print the public URL and the invite code
#   deploy/prod/rehearse.sh down     # stop (keeps the data); "down -v" also deletes it
#   deploy/prod/rehearse.sh <compose args>   # e.g.  rehearse.sh logs gateway
#   deploy/prod/rehearse.sh ./restore.sh ~/.concord-rehearsal-age.key latest   # any script here
# The age key for the rehearsal's backups is ~/.concord-rehearsal-age.key (not the real one).
set -euo pipefail
cd "$(dirname "$0")"
export COMPOSE_PROJECT_NAME=concord-rehearsal COMPOSE_PATH_SEPARATOR=: COMPOSE_FILE=compose.yaml:compose.rehearsal.yaml
dc() { docker compose "$@"; }
key=~/.concord-rehearsal-age.key
setenv() { # KEY VALUE: replace or add a line in .env
  if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else echo "$1=$2" >> .env; fi
}

case "${1:-}" in
  up)
    [ -f .env ] || ./gen-env.sh pending.trycloudflare.com rehearsal@example.com
    pass=$(grep '^MINIO_ROOT_PASSWORD=' .env | cut -d= -f2 || true)
    [ -n "$pass" ] || pass=$(openssl rand -hex 16)
    setenv MINIO_ROOT_PASSWORD "$pass"
    dc pull
    if [ ! -f "$key" ]; then
      docker run --rm --entrypoint age-keygen "ghcr.io/sohans1092004/concord-backup:$(grep ^CONCORD_TAG= .env | cut -d= -f2)" > "$key"
      chmod 600 "$key"
    fi
    setenv AGE_RECIPIENT "$(grep -o 'age1[0-9a-z]*' "$key")"
    setenv RCLONE_CONFIG_REMOTE_TYPE s3
    setenv RCLONE_CONFIG_REMOTE_PROVIDER Other
    setenv RCLONE_CONFIG_REMOTE_ACCESS_KEY_ID rehearsal
    setenv RCLONE_CONFIG_REMOTE_SECRET_ACCESS_KEY "$pass"
    setenv RCLONE_CONFIG_REMOTE_ENDPOINT http://minio:9000
    setenv BACKUP_REMOTE REMOTE:concord-backups/concord
    # The tunnel first: its random hostname is the public origin the gateway must know.
    dc up -d tunnel
    url=""
    for _ in $(seq 1 60); do
      url=$(dc logs tunnel 2>&1 | grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' | tail -1 || true)
      [ -n "$url" ] && break
      sleep 2
    done
    [ -n "$url" ] || { echo "no tunnel URL after 2 minutes" >&2; exit 1; }
    setenv CONCORD_DOMAIN "${url#https://}"
    dc up -d --wait
    echo "Concord rehearsal is live at $url"
    echo "invite code: $(grep ^CONCORD_INVITE_CODE= .env | cut -d= -f2)"
    ;;
  down) shift; dc down "$@" ;;
  ./*.sh) exec "$@" ;; # restore.sh, deploy.sh, ... against the rehearsal stack
  *) dc "$@" ;;
esac
