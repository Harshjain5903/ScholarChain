#!/usr/bin/env bash
# guard-dangerous.sh -- PreToolUse hook
# Blocks destructive AWS, Terraform, Git, and other dangerous commands.
# Reads JSON tool input from stdin. Exits 2 to block, 0 to allow.
set -euo pipefail

# Read the tool input JSON from stdin
INPUT="$(cat)"

# Extract the tool name and command from the JSON input.
# Use python3 for portability (avoid GNU-only jq flags).
TOOL_NAME="$(printf '%s' "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print(d.get('tool_name', ''))
" 2>/dev/null || echo "")"

# Only inspect Bash and shell-like tool calls
case "$TOOL_NAME" in
  Bash|bash|shell|terminal) ;;
  *) exit 0 ;;
esac

COMMAND="$(printf '%s' "$INPUT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
inp = d.get('tool_input', {})
print(inp.get('command', ''))
" 2>/dev/null || echo "")"

if [ -z "$COMMAND" ]; then
  exit 0
fi

# --- Terraform apply/destroy ---
if printf '%s' "$COMMAND" | grep -qE '(^|[;&|])\s*terraform\s+(apply|destroy)'; then
  echo "BLOCKED: terraform apply/destroy is forbidden. Use terraform plan and get human approval." >&2
  exit 2
fi

# --- Git force push ---
if printf '%s' "$COMMAND" | grep -qE 'git\s+push\s+.*--force|git\s+push\s+-f\b'; then
  echo "BLOCKED: git force push is forbidden." >&2
  exit 2
fi

# --- Read secrets / .env files ---
if printf '%s' "$COMMAND" | grep -qE '(cat|less|more|head|tail|vim|nano|code|open|source|\.)\s+[^ ]*(\.env|\.env\.[a-zA-Z]+|\.pem|\.key|id_rsa|id_ed25519|credentials\.json|\.aws/credentials|secrets\.ya?ml)'; then
  echo "BLOCKED: reading secret, .env, or private key files is forbidden." >&2
  exit 2
fi

# --- Destructive S3 commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+s3\s+rb\b|aws\s+s3\s+rm\s+.*--recursive|aws\s+s3api\s+delete-bucket'; then
  echo "BLOCKED: destructive S3 operations (bucket delete, recursive rm) are forbidden." >&2
  exit 2
fi

# --- Destructive DynamoDB commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+dynamodb\s+delete-table'; then
  echo "BLOCKED: DynamoDB table deletion is forbidden." >&2
  exit 2
fi

# --- Destructive Neptune commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+neptune\s+delete-db-cluster|aws\s+neptune\s+delete-db-instance'; then
  echo "BLOCKED: Neptune cluster/instance deletion is forbidden." >&2
  exit 2
fi

# --- Destructive S3 Vectors commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+s3vectors\s+delete-vector-bucket|aws\s+s3vectors\s+delete-vector-index'; then
  echo "BLOCKED: S3 Vectors bucket/index deletion is forbidden." >&2
  exit 2
fi

# --- Destructive ECR commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+ecr\s+delete-repository'; then
  echo "BLOCKED: ECR repository deletion is forbidden." >&2
  exit 2
fi

# --- Destructive KMS commands ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+kms\s+(schedule-key-deletion|disable-key)'; then
  echo "BLOCKED: KMS key deletion/disable is forbidden." >&2
  exit 2
fi

# --- EMR termination and large scale-out ---
if printf '%s' "$COMMAND" | grep -qE 'aws\s+emr\s+terminate-clusters'; then
  echo "BLOCKED: unreviewed EMR termination is forbidden. Get human approval." >&2
  exit 2
fi
if printf '%s' "$COMMAND" | grep -qE 'aws\s+emr\s+modify-instance-groups.*InstanceCount'; then
  echo "BLOCKED: EMR scale-out changes require human cost review." >&2
  exit 2
fi

# --- Constitutional file protection ---
# Block non-factory agents from editing constitutional files.
# The CLAUDE_AGENT environment variable is set by the agent runner.
AGENT="${CLAUDE_AGENT:-}"
if [ -n "$AGENT" ] && [ "$AGENT" != "factory-engineer" ]; then
  # Check if the command might write to constitutional paths
  if printf '%s' "$COMMAND" | grep -qE '(>\s*|tee\s+|cp\s+.*\s+)(\.claude/|CLAUDE\.md|docs/agents\.md|\.mcp\.json)'; then
    echo "BLOCKED: only factory-engineer may modify constitutional files. Current agent: $AGENT" >&2
    exit 2
  fi
fi

exit 0
