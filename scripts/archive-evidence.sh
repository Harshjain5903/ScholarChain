#!/usr/bin/env bash
set -euo pipefail

################################################################################
# archive-evidence.sh - Export grading/provenance evidence for ONE environment
#
# Usage: ./scripts/archive-evidence.sh <dev|prod>
#
# Writes to local-runs/evidence/<env>/<UTC timestamp>/ (local-runs/ is
# gitignored). All AWS calls are read-only (describe/list/get/scan/
# filter-log-events). Collects:
#   - context.json (env, account, region, state key, commit)
#   - terraform outputs and state address list for this env
#   - test reports and the sbt test-run marker
#   - plan checksums/metadata from local-runs/plans/<env>/
#   - ECR image digests and ECS service state (incl. embedding-worker)
#   - DynamoDB full scans (the CLI follows LastEvaluatedKey until the table
#     is exhausted) plus describe-table of ALL five tables: evidence-bundle,
#     run-ledger, artifact-registry (provenance), query-session and
#     entity-link-cache (N8); the scanned item count is checked against the
#     CLI's Count
#   - CloudWatch Logs: every event of every log group /scholarchain/<env>/*,
#     and of the Neptune audit log group /aws/neptune/<prefix>-neptune/audit
#     (N20)
#   - S3 Vectors: index list, per-index configuration, and per-index vector
#     key listing with metadata (vector data is not exported)
#   - SQS queue attributes (message counts; messages are NOT received, so the
#     DLQ is not disturbed)
#   - CloudWatch metrics snapshot (last hour)
#   - experiment outputs
# Then writes SHA256SUMS over every file and, only if every step succeeded,
# an ARCHIVE_COMPLETE marker that cleanup.sh requires before destroying.
#
# Not exported here: S3 bucket contents. cleanup.sh's separately confirmed
# "empty" phase adds bucket inventories (every key/version/size/etag) and a
# copy of the logs and artifacts buckets to this archive before emptying
# anything; raw/curated/graph/rag contents are reproducible from datasets/
# scripts and the pipeline and are not copied.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: archive-evidence.sh <dev|prod>" >&2
  exit 2
fi
ENVIRONMENT="$1"
tf_env_validate_name "${ENVIRONMENT}"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="${REPO_ROOT}/local-runs/evidence/${ENVIRONMENT}/${TIMESTAMP}"
PREFIX="scholarchain-${ENVIRONMENT}"
LOG_PREFIX="/scholarchain/${ENVIRONMENT}/"

declare -a STEP_RESULTS=()
FAILED_STEPS=0

record() {
  local name="$1" rc="$2"
  if [[ "${rc}" -eq 0 ]]; then
    STEP_RESULTS+=("OK      ${name}")
  else
    STEP_RESULTS+=("FAILED  ${name} (rc=${rc})")
    FAILED_STEPS=$((FAILED_STEPS + 1))
  fi
}

echo "=== STEP: Verify target ==="
tf_env_require_aws_target "${ENVIRONMENT}"
tf_env_init_backend "${ENVIRONMENT}"

echo "=== STEP: Create evidence directory ==="
mkdir -p "${EVIDENCE_DIR}"
echo "  Evidence dir: ${EVIDENCE_DIR}"

echo "=== STEP: Record context ==="
COMMIT_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
cat > "${EVIDENCE_DIR}/context.json" <<EOF
{
  "environment": "${ENVIRONMENT}",
  "timestamp": "${TIMESTAMP}",
  "commitSha": "${COMMIT_SHA}",
  "awsAccount": "${TF_ENV_AWS_ACCOUNT}",
  "awsRegion": "${TF_ENV_AWS_REGION}",
  "stateBucket": "${TF_ENV_STATE_BUCKET}",
  "stateKey": "${TF_ENV_STATE_KEY}",
  "collectedBy": "scripts/archive-evidence.sh"
}
EOF
echo "  Context recorded."

echo "=== STEP: Terraform outputs and state list ==="
rc=0
(cd "${TF_DIR}" && terraform output -json) > "${EVIDENCE_DIR}/terraform-outputs.json" || rc=$?
record "terraform output" "${rc}"
rc=0
(cd "${TF_DIR}" && terraform state list) > "${EVIDENCE_DIR}/terraform-state-list.txt" || rc=$?
record "terraform state list" "${rc}"

echo "=== STEP: Collect test reports ==="
if [[ -d "${REPO_ROOT}/target/test-reports" ]]; then
  cp -r "${REPO_ROOT}/target/test-reports" "${EVIDENCE_DIR}/test-reports"
  echo "  Test reports copied."
else
  echo "  No test reports found at target/test-reports."
fi
if [[ -f "${REPO_ROOT}/.claude/.last-test-run" ]]; then
  cp "${REPO_ROOT}/.claude/.last-test-run" "${EVIDENCE_DIR}/last-test-run"
  echo "  Test run marker copied."
fi

echo "=== STEP: Collect plan checksums ==="
PLAN_DIR="${REPO_ROOT}/local-runs/plans/${ENVIRONMENT}"
if compgen -G "${PLAN_DIR}/*.tfplan" >/dev/null; then
  mkdir -p "${EVIDENCE_DIR}/plans"
  for PLAN_FILE in "${PLAN_DIR}"/*.tfplan; do
    shasum -a 256 "${PLAN_FILE}" | sed "s#${PLAN_DIR}/##" >> "${EVIDENCE_DIR}/plans/plan-checksums.txt"
    if [[ -f "${PLAN_FILE}.meta" ]]; then
      cp "${PLAN_FILE}.meta" "${EVIDENCE_DIR}/plans/"
    fi
  done
  echo "  Plan checksums recorded."
else
  echo "  No plan files found in ${PLAN_DIR}."
fi

echo "=== STEP: Collect image digests and ECS state ==="
CLUSTER="${PREFIX}-cluster"
rc=0
{
  for SVC in query-api graph-retriever vector-retriever synthesis-worker verification-worker embedding-worker; do
    IMG="$(aws ecr describe-images \
      --repository-name "${PREFIX}-${SVC}" \
      --query 'imageDetails[].{digest:imageDigest,tags:imageTags,pushed:imagePushedAt}' \
      --output json)" || { rc=1; IMG='"ERROR"'; }
    echo "{\"service\":\"${SVC}\",\"images\":${IMG}}"
  done
} > "${EVIDENCE_DIR}/image-digests.jsonl"
record "ecr describe-images" "${rc}"
rc=0
{
  for SVC in query-api graph-retriever vector-retriever synthesis-worker verification-worker embedding-worker; do
    ECS="$(aws ecs describe-services \
      --cluster "${CLUSTER}" \
      --services "${PREFIX}-${SVC}" \
      --query 'services[0].{taskDefinition:taskDefinition,runningCount:runningCount,desiredCount:desiredCount}' \
      --output json)" || { rc=1; ECS='"ERROR"'; }
    echo "{\"service\":\"${SVC}\",\"ecs\":${ECS}}"
  done
} > "${EVIDENCE_DIR}/ecs-state.jsonl"
record "ecs describe-services" "${rc}"

echo "=== STEP: Export all DynamoDB tables (full scans) ==="
# N8: every table, including artifact-registry (provenance), query-session
# and entity-link-cache. cleanup.sh requires all five files.
DYNAMODB_TABLES="evidence-bundle run-ledger artifact-registry query-session entity-link-cache"
mkdir -p "${EVIDENCE_DIR}/dynamodb"
for TABLE in ${DYNAMODB_TABLES}; do
  rc=0
  # No --max-items / --page-size limit: the CLI follows LastEvaluatedKey and
  # aggregates every page into one document. --consistent-read so the export
  # reflects all writes acknowledged before the scan.
  aws dynamodb scan \
    --table-name "${PREFIX}-${TABLE}" \
    --consistent-read \
    --output json > "${EVIDENCE_DIR}/dynamodb/${TABLE}.json" || rc=$?
  if [[ ${rc} -eq 0 ]]; then
    # The aggregated output must be complete: no NextToken left and
    # Count == number of Items.
    if command -v jq >/dev/null 2>&1; then
      jq -e '(.NextToken == null) and ((.Items | length) == .Count)' \
        "${EVIDENCE_DIR}/dynamodb/${TABLE}.json" >/dev/null || rc=1
    else
      python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("NextToken") is None and len(d.get("Items",[]))==d.get("Count") else 1)' \
        "${EVIDENCE_DIR}/dynamodb/${TABLE}.json" || rc=1
    fi
  fi
  record "dynamodb scan ${PREFIX}-${TABLE}" "${rc}"
  rc=0
  aws dynamodb describe-table --table-name "${PREFIX}-${TABLE}" --output json \
    > "${EVIDENCE_DIR}/dynamodb/${TABLE}.describe.json" || rc=$?
  record "dynamodb describe-table ${PREFIX}-${TABLE}" "${rc}"
done

echo "=== STEP: Export CloudWatch log groups ${LOG_PREFIX}* ==="
mkdir -p "${EVIDENCE_DIR}/cloudwatch-logs"
rc=0
LOG_GROUPS="$(aws logs describe-log-groups \
  --log-group-name-prefix "${LOG_PREFIX}" \
  --query 'logGroups[].logGroupName' \
  --output text)" || rc=$?
record "logs describe-log-groups" "${rc}"
[[ "${LOG_GROUPS:-}" == "None" ]] && LOG_GROUPS=""
# N20: the Neptune audit log export lives outside /scholarchain/<env>/, in
# the fixed group /aws/neptune/<cluster-id>/audit (Terraform-managed). It is
# always included; a missing group fails the step.
NEPTUNE_AUDIT_LG="/aws/neptune/${PREFIX}-neptune/audit"
LOG_GROUPS="${LOG_GROUPS:-} ${NEPTUNE_AUDIT_LG}"
if [[ ${rc} -eq 0 ]]; then
  # Log group names under /scholarchain/<env>/ and the Neptune audit group
  # contain no whitespace; word splitting of the CLI's tab-separated text
  # output is intended here.
  # shellcheck disable=SC2086
  printf '%s\n' ${LOG_GROUPS} > "${EVIDENCE_DIR}/cloudwatch-logs/log-groups.txt"
  for LG in ${LOG_GROUPS}; do
    SAFE="$(echo "${LG}" | tr '/' '_')"
    rc=0
    # filter-log-events without a time window returns every retained event;
    # the CLI follows nextToken until exhausted.
    aws logs filter-log-events \
      --log-group-name "${LG}" \
      --output json > "${EVIDENCE_DIR}/cloudwatch-logs/${SAFE}.json" || rc=$?
    record "logs filter-log-events ${LG}" "${rc}"
  done
fi

echo "=== STEP: Export S3 Vectors index listing ==="
mkdir -p "${EVIDENCE_DIR}/s3vectors"
VECTOR_BUCKET="${PREFIX}-vectors"
rc=0
aws s3vectors list-indexes --vector-bucket-name "${VECTOR_BUCKET}" --output json \
  > "${EVIDENCE_DIR}/s3vectors/indexes.json" || rc=$?
record "s3vectors list-indexes ${VECTOR_BUCKET}" "${rc}"
if [[ ${rc} -eq 0 ]]; then
  INDEXES="$(aws s3vectors list-indexes --vector-bucket-name "${VECTOR_BUCKET}" \
    --query 'indexes[].indexName' --output text)" || { record "s3vectors index names" 1; INDEXES=""; }
  [[ "${INDEXES}" == "None" ]] && INDEXES=""
  for IDX in ${INDEXES}; do
    rc=0
    aws s3vectors get-index --vector-bucket-name "${VECTOR_BUCKET}" --index-name "${IDX}" \
      --output json > "${EVIDENCE_DIR}/s3vectors/${IDX}.index.json" || rc=$?
    record "s3vectors get-index ${IDX}" "${rc}"
    rc=0
    aws s3vectors list-vectors --vector-bucket-name "${VECTOR_BUCKET}" --index-name "${IDX}" \
      --return-metadata --output json > "${EVIDENCE_DIR}/s3vectors/${IDX}.vectors.json" || rc=$?
    record "s3vectors list-vectors ${IDX}" "${rc}"
  done
fi

echo "=== STEP: SQS queue attributes (no messages received) ==="
mkdir -p "${EVIDENCE_DIR}/sqs"
for Q in embedding-work-queue dead-letter-queue; do
  rc=0
  QURL="$(aws sqs get-queue-url --queue-name "${PREFIX}-${Q}" --query QueueUrl --output text)" || rc=$?
  if [[ ${rc} -eq 0 ]]; then
    aws sqs get-queue-attributes --queue-url "${QURL}" --attribute-names All --output json \
      > "${EVIDENCE_DIR}/sqs/${Q}.attributes.json" || rc=$?
  fi
  record "sqs attributes ${PREFIX}-${Q}" "${rc}"
done

echo "=== STEP: Collect CloudWatch metrics snapshot ==="
END_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
START_TIME="$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)"
rc=0
aws cloudwatch get-metric-data \
  --metric-data-queries '[
    {"Id":"api_latency","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"TargetResponseTime"},"Period":300,"Stat":"p99"}},
    {"Id":"neptune_cpu","MetricStat":{"Metric":{"Namespace":"AWS/Neptune","MetricName":"CPUUtilization"},"Period":300,"Stat":"Average"}},
    {"Id":"sqs_depth","MetricStat":{"Metric":{"Namespace":"AWS/SQS","MetricName":"ApproximateNumberOfMessagesVisible"},"Period":300,"Stat":"Sum"}}
  ]' \
  --start-time "${START_TIME}" \
  --end-time "${END_TIME}" \
  --output json > "${EVIDENCE_DIR}/cloudwatch-metrics.json" || rc=$?
record "cloudwatch get-metric-data" "${rc}"

echo "=== STEP: Collect experiment outputs ==="
if [[ -d "${REPO_ROOT}/experiments" ]]; then
  rc=0
  (cd "${REPO_ROOT}" && find experiments \( -name '*.json' -o -name '*.csv' \) -type f -print0 \
    | while IFS= read -r -d '' f; do
      mkdir -p "${EVIDENCE_DIR}/$(dirname "${f}")"
      cp "${f}" "${EVIDENCE_DIR}/${f}"
    done) || rc=$?
  record "copy experiment outputs" "${rc}"
fi

echo "=== STEP: Checksums and manifest ==="
(cd "${EVIDENCE_DIR}" && find . -type f ! -name SHA256SUMS ! -name ARCHIVE_COMPLETE -print0 \
  | sort -z | xargs -0 shasum -a 256) > "${EVIDENCE_DIR}/SHA256SUMS"
printf '%s\n' "${STEP_RESULTS[@]}" > "${EVIDENCE_DIR}/STEP_RESULTS.txt"
echo "  SHA256SUMS written ($(wc -l < "${EVIDENCE_DIR}/SHA256SUMS" | tr -d ' ') files)."

echo
echo "Step results:"
printf '  %s\n' "${STEP_RESULTS[@]}"

if [[ ${FAILED_STEPS} -ne 0 ]]; then
  cat <<EOF

================================================================================
EVIDENCE ARCHIVE INCOMPLETE - ${FAILED_STEPS} step(s) failed (see above)
  Directory: ${EVIDENCE_DIR}
  No ARCHIVE_COMPLETE marker was written; cleanup.sh will refuse to run.
================================================================================
EOF
  exit 1
fi

SUMS_SHA="$(shasum -a 256 "${EVIDENCE_DIR}/SHA256SUMS" | awk '{print $1}')"
cat > "${EVIDENCE_DIR}/ARCHIVE_COMPLETE" <<EOF
environment=${ENVIRONMENT}
aws_account=${TF_ENV_AWS_ACCOUNT}
aws_region=${TF_ENV_AWS_REGION}
state_key=${TF_ENV_STATE_KEY}
timestamp=${TIMESTAMP}
epoch=$(date -u +%s)
sha256sums_sha256=${SUMS_SHA}
EOF

cat <<EOF

================================================================================
EVIDENCE ARCHIVE COMPLETE
  Environment: ${ENVIRONMENT}
  Account:     ${TF_ENV_AWS_ACCOUNT}
  Region:      ${TF_ENV_AWS_REGION}
  Timestamp:   ${TIMESTAMP}
  Directory:   ${EVIDENCE_DIR}
  Checksums:   ${EVIDENCE_DIR}/SHA256SUMS
  Marker:      ${EVIDENCE_DIR}/ARCHIVE_COMPLETE
================================================================================
EOF

tf_env_exit 0
