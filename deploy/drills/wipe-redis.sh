#!/usr/bin/env bash
# Drill: wipe-redis (SPEC §8). Self-checking: fails unless every ACKed op is in board_ops and all
# clients converge to one state hash. Needs the stack: docker compose -f deploy/docker-compose.yml up -d
set -euo pipefail
cd "$(dirname "$0")/../../client-core"
DRILL=wipe-redis npx vitest run test/drills --reporter=default 2>&1 | sed "s/$(printf '\033')\[[0-9;]*m//g" | grep -E "DRILL |✓|×|FAIL|AssertionError|Error:|Tests "
exit "${PIPESTATUS[0]}"
