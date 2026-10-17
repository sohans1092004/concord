#!/bin/bash
# Delete one tester's data on request (Phase 4a step 8; what is removed: docs/trial-guide.md,
# "Privacy", and dev.concord.gateway.Accounts):
#   deploy/prod/delete-user.sh someone@example.com
set -euo pipefail
cd "$(dirname "$0")"
email=${1:?usage: delete-user.sh <email>}
read -r -p "Delete the account $email, the boards only they belong to, and their bug reports? Type the email again: " again
[ "$again" = "$email" ] || { echo "not confirmed" >&2; exit 1; }
docker compose exec -T gateway java -Xmx128m -cp '/app/gateway/lib/*' dev.concord.gateway.Accounts delete "$email"
