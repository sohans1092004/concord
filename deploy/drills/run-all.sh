#!/usr/bin/env bash
# Runs every Phase 2 drill against a fresh stack and stops at the first failure.
#   deploy/drills/run-all.sh
# Needs Docker, JDK 21 (JAVA_HOME) and Node 22. Prints one DRILL {...} line per drill.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../.."
(cd "$root/server" && ./gradlew -q :devtools:installDist)
(cd "$root/client-core" && npm ci --no-audit --no-fund >/dev/null)
docker compose -f "$root/deploy/docker-compose.yml" up -d --build
for port in 7001 7002 7003 8081 8082; do
  until curl -fs "localhost:$port/health" >/dev/null; do sleep 1; done
done
for d in kill-owner pause-owner stop-postgres stop-redis wipe-redis; do
  echo "== $d"
  "$here/$d.sh"
done
echo "all drills passed"
