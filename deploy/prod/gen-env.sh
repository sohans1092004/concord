#!/bin/bash
# Writes deploy/prod/.env for production (Phase 4a step 4): every secret freshly random, the
# rest from the arguments. Never committed (.gitignore). Refuses to overwrite an existing .env.
#
#   ./gen-env.sh <hostname> <email-for-lets-encrypt>
#   e.g. ./gen-env.sh concord-me.duckdns.org me@example.com
#
# Then edit the three CHANGE_ME groups: the age public key and the backup storage (docs/runbook.md).
set -euo pipefail
cd "$(dirname "$0")"
host=${1:?usage: gen-env.sh <hostname> <email>}
email=${2:?usage: gen-env.sh <hostname> <email>}
[ -e .env ] && { echo ".env exists; delete it first if you really mean to replace every secret" >&2; exit 1; }
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c "$1"; }
umask 077
cat > .env <<EOF
# Concord production settings. Generated $(date -u +%FT%TZ) by gen-env.sh. Never commit this file.
CONCORD_DOMAIN=$host
ACME_EMAIL=$email
# The version that runs: a full commit SHA whose images CI published (never "latest"). This is
# the commit you cloned; if docker compose pull says "manifest unknown", its CI run has not
# finished publishing yet: wait for it, or use the SHA of the latest green run. Deploys and
# rollbacks change it: deploy/prod/deploy.sh <sha>.
CONCORD_TAG=$(git rev-parse HEAD)
POSTGRES_PASSWORD=$(rand 32)
CONCORD_INTERNAL_SECRET=$(rand 48)
# Testers need this to sign up (docs/trial-guide.md). Share it only with them.
CONCORD_INVITE_CODE=$(rand 6)-$(rand 6)-$(rand 6)

# Backups (docs/runbook.md, "Backups"). The PUBLIC key from age-keygen on your laptop; the
# private key never goes on this machine.
AGE_RECIPIENT=CHANGE_ME
BACKUP_HOUR_UTC=3
# Optional: a healthchecks.io ping URL; it emails you if a nightly backup does not arrive.
BACKUP_PING_URL=

# Where backups go: an rclone remote named REMOTE, configured by these variables. Fill ONE of:
#
# (a) Backblaze B2:
#   RCLONE_CONFIG_REMOTE_TYPE=b2
#   RCLONE_CONFIG_REMOTE_ACCOUNT=<keyID>
#   RCLONE_CONFIG_REMOTE_KEY=<applicationKey>
#   BACKUP_REMOTE=REMOTE:<bucket>/concord
#
# (b) Oracle Object Storage (its S3-compatible API, with a "Customer Secret Key"):
#   RCLONE_CONFIG_REMOTE_TYPE=s3
#   RCLONE_CONFIG_REMOTE_PROVIDER=Other
#   RCLONE_CONFIG_REMOTE_ACCESS_KEY_ID=<access key>
#   RCLONE_CONFIG_REMOTE_SECRET_ACCESS_KEY=<secret key>
#   RCLONE_CONFIG_REMOTE_ENDPOINT=https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
#   RCLONE_CONFIG_REMOTE_REGION=<region, e.g. ap-mumbai-1>
#   BACKUP_REMOTE=REMOTE:<bucket>/concord
RCLONE_CONFIG_REMOTE_TYPE=CHANGE_ME
BACKUP_REMOTE=CHANGE_ME
EOF
echo "wrote $(pwd)/.env (mode 600). Next: set AGE_RECIPIENT and the backup storage in it."
echo "Invite code for testers: $(grep ^CONCORD_INVITE_CODE= .env | cut -d= -f2)"
