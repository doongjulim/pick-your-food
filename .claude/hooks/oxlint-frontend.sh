#!/bin/bash
# Lints an edited frontend file with oxlint and hands any problems back to Claude.
f=$(jq -r '.tool_input.file_path // empty')
case "$f" in
  */frontend/src/*.ts|*/frontend/src/*.tsx) ;;
  *) exit 0 ;;
esac
cd "$CLAUDE_PROJECT_DIR/frontend" || exit 0
out=$(node_modules/.bin/oxlint --deny-warnings "$f" 2>&1) || { echo "$out" >&2; exit 2; }
