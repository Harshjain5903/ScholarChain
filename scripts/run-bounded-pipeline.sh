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
PREFIX="scholarchain-${ENVIRONMENT}"
RUN_ID="run-$(date -u +%Y%m%dT%H%M%SZ)-$(head -c 4 /dev/urandom | xxd -p)"
ARTIFACTS_BUCKET="${PREFIX}-artifacts"
RAW_BUCKET="${PREFIX}-raw"
CURATED_BUCKET="${PREFIX}-curated"
GRAPH_BUCKET="${PREFIX}-graph"
LOGS_BUCKET="${PREFIX}-logs"

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
  if command -v jq >/dev/null 2>&1; then
    GOT="$(printf '%s' "${STATE_BUCKETS_JSON}" | jq -r --arg p "${PURPOSE}" '.[$p] // ""')"
  else
    GOT="$(printf '%s' "${STATE_BUCKETS_JSON}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1]) or "")' "${PURPOSE}")"
  fi
  if [[ "${GOT}" != "${PREFIX}-${PURPOSE}" ]]; then
    echo "ERROR: state ${TF_ENV_STATE_KEY} has ${PURPOSE} bucket '${GOT}', expected '${PREFIX}-${PURPOSE}'." >&2
    exit 3
  fi
done
echo "  Buckets match ${PREFIX}-*."

echo "=== STEP: Record pipeline context ==="
COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
echo "  Run ID:      ${RUN_ID}"
echo "  Environment: ${ENVIRONMENT}"
echo "  Commit:      ${COMMIT_SHA}"
echo "  Timeout:     ${TIMEOUT_MINUTES} minutes"

echo "=== STEP: Resolve EMR cluster ID ==="
# Verified state only; plain assignment so set -e stops on a guard failure.
CLUSTER_ID="$(tf_env_output "${ENVIRONMENT}" emr_cluster_id)"
if [[ -z "${CLUSTER_ID}" ]]; then
  echo "ERROR: emr_cluster_id is empty in state ${TF_ENV_STATE_KEY}." >&2
  exit 3
fi
echo "  Cluster ID: ${CLUSTER_ID}"

echo "=== STEP: Record dataset snapshot ==="
DATASET_SNAPSHOT="$(aws s3 ls "s3://${RAW_BUCKET}/" --recursive --summarize 2>/dev/null | tail -2 || echo 'unknown')"
echo "  Dataset snapshot:"
echo "  ${DATASET_SNAPSHOT}"

echo "=== STEP: Submit Hadoop step ==="
HADOOP_STEP_ID="$(aws emr add-steps \
  --cluster-id "${CLUSTER_ID}" \
  --steps "Type=CUSTOM_JAR,Name=ScholarChain-Hadoop-${RUN_ID},ActionOnFailure=CONTINUE,Jar=s3://${ARTIFACTS_BUCKET}/jars/scholarchain-hadoop.jar,Args=edu.scholarchain.hadoop.Main,--run-id,${RUN_ID},--input,s3://${RAW_BUCKET}/,--output,s3://${CURATED_BUCKET}/${RUN_ID}/" \
  --query 'StepIds[0]' \
  --output text)"
echo "  Hadoop step ID: ${HADOOP_STEP_ID}"

echo "=== STEP: Wait for Hadoop step (timeout: ${TIMEOUT_MINUTES}min) ==="
SECONDS=0
while true; do
  STATUS="$(aws emr describe-step \
    --cluster-id "${CLUSTER_ID}" \
    --step-id "${HADOOP_STEP_ID}" \
    --query 'Step.Status.State' \
    --output text)"
  echo "  Hadoop step status: ${STATUS} (${SECONDS}s elapsed)"
  if [[ "${STATUS}" == "COMPLETED" ]]; then
    break
  elif [[ "${STATUS}" == "FAILED" || "${STATUS}" == "CANCELLED" ]]; then
    echo "ERROR: Hadoop step ${STATUS}." >&2
    exit 1
  fi
  if [[ ${SECONDS} -ge ${TIMEOUT_SECONDS} ]]; then
    echo "ERROR: Hadoop step timed out after ${TIMEOUT_MINUTES} minutes." >&2
    exit 1
  fi
  sleep 30
done

echo "=== STEP: Submit Spark step ==="
SPARK_STEP_ID="$(aws emr add-steps \
  --cluster-id "${CLUSTER_ID}" \
  --steps "Type=CUSTOM_JAR,Name=ScholarChain-Spark-${RUN_ID},ActionOnFailure=CONTINUE,Jar=command-runner.jar,Args=spark-submit,--class,edu.scholarchain.spark.Main,--deploy-mode,cluster,s3://${ARTIFACTS_BUCKET}/jars/scholarchain-spark.jar,--run-id,${RUN_ID},--input,s3://${CURATED_BUCKET}/${RUN_ID}/,--output,s3://${GRAPH_BUCKET}/${RUN_ID}/" \
  --query 'StepIds[0]' \
  --output text)"
echo "  Spark step ID: ${SPARK_STEP_ID}"

echo "=== STEP: Wait for Spark step (timeout: ${TIMEOUT_MINUTES}min) ==="
SECONDS=0
while true; do
  STATUS="$(aws emr describe-step \
    --cluster-id "${CLUSTER_ID}" \
    --step-id "${SPARK_STEP_ID}" \
    --query 'Step.Status.State' \
    --output text)"
  echo "  Spark step status: ${STATUS} (${SECONDS}s elapsed)"
  if [[ "${STATUS}" == "COMPLETED" ]]; then
    break
  elif [[ "${STATUS}" == "FAILED" || "${STATUS}" == "CANCELLED" ]]; then
    echo "ERROR: Spark step ${STATUS}." >&2
    exit 1
  fi
  if [[ ${SECONDS} -ge ${TIMEOUT_SECONDS} ]]; then
    echo "ERROR: Spark step timed out after ${TIMEOUT_MINUTES} minutes." >&2
    exit 1
  fi
  sleep 30
done

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
  Hadoop Step:   ${HADOOP_STEP_ID}
  Spark Step:    ${SPARK_STEP_ID}
  Input:         s3://${RAW_BUCKET}/
  Curated:       s3://${CURATED_BUCKET}/${RUN_ID}/
  Graph Output:  s3://${GRAPH_BUCKET}/${RUN_ID}/
  Logs:          s3://${LOGS_BUCKET}/emr-logs/
================================================================================
EOF

tf_env_exit 0
