#!/usr/bin/env bash
set -euo pipefail

################################################################################
# run-bounded-pipeline.sh - Submit EMR steps for bounded Hadoop+Spark pipeline
#
# Usage: ./scripts/run-bounded-pipeline.sh <dev|prod> [timeout-minutes]
# Example: ./scripts/run-bounded-pipeline.sh dev 60
#
# Records dataset snapshot, run IDs, config, and output locations.
#
# Principal (N80): run this script as the HUMAN OPERATOR (an AWS CLI profile
# with the account's admin SSO role), not as the deploy role. The deploy role
# has only a minimal batch statement set and no read access to the Terraform
# state bucket (intentionally), which this script needs for state verification.
#
# Lock (N89): the tf-env.sh run lock (infra/terraform/.terraform/
# scholarchain-run.lock) is taken at backend init and held for the WHOLE run
# (up to three stage timeouts plus preflight); other scripts (plan, deploy,
# cleanup, ...) fail closed until this one exits.
#
# Reproducibility window (N88): intermediate run output in the curated bucket
# (and noncurrent object versions, including jar versions) expires after
# intermediate_expiration_days (60 in dev). A run can be reproduced from its
# record and the pinned jar VersionIds only within that 60-day window.
#
# Two compute paths, chosen by the verified state's `batch_compute` output (not
# by an argument, so it can never disagree with what is deployed):
#   emr  EMR steps on the cluster from state output emr_cluster_id (an
#        organization SCP may deny EMR; see environments/dev.tfvars).
#   ecs  ECS Fargate tasks (aws ecs run-task) running the SAME assembly jars
#        from the artifacts bucket: Hadoop local mode over s3a, the ER runner
#        on local disk (staged with `aws s3 cp`), Spark --master local[*].
#        Stages <base>-hadoop, <base>-er, <base>-spark run as three tasks in
#        the private subnets (no public IP), each waited on with the bounded
#        timeout (an overrunning task is stopped and the run fails). Task ARNs,
#        exit codes and timings go to local-runs/pipeline/<env>/<run id>.json
#        (gitignored) and the evidence block. A missing jar, image or
#        preflight tool fails closed before or inside the first task.
#
# Bounding and provenance (Pass 8 repairs):
#   N66  every ECS stage is bounded INSIDE the container (`timeout`, deadline =
#        the per-stage timeout) independently of this client; this client waits
#        that long plus a 5 minute grace for provisioning and teardown, polls
#        with bounded retries, and an EXIT/INT/TERM trap stops the running task
#        (loud error if stop-task fails). On EMR a timeout or interrupt runs
#        `aws emr cancel-steps` on the current step (N74).
#   N68  every job jar is recorded from head-object (S3 VersionId, ETag, size)
#        plus a sha256 of the downloaded bytes; ECS tasks fetch exactly that
#        version and verify the hash; all of it goes into the run record
#        (local-runs/pipeline/<env>/<run id>.json, gitignored, both modes) and
#        the evidence block. The EMR path cannot pin a version, so it re-checks
#        the jars and the dataset after the run and fails if any changed.
#   N74  the dataset snapshot (head-object of the DBLP file) is required, and
#        the existence checks of the run output prefixes fail closed on any
#        listing error.
#   Capacity: with batch_use_spot the tasks run on FARGATE_SPOT and fall back to
#        FARGATE when Spot capacity is unavailable at LAUNCH. A Spot
#        interruption after launch fails the stage (never silently retried).
#
# Target guard (N2, same as plan.sh/deploy.sh/rollback.sh via tf-env.sh):
# validates the environment name (allowlist dev, prod), requires the AWS CLI
# account/region to be readable and the CLI region to equal the env's
# aws_region, re-initialises the backend for <env> (-reconfigure) and refuses
# unless the state key is scholarchain/<env>/terraform.tfstate, refuses if the
# legacy shared state object exists, and requires the state's `environment`
# and `aws_region` outputs to equal the target. The EMR cluster ID is read
# only from that verified state, and the state's bucket names must equal the
# <prefix>-* buckets the steps are given, before any step is submitted.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"
# shellcheck source=scripts/pipeline-lib.sh
source "${SCRIPT_DIR}/pipeline-lib.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: run-bounded-pipeline.sh <dev|prod> [timeout-minutes]" >&2
  exit 2
fi
ENVIRONMENT="$1"
tf_env_validate_name "${ENVIRONMENT}"
TIMEOUT_MINUTES="${2:-60}"
# N25: decimal only, no leading zeros (bash would read 08/09 as invalid
# octal), at most 4 digits (no overflow); bounded to 1..1440 minutes. The
# value is then forced to base 10 with 10# before any arithmetic.
if [[ ! "${TIMEOUT_MINUTES}" =~ ^[1-9][0-9]{0,3}$ ]] || ((10#${TIMEOUT_MINUTES} > 1440)); then
  echo "ERROR: timeout-minutes must be a decimal integer from 1 to 1440 without leading zeros, got '${TIMEOUT_MINUTES}'." >&2
  exit 2
fi
TIMEOUT_MINUTES=$((10#${TIMEOUT_MINUTES}))
TIMEOUT_SECONDS=$((TIMEOUT_MINUTES * 60))
# N66: the client waits the in-container deadline plus this grace for task
# provisioning, image pull and teardown before it stops the task itself.
CLIENT_GRACE_SECONDS=300
CLIENT_TIMEOUT_SECONDS=$((TIMEOUT_SECONDS + CLIENT_GRACE_SECONDS))
PREFIX="scholarchain-${ENVIRONMENT}"
# One base id per execution; each stage gets its own id (N54: Spark run ids
# must be unique per stage/execution).
RUN_TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUN_ID="run-$(date -u +%Y%m%dT%H%M%SZ)-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
pipeline_validate_base_id "${RUN_ID}" || exit 2
RUN_ID_HADOOP="${RUN_ID}-hadoop"
RUN_ID_ER="${RUN_ID}-er"
RUN_ID_SPARK="${RUN_ID}-spark"
ARTIFACTS_BUCKET="${PREFIX}-artifacts"
RAW_BUCKET="${PREFIX}-raw"
CURATED_BUCKET="${PREFIX}-curated"
GRAPH_BUCKET="${PREFIX}-graph"
LOGS_BUCKET="${PREFIX}-logs"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required (EMR step JSON, ECS overrides, the run record)." >&2
  exit 1
fi

################################################################################
# Shared state, cleanup and helpers
################################################################################

MODE=""
ECS_CLUSTER=""
CLUSTER_ID=""
CURRENT_TASK_ARN=""
CURRENT_STEP_ID=""
JAR_TMP_DIR=""
RUN_STATUS="running"
STAGES_JSON="[]"
JARS_JSON="[]"
DATASET_JSON="null"
RECORD_FILE=""
IMAGE_REF=""
LOG_GROUP=""
STOP_FAILED=""

# Stop an ECS task. Three attempts; returns 1 (after a loud message) if it
# could not be stopped, because a running task keeps billing and keeps writing.
stop_ecs_task() {
  local arn="$1" reason="$2" n=0
  while [[ ${n} -lt 3 ]]; do
    n=$((n + 1))
    if aws ecs stop-task --cluster "${ECS_CLUSTER}" --task "${arn}" --reason "${reason:0:200}" >/dev/null; then
      echo "  stopped ECS task ${arn}"
      return 0
    fi
    [[ ${n} -lt 3 ]] && sleep 5
  done
  STOP_FAILED="${STOP_FAILED:+${STOP_FAILED} }${arn}"
  {
    echo "ERROR: ################################################################"
    echo "ERROR: FAILED TO STOP ECS TASK ${arn}"
    echo "ERROR: It may still be running and billing. Stop it by hand NOW:"
    echo "ERROR:   aws ecs stop-task --cluster ${ECS_CLUSTER} --task ${arn}"
    echo "ERROR: ################################################################"
  } >&2
  return 1
}

# Cancel an EMR step (N74). Three attempts; loud message when it is not
# cancelled. SEND_INTERRUPT also interrupts a RUNNING step.
cancel_emr_step() {
  local step_id="$1" n=0 status
  while [[ ${n} -lt 3 ]]; do
    n=$((n + 1))
    if status="$(aws emr cancel-steps --cluster-id "${CLUSTER_ID}" --step-ids "${step_id}" \
      --step-cancellation-option SEND_INTERRUPT --query 'CancelStepsInfoList[0].Status' --output text 2>&1)"; then
      if [[ "${status}" == "SUBMITTED" ]]; then
        echo "  cancel requested for EMR step ${step_id}"
        return 0
      fi
      # FAILED means the step was not cancellable (e.g. it already finished):
      # report it, and treat as unresolved so the operator looks.
      echo "  cancel-steps for ${step_id} returned '${status}'" >&2
    else
      echo "  cancel-steps for ${step_id} failed: ${status}" >&2
    fi
    [[ ${n} -lt 3 ]] && sleep 5
  done
  {
    echo "ERROR: ################################################################"
    echo "ERROR: COULD NOT CANCEL EMR STEP ${step_id} on cluster ${CLUSTER_ID}"
    echo "ERROR: It may still be running. Check and cancel by hand:"
    echo "ERROR:   aws emr cancel-steps --cluster-id ${CLUSTER_ID} --step-ids ${step_id} --step-cancellation-option SEND_INTERRUPT"
    echo "ERROR: ################################################################"
  } >&2
  return 1
}

# EXIT/INT/TERM (tf-env.sh routes INT and TERM into exit, hence into this
# hook): never leave a task or step running behind a dead client (N66, N74).
tf_env_script_cleanup() {
  # N87: a task may have been started (run-task returned) before its ARN was
  # recorded; find it by --started-by=${RUN_ID} and stop it.
  if [[ -z "${CURRENT_TASK_ARN}" && -n "${ECS_CLUSTER:-}" && "${MODE:-}" == "ecs" ]]; then
    local orphan_arns orphan
    orphan_arns="$(aws ecs list-tasks --cluster "${ECS_CLUSTER}" --started-by "${RUN_ID}" \
      --desired-status RUNNING --query 'taskArns[]' --output text 2>/dev/null || true)"
    for orphan in ${orphan_arns}; do
      [[ "${orphan}" == "None" ]] && continue
      echo "cleanup: stopping ECS task ${orphan} found by started-by=${RUN_ID}" >&2
      stop_ecs_task "${orphan}" "run-bounded-pipeline ${RUN_ID} exited" || true
    done
  fi
  if [[ -n "${CURRENT_TASK_ARN}" ]]; then
    echo "cleanup: stopping the running ECS task ${CURRENT_TASK_ARN}" >&2
    stop_ecs_task "${CURRENT_TASK_ARN}" "run-bounded-pipeline ${RUN_ID} exited" || true
    CURRENT_TASK_ARN=""
  fi
  if [[ -n "${CURRENT_STEP_ID}" ]]; then
    echo "cleanup: cancelling the running EMR step ${CURRENT_STEP_ID}" >&2
    cancel_emr_step "${CURRENT_STEP_ID}" || true
    CURRENT_STEP_ID=""
  fi
  if [[ "${RUN_STATUS}" == "running" && -n "${RECORD_FILE}" ]]; then
    RUN_STATUS="aborted${STOP_FAILED:+ (STOP FAILED for ${STOP_FAILED})}"
    write_record || true
  fi
  if [[ -n "${JAR_TMP_DIR}" && -d "${JAR_TMP_DIR}" ]]; then
    rm -rf "${JAR_TMP_DIR}"
  fi
}

# The run record (both modes): provenance of everything this run used.
write_record() {
  [[ -n "${RECORD_FILE}" ]] || return 0
  jq -n \
    --arg run_id "${RUN_ID}" --arg mode "${MODE}" --arg env "${ENVIRONMENT}" --arg account "${TF_ENV_AWS_ACCOUNT}" \
    --arg region "${TF_ENV_AWS_REGION}" --arg commit "${COMMIT_SHA}" --arg dirty "${GIT_DIRTY_FLAG}" \
    --arg run_ts "${RUN_TS}" --arg status "${RUN_STATUS}" --arg cluster "${ECS_CLUSTER:-${CLUSTER_ID}}" \
    --arg image "${IMAGE_REF}" --arg log_group "${LOG_GROUP}" \
    --arg input "${DBLP_XML}" --arg hadoop_out "${HADOOP_OUT}" --arg er_out "${ER_OUT}" --arg spark_out "${SPARK_OUT}" \
    --arg timeout "${TIMEOUT_MINUTES}" --arg capacity "${CAPACITY_STRATEGY:-}" \
    --argjson stages "${STAGES_JSON}" --argjson jars "${JARS_JSON}" --argjson dataset "${DATASET_JSON}" \
    --arg rid_h "${RUN_ID_HADOOP}" --arg rid_e "${RUN_ID_ER}" --arg rid_s "${RUN_ID_SPARK}" \
    '{run_id:$run_id,mode:$mode,environment:$env,account:$account,region:$region,commit:$commit,
      git_dirty:($dirty=="true"),run_timestamp:$run_ts,status:$status,timeout_minutes_per_stage:($timeout|tonumber),
      capacity_strategy:$capacity,
      stage_run_ids:{hadoop:$rid_h,er:$rid_e,spark:$rid_s},cluster:$cluster,image:$image,log_group:$log_group,
      input:$input,dataset_snapshot:$dataset,jars:$jars,
      outputs:{hadoop:$hadoop_out,er:$er_out,spark:$spark_out},stages:$stages}' >"${RECORD_FILE}.tmp"
  mv "${RECORD_FILE}.tmp" "${RECORD_FILE}"
}

# fail <status> <message>: record, say why on stderr, exit 1.
fail() {
  RUN_STATUS="failed: $1"
  write_record || true
  echo "ERROR: $2" >&2
  exit 1
}

# s3_prefix_must_be_empty <bucket> <prefix> (N74): fail closed. A listing
# error is NOT read as "nothing there".
s3_prefix_must_be_empty() {
  local bucket="$1" prefix="$2" out rc=0
  out="$(aws s3api list-objects-v2 --bucket "${bucket}" --prefix "${prefix}" --max-keys 1 --no-paginate \
    --expected-bucket-owner "${TF_ENV_AWS_ACCOUNT}" --query KeyCount --output text 2>&1)" || rc=$?
  if [[ ${rc} -ne 0 ]]; then
    echo "ERROR: could not list s3://${bucket}/${prefix} (${out}); refusing to assume it is empty." >&2
    exit 3
  fi
  rc=0
  pipeline_s3_prefix_empty "${out}" || rc=$?
  if [[ ${rc} -eq 1 ]]; then
    echo "ERROR: s3://${bucket}/${prefix} already holds objects; refusing to write into it." >&2
    exit 3
  elif [[ ${rc} -ne 0 ]]; then
    echo "ERROR: unexpected listing result '${out}' for s3://${bucket}/${prefix}." >&2
    exit 3
  fi
}

# head_json <bucket> <key>: head-object JSON, or exit 3 (read-only, owner-pinned).
head_json() {
  local out
  if ! out="$(aws s3api head-object --bucket "$1" --key "$2" --expected-bucket-owner "${TF_ENV_AWS_ACCOUNT}" --output json 2>&1)"; then
    echo "ERROR: s3://$1/$2 not found or not readable: ${out}" >&2
    exit 3
  fi
  printf '%s' "${out}"
}

# record_jar <name> <s3-uri> (N68): head-object (VersionId, ETag, size), then
# download that exact version and hash it. Sets RECORDED_SPEC and appends to
# JARS_JSON. Fails closed on a missing VersionId, a size mismatch or any error.
record_jar() {
  local name="$1" uri="$2" bucket key head ver etag size sha tmp got
  bucket="$(pipeline_jar_field "${uri}|x|y" bucket)"
  key="$(pipeline_jar_field "${uri}|x|y" key)"
  head="$(head_json "${bucket}" "${key}")"
  ver="$(printf '%s' "${head}" | jq -r '.VersionId // ""')"
  etag="$(printf '%s' "${head}" | jq -r '.ETag // "" | gsub("\""; "")')"
  size="$(printf '%s' "${head}" | jq -r '.ContentLength // 0')"
  if [[ -z "${ver}" ]]; then
    echo "ERROR: ${uri} has no S3 VersionId (is bucket versioning enabled?); cannot pin the jar." >&2
    exit 3
  fi
  tmp="${JAR_TMP_DIR}/${name}.jar"
  if ! aws s3api get-object --bucket "${bucket}" --key "${key}" --version-id "${ver}" \
    --expected-bucket-owner "${TF_ENV_AWS_ACCOUNT}" "${tmp}" >/dev/null; then
    echo "ERROR: could not download ${uri} version ${ver} to hash it." >&2
    exit 3
  fi
  got="$(wc -c <"${tmp}" | tr -d ' ')"
  if [[ "${got}" != "${size}" || "${size}" -le 0 ]]; then
    echo "ERROR: ${uri}: downloaded ${got} bytes, head-object said ${size}." >&2
    exit 3
  fi
  sha="$(shasum -a 256 "${tmp}" | awk '{print $1}')"
  rm -f "${tmp}"
  RECORDED_SPEC="$(pipeline_jar_spec "${uri}" "${ver}" "${sha}")" || exit 3
  JARS_JSON="$(printf '%s' "${JARS_JSON}" | jq -c --arg n "${name}" --arg u "${uri}" --arg v "${ver}" --arg e "${etag}" \
    --arg s "${sha}" --arg z "${size}" '. + [{name:$n,uri:$u,version_id:$v,etag:$e,sha256:$s,size_bytes:($z|tonumber)}]')"
  echo "  ${name}: ${uri}"
  echo "    VersionId ${ver}  ETag ${etag}  ${size} bytes"
  echo "    sha256    ${sha}"
}

# verify_inputs_unchanged (N68/N74): re-read the jar and dataset identities and
# compare with what was recorded. The EMR path cannot pin an object version, so
# this is how an overwrite during the run is caught; on ECS the jars are pinned
# and only the dataset (which Hadoop reads unpinned) can drift.
verify_inputs_unchanged() {
  local n i uri bucket key head ver etag want_ver want_etag
  n="$(printf '%s' "${JARS_JSON}" | jq 'length')"
  i=0
  while [[ ${i} -lt ${n} ]]; do
    uri="$(printf '%s' "${JARS_JSON}" | jq -r ".[${i}].uri")"
    want_ver="$(printf '%s' "${JARS_JSON}" | jq -r ".[${i}].version_id")"
    want_etag="$(printf '%s' "${JARS_JSON}" | jq -r ".[${i}].etag")"
    bucket="$(pipeline_jar_field "${uri}|x|y" bucket)"
    key="$(pipeline_jar_field "${uri}|x|y" key)"
    head="$(head_json "${bucket}" "${key}")"
    ver="$(printf '%s' "${head}" | jq -r '.VersionId // ""')"
    etag="$(printf '%s' "${head}" | jq -r '.ETag // "" | gsub("\""; "")')"
    if [[ "${ver}" != "${want_ver}" || "${etag}" != "${want_etag}" ]]; then
      fail "input changed during run" "${uri} changed during the run (VersionId ${want_ver} -> ${ver}, ETag ${want_etag} -> ${etag}). The outputs cannot be attributed to the recorded jar."
    fi
    i=$((i + 1))
  done
  head="$(head_json "${RAW_BUCKET}" "dblp/dblp.xml")"
  ver="$(printf '%s' "${head}" | jq -r '.VersionId // ""')"
  etag="$(printf '%s' "${head}" | jq -r '.ETag // "" | gsub("\""; "")')"
  if [[ "${ver}" != "$(printf '%s' "${DATASET_JSON}" | jq -r '.version_id')" || "${etag}" != "$(printf '%s' "${DATASET_JSON}" | jq -r '.etag')" ]]; then
    fail "input changed during run" "${DBLP_XML} changed during the run (VersionId/ETag differ from the recorded snapshot)."
  fi
  echo "  Recorded jars and dataset snapshot are unchanged."
}

echo "=== STEP: Verify target ==="
tf_env_require_aws_target "${ENVIRONMENT}"
tf_env_init_backend "${ENVIRONMENT}"
tf_env_require_state_identity "${ENVIRONMENT}"

echo "=== STEP: Verify state bucket names ==="
# The steps below address <prefix>-<purpose> buckets; require the verified
# state to own exactly those names so steps can never be pointed at buckets
# outside this environment.
STATE_BUCKETS_JSON="$(tf_env_output_json "${ENVIRONMENT}" s3_bucket_names)"
for PURPOSE in artifacts raw curated graph logs; do
  GOT="$(printf '%s' "${STATE_BUCKETS_JSON}" | jq -r --arg p "${PURPOSE}" '.[$p] // ""')"
  if [[ "${GOT}" != "${PREFIX}-${PURPOSE}" ]]; then
    echo "ERROR: state ${TF_ENV_STATE_KEY} has ${PURPOSE} bucket '${GOT}', expected '${PREFIX}-${PURPOSE}'." >&2
    exit 3
  fi
done
echo "  Buckets match ${PREFIX}-*."

echo "=== STEP: Resolve compute path ==="
# State written before batch_compute existed has no such output: that state can
# only describe an EMR deployment, and the EMR path fails closed without a cluster.
MODE="$(tf_env_output "${ENVIRONMENT}" batch_compute 2>/dev/null || true)"
if [[ -z "${MODE}" ]]; then
  MODE="emr"
  echo "  state has no batch_compute output; assuming emr"
fi
if [[ "${MODE}" != "emr" && "${MODE}" != "ecs" ]]; then
  echo "ERROR: state batch_compute is '${MODE}', expected emr or ecs." >&2
  exit 3
fi
echo "  Compute path: ${MODE}"

echo "=== STEP: Record pipeline context ==="
COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
echo "  Run ID:      ${RUN_ID}"
echo "  Environment: ${ENVIRONMENT}"
echo "  Commit:      ${COMMIT_SHA}"
GIT_DIRTY_FLAG="false"
if ! GIT_STATUS_OUT="$(git -C "${REPO_ROOT}" status --porcelain)"; then
  echo "ERROR: 'git status' failed." >&2
  exit 1
fi
[[ -n "${GIT_STATUS_OUT}" ]] && GIT_DIRTY_FLAG="true"
echo "  Git dirty:   ${GIT_DIRTY_FLAG}"
echo "  Stage ids:   ${RUN_ID_HADOOP} ${RUN_ID_ER} ${RUN_ID_SPARK}"
echo "  Timeout:     ${TIMEOUT_MINUTES} minutes per stage (in-container); client grace ${CLIENT_GRACE_SECONDS}s"

if [[ "${MODE}" == "emr" ]]; then
  echo "=== STEP: Resolve EMR cluster ID ==="
  # Verified state only; plain assignment so set -e stops on a guard failure.
  CLUSTER_ID="$(tf_env_output "${ENVIRONMENT}" emr_cluster_id)"
  if [[ -z "${CLUSTER_ID}" ]]; then
    echo "ERROR: emr_cluster_id is empty in state ${TF_ENV_STATE_KEY}." >&2
    exit 3
  fi
  echo "  Cluster ID: ${CLUSTER_ID}"
fi

DBLP_XML="s3://${RAW_BUCKET}/dblp/dblp.xml"
PIPE_ROOT="s3://${CURATED_BUCKET}/${RUN_ID}"
HADOOP_OUT="${PIPE_ROOT}/hadoop"
EXPORT_DIR="${HADOOP_OUT}/export"
ER_OUT="${PIPE_ROOT}/er"
SPARK_OUT="s3://${GRAPH_BUCKET}/${RUN_ID}/spark"
SPARK_CKPT="s3://${GRAPH_BUCKET}/${RUN_ID}/checkpoints"

echo "=== STEP: Record dataset snapshot (required) ==="
# N74: no `|| echo unknown`. The DBLP input must exist, be non-empty, and its
# VersionId/ETag/size are recorded (and re-checked after the run).
DS_HEAD="$(head_json "${RAW_BUCKET}" "dblp/dblp.xml")"
DATASET_JSON="$(printf '%s' "${DS_HEAD}" | jq -c --arg u "${DBLP_XML}" \
  '{uri:$u,version_id:(.VersionId // ""),etag:((.ETag // "")|gsub("\"";"")),size_bytes:(.ContentLength // 0),last_modified:(.LastModified // "")}')"
if [[ "$(printf '%s' "${DATASET_JSON}" | jq -r '.size_bytes')" -le 0 || -z "$(printf '%s' "${DATASET_JSON}" | jq -r '.version_id')" ]]; then
  echo "ERROR: dataset snapshot of ${DBLP_XML} is empty or has no VersionId: ${DATASET_JSON}" >&2
  exit 3
fi
echo "  ${DATASET_JSON}"

echo "=== STEP: Record job jars: S3 VersionId, ETag, sha256 (N68) ==="
JAR_TMP_DIR="$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/scholarchain-jars.XXXXXX")"
RECORDED_SPEC=""
HADOOP_JAR="s3://${ARTIFACTS_BUCKET}/jars/scholarchain-hadoop.jar"
ER_JAR="s3://${ARTIFACTS_BUCKET}/jars/scholarchain-entity-resolution.jar"
SPARK_JAR="s3://${ARTIFACTS_BUCKET}/jars/scholarchain-spark.jar"
record_jar hadoop "${HADOOP_JAR}"
HADOOP_SPEC="${RECORDED_SPEC}"
record_jar entity-resolution "${ER_JAR}"
ER_SPEC="${RECORDED_SPEC}"
record_jar spark "${SPARK_JAR}"
SPARK_SPEC="${RECORDED_SPEC}"

echo "=== STEP: Verify output locations are unused (read-only, fail closed) ==="
# The base id is unique per run, so any hit means something is wrong; any
# listing error is also fatal (N74).
s3_prefix_must_be_empty "${CURATED_BUCKET}" "${RUN_ID}/"
s3_prefix_must_be_empty "${GRAPH_BUCKET}" "${RUN_ID}/"
echo "  Hadoop output: ${HADOOP_OUT}"
echo "  ER output:     ${ER_OUT}"
echo "  Spark output:  ${SPARK_OUT}"

RECORD_DIR="${REPO_ROOT}/local-runs/pipeline/${ENVIRONMENT}"
RECORD_FILE="${RECORD_DIR}/${RUN_ID}.json"
mkdir -p "${RECORD_DIR}"

J01="${HADOOP_OUT}/stages/j01-work_records"
J06="${HADOOP_OUT}/stages/j06-teams"
J07="${HADOOP_OUT}/stages/j07-pair_candidates"
J03="${HADOOP_OUT}/stages/j03-identifiers"
J04="${HADOOP_OUT}/stages/j04-blocks"
J05="${HADOOP_OUT}/stages/j05-features"

# jar_evidence: the jar lines of the evidence block.
jar_evidence() {
  printf '%s' "${JARS_JSON}" | jq -r '.[] | "  Jar \(.name): \(.uri)\n      VersionId \(.version_id)  ETag \(.etag)  sha256 \(.sha256)  \(.size_bytes) bytes"'
  printf '  Dataset:       %s\n      VersionId %s  ETag %s  %s bytes\n' "${DBLP_XML}" \
    "$(printf '%s' "${DATASET_JSON}" | jq -r '.version_id')" "$(printf '%s' "${DATASET_JSON}" | jq -r '.etag')" \
    "$(printf '%s' "${DATASET_JSON}" | jq -r '.size_bytes')"
}

if [[ "${MODE}" == "ecs" ]]; then
  ##############################################################################
  # ECS Fargate path
  ##############################################################################
  echo "=== STEP: Resolve ECS batch configuration ==="
  RUN_CFG="$(tf_env_output_json "${ENVIRONMENT}" batch_run_config)"
  if [[ "$(printf '%s' "${RUN_CFG}" | jq -r 'type')" != "object" ]]; then
    echo "ERROR: state ${TF_ENV_STATE_KEY} has batch_compute=ecs but no batch_run_config." >&2
    exit 3
  fi
  cfg() { printf '%s' "${RUN_CFG}" | jq -r "$1"; }
  ECS_CLUSTER="$(cfg '.cluster_arn')"
  ECS_CONTAINER="$(cfg '.container_name')"
  ECS_SUBNETS="$(cfg '.subnet_ids | join(",")')"
  ECS_SG="$(cfg '.security_group_id')"
  LOG_GROUP="$(cfg '.log_group')"
  ECS_IMAGE_REPO="$(cfg '.image_repository')"
  ECS_IMAGE_TAG="$(cfg '.image_tag')"
  CAPACITY_STRATEGY="$(cfg '.capacity_strategy // "fargate"')"
  FAM_HADOOP="$(cfg '.task_definitions.hadoop')"
  FAM_ER="$(cfg '.task_definitions.er')"
  FAM_SPARK="$(cfg '.task_definitions.spark')"
  for FAM in "${FAM_HADOOP}" "${FAM_ER}" "${FAM_SPARK}"; do
    pipeline_ecs_validate "${PREFIX}" "${TF_ENV_AWS_REGION}" "${TF_ENV_AWS_ACCOUNT}" \
      "${ECS_CLUSTER}" "${FAM}" "${ECS_SUBNETS}" "${ECS_SG}" || exit 3
  done
  if [[ "${ECS_CONTAINER}" != "runner" || ! "${ECS_IMAGE_TAG}" =~ ^[0-9a-f]{40}$ || "${ECS_IMAGE_REPO}" != "${PREFIX}-batch-runner" ]]; then
    echo "ERROR: unexpected batch_run_config (container '${ECS_CONTAINER}', repo '${ECS_IMAGE_REPO}', tag '${ECS_IMAGE_TAG}')." >&2
    exit 3
  fi
  if [[ "${CAPACITY_STRATEGY}" != "spot_with_fargate_fallback" && "${CAPACITY_STRATEGY}" != "fargate" ]]; then
    echo "ERROR: unexpected capacity_strategy '${CAPACITY_STRATEGY}' in batch_run_config." >&2
    exit 3
  fi
  NETWORK_JSON="$(pipeline_ecs_network_json "${ECS_SUBNETS}" "${ECS_SG}")"
  echo "  Cluster:  ${ECS_CLUSTER}"
  echo "  Families: ${FAM_HADOOP} ${FAM_ER} ${FAM_SPARK}"
  echo "  Image:    ${ECS_IMAGE_REPO}:${ECS_IMAGE_TAG}"
  echo "  Capacity: ${CAPACITY_STRATEGY}"

  echo "=== STEP: Verify batch runner image exists (read-only) ==="
  IMAGE_DIGEST=""
  if ! IMAGE_DIGEST="$(aws ecr describe-images --repository-name "${ECS_IMAGE_REPO}" \
    --image-ids "imageTag=${ECS_IMAGE_TAG}" --query 'imageDetails[0].imageDigest' --output text 2>/dev/null)" \
    || [[ "${IMAGE_DIGEST}" != sha256:* ]]; then
    echo "ERROR: ${ECS_IMAGE_REPO}:${ECS_IMAGE_TAG} not found in ECR. Nothing was run." >&2
    echo "  The batch runner image (docker/batch-runner: bash, JRE 17, AWS CLI v2, coreutils," >&2
    echo "  Hadoop with hadoop-aws, Spark with hadoop-aws) must be built and pushed first." >&2
    exit 3
  fi
  echo "  ${IMAGE_DIGEST}"
  IMAGE_REF="${ECS_IMAGE_REPO}:${ECS_IMAGE_TAG}@${IMAGE_DIGEST}"

  STAGES_JSON="[]"
  write_record

  # ecs_describe <task-arn>: describe-tasks with bounded retries (N66). Prints
  # the JSON; returns 1 after 5 failed attempts (an error, or no such task).
  ecs_describe() {
    local arn="$1" n=0 out
    while [[ ${n} -lt 5 ]]; do
      n=$((n + 1))
      if out="$(aws ecs describe-tasks --cluster "${ECS_CLUSTER}" --tasks "${arn}" --output json 2>/dev/null)" \
        && [[ "$(printf '%s' "${out}" | jq -r '(.tasks | length)')" == "1" ]]; then
        printf '%s' "${out}"
        return 0
      fi
      [[ ${n} -lt 5 ]] && sleep $((n * 5))
    done
    return 1
  }

  # run_ecs_stage <stage> <family> <script>: run-task (Spot first, FARGATE
  # fallback at launch), bounded wait, require exit 0. Sets LAST_TASK_ARN.
  run_ecs_stage() {
    local stage="$1" family="$2" script="$3" overrides out="" task_arn desc status exit_code stop_code reason
    local created started stopped wall tid launch_rc provider capacity cp_used strategy_json
    local -a providers
    overrides="$(pipeline_ecs_overrides_json "${ECS_CONTAINER}" "${script}")"
    if [[ "${CAPACITY_STRATEGY}" == "spot_with_fargate_fallback" ]]; then
      providers=(FARGATE_SPOT FARGATE)
    else
      providers=(FARGATE)
    fi
    echo "=== STEP: ECS task ${stage} (${family}) ==="
    task_arn=""
    for provider in "${providers[@]}"; do
      strategy_json="$(pipeline_ecs_capacity_json "${provider}")"
      launch_rc=0
      out="$(aws ecs run-task --cluster "${ECS_CLUSTER}" --task-definition "${family}" \
        --capacity-provider-strategy "${strategy_json}" --count 1 --started-by "${RUN_ID}" \
        --network-configuration "${NETWORK_JSON}" --overrides "${overrides}" --output json)" || launch_rc=$?
      if [[ ${launch_rc} -eq 0 ]] && [[ "$(printf '%s' "${out}" | pipeline_ecs_task_field failures)" == "0" ]]; then
        task_arn="$(printf '%s' "${out}" | pipeline_ecs_task_field arn)"
        capacity="${provider}"
        break
      fi
      echo "  run-task on ${provider} did not start (rc ${launch_rc}; failures: $(printf '%s' "${out}" | jq -c '.failures // []' 2>/dev/null || echo '[]'))" >&2
      [[ "${provider}" == "FARGATE_SPOT" ]] && echo "  Spot capacity unavailable at launch: falling back to FARGATE." >&2
    done
    if [[ -z "${task_arn}" ]]; then
      fail "${stage} not started" "run-task for ${stage} failed to start on ${providers[*]}."
    fi
    CURRENT_TASK_ARN="${task_arn}"
    tid="${task_arn##*/}"
    echo "  ${stage} task ARN: ${task_arn} (requested capacity: ${capacity})"
    echo "  logs: ${LOG_GROUP} stream ${stage}/${ECS_CONTAINER}/${tid}"
    SECONDS=0
    while true; do
      if ! desc="$(ecs_describe "${task_arn}")"; then
        stop_ecs_task "${task_arn}" "run-bounded-pipeline ${RUN_ID} ${stage} describe-tasks failed" || true
        CURRENT_TASK_ARN=""
        STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${stage}" --arg f "${family}" --arg a "${task_arn}" --arg w "${SECONDS}" \
          '. + [{stage:$s,task_definition:$f,task_arn:$a,exit_code:null,stop_code:"DescribeFailed",wall_seconds:($w|tonumber)}]')"
        fail "${stage} describe-tasks failed" "describe-tasks failed 5 times for ${task_arn}; the task was stopped${STOP_FAILED:+ (STOP FAILED: ${STOP_FAILED})}."
      fi
      status="$(printf '%s' "${desc}" | pipeline_ecs_task_field status)"
      echo "  ${stage} status: ${status} (${SECONDS}s elapsed)"
      [[ "${status}" == "STOPPED" ]] && break
      if [[ ${SECONDS} -ge ${CLIENT_TIMEOUT_SECONDS} ]]; then
        # The in-container deadline (TIMEOUT_SECONDS) should have ended the
        # task already; this is the backstop. A failed stop is an error.
        stop_ecs_task "${task_arn}" "run-bounded-pipeline ${RUN_ID} ${stage} timeout" || true
        CURRENT_TASK_ARN=""
        STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${stage}" --arg f "${family}" --arg a "${task_arn}" --arg w "${SECONDS}" \
          '. + [{stage:$s,task_definition:$f,task_arn:$a,exit_code:null,stop_code:"RunnerTimeout",wall_seconds:($w|tonumber)}]')"
        fail "${stage} timed out" "${stage} timed out after ${TIMEOUT_MINUTES} minutes (+${CLIENT_GRACE_SECONDS}s grace); task stop requested${STOP_FAILED:+ BUT FAILED for ${STOP_FAILED}}."
      fi
      sleep 30
    done
    CURRENT_TASK_ARN=""
    wall="${SECONDS}"
    exit_code="$(printf '%s' "${desc}" | pipeline_ecs_task_field exit_code)"
    stop_code="$(printf '%s' "${desc}" | pipeline_ecs_task_field stop_code)"
    reason="$(printf '%s' "${desc}" | pipeline_ecs_task_field stopped_reason)"
    created="$(printf '%s' "${desc}" | pipeline_ecs_task_field created_at)"
    started="$(printf '%s' "${desc}" | pipeline_ecs_task_field started_at)"
    stopped="$(printf '%s' "${desc}" | pipeline_ecs_task_field stopped_at)"
    cp_used="$(printf '%s' "${desc}" | pipeline_ecs_task_field capacity_provider)"
    STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${stage}" --arg f "${family}" --arg a "${task_arn}" \
      --arg x "${exit_code}" --arg sc "${stop_code}" --arg sr "${reason}" --arg c "${created}" --arg st "${started}" \
      --arg sp "${stopped}" --arg w "${wall}" --arg cp "${cp_used}" \
      '. + [{stage:$s,task_definition:$f,task_arn:$a,capacity_provider:$cp,exit_code:(if $x=="" then null else ($x|tonumber) end),stop_code:$sc,
             stopped_reason:$sr,created_at:$c,started_at:$st,stopped_at:$sp,wall_seconds:($w|tonumber)}]')"
    LAST_TASK_ARN="${task_arn}"
    if [[ "${exit_code}" != "0" ]]; then
      if [[ "${stop_code}" == "SpotInterruption" ]]; then
        echo "ERROR: ${stage} was interrupted by a Fargate Spot reclaim after launch. It is NOT retried automatically" >&2
        echo "  (stage outputs are not idempotent). Set batch_use_spot = false or re-run the pipeline." >&2
      fi
      fail "${stage} exit '${exit_code}'" "${stage} task exited '${exit_code}' (stop code ${stop_code}: ${reason}). See ${LOG_GROUP}."
    fi
    write_record
  }

  HADOOP_SCRIPT="$(pipeline_ecs_hadoop_script "${TIMEOUT_SECONDS}" "${HADOOP_SPEC}" "${RUN_ID_HADOOP}" "${RUN_TS}" "${TF_ENV_AWS_REGION}" "${EXPORT_DIR}" \
    "dblp-extraction|${DBLP_XML}|${J01}" \
    "team-extraction|${J01}|${J06}" \
    "pair-expansion|${J06}|${J07}" \
    "graph-export|${J01},${J06},${J07}|${EXPORT_DIR}" \
    "identifier-extraction|${J01}|${J03}" \
    "blocking|${J01}|${J04}" \
    "feature-construction|${J04}|${J05}")"
  run_ecs_stage hadoop "${FAM_HADOOP}" "${HADOOP_SCRIPT}"
  TASK_HADOOP="${LAST_TASK_ARN}"

  ER_SCRIPT="$(pipeline_ecs_er_script "${TIMEOUT_SECONDS}" "${ER_SPEC}" "${EXPORT_DIR}/work_records" "${ER_OUT}" "${RUN_ID_ER}" "${RUN_ID_HADOOP}")"
  run_ecs_stage er "${FAM_ER}" "${ER_SCRIPT}"
  TASK_ER="${LAST_TASK_ARN}"

  SPARK_SCRIPT="$(pipeline_ecs_spark_script "${TIMEOUT_SECONDS}" "${SPARK_SPEC}" "${EXPORT_DIR}" "${ER_OUT}/match-decisions" "${SPARK_OUT}" \
    "${RUN_ID_SPARK}" "${RUN_TS}" "${COMMIT_SHA}" "${GIT_DIRTY_FLAG}" "${TF_ENV_AWS_REGION}")"
  run_ecs_stage spark "${FAM_SPARK}" "${SPARK_SCRIPT}"
  TASK_SPARK="${LAST_TASK_ARN}"

  echo "=== STEP: Re-check recorded inputs ==="
  verify_inputs_unchanged

  RUN_STATUS="succeeded"
  write_record
  STAGE_SUMMARY="$(printf '%s' "${STAGES_JSON}" | jq -r 'map("\(.stage) exit=\(.exit_code) wall=\(.wall_seconds)s capacity=\(.capacity_provider)") | join("; ")')"

  cat <<EOF

================================================================================
PIPELINE RUN EVIDENCE (ecs)
  Run ID:        ${RUN_ID}
  Environment:   ${ENVIRONMENT}
  Account:       ${TF_ENV_AWS_ACCOUNT}
  Region:        ${TF_ENV_AWS_REGION}
  State:         s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
  Cluster:       ${ECS_CLUSTER}
  Image:         ${IMAGE_REF}
  Capacity:      ${CAPACITY_STRATEGY}
  Commit:        ${COMMIT_SHA}
  Stage run ids: ${RUN_ID_HADOOP} ${RUN_ID_ER} ${RUN_ID_SPARK}
  Run timestamp: ${RUN_TS}
  Git dirty:     ${GIT_DIRTY_FLAG}
  Hadoop task:   ${TASK_HADOOP}
  ER task:       ${TASK_ER}
  Spark task:    ${TASK_SPARK}
  Stage results: ${STAGE_SUMMARY}
  Run record:    ${RECORD_FILE}
$(jar_evidence)
  Hadoop Output: ${HADOOP_OUT}/
  ER Output:     ${ER_OUT}/
  Graph Output:  ${SPARK_OUT}/
  Logs:          ${LOG_GROUP} (streams hadoop/, er/, spark/)
================================================================================
EOF
  tf_env_exit 0
fi

################################################################################
# EMR path
################################################################################

LAST_TASK_ARN=""
CAPACITY_STRATEGY="emr"
LOG_GROUP="s3://${LOGS_BUCKET}/emr-logs/"
STAGES_JSON="[]"
write_record

# emr_step_state <step-id>: describe-step with bounded retries (N66). Prints
# the state; returns 1 after 5 failed attempts.
emr_step_state() {
  local step_id="$1" n=0 state
  while [[ ${n} -lt 5 ]]; do
    n=$((n + 1))
    if state="$(aws emr describe-step --cluster-id "${CLUSTER_ID}" --step-id "${step_id}" \
      --query 'Step.Status.State' --output text 2>/dev/null)" && [[ -n "${state}" && "${state}" != "None" ]]; then
      printf '%s' "${state}"
      return 0
    fi
    [[ ${n} -lt 5 ]] && sleep $((n * 5))
  done
  return 1
}

# wait_for_step <label> <step-id>: poll until COMPLETED; fail (and cancel the
# step on timeout, N74) otherwise. Records the step in the run record.
wait_for_step() {
  local label="$1" step_id="$2" state=""
  SECONDS=0
  while true; do
    if ! state="$(emr_step_state "${step_id}")"; then
      cancel_emr_step "${step_id}" || true
      CURRENT_STEP_ID=""
      fail "${label} describe-step failed" "describe-step failed 5 times for ${step_id}; cancellation requested."
    fi
    echo "  ${label} status: ${state} (${SECONDS}s elapsed)"
    if [[ "${state}" == "COMPLETED" ]]; then
      CURRENT_STEP_ID=""
      STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${label}" --arg i "${step_id}" --arg st "${state}" --arg w "${SECONDS}" \
        '. + [{stage:$s,step_id:$i,state:$st,wall_seconds:($w|tonumber)}]')"
      write_record
      return 0
    elif [[ "${state}" == "FAILED" || "${state}" == "CANCELLED" || "${state}" == "INTERRUPTED" ]]; then
      CURRENT_STEP_ID=""
      STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${label}" --arg i "${step_id}" --arg st "${state}" --arg w "${SECONDS}" \
        '. + [{stage:$s,step_id:$i,state:$st,wall_seconds:($w|tonumber)}]')"
      fail "${label} ${state}" "${label} ${state}."
    fi
    if [[ ${SECONDS} -ge ${TIMEOUT_SECONDS} ]]; then
      # N74: stop the step on the cluster, not just the wait.
      cancel_emr_step "${step_id}" || true
      CURRENT_STEP_ID=""
      STAGES_JSON="$(printf '%s' "${STAGES_JSON}" | jq -c --arg s "${label}" --arg i "${step_id}" --arg w "${SECONDS}" \
        '. + [{stage:$s,step_id:$i,state:"RunnerTimeout",wall_seconds:($w|tonumber)}]')"
      fail "${label} timed out" "${label} timed out after ${TIMEOUT_MINUTES} minutes; cancel-steps was requested."
    fi
    sleep 30
  done
}

# submit_step <label> <json>: prints the step id via LAST_STEP_ID.
submit_step() {
  local label="$1" json="$2"
  LAST_STEP_ID="$(aws emr add-steps \
    --cluster-id "${CLUSTER_ID}" \
    --steps "${json}" \
    --query 'StepIds[0]' \
    --output text)"
  CURRENT_STEP_ID="${LAST_STEP_ID}"
  echo "  ${label} step ID: ${LAST_STEP_ID}"
}

# run_cmd_step <label> <arg>...: a command-runner.jar step, submitted then awaited.
run_cmd_step() {
  local label="$1" json
  shift
  json="$(pipeline_step_json "ScholarChain-${label}-${RUN_ID}" CONTINUE command-runner.jar "$@")"
  submit_step "${label}" "${json}"
  wait_for_step "${label}" "${LAST_STEP_ID}"
}

run_hadoop_job() {
  local job="$1" in="$2" out="$3" args=() line
  while IFS= read -r line; do args+=("${line}"); done < <(
    pipeline_hadoop_args "${HADOOP_JAR}" "${RUN_ID_HADOOP}" "${RUN_TS}" "${job}" "${in}" "${out}"
  )
  echo "=== STEP: Hadoop job ${job} ==="
  run_cmd_step "Hadoop-${job}" "${args[@]}"
  HADOOP_STEP_IDS="${HADOOP_STEP_IDS} ${LAST_STEP_ID}"
}

HADOOP_STEP_IDS=""
run_hadoop_job dblp-extraction "${DBLP_XML}" "${J01}"
run_hadoop_job team-extraction "${J01}" "${J06}"
run_hadoop_job pair-expansion "${J06}" "${J07}"
run_hadoop_job graph-export "${J01},${J06},${J07}" "${EXPORT_DIR}"
run_hadoop_job identifier-extraction "${J01}" "${J03}"
run_hadoop_job blocking "${J01}" "${J04}"
run_hadoop_job feature-construction "${J04}" "${J05}"

echo "=== STEP: Verify Hadoop export (ExportVerifier, exit 0 = ok) ==="
run_cmd_step "Hadoop-ExportVerifier" hadoop jar "${HADOOP_JAR}" edu.scholarchain.hadoop.driver.ExportVerifier "${EXPORT_DIR}"
VERIFY_STEP_ID="${LAST_STEP_ID}"

echo "=== STEP: Entity resolution ==="
# N65: the ER script (a quoted `bash -c` body) goes through pipeline_step_json,
# which builds the step with jq --args.
ER_SCRIPT="$(pipeline_er_script "${ER_JAR}" "${EXPORT_DIR}/work_records" "${ER_OUT}" "${RUN_ID_ER}" "${RUN_ID_HADOOP}")"
run_cmd_step "EntityResolution" bash -c "${ER_SCRIPT}"
ER_STEP_ID="${LAST_STEP_ID}"

echo "=== STEP: Spark pipeline ==="
SPARK_ARGS=()
while IFS= read -r LINE; do SPARK_ARGS+=("${LINE}"); done < <(
  pipeline_spark_args "${SPARK_JAR}" "${EXPORT_DIR}" "${ER_OUT}/match-decisions" "${SPARK_OUT}" \
    "${RUN_ID_SPARK}" "${RUN_TS}" "${COMMIT_SHA}" "${GIT_DIRTY_FLAG}" "${SPARK_CKPT}"
)
run_cmd_step "Spark" "${SPARK_ARGS[@]}"
SPARK_STEP_ID="${LAST_STEP_ID}"

echo "=== STEP: Re-check recorded inputs ==="
verify_inputs_unchanged

RUN_STATUS="succeeded"
write_record

cat <<EOF

================================================================================
PIPELINE RUN EVIDENCE
  Run ID:        ${RUN_ID}
  Environment:   ${ENVIRONMENT}
  Account:       ${TF_ENV_AWS_ACCOUNT}
  Region:        ${TF_ENV_AWS_REGION}
  State:         s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
  Cluster:       ${CLUSTER_ID}
  Commit:        ${COMMIT_SHA}
  Stage run ids: ${RUN_ID_HADOOP} ${RUN_ID_ER} ${RUN_ID_SPARK}
  Run timestamp: ${RUN_TS}
  Git dirty:     ${GIT_DIRTY_FLAG}
  Hadoop Steps: ${HADOOP_STEP_IDS}
  Verify Step:   ${VERIFY_STEP_ID}
  ER Step:       ${ER_STEP_ID}
  Spark Step:    ${SPARK_STEP_ID}
  Run record:    ${RECORD_FILE}
$(jar_evidence)
  Hadoop Output: ${HADOOP_OUT}/
  ER Output:     ${ER_OUT}/
  Graph Output:  ${SPARK_OUT}/
  Logs:          s3://${LOGS_BUCKET}/emr-logs/
================================================================================
EOF

tf_env_exit 0
