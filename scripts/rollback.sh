#!/usr/bin/env bash
set -euo pipefail

################################################################################
# rollback.sh - Roll back ECS services to the previous task definition revision
#
# Usage: ./scripts/rollback.sh <dev|prod>
#
# Before touching anything it validates the environment name (dev, prod),
# re-initialises the Terraform backend for that environment and refuses unless
# the state key is scholarchain/<env>/terraform.tfstate (R1), checks that the
# state's cluster output names this environment's cluster, and requires the
# AWS CLI region to equal the env's aws_region.
#
# Bounded timeout: 300 seconds per service. Any failed or timed-out service
# makes the script exit non-zero with a summary.
#
# Note: update-service changes the running task definition outside Terraform.
# The next plan.sh for this env will show the difference; re-plan from the
# intended commit afterwards.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: rollback.sh <dev|prod>" >&2
  exit 2
fi
ENVIRONMENT="$1"
tf_env_validate_name "${ENVIRONMENT}"

PREFIX="scholarchain-${ENVIRONMENT}"
CLUSTER="${PREFIX}-cluster"
TIMEOUT_SECONDS=300
SERVICES=(query-api graph-retriever vector-retriever synthesis-worker verification-worker)
ROLLBACK_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "=== STEP: Verify target ==="
tf_env_require_aws_target "${ENVIRONMENT}"
tf_env_init_backend "${ENVIRONMENT}"
STATE_CLUSTER="$(cd "${TF_DIR}" && terraform output -raw ecs_cluster_name)"
if [[ "${STATE_CLUSTER}" != "${CLUSTER}" ]]; then
  echo "ERROR: state for ${ENVIRONMENT} reports cluster '${STATE_CLUSTER}', expected '${CLUSTER}'." >&2
  exit 3
fi

echo "=== STEP: Record rollback context ==="
echo "  Environment: ${ENVIRONMENT}"
echo "  Cluster:     ${CLUSTER}"
echo "  Timestamp:   ${ROLLBACK_TS}"

declare -a RESULTS=()
FAILED=0

for SVC in "${SERVICES[@]}"; do
  SVC_NAME="${PREFIX}-${SVC}"

  echo "=== STEP: Rollback ${SVC_NAME} ==="

  if ! CURRENT_TASK_DEF="$(aws ecs describe-services \
    --cluster "${CLUSTER}" \
    --services "${SVC_NAME}" \
    --query 'services[0].taskDefinition' \
    --output text)"; then
    RESULTS+=("${SVC_NAME}: FAILED (describe-services)")
    FAILED=1
    continue
  fi

  echo "  Current task definition: ${CURRENT_TASK_DEF}"

  FAMILY="$(echo "${CURRENT_TASK_DEF}" | sed 's/:/ /' | awk -F'/' '{print $NF}' | awk '{print $1}')"
  CURRENT_REV="$(echo "${CURRENT_TASK_DEF}" | awk -F':' '{print $NF}')"
  if [[ ! "${CURRENT_REV}" =~ ^[0-9]+$ ]]; then
    RESULTS+=("${SVC_NAME}: FAILED (unparseable task definition '${CURRENT_TASK_DEF}')")
    FAILED=1
    continue
  fi
  PREV_REV=$((10#${CURRENT_REV} - 1)) # N25: force base 10

  if [[ "${PREV_REV}" -lt 1 ]]; then
    echo "  No previous revision for ${SVC_NAME}. Skipping."
    RESULTS+=("${SVC_NAME}: SKIPPED (no previous revision)")
    continue
  fi

  PREV_TASK_DEF="${FAMILY}:${PREV_REV}"
  echo "  Rolling back to: ${PREV_TASK_DEF}"

  if ! aws ecs update-service \
    --cluster "${CLUSTER}" \
    --service "${SVC_NAME}" \
    --task-definition "${PREV_TASK_DEF}" \
    --query 'service.serviceName' \
    --output text; then
    RESULTS+=("${SVC_NAME}: FAILED (update-service)")
    FAILED=1
    continue
  fi

  echo "  Waiting for service stability (timeout: ${TIMEOUT_SECONDS}s)..."
  SECONDS=0
  STABLE=0
  while [[ ${SECONDS} -lt ${TIMEOUT_SECONDS} ]]; do
    STATUS="$(aws ecs describe-services \
      --cluster "${CLUSTER}" \
      --services "${SVC_NAME}" \
      --query 'services[0].deployments | length(@)' \
      --output text || echo "error")"
    if [[ "${STATUS}" == "1" ]]; then
      STABLE=1
      break
    fi
    sleep 10
  done
  if [[ ${STABLE} -eq 1 ]]; then
    echo "  Service ${SVC_NAME} stable after ${SECONDS}s."
    RESULTS+=("${SVC_NAME}: ROLLED BACK to ${PREV_TASK_DEF}")
  else
    echo "  ERROR: Timeout waiting for ${SVC_NAME} to stabilize." >&2
    RESULTS+=("${SVC_NAME}: FAILED (not stable after ${TIMEOUT_SECONDS}s)")
    FAILED=1
  fi
done

echo
echo "================================================================================"
if [[ ${FAILED} -eq 0 ]]; then
  echo "ROLLBACK EVIDENCE"
else
  echo "ROLLBACK INCOMPLETE - ONE OR MORE SERVICES FAILED"
fi
echo "  Environment: ${ENVIRONMENT}"
echo "  Account:     ${TF_ENV_AWS_ACCOUNT}"
echo "  Region:      ${TF_ENV_AWS_REGION}"
echo "  State:       s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}"
echo "  Cluster:     ${CLUSTER}"
echo "  Timestamp:   ${ROLLBACK_TS}"
for R in "${RESULTS[@]}"; do
  echo "  - ${R}"
done
echo "================================================================================"

tf_env_exit "${FAILED}"
