#!/usr/bin/env bash
set -euo pipefail

################################################################################
# cleanup.sh - Destroy ONE ScholarChain environment after evidence is archived
#
# Usage: CONFIRM_CLEANUP=yes ./scripts/cleanup.sh <dev|prod>
#
# Run only by a human (or deploy-engineer at a human's direction) after the
# human architect approves destruction (docs/agents.md escalation rules).
#
# Order of operations (N27: the destroy plan is built and fully checked
# BEFORE anything is deleted). Each phase is tracked; any failure stops
# before the destroy (phases 1-9) or is reported (phases 9-10), and the
# script exits non-zero with a summary whose title says exactly what was and
# was not changed. Phases 1-6 change nothing in AWS. "CLEANUP COMPLETE" is
# printed only if every phase succeeded.
#   1 preflight      allowlisted env, CONFIRM_CLEANUP=yes, interactive TTY, jq
#   2 target         AWS account (read-only sts get-caller-identity), region
#                    (tfvars == CLI), backend re-initialised for <env> and the
#                    state key verified as scholarchain/<env>/terraform.tfstate,
#                    state outputs verified to belong to <env>
#   3 evidence       the newest local-runs/evidence/<env>/*/ARCHIVE_COMPLETE
#                    from scripts/archive-evidence.sh matches account, region,
#                    env and state key, is recent, SHA256SUMS verifies, and it
#                    holds all five DynamoDB exports and the Neptune audit log
#   4 inventory      read-only pre-cleanup inventory and blockers (Neptune
#                    deletion protection; prod final snapshot id already in
#                    use, N13)
#   5 destroy plan   `terraform plan -destroy -out` over every state address
#                    EXCEPT the retained ones below; the plan is shown, checked
#                    to contain deletes only and none of the retained
#                    addresses, saved, and its sha256 recorded (<plan>.sha256)
#   6 archive (N9)   lists every bucket (raw, curated, graph, rag, artifacts,
#                    logs) with its object-version and delete-marker counts
#                    and every ECR repository with its image count; writes
#                    full inventories (key, version id, size, etag; image
#                    digests and tags) and a verified copy of the current
#                    objects of the logs and artifacts buckets into
#                    <archive>/cleanup-empty-<UTC>/ with its own SHA256SUMS.
#                    raw/curated/graph/rag contents are NOT copied (inventory
#                    only).
#   7 empty (N9)     prints an explicit data-loss warning, then ONLY after the
#                    typed confirmation `EMPTY <account>/<region>/<env>` (the
#                    operator's typed value records the human data-loss
#                    decision in empty-confirmation.txt) deletes exactly the
#                    inventoried object versions/delete markers from the five
#                    destroyable buckets and the inventoried images from the
#                    six ECR repositories, and re-counts. The logs bucket is
#                    retained and NOT emptied. If anything is left, the script
#                    stops before the destroy.
#   8 confirmation   operator types <account>/<region>/<env> to destroy the
#                    plan reviewed in phase 5
#   9 apply          applies exactly the saved phase-5 destroy plan (sha256
#                    re-verified, backend re-verified); no -auto-approve
#                    anywhere. Bucket objects and ECR images are not in
#                    Terraform state, so emptying them in phase 7 does not
#                    make the saved plan stale; any STATE change since phase 5
#                    makes Terraform reject the saved plan (fails closed).
#  10 inventory      read-only post-cleanup inventory: ECR, ECS, log groups,
#                    SQS, EMR, Neptune, S3 Vectors, DynamoDB (+ backups), S3
#
# Abort titles: before phase 7 deletes anything an abort says "NOTHING WAS
# DESTROYED OR DELETED"; once phase 7 has deleted any object/image it says
# "BUCKET/ECR CONTENTS WERE DELETED ... NOTHING WAS DESTROYED". An unapplied
# saved destroy plan is removed on abort (its .txt rendering is kept). An
# unexpected exit (set -e, signal) prints the same kind of summary.
#
# DELETED by the destroy plan (after archive-evidence.sh exported them):
#   - DynamoDB tables run-ledger, entity-link-cache, query-session,
#     evidence-bundle, artifact-registry and all their items. Every table has point-in-time
#     recovery enabled, so DynamoDB creates a SYSTEM backup of each deleted
#     table that AWS retains for 35 days; it is listed in phase 10.
#   - CloudWatch log groups /scholarchain/<env>/* and
#     /aws/neptune/<prefix>-neptune/audit and ALL their log events (exported
#     to the evidence archive in phase 3's prerequisite).
#   - S3 buckets raw, curated, graph, rag, artifacts, after phase 7 emptied
#     them. No bucket sets force_destroy (Terraform never deletes data
#     implicitly); a bucket that is not empty is NOT deleted.
#   - ECR repositories, after phase 7 emptied them (no force_delete).
#   - ECS cluster/services/task definitions, ALB, SQS queues (including the
#     DLQ and any messages in it), Step Functions, EMR, Neptune (prod takes a
#     final snapshot <prefix>-neptune-final-<UTC created>, N13; dev does
#     not), IAM, network.
#
# NOTE (N84): dev creates the ONLY account-wide AWS Budget (create_budget is
# true in dev, false in prod). Destroying dev removes that budget and its
# cost notifications; re-create it (apply dev again, or set create_budget in
# another environment) if the account should stay covered.
#
# RETAINED by design (left out of the destroy plan):
#   - S3 bucket <prefix>-logs and its configuration (EMR and service logs);
#     its current objects are also copied into the archive in phase 6.
#   - S3 Vectors bucket <prefix>-vectors and its five indexes. They carry
#     lifecycle { prevent_destroy = true } (R19), so Terraform refuses to plan
#     their destruction at all. Removing them is a separate, deliberate human
#     procedure printed at the end of this script.
#   - Terraform state bucket (infra/bootstrap; S3 native locking, no lock table).
#   - Local evidence under local-runs/evidence/<env>/ and docs/agent-runs/.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: CONFIRM_CLEANUP=yes cleanup.sh <dev|prod>" >&2
  exit 2
fi
ENVIRONMENT="$1"
ARCHIVE_MAX_AGE_HOURS="${ARCHIVE_MAX_AGE_HOURS:-24}"
# N25: decimal, no leading zeros, 1..168 hours; forced to base 10.
if [[ ! "${ARCHIVE_MAX_AGE_HOURS}" =~ ^[1-9][0-9]{0,2}$ ]] || ((10#${ARCHIVE_MAX_AGE_HOURS} > 168)); then
  echo "ERROR: ARCHIVE_MAX_AGE_HOURS must be a decimal integer from 1 to 168 without leading zeros, got '${ARCHIVE_MAX_AGE_HOURS}'." >&2
  exit 2
fi
ARCHIVE_MAX_AGE_HOURS=$((10#${ARCHIVE_MAX_AGE_HOURS}))


declare -a PHASES=()
FAILED_PHASES=0
# Set to "yes" once phase 7 has sent any bucket-object or ECR-image delete
# request, so an abort after that point never claims that nothing changed.
DATA_EMPTIED="no"
# Set to "yes" immediately before the saved destroy plan is applied.
APPLY_STARTED="no"
# Set to "yes" by summary_and_exit; the EXIT hook below prints a summary for
# any other (unexpected) exit.
SUMMARY_PRINTED="no"
# Set in phase 5 once a destroy plan file has been written.
DESTROY_PLAN=""
DESTROY_SHA=""
EMPTY_DIR=""

phase_ok() { PHASES+=("OK       $1"); }
phase_fail() {
  PHASES+=("FAILED   $1: $2")
  FAILED_PHASES=$((FAILED_PHASES + 1))
}

# A non-negative decimal integer (jq count output). N33: every count is
# checked with this before it is used in $(( )) or [[ -eq ]].
is_count() { [[ "${1:-}" =~ ^[0-9]+$ ]]; }

print_summary() {
  local title="$1"
  echo
  echo "================================================================================"
  echo "${title}"
  echo "  Environment: ${ENVIRONMENT}"
  echo "  Account:     ${TF_ENV_AWS_ACCOUNT:-unverified}"
  echo "  Region:      ${TF_ENV_AWS_REGION:-unverified}"
  echo "  State:       ${TF_ENV_STATE_KEY:-unverified}"
  if [[ -n "${DESTROY_SHA}" ]]; then
    echo "  Destroy plan sha256: ${DESTROY_SHA}"
  fi
  if [[ -n "${EMPTY_DIR}" ]]; then
    echo "  Bucket/ECR inventories, copies, delete records: ${EMPTY_DIR}"
  fi
  echo "  Phases:"
  local p
  for p in ${PHASES[@]+"${PHASES[@]}"}; do
    echo "    ${p}"
  done
  echo "================================================================================"
}

summary_and_exit() {
  local code="$1" title="$2"
  SUMMARY_PRINTED="yes"
  print_summary "${title}"
  tf_env_exit "${code}"
}

# Remove a saved destroy plan that was never applied, so it cannot be applied
# later by hand without going through this script's checks again. The
# human-readable rendering (<plan>.txt) and <plan>.sha256 are kept.
discard_unapplied_plan() {
  if [[ "${APPLY_STARTED}" != "yes" && -n "${DESTROY_PLAN}" && -f "${DESTROY_PLAN}" ]]; then
    rm -f "${DESTROY_PLAN}"
    echo "  Unapplied destroy plan removed: ${DESTROY_PLAN} (rendering kept: ${DESTROY_PLAN}.txt)" >&2
  fi
}

# Title for any stop before the destroy apply started.
abort_title() {
  local prefix="$1"
  if [[ "${DATA_EMPTIED}" == "yes" ]]; then
    echo "${prefix} - BUCKET/ECR CONTENTS WERE DELETED (phase 7) BUT NOTHING WAS DESTROYED (no Terraform resource removed; environment still exists without those contents)"
  else
    echo "${prefix} - NOTHING WAS DESTROYED OR DELETED"
  fi
}

# Abort before the Terraform destroy apply.
abort() {
  phase_fail "$1" "$2"
  echo "ERROR: $1: $2" >&2
  discard_unapplied_plan
  summary_and_exit 1 "$(abort_title "CLEANUP ABORTED")"
}

# EXIT hook (tf-env.sh runs it before releasing the run lock; the exit status
# is preserved). Only acts on an exit that did not go through
# summary_and_exit: a set -e failure, a guard's exit in tf-env.sh, or a
# signal.
tf_env_script_cleanup() {
  [[ "${SUMMARY_PRINTED}" == "yes" ]] && return 0
  SUMMARY_PRINTED="yes"
  echo "ERROR: cleanup.sh stopped unexpectedly (see the messages above)." >&2
  discard_unapplied_plan
  if [[ "${APPLY_STARTED}" == "yes" ]]; then
    print_summary "CLEANUP STOPPED UNEXPECTEDLY DURING/AFTER THE DESTROY APPLY - resources may be partially destroyed; run 'terraform state list' and re-run cleanup.sh" >&2
  else
    print_summary "$(abort_title "CLEANUP STOPPED UNEXPECTEDLY")" >&2
  fi
}

################################################################################
# Phase 1: preflight
################################################################################
echo "=== PHASE 1: Preflight ==="
tf_env_validate_name "${ENVIRONMENT}"
PREFIX="scholarchain-${ENVIRONMENT}"
if [[ "${CONFIRM_CLEANUP:-}" != "yes" ]]; then
  abort "preflight" "set CONFIRM_CLEANUP=yes (e.g. CONFIRM_CLEANUP=yes ./scripts/cleanup.sh ${ENVIRONMENT})"
fi
if [[ ! -t 0 ]]; then
  abort "preflight" "an interactive terminal is required for the typed confirmation"
fi
if ! command -v jq >/dev/null 2>&1; then
  abort "preflight" "jq is required to inspect the destroy plan"
fi
phase_ok "preflight"

################################################################################
# Phase 2: target verification
################################################################################
echo "=== PHASE 2: Verify target (account, region, env, state key) ==="
tf_env_require_aws_target "${ENVIRONMENT}"
tf_env_init_backend "${ENVIRONMENT}"
cd "${TF_DIR}"

STATE_ADDRS="$(terraform state list)" || abort "target" "terraform state list failed"
if [[ -z "${STATE_ADDRS}" ]]; then
  phase_ok "target (state is empty)"
  echo "  State s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY} is empty; nothing to destroy."
  summary_and_exit 0 "NOTHING TO DESTROY"
fi

STATE_ENV="$(terraform output -raw environment 2>/dev/null || true)"
STATE_CLUSTER="$(terraform output -raw ecs_cluster_name 2>/dev/null || true)"
STATE_REGION="$(terraform output -raw aws_region 2>/dev/null || true)"
if [[ -n "${STATE_ENV}" && "${STATE_ENV}" != "${ENVIRONMENT}" ]]; then
  abort "target" "state output environment='${STATE_ENV}' but requested '${ENVIRONMENT}'"
fi
if [[ -n "${STATE_REGION}" && "${STATE_REGION}" != "${TF_ENV_AWS_REGION}" ]]; then
  abort "target" "state output aws_region='${STATE_REGION}' but target region is '${TF_ENV_AWS_REGION}'"
fi
if [[ -z "${STATE_ENV}" && "${STATE_CLUSTER}" != "${PREFIX}-cluster" ]]; then
  abort "target" "cannot prove the state belongs to ${ENVIRONMENT} (no environment output; ecs_cluster_name='${STATE_CLUSTER}')"
fi

cat <<EOF
  ------------------------------------------------------------------
  TARGET
    AWS account: ${TF_ENV_AWS_ACCOUNT}
    AWS region:  ${TF_ENV_AWS_REGION}
    Environment: ${ENVIRONMENT}
    State:       s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
    Resources in state: $(echo "${STATE_ADDRS}" | wc -l | tr -d ' ')
  ------------------------------------------------------------------
EOF
phase_ok "target"

################################################################################
# Phase 3: evidence archive must exist and verify
################################################################################
echo "=== PHASE 3: Verify evidence archive (scripts/archive-evidence.sh) ==="
EVIDENCE_ROOT="${REPO_ROOT}/local-runs/evidence/${ENVIRONMENT}"
LATEST_MARKER=""
if [[ -d "${EVIDENCE_ROOT}" ]]; then
  LATEST_MARKER="$(find "${EVIDENCE_ROOT}" -mindepth 2 -maxdepth 2 -name ARCHIVE_COMPLETE | sort | tail -n 1)"
fi
if [[ -z "${LATEST_MARKER}" ]]; then
  abort "evidence" "no completed archive under ${EVIDENCE_ROOT}; run ./scripts/archive-evidence.sh ${ENVIRONMENT} first"
fi
ARCHIVE_DIR="$(dirname "${LATEST_MARKER}")"
marker() { sed -n -E "s/^${1}=(.*)$/\\1/p" "${LATEST_MARKER}" | head -n 1; }
[[ "$(marker environment)" == "${ENVIRONMENT}" ]] || abort "evidence" "archive environment mismatch in ${LATEST_MARKER}"
[[ "$(marker aws_account)" == "${TF_ENV_AWS_ACCOUNT}" ]] || abort "evidence" "archive account mismatch in ${LATEST_MARKER}"
[[ "$(marker aws_region)" == "${TF_ENV_AWS_REGION}" ]] || abort "evidence" "archive region mismatch in ${LATEST_MARKER}"
[[ "$(marker state_key)" == "${TF_ENV_STATE_KEY}" ]] || abort "evidence" "archive state key mismatch in ${LATEST_MARKER}"
ARCHIVE_EPOCH="$(marker epoch)"
NOW_EPOCH="$(date -u +%s)"
if [[ ! "${ARCHIVE_EPOCH}" =~ ^[1-9][0-9]{0,11}$ ]] || ((10#${NOW_EPOCH} - 10#${ARCHIVE_EPOCH} > ARCHIVE_MAX_AGE_HOURS * 3600)); then
  abort "evidence" "newest archive ${ARCHIVE_DIR} is older than ${ARCHIVE_MAX_AGE_HOURS}h; re-run archive-evidence.sh"
fi
SUMS_SHA="$(shasum -a 256 "${ARCHIVE_DIR}/SHA256SUMS" | awk '{print $1}')"
[[ "${SUMS_SHA}" == "$(marker sha256sums_sha256)" ]] || abort "evidence" "SHA256SUMS in ${ARCHIVE_DIR} does not match its marker"
if ! (cd "${ARCHIVE_DIR}" && shasum -a 256 -c --quiet SHA256SUMS); then
  abort "evidence" "checksum verification failed in ${ARCHIVE_DIR}"
fi
# N8: all five DynamoDB tables; N20: the Neptune audit log export.
NEPTUNE_AUDIT_LG="/aws/neptune/${PREFIX}-neptune/audit"
NEPTUNE_AUDIT_FILE="cloudwatch-logs/$(echo "${NEPTUNE_AUDIT_LG}" | tr '/' '_').json"
for REQUIRED in \
  dynamodb/evidence-bundle.json \
  dynamodb/run-ledger.json \
  dynamodb/artifact-registry.json \
  dynamodb/query-session.json \
  dynamodb/entity-link-cache.json \
  cloudwatch-logs/log-groups.txt \
  "${NEPTUNE_AUDIT_FILE}" \
  s3vectors/indexes.json; do
  [[ -f "${ARCHIVE_DIR}/${REQUIRED}" ]] || abort "evidence" "archive lacks ${REQUIRED} (re-run archive-evidence.sh)"
  grep -q -F "  ./${REQUIRED}" "${ARCHIVE_DIR}/SHA256SUMS" || abort "evidence" "${REQUIRED} is not covered by ${ARCHIVE_DIR}/SHA256SUMS"
done
echo "  Evidence archive verified: ${ARCHIVE_DIR}"
phase_ok "evidence (${ARCHIVE_DIR})"

################################################################################
# Phase 4: pre-cleanup inventory and blockers (read-only)
################################################################################
echo "=== PHASE 4: Pre-cleanup inventory ==="
inventory() {
  local label="$1"
  local rc=0
  echo "  --- ${label} ---"
  echo "  --- ECR repositories (image counts) ---"
  aws ecr describe-repositories \
    --query "repositories[?starts_with(repositoryName,'${PREFIX}-')].repositoryName" \
    --output text | tr '\t' '\n' | while read -r REPO; do
    [[ -z "${REPO}" || "${REPO}" == "None" ]] && continue
    # N37 (audit): inventory runs in `if ! inventory` context (set -e off),
    # and a substitution inside echo's arguments ignores its status, so a
    # failed list-images used to print an empty count and pass. Checked
    # explicitly; the failure ends this pipeline subshell non-zero -> rc=1.
    if ! IMG_COUNT="$(aws ecr list-images --repository-name "${REPO}" --query 'length(imageIds)' --output text)"; then
      echo "    ${REPO}: list-images failed" >&2
      exit 1
    fi
    echo "    ${REPO}: ${IMG_COUNT} image(s)"
  done || rc=1
  echo "  --- ECS cluster and services ---"
  aws ecs describe-clusters --clusters "${PREFIX}-cluster" \
    --query "clusters[?status=='ACTIVE'].[clusterName,activeServicesCount,runningTasksCount]" --output text || rc=1
  echo "  --- CloudWatch log groups /scholarchain/${ENVIRONMENT}/ and Neptune audit ---"
  aws logs describe-log-groups --log-group-name-prefix "/scholarchain/${ENVIRONMENT}/" \
    --query 'logGroups[].[logGroupName,storedBytes]' --output text || rc=1
  aws logs describe-log-groups --log-group-name-prefix "/aws/neptune/${PREFIX}-neptune/" \
    --query 'logGroups[].[logGroupName,storedBytes]' --output text || rc=1
  echo "  --- SQS queues ---"
  aws sqs list-queues --queue-name-prefix "${PREFIX}" --output text || rc=1
  echo "  --- EMR clusters (not terminated) ---"
  EMR_OUT=""
  EMR_RC=0
  EMR_OUT="$(aws emr list-clusters --active \
    --query "Clusters[?starts_with(Name,'${PREFIX}')].[Id,Name,Status.State]" --output text 2>&1)" || EMR_RC=$?
  if [[ ${EMR_RC} -eq 0 ]]; then
    echo "${EMR_OUT}"
  elif [[ "${EMR_OUT}" == *"AccessDenied"* && "${EMR_OUT}" == *"service control policy"* ]]; then
    # Organization SCP denies elasticmapreduce:* (batch_compute = "ecs"); no
    # EMR cluster can exist, so this is not a blocker.
    echo "  (EMR API explicitly denied by an organization SCP: no EMR cluster can exist)"
  else
    echo "${EMR_OUT}" >&2
    rc=1
  fi
  echo "  --- Neptune clusters ---"
  aws neptune describe-db-clusters \
    --query "DBClusters[?DBClusterIdentifier=='${PREFIX}-neptune'].[DBClusterIdentifier,Status,DeletionProtection]" \
    --output text || rc=1
  echo "  --- S3 Vectors buckets and indexes ---"
  aws s3vectors list-vector-buckets --prefix "${PREFIX}" --output text || rc=1
  # N47: fail closed. Only an explicit NotFound from the service means "no such
  # vector bucket"; any other error (access denied, throttling, network) marks
  # the inventory as failed instead of being reported as absent.
  local vec_err vec_rc=0
  vec_err="$(aws s3vectors list-indexes --vector-bucket-name "${PREFIX}-vectors" --output text 2>&1)" || vec_rc=$?
  if [[ ${vec_rc} -eq 0 ]]; then
    [[ -n "${vec_err}" ]] && echo "${vec_err}"
  elif printf '%s' "${vec_err}" | grep -q -E 'NotFoundException|ResourceNotFound'; then
    echo "    (vector bucket ${PREFIX}-vectors not found)"
  else
    echo "ERROR: aws s3vectors list-indexes failed (exit ${vec_rc}); inventory is incomplete:" >&2
    echo "${vec_err}" | sed 's/^/    /' >&2
    rc=1
  fi
  echo "  --- DynamoDB tables ---"
  aws dynamodb list-tables --query "TableNames[?starts_with(@,'${PREFIX}-')]" --output text || rc=1
  echo "  --- DynamoDB backups (incl. SYSTEM backups from PITR tables) ---"
  aws dynamodb list-backups --backup-type ALL \
    --query "BackupSummaries[?starts_with(TableName,'${PREFIX}-')].[TableName,BackupType,BackupStatus,BackupCreationDateTime]" \
    --output text || rc=1
  echo "  --- S3 buckets ---"
  aws s3api list-buckets --query "Buckets[?starts_with(Name,'${PREFIX}-')].Name" --output text || rc=1
  return "${rc}"
}

if ! inventory "before"; then
  abort "inventory" "one or more read-only inventory calls failed (check credentials/region)"
fi

BLOCKERS=0
NEPTUNE_DP="$(aws neptune describe-db-clusters \
  --query "DBClusters[?DBClusterIdentifier=='${PREFIX}-neptune'].DeletionProtection | [0]" --output text)" \
  || abort "inventory" "could not read Neptune deletion protection"
if [[ "${NEPTUNE_DP}" == "True" || "${NEPTUNE_DP}" == "true" ]]; then
  echo "  BLOCKER: ${PREFIX}-neptune has deletion protection enabled; the destroy would fail." >&2
  echo "    Set neptune_deletion_protection = false in environments/${ENVIRONMENT}.tfvars," >&2
  echo "    plan.sh + human approval + deploy.sh, then re-run cleanup." >&2
  BLOCKERS=1
fi
# N13: the prod final snapshot identifier is unique per cluster lifetime
# (modules/neptune terraform_data stamp). If a snapshot with that id already
# exists (cluster replaced earlier, or a previous failed destroy), the
# destroy would fail on the snapshot step; stop now with a clear message.
NEPTUNE_FINAL_SNAPSHOT="$(terraform output -raw neptune_final_snapshot_identifier 2>/dev/null || true)"
if [[ "${NEPTUNE_FINAL_SNAPSHOT}" == "null" ]]; then
  NEPTUNE_FINAL_SNAPSHOT=""
fi
if [[ -n "${NEPTUNE_FINAL_SNAPSHOT}" ]]; then
  if ! SNAP_HIT="$(aws neptune describe-db-cluster-snapshots --snapshot-type manual \
    --query "DBClusterSnapshots[?DBClusterSnapshotIdentifier=='${NEPTUNE_FINAL_SNAPSHOT}'].DBClusterSnapshotIdentifier" \
    --output text)"; then
    abort "inventory" "could not list Neptune cluster snapshots to check ${NEPTUNE_FINAL_SNAPSHOT}"
  fi
  if [[ -n "${SNAP_HIT}" && "${SNAP_HIT}" != "None" ]]; then
    echo "  BLOCKER: Neptune snapshot ${NEPTUNE_FINAL_SNAPSHOT} already exists; the destroy's final" >&2
    echo "    snapshot would fail. Archive/rename that snapshot (human decision), or replace" >&2
    echo "    module.neptune.terraform_data.final_snapshot_stamp via plan.sh + approval + deploy.sh." >&2
    BLOCKERS=1
  else
    echo "  Neptune final snapshot on destroy: ${NEPTUNE_FINAL_SNAPSHOT} (not yet in use)"
  fi
fi
if [[ ${BLOCKERS} -ne 0 ]]; then
  abort "inventory" "blocking condition(s) found; see above"
fi
phase_ok "inventory (before)"

################################################################################
# Phase 5: saved destroy plan, excluding retained resources (N27: built and
# fully checked BEFORE anything is deleted)
################################################################################
echo "=== PHASE 5: Build and check destroy plan (nothing is deleted yet) ==="
# Retained by design: the logs bucket (and its config resources) and the S3
# Vectors bucket and indexes (prevent_destroy). Data sources are not targets.
is_retained() {
  local a="$1"
  [[ "${a}" == data.* || "${a}" == *.data.* ]] && return 0
  [[ "${a}" == 'module.storage.aws_s3vectors_vector_bucket.vectors' ]] && return 0
  [[ "${a}" == module.storage.aws_s3vectors_index.indexes* ]] && return 0
  [[ "${a}" == module.storage.aws_s3_bucket*'["logs"]' ]] && return 0
  [[ "${a}" == 'module.storage.aws_s3_bucket_lifecycle_configuration.logs' ]] && return 0
  return 1
}

declare -a TARGET_ARGS=()
declare -a RETAINED=()
while IFS= read -r ADDR; do
  [[ -z "${ADDR}" ]] && continue
  if is_retained "${ADDR}"; then
    RETAINED+=("${ADDR}")
  else
    TARGET_ARGS+=("-target=${ADDR}")
  fi
done <<<"${STATE_ADDRS}"

echo "  Retained (not in destroy plan):"
# ${arr[@]+...} keeps bash 3.2 (macOS /bin/bash) happy with set -u.
for R in ${RETAINED[@]+"${RETAINED[@]}"}; do
  echo "    ${R}"
done
if [[ ${#TARGET_ARGS[@]} -eq 0 ]]; then
  phase_ok "destroy plan (no destroyable resources)"
  summary_and_exit 0 "NOTHING TO DESTROY (only retained resources remain) - NOTHING WAS DELETED"
fi

PLAN_DIR="${REPO_ROOT}/local-runs/plans/${ENVIRONMENT}"
mkdir -p "${PLAN_DIR}"
DESTROY_PLAN="${PLAN_DIR}/${ENVIRONMENT}-destroy.tfplan"
rm -f "${DESTROY_PLAN}" "${DESTROY_PLAN}.txt" "${DESTROY_PLAN}.sha256"
# image_tag and alb_ingress_cidrs have no default. A destroy plan only
# deletes (checked below: any non-delete action aborts), so their values are
# never applied; any valid value satisfies the variables (the budget email is a
# reserved .invalid placeholder, never a real address). 192.0.2.0/24 is
# RFC 5737 TEST-NET-1 (documentation only, never routable).
IMAGE_TAG="${IMAGE_TAG:-$(git -C "${REPO_ROOT}" rev-parse HEAD)}"

if ! terraform plan -destroy -input=false \
  -var-file="$(tf_env_tfvars_file "${ENVIRONMENT}")" \
  -var="image_tag=${IMAGE_TAG}" \
  -var='alb_ingress_cidrs=["192.0.2.0/24"]' \
  -var='budget_alert_email=destroy-plan@example.invalid' \
  "${TARGET_ARGS[@]}" \
  -out="${DESTROY_PLAN}"; then
  abort "destroy plan" "terraform plan -destroy failed"
fi

PLAN_JSON="$(terraform show -json "${DESTROY_PLAN}")" || abort "destroy plan" "terraform show -json failed"
terraform show -no-color "${DESTROY_PLAN}" > "${DESTROY_PLAN}.txt" || abort "destroy plan" "terraform show failed"
NON_DELETE="$(echo "${PLAN_JSON}" | jq -r '.resource_changes[]? | select((.change.actions != ["delete"]) and (.change.actions != ["no-op"]) and (.change.actions != ["read"])) | "\(.change.actions|join(","))  \(.address)"')" \
  || abort "destroy plan" "could not inspect plan actions (jq failed)"
DELETES="$(echo "${PLAN_JSON}" | jq -r '.resource_changes[]? | select(.change.actions == ["delete"]) | .address')" \
  || abort "destroy plan" "could not list planned deletes (jq failed)"
if [[ -n "${NON_DELETE}" ]]; then
  echo "${NON_DELETE}" >&2
  abort "destroy plan" "plan contains non-delete actions"
fi
while IFS= read -r D; do
  [[ -z "${D}" ]] && continue
  if is_retained "${D}"; then
    abort "destroy plan" "plan would delete retained resource ${D}"
  fi
done <<<"${DELETES}"
if [[ -z "${DELETES}" ]]; then
  abort "destroy plan" "plan contains no deletes although destroyable addresses are in state"
fi
DESTROY_SHA="$(shasum -a 256 "${DESTROY_PLAN}" | awk '{print $1}')"
is_sha="^[0-9a-f]{64}$"
[[ "${DESTROY_SHA}" =~ ${is_sha} ]] || abort "destroy plan" "could not checksum ${DESTROY_PLAN}"
printf '%s  %s\n' "${DESTROY_SHA}" "$(basename "${DESTROY_PLAN}")" > "${DESTROY_PLAN}.sha256"

echo
echo "  ------------------------------------------------------------------"
echo "  DESTROY PLAN SUMMARY ($(echo "${DELETES}" | grep -c . || true) resources will be deleted)"
echo "${DELETES}" | sed 's/^/    - /'
echo "  Full plan text: ${DESTROY_PLAN}.txt"
echo "  Plan file:      ${DESTROY_PLAN}"
echo "  Plan sha256:    ${DESTROY_SHA} (also in ${DESTROY_PLAN}.sha256)"
echo "  This plan is NOT applied now. It is applied in phase 9, unchanged"
echo "  (checksum re-verified), only after phase 7 (EMPTY) and the typed"
echo "  destroy confirmation in phase 8."
echo "  ------------------------------------------------------------------"
phase_ok "destroy plan (built and checked, not applied; ${DESTROY_SHA})"

################################################################################
# Phase 6: inventory and archive bucket/ECR contents (N9; read-only in AWS)
################################################################################
echo "=== PHASE 6: Inventory and archive bucket/ECR contents (nothing is deleted yet) ==="
# Buckets the destroy plan deletes; they must be empty for it to succeed.
EMPTY_BUCKETS="raw curated graph rag artifacts"
# Buckets whose current objects are copied into the archive. logs is
# retained (never emptied); artifacts holds the jars that ran.
COPY_BUCKETS="logs artifacts"
# Destroyable buckets whose contents are deleted WITHOUT a copy (inventory
# only). Must stay in sync with EMPTY_BUCKETS minus COPY_BUCKETS.
UNCOPIED_BUCKETS="raw curated graph rag"
ECR_SERVICES="query-api graph-retriever vector-retriever synthesis-worker verification-worker embedding-worker batch-runner"
EMPTY_DIR="${ARCHIVE_DIR}/cleanup-empty-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${EMPTY_DIR}/s3-inventory" "${EMPTY_DIR}/ecr-inventory" "${EMPTY_DIR}/s3-contents" \
  "${EMPTY_DIR}/delete-requests" "${EMPTY_DIR}/delete-responses"

# s3_inventory <bucket> <outfile>: full version listing (the CLI follows
# every page). Returns 0 and writes the file, 2 if the bucket does not
# exist, 1 on any other error.
s3_inventory() {
  local b="$1" out="$2" err rc=0
  err="$(aws s3api head-bucket --bucket "${b}" 2>&1 >/dev/null)" || rc=$?
  if [[ ${rc} -ne 0 ]]; then
    if [[ "${err}" == *"Not Found"* || "${err}" == *"404"* || "${err}" == *"NoSuchBucket"* ]]; then
      return 2
    fi
    echo "    ${b}: head-bucket failed: ${err}" >&2
    return 1
  fi
  aws s3api list-object-versions --bucket "${b}" --output json > "${out}" || return 1
  return 0
}
# Counts from an inventory file (empty file means no objects). Callers must
# check the result with is_count (N33).
inv_versions() { jq -s '(.[0] // {}) | (.Versions // []) | length' "$1"; }
inv_markers() { jq -s '(.[0] // {}) | (.DeleteMarkers // []) | length' "$1"; }

# ecr_inventory <repo> <outfile>: 0 written, 2 repository absent, 1 error.
ecr_inventory() {
  local r="$1" out="$2" err rc=0
  err="$(aws ecr describe-images --repository-name "${r}" --output json 2>&1 >"${out}")" || rc=$?
  if [[ ${rc} -ne 0 ]]; then
    rm -f "${out}"
    if [[ "${err}" == *"RepositoryNotFoundException"* ]]; then
      return 2
    fi
    echo "    ${r}: describe-images failed: ${err}" >&2
    return 1
  fi
  return 0
}
inv_images() { jq -s '(.[0] // {}) | (.imageDetails // []) | length' "$1"; }

TOTAL_TO_DELETE=0
declare -a NONEMPTY_BUCKETS=()
declare -a NONEMPTY_REPOS=()
echo "  --- S3 buckets (object versions / delete markers) ---"
for B in ${EMPTY_BUCKETS} logs; do
  BN="${PREFIX}-${B}"
  INV="${EMPTY_DIR}/s3-inventory/${BN}.versions.json"
  rc=0
  s3_inventory "${BN}" "${INV}" || rc=$?
  if [[ ${rc} -eq 2 ]]; then
    echo "    s3://${BN}: does not exist"
    continue
  elif [[ ${rc} -ne 0 ]]; then
    abort "archive" "could not inventory s3://${BN}"
  fi
  NV="$(inv_versions "${INV}")" || abort "archive" "could not parse inventory of s3://${BN}"
  NM="$(inv_markers "${INV}")" || abort "archive" "could not parse inventory of s3://${BN}"
  if ! is_count "${NV}" || ! is_count "${NM}"; then
    abort "archive" "inventory of s3://${BN} gave non-numeric counts (versions='${NV}', markers='${NM}')"
  fi
  # Human-readable listing: kind, key, version id, size, etag, is-latest.
  jq -s -r '(.[0] // {}) | ((.Versions // [])[] | ["version", .Key, .VersionId, (.Size|tostring), .ETag, (.IsLatest|tostring)]),
           ((.DeleteMarkers // [])[] | ["delete-marker", .Key, .VersionId, "", "", (.IsLatest|tostring)]) | @tsv' \
    "${INV}" > "${EMPTY_DIR}/s3-inventory/${BN}.versions.tsv" || abort "archive" "could not write inventory listing of s3://${BN}"
  ROLE="destroyable"
  [[ "${B}" == "logs" ]] && ROLE="retained, not emptied"
  echo "    s3://${BN}: ${NV} object version(s), ${NM} delete marker(s) (${ROLE})"
  if [[ "${B}" != "logs" && $((NV + NM)) -gt 0 ]]; then
    NONEMPTY_BUCKETS+=("${B}")
    TOTAL_TO_DELETE=$((TOTAL_TO_DELETE + NV + NM))
  fi
done
echo "  --- ECR repositories (images) ---"
for SVC in ${ECR_SERVICES}; do
  RN="${PREFIX}-${SVC}"
  INV="${EMPTY_DIR}/ecr-inventory/${RN}.images.json"
  rc=0
  ecr_inventory "${RN}" "${INV}" || rc=$?
  if [[ ${rc} -eq 2 ]]; then
    echo "    ${RN}: does not exist"
    continue
  elif [[ ${rc} -ne 0 ]]; then
    abort "archive" "could not inventory ECR repository ${RN}"
  fi
  NI="$(inv_images "${INV}")" || abort "archive" "could not parse inventory of ${RN}"
  is_count "${NI}" || abort "archive" "inventory of ${RN} gave a non-numeric image count ('${NI}')"
  echo "    ${RN}: ${NI} image(s)"
  if [[ "${NI}" -gt 0 ]]; then
    NONEMPTY_REPOS+=("${SVC}")
    TOTAL_TO_DELETE=$((TOTAL_TO_DELETE + NI))
  fi
done

echo "  --- Copy current objects of the logs and artifacts buckets into the archive ---"
echo "  (raw, curated, graph and rag contents are NOT copied, only inventoried; see"
echo "   the data-loss warning before the EMPTY confirmation in phase 7.)"
for B in ${COPY_BUCKETS}; do
  BN="${PREFIX}-${B}"
  INV="${EMPTY_DIR}/s3-inventory/${BN}.versions.json"
  [[ -f "${INV}" ]] || continue
  DEST="${EMPTY_DIR}/s3-contents/${BN}"
  mkdir -p "${DEST}"
  # Current (latest, non-deleted) objects only; non-current versions are
  # listed in the inventory but not copied.
  SYNC_RC=0
  aws s3 sync "s3://${BN}" "${DEST}" --only-show-errors || SYNC_RC=$?
  if [[ ${SYNC_RC} -ne 0 ]]; then
    # N33: explicit. Nothing has been deleted at this point.
    echo "  The copy of s3://${BN} failed (aws s3 sync exit ${SYNC_RC}). Typical causes: keys" >&2
    echo "  that cannot coexist as local paths (e.g. 'a' and 'a/b'), keys that are not valid" >&2
    echo "  local file names, permissions, or throttling. The archive copy is incomplete," >&2
    echo "  so NOTHING will be deleted. Resolve the cause (a human may copy the bucket" >&2
    echo "  another way and record it) and re-run cleanup.sh." >&2
    abort "archive" "copy of s3://${BN} into the archive failed (aws s3 sync exit ${SYNC_RC}); nothing was deleted"
  fi
  # Verify every latest object version (excluding zero-byte "folder/" keys)
  # arrived with the inventoried size.
  MISSING_COPY=0
  LATEST_TSV="${EMPTY_DIR}/s3-inventory/${BN}.latest.tsv"
  jq -s -r '(.[0] // {}) | (.Versions // [])[] | select(.IsLatest == true) | [(.Size|tostring), .Key] | @tsv' "${INV}" \
    > "${LATEST_TSV}" || abort "archive" "could not list latest objects of s3://${BN} from its inventory"
  while IFS="$(printf '\t')" read -r SIZE KEY; do
    [[ -z "${KEY}" || "${KEY}" == */ ]] && continue
    if [[ ! -f "${DEST}/${KEY}" ]] || [[ "$(wc -c <"${DEST}/${KEY}" | tr -d ' ')" != "${SIZE}" ]]; then
      echo "    copy mismatch: s3://${BN}/${KEY}" >&2
      MISSING_COPY=1
    fi
  done <"${LATEST_TSV}"
  if [[ ${MISSING_COPY} -ne 0 ]]; then
    abort "archive" "archived copy of s3://${BN} is incomplete (objects may have changed during the copy; re-run); nothing was deleted"
  fi
  echo "    s3://${BN}: copied to ${DEST}"
done

(cd "${EMPTY_DIR}" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256) \
  > "${EMPTY_DIR}/SHA256SUMS" || abort "archive" "could not checksum ${EMPTY_DIR}"
(cd "${EMPTY_DIR}" && shasum -a 256 -c --quiet SHA256SUMS) || abort "archive" "checksum verification failed in ${EMPTY_DIR}"
echo "  Inventories and copies archived and checksummed: ${EMPTY_DIR}/SHA256SUMS"
phase_ok "archive (inventories and logs/artifacts copies verified in ${EMPTY_DIR})"

################################################################################
# Phase 7: empty buckets and ECR repositories (N9), separately confirmed
################################################################################
echo "=== PHASE 7: Empty buckets and ECR repositories (typed EMPTY confirmation) ==="
if [[ ${TOTAL_TO_DELETE} -eq 0 ]]; then
  echo "  All destroyable buckets and ECR repositories are already empty."
  phase_ok "empty (nothing to delete; inventory ${EMPTY_DIR})"
else
  EMPTY_CONFIRM="EMPTY ${TF_ENV_AWS_ACCOUNT}/${TF_ENV_AWS_REGION}/${ENVIRONMENT}"
  declare -a UNCOPIED_NONEMPTY=()
  for B in ${NONEMPTY_BUCKETS[@]+"${NONEMPTY_BUCKETS[@]}"}; do
    case " ${UNCOPIED_BUCKETS} " in
      *" ${B} "*) UNCOPIED_NONEMPTY+=("${B}") ;;
    esac
  done
  echo
  echo "  ------------------------------------------------------------------"
  echo "  PERMANENT DELETION (${TOTAL_TO_DELETE} object versions/delete markers/images):"
  for B in ${NONEMPTY_BUCKETS[@]+"${NONEMPTY_BUCKETS[@]}"}; do
    echo "    - every inventoried version and delete marker in s3://${PREFIX}-${B}"
  done
  for SVC in ${NONEMPTY_REPOS[@]+"${NONEMPTY_REPOS[@]}"}; do
    echo "    - every inventoried image in ECR ${PREFIX}-${SVC}"
  done
  echo "  NOT deleted: s3://${PREFIX}-logs (retained), S3 Vectors (prevent_destroy)."
  echo "  Inventoried first: ${EMPTY_DIR}"
  echo
  echo "  !!! DATA-LOSS WARNING !!!"
  echo "  The contents of the raw, curated, graph and rag buckets are deleted"
  echo "  WITHOUT A COPY. Only their key/version/size/etag inventories are archived."
  if [[ ${#UNCOPIED_NONEMPTY[@]} -gt 0 ]]; then
    echo "  Non-empty now (contents will be lost):"
    for B in "${UNCOPIED_NONEMPTY[@]}"; do
      echo "    - s3://${PREFIX}-${B}"
    done
  else
    echo "  (All four are empty or absent now; nothing in them will be lost.)"
  fi
  echo "  Recovery relies on re-downloading raw data with the datasets/ scripts and"
  echo "  manifest and re-running the pipeline for curated, graph and rag. Upstream"
  echo "  sources may have changed since; results may not be bit-identical."
  echo "  ECR images are deleted too (rebuildable from Git at their commit SHA)."
  echo "  Typing the EMPTY value below records YOUR (human) data-loss decision in"
  echo "  ${EMPTY_DIR}/empty-confirmation.txt."
  echo "  After emptying, the destroy plan from phase 5 (sha256 ${DESTROY_SHA})"
  echo "  is NOT applied until you confirm it separately in phase 8."
  echo "  ------------------------------------------------------------------"
  read -r -p "  Type ${EMPTY_CONFIRM} to permanently delete the contents listed above: " TYPED_EMPTY
  if [[ "${TYPED_EMPTY}" != "${EMPTY_CONFIRM}" ]]; then
    abort "empty" "typed value did not match '${EMPTY_CONFIRM}'; nothing was deleted"
  fi
  {
    echo "decision=delete bucket and ECR contents without a copy of raw/curated/graph/rag"
    echo "typed=${TYPED_EMPTY}"
    echo "confirmed_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "aws_account=${TF_ENV_AWS_ACCOUNT}"
    echo "aws_region=${TF_ENV_AWS_REGION}"
    echo "environment=${ENVIRONMENT}"
    echo "uncopied_nonempty_buckets=${UNCOPIED_NONEMPTY[*]+${UNCOPIED_NONEMPTY[*]}}"
    echo "items_to_delete=${TOTAL_TO_DELETE}"
    echo "destroy_plan_sha256=${DESTROY_SHA}"
  } > "${EMPTY_DIR}/empty-confirmation.txt" || abort "empty" "could not record the EMPTY confirmation; nothing was deleted"

  EMPTY_FAIL=0
  for B in ${NONEMPTY_BUCKETS[@]+"${NONEMPTY_BUCKETS[@]}"}; do
    BN="${PREFIX}-${B}"
    INV="${EMPTY_DIR}/s3-inventory/${BN}.versions.json"
    N=0
    # Delete exactly the inventoried versions and delete markers, 1000 per
    # request (the DeleteObjects maximum). Objects written after the
    # inventory are not touched and are caught by the re-count below.
    while IFS= read -r BATCH; do
      [[ -z "${BATCH}" ]] && continue
      N=$((N + 1))
      REQ="${EMPTY_DIR}/delete-requests/${BN}.batch-${N}.json"
      RESP="${EMPTY_DIR}/delete-responses/${BN}.batch-${N}.json"
      printf '%s\n' "${BATCH}" > "${REQ}"
      DATA_EMPTIED="yes"
      if ! aws s3api delete-objects --bucket "${BN}" --delete "file://${REQ}" --output json > "${RESP}"; then
        echo "    s3://${BN} batch ${N}: delete-objects failed" >&2
        EMPTY_FAIL=1
        continue
      fi
      if ! jq -s -e '((.[0] // {}) | (.Errors // []) | length) == 0' "${RESP}" >/dev/null; then
        echo "    s3://${BN} batch ${N}: some objects were not deleted (see ${RESP})" >&2
        EMPTY_FAIL=1
      fi
    done < <(jq -s -c '(.[0] // {}) | [((.Versions // [])[], (.DeleteMarkers // [])[]) | {Key, VersionId}] as $all
                       | range(0; ($all | length); 1000) as $i | {Objects: $all[$i:($i + 1000)], Quiet: true}' "${INV}")
    REINV="${EMPTY_DIR}/s3-inventory/${BN}.after-empty.json"
    rc=0
    s3_inventory "${BN}" "${REINV}" || rc=$?
    if [[ ${rc} -ne 0 ]]; then
      echo "    s3://${BN}: could not re-count after emptying" >&2
      EMPTY_FAIL=1
      continue
    fi
    # N33: validate both counts before any arithmetic.
    LV=""
    LM=""
    if ! LV="$(inv_versions "${REINV}")" || ! LM="$(inv_markers "${REINV}")" \
      || ! is_count "${LV}" || ! is_count "${LM}"; then
      echo "    s3://${BN}: could not parse the re-count (versions='${LV}', markers='${LM}')" >&2
      EMPTY_FAIL=1
      continue
    fi
    LEFT=$((LV + LM))
    echo "    s3://${BN}: ${N} delete request(s); ${LEFT} version(s)/marker(s) remain"
    [[ ${LEFT} -eq 0 ]] || EMPTY_FAIL=1
  done

  for SVC in ${NONEMPTY_REPOS[@]+"${NONEMPTY_REPOS[@]}"}; do
    RN="${PREFIX}-${SVC}"
    INV="${EMPTY_DIR}/ecr-inventory/${RN}.images.json"
    if ! jq -s -r '(.[0] // {}) | (.imageDetails // [])[].imageDigest' "${INV}" | sort -u > "${EMPTY_DIR}/ecr-inventory/${RN}.digests.txt"; then
      echo "    ${RN}: could not read inventoried digests; repository not emptied" >&2
      EMPTY_FAIL=1
      continue
    fi
    # Up to three passes: an image index must go before the manifests it
    # references. Only inventoried digests are ever deleted.
    for PASS in 1 2 3; do
      CUR="${EMPTY_DIR}/ecr-inventory/${RN}.pass-${PASS}.json"
      rc=0
      ecr_inventory "${RN}" "${CUR}" || rc=$?
      if [[ ${rc} -ne 0 ]]; then
        echo "    ${RN}: could not list images (pass ${PASS})" >&2
        EMPTY_FAIL=1
        break
      fi
      TARGETS="$(jq -s -r '(.[0] // {}) | (.imageDetails // [])[].imageDigest' "${CUR}" | sort -u \
        | grep -F -x -f "${EMPTY_DIR}/ecr-inventory/${RN}.digests.txt" || true)"
      [[ -z "${TARGETS}" ]] && break
      N=0
      while IFS= read -r BATCH; do
        [[ -z "${BATCH}" ]] && continue
        N=$((N + 1))
        REQ="${EMPTY_DIR}/delete-requests/${RN}.pass-${PASS}.batch-${N}.json"
        RESP="${EMPTY_DIR}/delete-responses/${RN}.pass-${PASS}.batch-${N}.json"
        printf '%s\n' "${BATCH}" > "${REQ}"
        DATA_EMPTIED="yes"
        aws ecr batch-delete-image --repository-name "${RN}" --image-ids "file://${REQ}" \
          --output json > "${RESP}" || echo "    ${RN}: batch-delete-image call failed (pass ${PASS})" >&2
      done < <(printf '%s\n' "${TARGETS}" | jq -R -s -c 'split("\n") | map(select(length > 0))
                 | range(0; length; 100) as $i | [.[$i:($i + 100)][] | {imageDigest: .}]')
    done
    FINAL="${EMPTY_DIR}/ecr-inventory/${RN}.after-empty.json"
    rc=0
    ecr_inventory "${RN}" "${FINAL}" || rc=$?
    if [[ ${rc} -eq 2 ]]; then
      LEFT=0
    elif [[ ${rc} -ne 0 ]]; then
      echo "    ${RN}: could not re-count after emptying" >&2
      EMPTY_FAIL=1
      continue
    else
      LEFT=""
      if ! LEFT="$(inv_images "${FINAL}")" || ! is_count "${LEFT}"; then
        echo "    ${RN}: could not parse the re-count ('${LEFT}')" >&2
        EMPTY_FAIL=1
        continue
      fi
    fi
    echo "    ${RN}: ${LEFT} image(s) remain"
    [[ "${LEFT}" -eq 0 ]] || EMPTY_FAIL=1
  done

  # Re-checksum the empty-phase directory (now also holding the confirmation
  # record, delete requests/responses and re-counts).
  (cd "${EMPTY_DIR}" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 shasum -a 256) \
    > "${EMPTY_DIR}/SHA256SUMS" || EMPTY_FAIL=1

  if [[ ${EMPTY_FAIL} -ne 0 ]]; then
    abort "empty" "some bucket/repository contents remain or a delete/re-count failed (see above); the destroy would only partially succeed, so it was not attempted"
  fi
  phase_ok "empty (${TOTAL_TO_DELETE} item(s) deleted; inventory, copies and delete records in ${EMPTY_DIR})"
fi

################################################################################
# Phase 8: typed destroy confirmation
################################################################################
echo "=== PHASE 8: Destroy confirmation ==="
EXPECTED_CONFIRM="${TF_ENV_AWS_ACCOUNT}/${TF_ENV_AWS_REGION}/${ENVIRONMENT}"
echo "  Review the destroy plan built and checked in phase 5 (${DESTROY_PLAN}.txt)."
echo "  Plan sha256: ${DESTROY_SHA}"
echo "  Evidence archive: ${ARCHIVE_DIR}"
if [[ "${DATA_EMPTIED}" == "yes" ]]; then
  echo "  NOTE: bucket/ECR contents were already deleted in phase 7. Declining here"
  echo "  leaves the environment's resources in place without those contents."
fi
read -r -p "  Type ${EXPECTED_CONFIRM} to destroy exactly this plan: " TYPED
if [[ "${TYPED}" != "${EXPECTED_CONFIRM}" ]]; then
  abort "confirmation" "typed value did not match ${EXPECTED_CONFIRM}"
fi
phase_ok "confirmation"

################################################################################
# Phase 9: apply the saved destroy plan from phase 5 (unchanged)
################################################################################
echo "=== PHASE 9: Apply saved destroy plan (built and checked in phase 5) ==="
if [[ ! -f "${DESTROY_PLAN}" ]] \
  || [[ "$(shasum -a 256 "${DESTROY_PLAN}" | awk '{print $1}')" != "${DESTROY_SHA}" ]] \
  || [[ "$(awk '{print $1}' "${DESTROY_PLAN}.sha256" 2>/dev/null || true)" != "${DESTROY_SHA}" ]]; then
  abort "apply" "destroy plan file is missing or changed after review (sha256 ${DESTROY_SHA} expected)"
fi
tf_env_verify_backend "${ENVIRONMENT}"
echo "  Plan sha256 re-verified: ${DESTROY_SHA}"
APPLY_RC=0
APPLY_STARTED="yes"
terraform apply -input=false "${DESTROY_PLAN}" || APPLY_RC=$?
if [[ ${APPLY_RC} -eq 0 ]]; then
  phase_ok "apply"
else
  phase_fail "apply" "terraform apply exited ${APPLY_RC}; some resources were not destroyed (see the errors above, phase 10 and 'Resources still in Terraform state'; if Terraform reported the saved plan as stale, the state changed since phase 5 and nothing was destroyed). Re-run cleanup.sh after fixing the cause; it plans only what remains"
fi

################################################################################
# Phase 10: post-cleanup inventory
################################################################################
echo "=== PHASE 10: Post-cleanup inventory ==="
if inventory "after"; then
  phase_ok "inventory (after)"
else
  phase_fail "inventory (after)" "one or more read-only inventory calls failed"
fi
echo "  --- Resources still in Terraform state ---"
terraform state list || phase_fail "state list (after)" "terraform state list failed"

cat <<EOF

  RETAINED BY DESIGN
    - s3://${PREFIX}-logs (logs bucket) and its configuration.
    - S3 Vectors bucket ${PREFIX}-vectors and its five indexes (prevent_destroy).
    - DynamoDB SYSTEM backups that DynamoDB created for each deleted
      point-in-time-recovery table (retained by AWS for 35 days; listed above).
    - Neptune final snapshot ${NEPTUNE_FINAL_SNAPSHOT:-none (dev: skip_final_snapshot)}.
    - Terraform state bucket (infra/bootstrap; no lock table); local evidence at ${ARCHIVE_DIR}
      (bucket/ECR inventories, logs+artifacts copies and delete records in
      ${EMPTY_DIR}).

  TO REMOVE THE S3 VECTORS BUCKET AND INDEXES (separate human decision)
    lifecycle.prevent_destroy cannot be driven by a variable. A human must:
      1. commit a reviewed change to infra/terraform/modules/storage/main.tf
         removing the two "prevent_destroy = true" lines;
      2. confirm the vectors are archived or reproducible;
      3. run plan.sh / human approval / deploy-engineer apply of a destroy plan
         targeting module.storage.aws_s3vectors_index.indexes and
         module.storage.aws_s3vectors_vector_bucket.vectors;
      4. restore prevent_destroy in a follow-up commit.

  TO REMOVE THE LOGS BUCKET
    Only after its contents are archived: a separate human-approved targeted
    destroy plan for module.storage.aws_s3_bucket.buckets["logs"].
EOF

if [[ ${FAILED_PHASES} -ne 0 ]]; then
  summary_and_exit 1 "CLEANUP FAILED - ${FAILED_PHASES} phase(s) failed; resources may remain (see phase 10)"
fi
summary_and_exit 0 "CLEANUP COMPLETE (retained resources listed above)"
