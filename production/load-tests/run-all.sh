#!/usr/bin/env bash
# Runs every SPEC §10.1 measurement in turn against the dev compose stack and keeps each one's
# command and raw output in production/load-tests/raw/. Run it on an otherwise idle machine:
#   production/load-tests/run-all.sh [test ...]      # default: all, in this order
# Needs Docker, JDK 21 (JAVA_HOME), Node 22, and the stack built from this commit, without
# tracing: docker compose -f deploy/docker-compose.yml up -d --build
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../.."
compose=(docker compose -f "$root/deploy/docker-compose.yml")
mkdir -p "$here/raw"

sessions() { # restart the Session instances with a group-commit window, and wait until healthy
  CONCORD_BATCH_MS=$1 "${compose[@]}" up -d session1 session2 session3 >/dev/null 2>&1
  for port in 7001 7002 7003; do timeout 120 bash -c "until curl -fs localhost:$port/health >/dev/null; do sleep 1; done"; done
}

measure() { # measure <file> <env...>: one vitest run, recorded
  local out="$here/raw/$1.txt"; shift
  {
    echo "command: (cd client-core && $* npx vitest run test/load)"
    echo "commit: $(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet HEAD -- client-core server deploy || echo ' + uncommitted changes')"
    echo "started: $(date -u +%FT%TZ)"
    (cd "$root/client-core" && env "$@" npx vitest run test/load 2>&1) | sed "s/$(printf '\033')\[[0-9;]*m//g"
    echo "ended: $(date -u +%FT%TZ)"
  } > "$out"
  grep -h "^LOAD" "$out"
}

tests=("$@")
[ ${#tests[@]} -eq 0 ] && tests=(baseline editors batching inflight offline many-boards)
for t in "${tests[@]}"; do
  echo "== $t"
  case "$t" in
    batching)
      for ms in 0 5 15 30; do sessions "$ms"; measure "batching-${ms}ms" LOAD=batching CONCORD_BATCH_MS="$ms"; done
      sessions 15 ;;
    offline)
      for h in 8 24; do measure "offline-${h}h-accelerated" LOAD=offline OFFLINE_HOURS="$h"; done ;;
    *) measure "$t" LOAD="$t" ;;
  esac
done
