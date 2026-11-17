#!/usr/bin/env bash
# format-scala.sh -- PostToolUse hook
# Runs scalafmt on Scala files after writes. Exits 0 harmlessly when no
# Scala file changed or when scalafmt is not available.
set -euo pipefail

INPUT="$(cat)"

TOOL_NAME="$(printf '%s' "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tool_name', ''))
" 2>/dev/null || echo "")"

# Only act on Write or Edit tools
case "$TOOL_NAME" in
  Write|Edit|write|edit) ;;
  *) exit 0 ;;
esac

# Extract the file path
FILE_PATH="$(printf '%s' "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
path = inp.get('file_path', '')
print(path)
" 2>/dev/null || echo "")"

if [ -z "$FILE_PATH" ]; then
  exit 0
fi

# Check if it is a Scala file
case "$FILE_PATH" in
  *.scala|*.sc) ;;
  *) exit 0 ;;
esac

# Try to format with scalafmt if available
if command -v scalafmt >/dev/null 2>&1; then
  scalafmt --quiet "$FILE_PATH" 2>/dev/null || true
elif command -v sbt >/dev/null 2>&1; then
  # Attempt sbt scalafmtOnly if scalafmt binary is not installed
  # This is a best-effort; silently skip if it fails
  sbt "scalafmtOnly $FILE_PATH" 2>/dev/null || true
fi

# Always exit success -- formatting is best-effort when tools are not installed
exit 0
