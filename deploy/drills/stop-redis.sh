#!/usr/bin/env bash
# Drill: stop-redis (SPEC §8). Self-checking: fails unless every ACKed op is in board_ops and all
# clients converge to one state hash. Needs the stack: docker compose -f deploy/docker-compose.yml up -d
set -euo pipefail
cd "$(dirname "$0")/../../client-core"
DRILL=stop-redis npx vitest run test/drills --reporter=default 2>&1 | grep -E "DRILL |✓|×|FAIL|AssertionError|Error:|Tests "
exit "${PIPESTATUS[0]}"
