#!/bin/bash
# Blocks edits to Flyway migrations that are already committed: changing them breaks the checksum on deploy.
f=$(jq -r '.tool_input.file_path // empty')
case "$f" in
  */src/main/resources/db/*V*.sql) ;;
  *) exit 0 ;;
esac
cd "$CLAUDE_PROJECT_DIR" || exit 0
if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
  echo "Blocked: $f is an applied Flyway migration. Add a new V<next>__*.sql instead." >&2
  exit 2
fi
