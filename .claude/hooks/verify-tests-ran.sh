#!/usr/bin/env bash
# verify-tests-ran.sh -- Stop hook
# Prevents an agent from stopping when source files have been modified but
# the test marker (.claude/.last-test-run) is stale or missing.
#
# Behavior:
# - If no source files exist yet (Phase 1), exits 0 (nothing to test).
# - If no source files were modified in the working tree, exits 0.
# - If source files were modified and the test marker is newer, exits 0.
# - If source files were modified and the test marker is stale or missing,
#   exits 1 with an actionable error message.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
MARKER="$REPO_ROOT/.claude/.last-test-run"

# Gather source directories that might contain implementation code.
# If none of these directories exist, there is nothing to test.
SOURCE_DIRS=()
for d in modules/core modules/hadoop modules/entity-resolution modules/spark modules/storage modules/rag modules/services; do
  full="$REPO_ROOT/$d"
  if [ -d "$full" ]; then
    SOURCE_DIRS+=("$full")
  fi
done

if [ ${#SOURCE_DIRS[@]} -eq 0 ]; then
  # No source directories exist yet (e.g., Phase 1). Nothing to test.
  exit 0
fi

# Check whether any source file was modified (staged or unstaged) since
# the last commit. Use git status porcelain output.
CHANGED_SOURCES=""
for d in "${SOURCE_DIRS[@]}"; do
  rel="${d#"$REPO_ROOT/"}"
  changes="$(git -C "$REPO_ROOT" status --porcelain -- "$rel" 2>/dev/null || true)"
  if [ -n "$changes" ]; then
    CHANGED_SOURCES="$changes"
    break
  fi
done

if [ -z "$CHANGED_SOURCES" ]; then
  # No source changes in the working tree. Safe to stop.
  exit 0
fi

# Source files are modified. Check the test marker.
if [ ! -f "$MARKER" ]; then
  echo "STOP BLOCKED: source files have been modified but the test marker" >&2
  echo "($MARKER) does not exist. Run 'sbt check' before stopping." >&2
  exit 1
fi

# Find the newest modified source file and compare with the marker.
# Use -newer on macOS (POSIX compatible).
STALE="$(find "${SOURCE_DIRS[@]}" -name '*.scala' -newer "$MARKER" -print -quit 2>/dev/null || true)"

if [ -n "$STALE" ]; then
  echo "STOP BLOCKED: source file $STALE is newer than the test marker." >&2
  echo "Run 'sbt check' to update the test marker before stopping." >&2
  exit 1
fi

exit 0
