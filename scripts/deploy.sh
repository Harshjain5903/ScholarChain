#!/usr/bin/env bash
set -euo pipefail

################################################################################
# deploy.sh - Apply an approved Terraform plan for ONE environment
#
# Usage: ./scripts/deploy.sh <dev|prod> <plan-file>
# Example: ./scripts/deploy.sh dev local-runs/plans/dev/dev.tfplan
#
# Executed only by deploy-engineer after human approval. Refuses unless:
#   - the environment is in the allowlist (dev, prod);
#   - the git tree is clean and HEAD equals the commit the plan was made from;
#   - the plan file's checksum, environment, state key, account and region
#     equal the metadata plan.sh recorded;
#   - the backend re-initialises to scholarchain/<env>/terraform.tfstate (R1);
#   - the live AWS account/region equal the plan's;
#   - N7: BEFORE apply, the image tag recorded in the plan metadata exists in
#     every one of the six ECR repositories (read-only `aws ecr
#     describe-images`). The only exception is a plan whose changes are
#     limited to the ECR repositories and their lifecycle policies (the
#     PLAN_SCOPE=ecr-bootstrap plan from plan.sh), which creates the
#     repositories the images are then pushed to;
#   - the operator types <account>/<region>/<env> to confirm.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" || -z "${2:-}" ]]; then
  echo "Usage: deploy.sh <dev|prod> <plan-file>" >&2
  exit 2
fi
ENVIRONMENT="$1"
PLAN_FILE_ARG="$2"
tf_env_validate_name "${ENVIRONMENT}"

if [[ ! -f "${PLAN_FILE_ARG}" ]]; then
  echo "ERROR: Plan file not found: ${PLAN_FILE_ARG}" >&2
  exit 1
fi
PLAN_FILE="$(cd "$(dirname "${PLAN_FILE_ARG}")" && pwd)/$(basename "${PLAN_FILE_ARG}")"
META_FILE="${PLAN_FILE}.meta"

meta() {
  sed -n -E "s/^${1}=(.*)$/\\1/p" "${META_FILE}" | head -n 1
}

echo "=== STEP: Verify clean git tree ==="
# N37 (audit): a command substitution inside [[ ]] ignores git's exit status
# (and set -e does not apply there), so a failing `git status` printed nothing
# and was read as "clean". Each git call is now checked explicitly; any
# failure counts as NOT clean. Exported GIT_DIR/GIT_WORK_TREE/... are refused
# because they make `git -C <repo>` inspect another repository or index.
GIT_BAD=""
for GIT_VAR in $(compgen -e); do
  case "${GIT_VAR}" in
    GIT_DIR | GIT_WORK_TREE | GIT_INDEX_FILE | GIT_COMMON_DIR | GIT_OBJECT_DIRECTORY | GIT_ALTERNATE_OBJECT_DIRECTORIES | GIT_CONFIG_*)
      GIT_BAD="${GIT_BAD} ${GIT_VAR}"
      ;;
  esac
done
if [[ -n "${GIT_BAD}" ]]; then
  echo "ERROR: refusing the clean-tree check with Git override variable(s) set:${GIT_BAD}" >&2
  exit 1
fi
# N48: the git work tree must be exactly this repository's toplevel.
if ! REPO_PHYS="$(cd "${REPO_ROOT}" && pwd -P)" || [[ -z "${REPO_PHYS}" ]]; then
  echo "ERROR: cannot resolve ${REPO_ROOT}; treating the tree as NOT clean. Nothing was applied." >&2
  exit 1
fi
if ! GIT_TOP="$(git -C "${REPO_ROOT}" rev-parse --show-toplevel)" || [[ -z "${GIT_TOP}" ]]; then
  echo "ERROR: 'git rev-parse --show-toplevel' failed; treating the tree as NOT clean. Nothing was applied." >&2
  exit 1
fi
if [[ "${GIT_TOP}" != "${REPO_PHYS}" ]]; then
  echo "ERROR: Git work tree is '${GIT_TOP}', expected '${REPO_PHYS}'. Nothing was applied." >&2
  exit 1
fi
if ! GIT_DIRTY="$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=all)"; then
  echo "ERROR: 'git status' failed; treating the tree as NOT clean. Nothing was applied." >&2
  exit 1
fi
if [[ -n "${GIT_DIRTY}" ]]; then
  echo "ERROR: Git tree is dirty. Commit or stash changes before deploying." >&2
  exit 1
fi
if ! COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse --verify HEAD)" || [[ ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: could not read HEAD as a 40-hex commit SHA (got '${COMMIT_SHA:-}'). Nothing was applied." >&2
  exit 1
fi
echo "  Git tree is clean at ${COMMIT_SHA}."

echo "=== STEP: Verify plan file and metadata ==="
if [[ ! -f "${META_FILE}" ]]; then
  echo "ERROR: plan metadata not found: ${META_FILE} (create plans with plan.sh)." >&2
  exit 1
fi
PLAN_CHECKSUM="$(shasum -a 256 "${PLAN_FILE}" | awk '{print $1}')"
FAIL=0
check_meta() {
  local name="$1" want="$2" got
  got="$(meta "${name}")"
  if [[ "${got}" != "${want}" ]]; then
    echo "  MISMATCH ${name}: plan has '${got}', expected '${want}'" >&2
    FAIL=1
  fi
}
check_meta environment "${ENVIRONMENT}"
check_meta state_key "$(tf_env_expected_key "${ENVIRONMENT}")"
check_meta plan_sha256 "${PLAN_CHECKSUM}"
check_meta commit "${COMMIT_SHA}"
check_meta tree_clean "yes"
if [[ ${FAIL} -ne 0 ]]; then
  echo "ERROR: plan does not belong to this environment/commit, or was modified." >&2
  exit 1
fi
IMAGE_TAG="$(meta image_tag)"
echo "  Plan file:     ${PLAN_FILE}"
echo "  Plan checksum: ${PLAN_CHECKSUM}"
echo "  Image tag:     ${IMAGE_TAG}"

echo "=== STEP: Verify AWS target ==="
tf_env_require_aws_target "${ENVIRONMENT}"
check_meta aws_account "${TF_ENV_AWS_ACCOUNT}"
check_meta aws_region "${TF_ENV_AWS_REGION}"
if [[ ${FAIL} -ne 0 ]]; then
  echo "ERROR: current AWS account/region differ from the planned target." >&2
  exit 1
fi

tf_env_init_backend "${ENVIRONMENT}"

PREFIX="scholarchain-${ENVIRONMENT}"
CLUSTER="${PREFIX}-cluster"
IMAGE_SERVICES="query-api graph-retriever vector-retriever synthesis-worker verification-worker embedding-worker"

echo "=== STEP: Verify images exist before apply (N7) ==="
if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required to inspect the saved plan." >&2
  exit 1
fi
if [[ ! "${IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: plan metadata image_tag '${IMAGE_TAG}' is not a 40-hex Git SHA." >&2
  exit 1
fi
PLAN_JSON="$(cd "${TF_DIR}" && terraform show -json "${PLAN_FILE}")"
# Every changing resource that is not an ECR repository or lifecycle policy.
NON_ECR_CHANGES="$(printf '%s' "${PLAN_JSON}" | jq -r '
  .resource_changes[]?
  | select((.change.actions != ["no-op"]) and (.change.actions != ["read"]))
  | select(.address | test("^module\\.(ecs\\.|batch_fargate\\[0\\]\\.)aws_ecr_(repository|lifecycle_policy)\\.") | not)
  | .address')"
if [[ -z "${NON_ECR_CHANGES}" ]]; then
  echo "  Plan changes only ECR repositories/lifecycle policies (ECR bootstrap);"
  echo "  no task definition or service changes, so no image is required yet."
  echo "  Next: push images tagged ${IMAGE_TAG} to all six repositories, then run a full plan."
else
  MISSING=0
  for SVC in ${IMAGE_SERVICES}; do
    if DIGEST="$(aws ecr describe-images \
      --repository-name "${PREFIX}-${SVC}" \
      --image-ids "imageTag=${IMAGE_TAG}" \
      --query 'imageDetails[0].imageDigest' \
      --output text 2>/dev/null)" && [[ "${DIGEST}" == sha256:* ]]; then
      echo "  ${SVC}: ${IMAGE_TAG} present (${DIGEST})"
    else
      echo "  ${SVC}: MISSING ${PREFIX}-${SVC}:${IMAGE_TAG} (repository or tag not found)" >&2
      MISSING=1
    fi
  done
  if [[ ${MISSING} -ne 0 ]]; then
    echo "ERROR: one or more images tagged ${IMAGE_TAG} are not in ECR; nothing applied." >&2
    echo "  Services would roll to a tag that cannot be pulled. Push the images first." >&2
    echo "  First deployment of ${ENVIRONMENT}: PLAN_SCOPE=ecr-bootstrap ./scripts/plan.sh ${ENVIRONMENT}," >&2
    echo "  apply that plan with deploy.sh, push the images, then plan and deploy in full." >&2
    exit 1
  fi
fi

echo "=== STEP: Human confirmation ==="
EXPECTED_CONFIRM="${TF_ENV_AWS_ACCOUNT}/${TF_ENV_AWS_REGION}/${ENVIRONMENT}"
if [[ ! -t 0 ]]; then
  echo "ERROR: deploy requires an interactive terminal for confirmation." >&2
  exit 1
fi
read -r -p "  Type ${EXPECTED_CONFIRM} to apply this plan: " TYPED
if [[ "${TYPED}" != "${EXPECTED_CONFIRM}" ]]; then
  echo "ERROR: confirmation did not match. Nothing applied." >&2
  exit 1
fi

DEPLOY_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "=== STEP: terraform apply (saved plan) ==="
cd "${TF_DIR}"
terraform apply -input=false "${PLAN_FILE}"

if [[ -z "${NON_ECR_CHANGES}" ]]; then
  cat <<EOF

================================================================================
APPLIED: plan changed only ECR repositories/lifecycle policies (or nothing)
  Environment: ${ENVIRONMENT}
  Account:     ${TF_ENV_AWS_ACCOUNT}
  Region:      ${TF_ENV_AWS_REGION}
  Commit:      ${COMMIT_SHA}
  Timestamp:   ${DEPLOY_TS}
  Checksum:    ${PLAN_CHECKSUM}
  Next: push images tagged ${IMAGE_TAG}, then plan.sh + deploy.sh in full.
================================================================================
EOF
  tf_env_exit 0
fi

echo "=== STEP: Record image digests and task revisions ==="
RECORD_FAIL=0
for SVC in ${IMAGE_SERVICES}; do
  if DIGEST="$(aws ecr describe-images \
    --repository-name "${PREFIX}-${SVC}" \
    --image-ids "imageTag=${IMAGE_TAG}" \
    --query 'imageDetails[0].imageDigest' \
    --output text)"; then
    echo "  ${SVC}: tag=${IMAGE_TAG} digest=${DIGEST}"
  else
    echo "  ${SVC}: ERROR image ${IMAGE_TAG} not found in ${PREFIX}-${SVC}" >&2
    RECORD_FAIL=1
  fi
  if TASK_DEF="$(aws ecs describe-services \
    --cluster "${CLUSTER}" \
    --services "${PREFIX}-${SVC}" \
    --query 'services[0].taskDefinition' \
    --output text)"; then
    echo "  ${SVC}: taskDef=${TASK_DEF}"
  else
    echo "  ${SVC}: ERROR could not describe service" >&2
    RECORD_FAIL=1
  fi
done

echo "=== STEP: Run smoke test ==="
SMOKE_RC=0
"${SCRIPT_DIR}/smoke-test.sh" "${ENVIRONMENT}" || SMOKE_RC=$?

STATUS="DEPLOY COMPLETE"
if [[ ${RECORD_FAIL} -ne 0 || ${SMOKE_RC} -ne 0 ]]; then
  STATUS="DEPLOY APPLIED WITH FAILURES (evidence recording rc=${RECORD_FAIL}, smoke test rc=${SMOKE_RC})"
fi

cat <<EOF

================================================================================
${STATUS}
  Environment: ${ENVIRONMENT}
  State:       s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
  Commit:      ${COMMIT_SHA}
  Image tag:   ${IMAGE_TAG}
  Account:     ${TF_ENV_AWS_ACCOUNT}
  Region:      ${TF_ENV_AWS_REGION}
  Timestamp:   ${DEPLOY_TS}
  Checksum:    ${PLAN_CHECKSUM}
================================================================================
EOF

if [[ ${RECORD_FAIL} -ne 0 || ${SMOKE_RC} -ne 0 ]]; then
  exit 1
fi
tf_env_exit 0
