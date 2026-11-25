#!/usr/bin/env bash
set -euo pipefail

################################################################################
# plan.sh - Terraform plan for ONE environment, with state-key verification
#
# Usage: ./scripts/plan.sh <dev|prod>
#
# - Validates the environment name (allowlist dev, prod).
# - Initialises the backend with environments/<env>.backend.hcl
#   (-reconfigure) and refuses to continue unless the initialised state key is
#   scholarchain/<env>/terraform.tfstate (R1).
# - Requires the AWS CLI account/region to be readable and the CLI region to
#   equal the env's aws_region.
# - Plans with image_tag = the full Git commit SHA of HEAD (R14). Override with
#   IMAGE_TAG=<40-hex sha> if the images were built from another commit.
# - N6: refuses to plan unless the whole Git working tree is clean (tracked
#   and untracked files; the plan is built from infra/terraform and run by
#   scripts/, and deploy.sh requires the same clean HEAD), and unless
#   infra/terraform holds no gitignored file outside .terraform/ (a local
#   terraform.tfvars, *.auto.tfvars or *_override.tf would silently change
#   the plan). The clean HEAD is recorded as `commit` in the metadata.
# - N16: ALB_INGRESS_CIDRS (required) = comma-separated canonical IPv4 CIDRs
#   allowed to reach the ALB on HTTP/80, e.g.
#     ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh dev
#   0.0.0.0/0, ::/0, any /0, leading-zero octets and (N28) any prefix
#   shorter than /16 are refused here (no bypass); Terraform validates again.
#   Passed as -var alb_ingress_cidrs and recorded in the metadata. It is
#   deliberately not in the committed tfvars.
# - N30: every value this script gives Terraform is passed explicitly with
#   -var-file/-var; exported TF_VAR_* variables are refused (tf-env.sh).
# - N32: `terraform init` runs with -lockfile=readonly (tf-env.sh), and the
#   clean-tree check (N6) is repeated after init and again after plan; if the
#   tree changed, the plan file is deleted and no metadata is written.
# - BUDGET_ALERT_EMAIL (required) = address for the AWS Budgets 50/80/100%
#   notifications (USD 25/month). No default and never committed: it is passed
#   in a mode-600 mktemp var-file removed on exit (N75; never as -var on the
#   command line; sensitive in Terraform) and ONLY a short
#   sha256 fingerprint is printed or written to the plan metadata. Note the
#   saved plan file itself contains the value; it is gitignored (*.tfplan).
#     BUDGET_ALERT_EMAIL="you@example.com" ALB_INGRESS_CIDRS=... ./scripts/plan.sh dev
# - N7: PLAN_SCOPE=ecr-bootstrap plans ONLY the six ECR repositories and their
#   lifecycle policies (-target). Use it for the first deployment of an
#   environment: apply it, push images tagged <HEAD sha>, then run a full
#   plan. deploy.sh requires every image tag to exist before applying any
#   plan that changes anything other than ECR repositories/policies.
# - Writes the plan and a metadata file under local-runs/plans/<env>/
#   (gitignored). deploy.sh refuses a plan whose metadata does not match.
#
# Never applies.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: plan.sh <dev|prod>" >&2
  exit 2
fi
ENVIRONMENT="$1"
tf_env_validate_name "${ENVIRONMENT}"

BUDGET_VAR_DIR=""
tf_env_script_cleanup() {
  if [[ -n "${BUDGET_VAR_DIR}" && -d "${BUDGET_VAR_DIR}" ]]; then
    rm -rf "${BUDGET_VAR_DIR}"
  fi
}

TFVARS_FILE="$(tf_env_tfvars_file "${ENVIRONMENT}")"
PLAN_DIR="${REPO_ROOT}/local-runs/plans/${ENVIRONMENT}"

PLAN_SCOPE="${PLAN_SCOPE:-full}"
declare -a SCOPE_ARGS=()
case "${PLAN_SCOPE}" in
  full)
    PLAN_FILE="${PLAN_DIR}/${ENVIRONMENT}.tfplan"
    ;;
  ecr-bootstrap)
    PLAN_FILE="${PLAN_DIR}/${ENVIRONMENT}-ecr-bootstrap.tfplan"
    SCOPE_ARGS=(
      "-target=module.ecs.aws_ecr_repository.services"
      "-target=module.ecs.aws_ecr_repository.embedding_worker"
      "-target=module.ecs.aws_ecr_lifecycle_policy.services"
      "-target=module.ecs.aws_ecr_lifecycle_policy.embedding_worker"
    )
    # batch_compute = "ecs" also has a batch runner image repository.
    if [[ "$(tf_env_hcl_attr "${TFVARS_FILE}" batch_compute)" == "ecs" ]]; then
      SCOPE_ARGS+=(
        "-target=module.batch_fargate[0].aws_ecr_repository.runner"
        "-target=module.batch_fargate[0].aws_ecr_lifecycle_policy.runner"
      )
    fi
    ;;
  *)
    echo "ERROR: PLAN_SCOPE must be 'full' or 'ecr-bootstrap', got '${PLAN_SCOPE}'." >&2
    exit 2
    ;;
esac
META_FILE="${PLAN_FILE}.meta"

# N6/N32: require a clean tree at exactly <expected HEAD> (empty = any HEAD).
# <when> names the step for the error message. Returns 2 when not clean.
# N37: this function is always called as `require_clean_tree ... || exit 2`
# or `if ! require_clean_tree ...`, which suppresses set -e for its whole
# body. Every git call is therefore checked explicitly, and ANY git failure
# (or an unexpected result) is treated as NOT clean (return 2), never as
# clean. Exported GIT_DIR/GIT_WORK_TREE/... are refused: they would make
# `git -C <repo>` inspect another repository, worktree or index.
require_clean_tree() {
  local when="$1" expected_head="${2:-}"
  local dirty all_ignored ignored_tf head top repo_phys name bad=""
  # Only exported variables reach git; compgen -e is bash 3.2-safe.
  for name in $(compgen -e); do
    case "${name}" in
      GIT_DIR | GIT_WORK_TREE | GIT_INDEX_FILE | GIT_COMMON_DIR | GIT_OBJECT_DIRECTORY | GIT_ALTERNATE_OBJECT_DIRECTORIES | GIT_CONFIG_*)
        bad="${bad} ${name}"
        ;;
    esac
  done
  if [[ -n "${bad}" ]]; then
    echo "ERROR (${when}): refusing the clean-tree check with Git override variable(s) set:${bad}" >&2
    echo "  They make git inspect a different repository, worktree or index. Unset them and re-run." >&2
    return 2
  fi
  if ! repo_phys="$(cd "${REPO_ROOT}" && pwd -P)" || [[ -z "${repo_phys}" ]]; then
    echo "ERROR (${when}): cannot resolve ${REPO_ROOT}; treating the tree as NOT clean." >&2
    return 2
  fi
  if ! top="$(git -C "${REPO_ROOT}" rev-parse --show-toplevel)" || [[ -z "${top}" ]]; then
    echo "ERROR (${when}): 'git rev-parse --show-toplevel' failed; treating the tree as NOT clean." >&2
    return 2
  fi
  if [[ "${top}" != "${repo_phys}" ]]; then
    echo "ERROR (${when}): Git work tree is '${top}', expected '${repo_phys}'; treating the tree as NOT clean." >&2
    return 2
  fi
  if ! dirty="$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=all)"; then
    echo "ERROR (${when}): 'git status' failed; treating the tree as NOT clean." >&2
    return 2
  fi
  if [[ -n "${dirty}" ]]; then
    echo "ERROR (${when}): Git working tree is not clean; a plan must be built from a committed tree." >&2
    echo "${dirty}" | sed 's/^/    /' >&2
    echo "  Commit (or remove) these changes and re-run." >&2
    return 2
  fi
  # git and the filter run separately so that a git failure cannot be hidden
  # by the filter's "no lines left" status (grep -v exits 1 when it prints
  # nothing, which is the normal clean case).
  if ! all_ignored="$(git -C "${REPO_ROOT}" ls-files --others --ignored --exclude-standard --directory -- infra/terraform)"; then
    echo "ERROR (${when}): 'git ls-files' (ignored files under infra/terraform) failed; treating the tree as NOT clean." >&2
    return 2
  fi
  ignored_tf=""
  if [[ -n "${all_ignored}" ]]; then
    ignored_tf="$(printf '%s\n' "${all_ignored}" | grep -v -x 'infra/terraform/\.terraform/' || true)"
  fi
  if [[ -n "${ignored_tf}" ]]; then
    echo "ERROR (${when}): gitignored files exist under infra/terraform (outside .terraform/);" >&2
    echo "  Terraform could load them (e.g. *.auto.tfvars, *_override.tf) without Git seeing them:" >&2
    echo "${ignored_tf}" | sed 's/^/    /' >&2
    return 2
  fi
  if ! head="$(git -C "${REPO_ROOT}" rev-parse --verify HEAD)"; then
    echo "ERROR (${when}): 'git rev-parse HEAD' failed; treating the tree as NOT clean." >&2
    return 2
  fi
  if [[ ! "${head}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR (${when}): 'git rev-parse HEAD' returned '${head}', not a 40-hex commit SHA; treating the tree as NOT clean." >&2
    return 2
  fi
  if [[ -n "${expected_head}" && "${head}" != "${expected_head}" ]]; then
    echo "ERROR (${when}): HEAD moved from ${expected_head} to ${head} during the run." >&2
    return 2
  fi
  return 0
}

echo "=== STEP: Verify clean working tree (N6) ==="
require_clean_tree "before plan" || exit 2
if ! COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse --verify HEAD)" || [[ ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: could not read HEAD as a 40-hex commit SHA (got '${COMMIT_SHA:-}')." >&2
  exit 2
fi
echo "  Working tree clean at ${COMMIT_SHA}."

echo "=== STEP: Validate ALB ingress CIDRs (N16) ==="
# Canonical IPv4 CIDR, no leading zeros, prefix 1-32, host bits zero. The
# minimum prefix length (N28) is checked separately for a clearer message.
ALB_MIN_PREFIX=16
valid_ipv4_cidr() {
  local c="$1" o='(0|[1-9][0-9]{0,2})'
  local a b d e p ip mask
  [[ "${c}" =~ ^${o}\.${o}\.${o}\.${o}/([1-9]|[12][0-9]|3[0-2])$ ]] || return 1
  a="${BASH_REMATCH[1]}"
  b="${BASH_REMATCH[2]}"
  d="${BASH_REMATCH[3]}"
  e="${BASH_REMATCH[4]}"
  p="${BASH_REMATCH[5]}"
  ((a <= 255 && b <= 255 && d <= 255 && e <= 255)) || return 1
  ip=$(((a << 24) | (b << 16) | (d << 8) | e))
  mask=$(((0xFFFFFFFF << (32 - p)) & 0xFFFFFFFF))
  (((ip & ~mask & 0xFFFFFFFF) == 0)) || return 1
  return 0
}
if [[ -z "${ALB_INGRESS_CIDRS:-}" ]]; then
  echo "ERROR: ALB_INGRESS_CIDRS is not set (N16)." >&2
  echo "  The ALB accepts HTTP/80 only from operator CIDRs; none is committed to Git." >&2
  echo "  Example: ALB_INGRESS_CIDRS=\"203.0.113.10/32\" ./scripts/plan.sh ${ENVIRONMENT}" >&2
  exit 2
fi
CIDR_HCL=""
OLD_IFS="${IFS}"
IFS=','
set -f # no pathname expansion while splitting
# shellcheck disable=SC2206
CIDR_LIST=(${ALB_INGRESS_CIDRS})
set +f
IFS="${OLD_IFS}"
if [[ ${#CIDR_LIST[@]} -eq 0 ]]; then
  echo "ERROR: ALB_INGRESS_CIDRS='${ALB_INGRESS_CIDRS}' contains no CIDR." >&2
  exit 2
fi
for RAW in "${CIDR_LIST[@]}"; do
  C="$(echo "${RAW}" | tr -d '[:space:]')"
  if [[ "${C}" == "0.0.0.0/0" || "${C}" == "::/0" || "${C}" == */0 ]]; then
    echo "ERROR: ALB_INGRESS_CIDRS must not contain '${C}' (N16: no open ingress)." >&2
    exit 2
  fi
  if ! valid_ipv4_cidr "${C}"; then
    echo "ERROR: '${C}' is not a canonical IPv4 CIDR (e.g. 203.0.113.10/32, 198.51.100.0/24)." >&2
    exit 2
  fi
  # N28: valid_ipv4_cidr guarantees a decimal prefix 1-32 here.
  if ((10#${C##*/} < ALB_MIN_PREFIX)); then
    echo "ERROR: '${C}' is wider than /${ALB_MIN_PREFIX} (N28). Each ALB_INGRESS_CIDRS entry must be /${ALB_MIN_PREFIX}-/32;" >&2
    echo "  ranges such as 10.0.0.0/8 or 0.0.0.0/1,128.0.0.0/1 would open the ALB far beyond an" >&2
    echo "  operator allowlist. There is no bypass; list narrower operator CIDRs instead." >&2
    exit 2
  fi
  CIDR_HCL="${CIDR_HCL:+${CIDR_HCL},}\"${C}\""
done
CIDR_HCL="[${CIDR_HCL}]"
echo "  alb_ingress_cidrs = ${CIDR_HCL}"

# create_budget (tfvars): true in dev, false in prod (an account-wide budget
# must exist once, not once per environment). The email is only needed, and
# only passed to Terraform, when this environment creates the budget.
CREATE_BUDGET=false
if grep -E -q '^[[:space:]]*create_budget[[:space:]]*=[[:space:]]*true([[:space:]]|#|$)' "${TFVARS_FILE}"; then
  CREATE_BUDGET=true
fi
BUDGET_EMAIL_FPR="not-used"
if [[ "${CREATE_BUDGET}" == "true" ]]; then
  if [[ -z "${BUDGET_ALERT_EMAIL:-}" ]]; then
    echo "ERROR: BUDGET_ALERT_EMAIL is not set." >&2
    echo "  AWS Budgets (USD 25/month, 50/80/100% actual + 100% forecast) needs an address; none is committed to Git." >&2
    echo "  Example: BUDGET_ALERT_EMAIL=\"you@example.com\" ALB_INGRESS_CIDRS=... ./scripts/plan.sh ${ENVIRONMENT}" >&2
    exit 2
  fi
  if [[ ! "${BUDGET_ALERT_EMAIL}" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
    echo "ERROR: BUDGET_ALERT_EMAIL is not a valid email address (value not shown)." >&2
    exit 2
  fi
  BUDGET_EMAIL_FPR="$(printf '%s' "${BUDGET_ALERT_EMAIL}" | shasum -a 256 | cut -c1-12)"
  echo "  budget_alert_email = (set, sha256:${BUDGET_EMAIL_FPR})"
else
  echo "  create_budget = false: no budget in this environment; BUDGET_ALERT_EMAIL is not used."
fi

echo "=== STEP: Record context ==="
IMAGE_TAG="${IMAGE_TAG:-${COMMIT_SHA}}"
if [[ ! "${IMAGE_TAG}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: IMAGE_TAG must be a full 40-character lowercase Git SHA, got '${IMAGE_TAG}'." >&2
  exit 2
fi
echo "  Commit:    ${COMMIT_SHA}"
echo "  Image tag: ${IMAGE_TAG}"
echo "  Scope:     ${PLAN_SCOPE}"
tf_env_require_aws_target "${ENVIRONMENT}"

echo "=== STEP: terraform fmt -check ==="
cd "${TF_DIR}"
terraform fmt -check -recursive
echo "  Format check passed."

tf_env_init_backend "${ENVIRONMENT}"

echo "=== STEP: Re-verify clean working tree after init (N32) ==="
require_clean_tree "after terraform init" "${COMMIT_SHA}" || exit 2
echo "  Working tree still clean at ${COMMIT_SHA}."

echo "=== STEP: terraform validate ==="
terraform validate
echo "  Validation passed."

echo "=== STEP: terraform plan ==="
# N75: the address goes to Terraform through a mode-600 var-file in a private
# mktemp directory, never as -var on the command line (visible in ps and in
# shell history of wrapper tooling). tf_env_script_cleanup removes it on every
# exit path (EXIT, INT and TERM all reach it through tf-env.sh's traps).
declare -a BUDGET_ARGS=()
if [[ "${CREATE_BUDGET}" == "true" ]]; then
  BUDGET_VAR_DIR="$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/scholarchain-plan.XXXXXX")"
  BUDGET_VAR_FILE="${BUDGET_VAR_DIR}/budget.tfvars"
  (umask 077 && printf 'budget_alert_email = "%s"\n' "${BUDGET_ALERT_EMAIL}" >"${BUDGET_VAR_FILE}")
  chmod 600 "${BUDGET_VAR_FILE}"
  BUDGET_ARGS=("-var-file=${BUDGET_VAR_FILE}")
fi
mkdir -p "${PLAN_DIR}"
rm -f "${PLAN_FILE}" "${META_FILE}"
terraform plan \
  -var-file="${TFVARS_FILE}" \
  -var="image_tag=${IMAGE_TAG}" \
  -var="alb_ingress_cidrs=${CIDR_HCL}" \
  ${BUDGET_ARGS[@]+"${BUDGET_ARGS[@]}"} \
  ${SCOPE_ARGS[@]+"${SCOPE_ARGS[@]}"} \
  -out="${PLAN_FILE}" \
  -input=false

echo "=== STEP: Re-verify clean working tree after plan (N32) ==="
if ! require_clean_tree "after terraform plan" "${COMMIT_SHA}"; then
  rm -f "${PLAN_FILE}" "${META_FILE}"
  echo "ERROR: the tree changed while planning; ${PLAN_FILE} was deleted and no metadata was written." >&2
  exit 2
fi
echo "  Working tree still clean at ${COMMIT_SHA}."

echo "=== STEP: Record plan checksum and metadata ==="
PLAN_CHECKSUM="$(shasum -a 256 "${PLAN_FILE}" | awk '{print $1}')"
cat > "${META_FILE}" <<EOF
environment=${ENVIRONMENT}
state_bucket=${TF_ENV_STATE_BUCKET}
state_key=${TF_ENV_STATE_KEY}
aws_account=${TF_ENV_AWS_ACCOUNT}
aws_region=${TF_ENV_AWS_REGION}
commit=${COMMIT_SHA}
tree_clean=yes
plan_scope=${PLAN_SCOPE}
alb_ingress_cidrs=${CIDR_HCL}
budget_alert_email_sha256_prefix=${BUDGET_EMAIL_FPR}
image_tag=${IMAGE_TAG}
plan_sha256=${PLAN_CHECKSUM}
created_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

cat <<EOF

================================================================================
PLAN SUMMARY
  Environment: ${ENVIRONMENT}
  State:       s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
  Commit:      ${COMMIT_SHA}
  Image tag:   ${IMAGE_TAG}
  Scope:       ${PLAN_SCOPE}
  ALB ingress: ${CIDR_HCL} (HTTP/80 only)
  Account:     ${TF_ENV_AWS_ACCOUNT}
  Region:      ${TF_ENV_AWS_REGION}
  Plan file:   ${PLAN_FILE}
  Metadata:    ${META_FILE}
  Checksum:    ${PLAN_CHECKSUM}

  ** This script does NOT apply. A human reviews the plan, cost, account and
     region; deploy-engineer then runs deploy.sh ${ENVIRONMENT} ${PLAN_FILE} **
================================================================================
EOF

tf_env_exit 0
