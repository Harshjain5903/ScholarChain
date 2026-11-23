#!/usr/bin/env bash
set -euo pipefail

################################################################################
# smoke-test.sh - Post-deployment health and functional smoke tests
#
# Usage: ./scripts/smoke-test.sh <dev|prod>
# Example: ./scripts/smoke-test.sh dev
#
# Target guard (N2, same as plan.sh/deploy.sh/rollback.sh via tf-env.sh):
# validates the environment name (allowlist dev, prod), requires the AWS CLI
# account/region to be readable and the CLI region to equal the env's
# aws_region, re-initialises the backend for <env> (-reconfigure) and refuses
# unless the state key is scholarchain/<env>/terraform.tfstate, refuses if the
# legacy shared state object exists, and requires the state's `environment`
# and `aws_region` outputs to equal the target. The ALB DNS name is read only
# from that verified state; there is no environment-variable or localhost
# fallback, so a result can never be reported for <env> against another
# environment's endpoint.
#
# Tests:
#   1. GET /healthz  -> 200
#   2. GET /readyz   -> 200
#   3. POST /v1/query {"question": ...} with an Idempotency-Key -> 2xx and a
#      JSON body with a queryId (N18: QueryRequest's only required field is
#      `question`, modules/services/.../JsonCodecs.scala:7-8)
#   4. GET /v1/evidence/<id>, where <id> is claims[].evidenceIds[0] of the
#      first claim that has one in the Test 3 response -> 2xx. FAILS (never
#      skips) if the response has no evidence id.
#   5. Re-POST with the same Idempotency-Key -> 2xx and a byte-identical body
#   6. POST with a body lacking `question` -> 400
# Tests 3-5 share a cookie jar so the ALB stickiness cookie (AWSALB, N19)
# keeps them on the query-api task that holds the in-process query results.
# JSON is parsed with jq if present, otherwise python3 -I; neither present
# fails the run.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/tf-env.sh
source "${SCRIPT_DIR}/tf-env.sh"

if [[ -z "${1:-}" ]]; then
  echo "Usage: smoke-test.sh <dev|prod>" >&2
  exit 2
fi
ENVIRONMENT="$1"
tf_env_validate_name "${ENVIRONMENT}"

TIMEOUT_SECONDS=30
PASS_COUNT=0
FAIL_COUNT=0

if command -v jq >/dev/null 2>&1; then
  JSON_TOOL="jq"
elif command -v python3 >/dev/null 2>&1; then
  JSON_TOOL="python3"
else
  echo "ERROR: jq or python3 is required to parse API responses." >&2
  exit 2
fi

# Per-run temp dir for the cookie jar; removed on exit by tf-env.sh's EXIT
# trap via this hook.
SMOKE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/scholarchain-smoke.XXXXXX")"
COOKIE_JAR="${SMOKE_TMP}/cookies.txt"
tf_env_script_cleanup() {
  rm -rf "${SMOKE_TMP}"
}

# json_get <json> <field>: prints the top-level string field or nothing.
json_get() {
  if [[ "${JSON_TOOL}" == "jq" ]]; then
    printf '%s' "$1" | jq -r --arg f "$2" 'if type == "object" then (.[$f] // empty) else empty end | strings' 2>/dev/null || true
  else
    printf '%s' "$1" | python3 -I -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
v = d.get(sys.argv[1]) if isinstance(d, dict) else None
if isinstance(v, str):
    print(v)' "$2" 2>/dev/null || true
  fi
}

# first_evidence_id <json>: claims[].evidenceIds[0] of the first claim that
# has a non-empty string there; prints nothing if none.
first_evidence_id() {
  if [[ "${JSON_TOOL}" == "jq" ]]; then
    printf '%s' "$1" | jq -r '[(.claims // [])[] | (.evidenceIds // [])[0]? | strings | select(length > 0)] | first // empty' 2>/dev/null || true
  else
    printf '%s' "$1" | python3 -I -c 'import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for c in (d.get("claims") or []) if isinstance(d, dict) else []:
    ids = c.get("evidenceIds") if isinstance(c, dict) else None
    if isinstance(ids, list) and ids and isinstance(ids[0], str) and ids[0]:
        print(ids[0])
        break' 2>/dev/null || true
  fi
}

echo "=== STEP: Verify target ==="
tf_env_require_aws_target "${ENVIRONMENT}"
tf_env_init_backend "${ENVIRONMENT}"
tf_env_require_state_identity "${ENVIRONMENT}"

# Resolve ALB endpoint from the verified state only. Plain assignment so that
# set -e stops the script if the guard or the output read fails.
ALB_DNS="$(tf_env_output "${ENVIRONMENT}" alb_dns_name)"
if [[ -z "${ALB_DNS}" ]]; then
  echo "ERROR: alb_dns_name is empty in state ${TF_ENV_STATE_KEY}; ${ENVIRONMENT} is not deployed." >&2
  exit 3
fi
BASE_URL="http://${ALB_DNS}"

echo "=== STEP: Smoke test against ${BASE_URL} ==="

check_response() {
  local test_name="$1"
  local expected_code="$2"
  local actual_code="$3"

  if [[ "${actual_code}" == "${expected_code}" ]]; then
    echo "  PASS: ${test_name} (HTTP ${actual_code})"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: ${test_name} (expected HTTP ${expected_code}, got ${actual_code})" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

echo "=== STEP: Test 1 - GET /healthz ==="
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT_SECONDS}" "${BASE_URL}/healthz" || echo "000")"
check_response "GET /healthz" "200" "${HTTP_CODE}"

echo "=== STEP: Test 2 - GET /readyz ==="
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT_SECONDS}" "${BASE_URL}/readyz" || echo "000")"
check_response "GET /readyz" "200" "${HTTP_CODE}"

echo "=== STEP: Test 3 - POST /v1/query (bounded test) ==="
QUERY_PAYLOAD='{"question":"Who are the top collaborators of Albert Einstein?"}'
IDEMPOTENCY_KEY="smoke-${ENVIRONMENT}-$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}${RANDOM}"
QUERY_RESPONSE="$(curl -s --max-time "${TIMEOUT_SECONDS}" \
  -c "${COOKIE_JAR}" -b "${COOKIE_JAR}" \
  -w '\n%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "${QUERY_PAYLOAD}" \
  "${BASE_URL}/v1/query" || printf '\n000')"
# Drop the trailing status-code line (sed '$d' is portable; BSD head on macOS
# does not accept a negative -n count).
QUERY_BODY="$(printf '%s\n' "${QUERY_RESPONSE}" | sed '$d')"
QUERY_CODE="$(printf '%s\n' "${QUERY_RESPONSE}" | tail -n 1)"
QUERY_ID="$(json_get "${QUERY_BODY}" queryId)"

if [[ "${QUERY_CODE}" =~ ^2[0-9][0-9]$ && -n "${QUERY_ID}" ]]; then
  echo "  PASS: POST /v1/query (HTTP ${QUERY_CODE}, queryId ${QUERY_ID})"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: POST /v1/query (expected 2xx with a queryId, got HTTP ${QUERY_CODE}, queryId '${QUERY_ID}')" >&2
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo "=== STEP: Test 4 - GET /v1/evidence/<claims[].evidenceIds[0]> ==="
EVIDENCE_ID="$(first_evidence_id "${QUERY_BODY}")"
if [[ -z "${EVIDENCE_ID}" ]]; then
  echo "  FAIL: no claims[].evidenceIds[0] in the /v1/query response; the evidence endpoint could not be exercised." >&2
  FAIL_COUNT=$((FAIL_COUNT + 1))
elif [[ ! "${EVIDENCE_ID}" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "  FAIL: evidence id '${EVIDENCE_ID}' contains characters not safe for a URL path segment." >&2
  FAIL_COUNT=$((FAIL_COUNT + 1))
else
  EVIDENCE_RESPONSE="$(curl -s --max-time "${TIMEOUT_SECONDS}" \
    -c "${COOKIE_JAR}" -b "${COOKIE_JAR}" \
    -w '\n%{http_code}' \
    "${BASE_URL}/v1/evidence/${EVIDENCE_ID}" || printf '\n000')"
  EVIDENCE_BODY="$(printf '%s\n' "${EVIDENCE_RESPONSE}" | sed '$d')"
  HTTP_CODE="$(printf '%s\n' "${EVIDENCE_RESPONSE}" | tail -n 1)"
  GOT_ID="$(json_get "${EVIDENCE_BODY}" evidenceId)"
  if [[ "${HTTP_CODE}" =~ ^2[0-9][0-9]$ && "${GOT_ID}" == "${EVIDENCE_ID}" ]]; then
    echo "  PASS: GET /v1/evidence/${EVIDENCE_ID} (HTTP ${HTTP_CODE})"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  FAIL: GET /v1/evidence/${EVIDENCE_ID} (expected 2xx with evidenceId ${EVIDENCE_ID}, got HTTP ${HTTP_CODE}, evidenceId '${GOT_ID}')" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
fi

echo "=== STEP: Test 5 - Idempotent re-POST (same Idempotency-Key) ==="
REPOST_RESPONSE="$(curl -s --max-time "${TIMEOUT_SECONDS}" \
  -c "${COOKIE_JAR}" -b "${COOKIE_JAR}" \
  -w '\n%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "${QUERY_PAYLOAD}" \
  "${BASE_URL}/v1/query" || printf '\n000')"
REPOST_BODY="$(printf '%s\n' "${REPOST_RESPONSE}" | sed '$d')"
REPOST_CODE="$(printf '%s\n' "${REPOST_RESPONSE}" | tail -n 1)"
if [[ "${REPOST_CODE}" =~ ^2[0-9][0-9]$ && -n "${QUERY_BODY}" && "${REPOST_BODY}" == "${QUERY_BODY}" ]]; then
  echo "  PASS: Idempotent re-POST (HTTP ${REPOST_CODE}, identical body)"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: Idempotent re-POST (expected 2xx and the Test 3 body, got HTTP ${REPOST_CODE}; bodies $([[ "${REPOST_BODY}" == "${QUERY_BODY}" ]] && echo equal || echo differ))" >&2
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo "=== STEP: Test 6 - POST without required field question (expect 400) ==="
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT_SECONDS}" \
  -H 'Content-Type: application/json' \
  -d '{"invalid": true}' \
  "${BASE_URL}/v1/query" || echo "000")"
check_response "POST /v1/query invalid body" "400" "${HTTP_CODE}"

cat <<EOF

================================================================================
SMOKE TEST RESULTS
  Environment: ${ENVIRONMENT}
  Account:     ${TF_ENV_AWS_ACCOUNT}
  Region:      ${TF_ENV_AWS_REGION}
  State:       s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY}
  Endpoint:    ${BASE_URL}
  Passed:      ${PASS_COUNT}
  Failed:      ${FAIL_COUNT}
================================================================================
EOF

if [[ ${FAIL_COUNT} -gt 0 ]]; then
  exit 1
fi
tf_env_exit 0
