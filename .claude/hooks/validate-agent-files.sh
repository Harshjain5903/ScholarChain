#!/usr/bin/env bash
# validate-agent-files.sh -- Validates agent file frontmatter, required fields,
# unique names, executable hooks, and JSON syntax.
# This is a validation script, not a hook entry point. Run it manually or from CI.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
AGENTS_DIR="$REPO_ROOT/.claude/agents"
HOOKS_DIR="$REPO_ROOT/.claude/hooks"
ERRORS=0

echo "=== Agent file validation ==="

# Collect all agent names for uniqueness check
declare -a AGENT_NAMES=()

for f in "$AGENTS_DIR"/*.md; do
  [ -f "$f" ] || continue
  basename_f="$(basename "$f")"
  echo "--- Checking $basename_f ---"

  # Check frontmatter exists (starts with ---)
  first_line="$(head -1 "$f")"
  if [ "$first_line" != "---" ]; then
    echo "ERROR: $basename_f missing frontmatter (no opening ---)" >&2
    ERRORS=$((ERRORS + 1))
    continue
  fi

  # Extract frontmatter (between first and second ---)
  FRONTMATTER="$(sed -n '2,/^---$/p' "$f" | sed '$d')"

  # Check required fields: name, description, tools
  for field in name description tools; do
    if ! printf '%s\n' "$FRONTMATTER" | grep -q "^${field}:"; then
      echo "ERROR: $basename_f missing required field: $field" >&2
      ERRORS=$((ERRORS + 1))
    fi
  done

  # Extract name for uniqueness check
  NAME="$(printf '%s\n' "$FRONTMATTER" | grep '^name:' | sed 's/^name: *//' | tr -d '[:space:]')"
  if [ -n "$NAME" ]; then
    AGENT_NAMES+=("$NAME")
  fi

  # Check that code-reviewer has no write tools
  if [ "$NAME" = "code-reviewer" ]; then
    TOOLS="$(printf '%s\n' "$FRONTMATTER" | grep '^tools:' | sed 's/^tools: *//')"
    for forbidden in Write Edit NotebookEdit; do
      if printf '%s' "$TOOLS" | grep -qw "$forbidden"; then
        echo "ERROR: code-reviewer must not have $forbidden tool" >&2
        ERRORS=$((ERRORS + 1))
      fi
    done
  fi

  echo "  name=$NAME OK"
done

# Check uniqueness of agent names
echo ""
echo "=== Uniqueness check ==="
SORTED="$(printf '%s\n' "${AGENT_NAMES[@]}" | sort)"
DUPES="$(printf '%s\n' "${AGENT_NAMES[@]}" | sort | uniq -d)"
if [ -n "$DUPES" ]; then
  echo "ERROR: duplicate agent names: $DUPES" >&2
  ERRORS=$((ERRORS + 1))
else
  echo "All ${#AGENT_NAMES[@]} agent names are unique."
fi

# Check hook executability
echo ""
echo "=== Hook executable check ==="
for h in "$HOOKS_DIR"/*.sh; do
  [ -f "$h" ] || continue
  basename_h="$(basename "$h")"
  if [ -x "$h" ]; then
    echo "  $basename_h: executable OK"
  else
    echo "ERROR: $basename_h is not executable" >&2
    ERRORS=$((ERRORS + 1))
  fi
done

# Check JSON files
echo ""
echo "=== JSON syntax check ==="
for j in "$REPO_ROOT/.claude/settings.json" "$REPO_ROOT/.mcp.json"; do
  if [ -f "$j" ]; then
    basename_j="$(basename "$j")"
    if python3 -m json.tool "$j" >/dev/null 2>&1; then
      echo "  $basename_j: valid JSON"
    else
      echo "ERROR: $basename_j is not valid JSON" >&2
      ERRORS=$((ERRORS + 1))
    fi
  fi
done

echo ""
if [ "$ERRORS" -gt 0 ]; then
  echo "VALIDATION FAILED: $ERRORS error(s) found."
  exit 1
else
  echo "VALIDATION PASSED: all checks OK."
  exit 0
fi
