#!/bin/bash
# Runs once when Claude finishes a turn: feature boundaries, then a type check if the frontend changed.
# Exit 2 sends the problems back to Claude; stop_hook_active stops it from looping on the same failure.
[ "$(jq -r '.stop_hook_active')" = "true" ] && exit 0
cd "$CLAUDE_PROJECT_DIR" || exit 0
python3 .claude/hooks/check-feature-boundaries.py . || exit 2
if [ -n "$(git status --porcelain -- frontend/src)" ]; then
  out=$(cd frontend && npx tsc -b 2>&1) || { echo "$out" >&2; exit 2; }
fi
exit 0
