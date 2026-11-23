#!/usr/bin/env bash
################################################################################
# tf-env.sh - Shared environment/backend guard. SOURCE this file; do not run it.
#
#   source "${SCRIPT_DIR}/tf-env.sh"
#   tf_env_validate_name "${ENVIRONMENT}"        # allowlist: dev, prod
#   tf_env_init_backend  "${ENVIRONMENT}"        # init + verify state key
#   tf_env_require_aws_target "${ENVIRONMENT}"   # account/region (read-only STS)
#
# Scripts that only READ outputs of a deployed environment (smoke-test.sh,
# run-bounded-pipeline.sh) additionally use, after the two calls above:
#   tf_env_require_state_identity "${ENVIRONMENT}" # state outputs env/region
#   tf_env_output "${ENVIRONMENT}" <output-name>   # one output (-raw), guarded
#   tf_env_output_json "${ENVIRONMENT}" <name>     # one output (-json), guarded
#
# R1: every environment has its own state object, selected by
# infra/terraform/environments/<env>.backend.hcl. tf_env_init_backend runs
# `terraform init -reconfigure` with that file and then reads the initialised
# backend config from .terraform/terraform.tfstate; if the key is not exactly
# the expected key for <env>, it refuses to continue.
#
# N3: tf_env_refuse_tf_overrides (called from tf_env_validate_name and again
# from tf_env_init_backend, i.e. by every script) refuses to run when
# TF_WORKSPACE, TF_DATA_DIR or any TF_CLI_ARGS / TF_CLI_ARGS_<cmd> variable
# is exported: each can make Terraform use a state or data directory other
# than the one verified here (env:/<ws>/<key>, a stale .terraform elsewhere)
# or inject hidden arguments into init/plan/apply.
# N30: it also refuses any exported TF_VAR_<name>: Terraform would silently
# use it for every variable not given by -var/-var-file, e.g. overriding a
# defaulted, human-approved value such as titan_embedding_model_arn. The
# scripts pass every value they intend explicitly with -var-file/-var.
# N39: it also refuses TF_CLI_CONFIG_FILE, TERRAFORM_CONFIG (legacy alias),
# TF_REATTACH_PROVIDERS, TF_PLUGIN_CACHE_DIR and
# TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE, and a default CLI config
# ($HOME/.terraformrc) that mentions provider_installation or dev_overrides:
# each can make Terraform run provider code other than the locked providers.
#
# N32: `terraform init` runs with -lockfile=readonly, so init can never
# rewrite the committed .terraform.lock.hcl (it fails instead if the lock
# file does not satisfy the provider constraints).
#
# N24: tf_env_init_backend first takes an exclusive run lock (mkdir of
# infra/terraform/.terraform/scholarchain-run.lock, atomic; .terraform/ is
# gitignored) and holds it until the script exits (EXIT trap), so two
# scripts can never re-initialise the shared .terraform directory between
# each other's verification and use. A second script fails closed with the
# holder's details. Child scripts started by the holder (deploy.sh ->
# smoke-test.sh) inherit TF_ENV_LOCK_TOKEN and may re-use the lock for the
# SAME environment only.
#
# Nothing in this file mutates cloud resources. The only AWS calls are
# `aws sts get-caller-identity` and `aws s3api head-object` (read-only), and
# they run only when a calling script invokes the functions.
################################################################################

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "ERROR: tf-env.sh must be sourced, not executed." >&2
  exit 2
fi

TF_ENV_ALLOWED=(dev prod)
TF_ENV_LEGACY_KEY="scholarchain/terraform.tfstate"

TF_ENV_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_ENV_REPO_ROOT="$(cd "${TF_ENV_SCRIPT_DIR}/.." && pwd)"
TF_DIR="${TF_ENV_REPO_ROOT}/infra/terraform"

# Set only by tf_env_verify_backend in this shell. Reset on every source so a
# value inherited from the caller's environment can never satisfy the guard.
TF_ENV_VERIFIED_ENV=""

# N3: refuse Terraform environment overrides that bypass the backend/key
# verification. Exits 3. Only exported variables reach terraform, so
# `compgen -e` (bash 3.2-safe) is the complete set to check.
tf_env_refuse_tf_overrides() {
  local name bad=""
  for name in $(compgen -e); do
    case "${name}" in
      TF_WORKSPACE | TF_DATA_DIR | TF_CLI_ARGS | TF_CLI_ARGS_* | TF_VAR_*)
        bad="${bad} ${name}"
        ;;
      TF_CLI_CONFIG_FILE | TERRAFORM_CONFIG | TF_REATTACH_PROVIDERS | TF_PLUGIN_CACHE_DIR | TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE)
        # N39: provider-source / CLI-config overrides.
        bad="${bad} ${name}"
        ;;
    esac
  done
  if [[ -n "${bad}" ]]; then
    echo "ERROR: refusing to run with Terraform override variable(s) set:${bad}" >&2
    echo "  TF_WORKSPACE moves state to env:/<workspace>/<key>, TF_DATA_DIR points" >&2
    echo "  Terraform at a different .terraform directory than the one verified," >&2
    echo "  TF_CLI_ARGS* inject hidden arguments, and TF_VAR_* silently override" >&2
    echo "  variables (including defaulted, human-approved values such as" >&2
    echo "  titan_embedding_model_arn); the scripts pass values only via" >&2
    echo "  -var-file/-var. TF_CLI_CONFIG_FILE/TERRAFORM_CONFIG select a CLI config" >&2
    echo "  (dev_overrides, provider_installation) that can replace the providers" >&2
    echo "  pinned in .terraform.lock.hcl, TF_REATTACH_PROVIDERS attaches to an" >&2
    echo "  already-running (unverified) provider process, and TF_PLUGIN_CACHE_DIR /" >&2
    echo "  TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE install providers from a" >&2
    echo "  local cache. Unset them and re-run, e.g.:" >&2
    echo "    unset${bad}" >&2
    exit 3
  fi
  tf_env_refuse_default_cli_config
}

# N39: Terraform also reads a CLI config from the default location
# ($HOME/.terraformrc on macOS/Linux) when TF_CLI_CONFIG_FILE is not set. A
# provider_installation block (including dev_overrides) there can make init/
# plan/apply use provider binaries other than the ones pinned and checksummed
# in .terraform.lock.hcl. Refuse (exit 3) if that file mentions either
# keyword. Only this one file is read, only by grep -q (its content, which
# may hold credentials, is never printed). Fail closed: an unset HOME or an
# existing but unreadable file is refused too. Credential files under
# $HOME/.terraform.d/ are deliberately NOT read.
tf_env_refuse_default_cli_config() {
  local home="${HOME:-}" rc_file grep_rc
  if [[ -z "${home}" ]]; then
    echo "ERROR: HOME is not set; cannot check the default Terraform CLI config (~/.terraformrc)." >&2
    exit 3
  fi
  rc_file="${home}/.terraformrc"
  if [[ ! -e "${rc_file}" && ! -L "${rc_file}" ]]; then
    return 0
  fi
  if [[ ! -f "${rc_file}" || ! -r "${rc_file}" ]]; then
    echo "ERROR: ${rc_file} exists but is not a readable regular file; cannot verify it has no" >&2
    echo "  provider_installation/dev_overrides block. Fix or move it and re-run." >&2
    exit 3
  fi
  grep_rc=0
  grep -E -q 'dev_overrides|provider_installation' "${rc_file}" >/dev/null 2>&1 || grep_rc=$?
  if [[ ${grep_rc} -eq 1 ]]; then
    return 0
  fi
  if [[ ${grep_rc} -eq 0 ]]; then
    echo "ERROR: ${rc_file} contains a provider_installation or dev_overrides block (content not shown)." >&2
    echo "  Terraform would install or override providers from it instead of the providers" >&2
    echo "  pinned in infra/terraform/.terraform.lock.hcl. Move that block (or the file) out" >&2
    echo "  of the way for ScholarChain runs and re-run." >&2
  else
    echo "ERROR: could not read ${rc_file} (grep exit ${grep_rc}); cannot verify it has no" >&2
    echo "  provider_installation/dev_overrides block." >&2
  fi
  exit 3
}

# Validate the environment name against the allowlist. Exits on failure.
# Also refuses Terraform override variables (N3); every script calls this
# first.
tf_env_validate_name() {
  local env="${1:-}"
  local allowed
  tf_env_refuse_tf_overrides
  for allowed in "${TF_ENV_ALLOWED[@]}"; do
    if [[ "${env}" == "${allowed}" ]]; then
      return 0
    fi
  done
  echo "ERROR: environment '${env}' is not allowed. Allowed: ${TF_ENV_ALLOWED[*]}" >&2
  exit 2
}

tf_env_expected_key() {
  echo "scholarchain/${1}/terraform.tfstate"
}

tf_env_backend_file() {
  echo "${TF_DIR}/environments/${1}.backend.hcl"
}

tf_env_tfvars_file() {
  echo "${TF_DIR}/environments/${1}.tfvars"
}

# Read a string attribute of the initialised backend config (bucket, key,
# region) from .terraform/terraform.tfstate. Prints an empty string if absent.
tf_env_backend_attr() {
  local attr="$1"
  local f="${TF_DIR}/.terraform/terraform.tfstate"
  if [[ ! -f "${f}" ]]; then
    echo ""
    return 0
  fi
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg a "${attr}" '.backend.config[$a] // ""' "${f}"
  else
    python3 -c 'import json,sys; c=json.load(open(sys.argv[1])).get("backend",{}).get("config",{}); print(c.get(sys.argv[2]) or "")' "${f}" "${attr}"
  fi
}

# Read a simple `name = "value"` attribute from an HCL file (tfvars or
# backend config). Prints an empty string if absent.
tf_env_hcl_attr() {
  local file="$1" attr="$2"
  sed -n -E "s/^[[:space:]]*${attr}[[:space:]]*=[[:space:]]*\"([^\"]*)\".*/\\1/p" "${file}" | head -n 1
}

# Verify the currently initialised backend belongs to <env>. Exits on mismatch.
tf_env_verify_backend() {
  local env="$1"
  local expected actual btype
  expected="$(tf_env_expected_key "${env}")"
  btype=""
  if [[ -f "${TF_DIR}/.terraform/terraform.tfstate" ]]; then
    if command -v jq >/dev/null 2>&1; then
      btype="$(jq -r '.backend.type // ""' "${TF_DIR}/.terraform/terraform.tfstate")"
    else
      btype="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("backend",{}).get("type",""))' "${TF_DIR}/.terraform/terraform.tfstate")"
    fi
  fi
  actual="$(tf_env_backend_attr key)"
  if [[ "${btype}" != "s3" || "${actual}" != "${expected}" ]]; then
    echo "ERROR: initialised backend does not match environment '${env}'." >&2
    echo "  backend type: '${btype}' (expected 's3')" >&2
    echo "  state key:    '${actual}'" >&2
    echo "  expected key: '${expected}'" >&2
    exit 3
  fi
  TF_ENV_STATE_BUCKET="$(tf_env_backend_attr bucket)"
  TF_ENV_STATE_KEY="${actual}"
  TF_ENV_STATE_REGION="$(tf_env_backend_attr region)"
  export TF_ENV_STATE_BUCKET TF_ENV_STATE_KEY TF_ENV_STATE_REGION
  TF_ENV_VERIFIED_ENV="${env}"
  echo "  Backend verified: s3://${TF_ENV_STATE_BUCKET}/${TF_ENV_STATE_KEY} (state region ${TF_ENV_STATE_REGION})"
}

################################################################################
# N24: exclusive run lock on the shared infra/terraform/.terraform directory
################################################################################

TF_ENV_LOCK_DIR="${TF_DIR}/.terraform/scholarchain-run.lock"
TF_ENV_LOCK_OWNED=""

# Remove the lock only if this shell created it.
tf_env_release_lock() {
  if [[ "${TF_ENV_LOCK_OWNED}" == "yes" && -d "${TF_ENV_LOCK_DIR}" ]]; then
    if [[ "$(cat "${TF_ENV_LOCK_DIR}/token" 2>/dev/null || true)" == "${TF_ENV_LOCK_TOKEN:-}" ]]; then
      rm -f "${TF_ENV_LOCK_DIR}/token" "${TF_ENV_LOCK_DIR}/info"
      rmdir "${TF_ENV_LOCK_DIR}" 2>/dev/null || true
    fi
  fi
  TF_ENV_LOCK_OWNED=""
}

# Set only by tf_env_exit. Reset on every source (never inherited).
TF_ENV_CLEAN_EXIT=""

# Every intentional exit with status 0 MUST go through tf_env_exit (it may
# also be used for any other status). Any other exit that reaches the EXIT
# trap with status 0 is treated as a failure, see tf_env_on_exit.
tf_env_exit() {
  TF_ENV_CLEAN_EXIT="yes"
  exit "${1:-0}"
}

# EXIT trap. A script that needs its own exit cleanup defines
# tf_env_script_cleanup; it runs first. The exit status is captured on entry
# and re-raised explicitly with `exit`, so nothing in the cleanup can change
# it.
# bash 3.2 (macOS /bin/bash) bug: when an EXIT trap is set, a fatal shell
# expansion error -- ${var:?msg}, an unbound variable under set -u, an
# arithmetic syntax error -- terminates the script but reports status 0 (both
# to the trap and to the caller). Such an exit never passed through
# tf_env_exit, so a 0 status without TF_ENV_CLEAN_EXIT=yes is turned into 1.
tf_env_on_exit() {
  local rc=$?
  if [[ ${rc} -eq 0 && "${TF_ENV_CLEAN_EXIT}" != "yes" ]]; then
    echo "ERROR: ${0##*/} stopped with status 0 but not through tf_env_exit (most likely a" >&2
    echo "  shell expansion error above, which bash 3.2 reports as status 0). Exiting 1." >&2
    rc=1
  fi
  if declare -F tf_env_script_cleanup >/dev/null 2>&1; then
    tf_env_script_cleanup || true
  fi
  tf_env_release_lock
  exit "${rc}"
}

# Installed when this file is sourced, so every script (including a child
# that only inherits the lock) runs tf_env_script_cleanup on exit. Scripts
# must not install their own EXIT trap; define tf_env_script_cleanup instead.
trap tf_env_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Take the lock for <env> or exit 3. Re-entrant for a child of the holder
# (inherited TF_ENV_LOCK_TOKEN equal to the lock's token) for the same env.
tf_env_acquire_lock() {
  local env="$1"
  local held_token held_env
  if [[ "${TF_ENV_LOCK_OWNED}" == "yes" ]]; then
    held_env="$(sed -n 's/^env=//p' "${TF_ENV_LOCK_DIR}/info" 2>/dev/null || true)"
    if [[ "${held_env}" != "${env}" ]]; then
      echo "ERROR: this script holds the Terraform run lock for '${held_env}', not '${env}'." >&2
      exit 3
    fi
    return 0
  fi
  mkdir -p "${TF_DIR}/.terraform"
  if mkdir "${TF_ENV_LOCK_DIR}" 2>/dev/null; then
    TF_ENV_LOCK_TOKEN="$$-$(date -u +%s)-${RANDOM}${RANDOM}"
    export TF_ENV_LOCK_TOKEN
    TF_ENV_LOCK_OWNED="yes"
    printf '%s\n' "${TF_ENV_LOCK_TOKEN}" > "${TF_ENV_LOCK_DIR}/token"
    {
      echo "env=${env}"
      echo "pid=$$"
      echo "script=${0##*/}"
      echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "${TF_ENV_LOCK_DIR}/info"
    return 0
  fi
  held_token="$(cat "${TF_ENV_LOCK_DIR}/token" 2>/dev/null || true)"
  held_env="$(sed -n 's/^env=//p' "${TF_ENV_LOCK_DIR}/info" 2>/dev/null || true)"
  if [[ -n "${TF_ENV_LOCK_TOKEN:-}" && "${held_token}" == "${TF_ENV_LOCK_TOKEN}" ]]; then
    if [[ "${held_env}" != "${env}" ]]; then
      echo "ERROR: parent script holds the Terraform run lock for '${held_env}'; refusing '${env}'." >&2
      exit 3
    fi
    echo "  Run lock inherited from parent script (env ${held_env})."
    return 0
  fi
  echo "ERROR: another ScholarChain script holds the Terraform run lock:" >&2
  echo "  ${TF_ENV_LOCK_DIR}" >&2
  sed 's/^/    /' "${TF_ENV_LOCK_DIR}/info" >&2 2>/dev/null || echo "    (no lock info)" >&2
  echo "  Wait for it to finish. If no such process is running (crash/kill -9)," >&2
  echo "  a human may remove the stale lock: rm -r '${TF_ENV_LOCK_DIR}'" >&2
  exit 3
}

# Initialise the backend for <env> with -reconfigure (never reuses whatever
# backend was initialised before) and -lockfile=readonly (N32: never rewrites
# .terraform.lock.hcl), then verify the key. Takes the run lock
# (N24) first and re-checks Terraform override variables (N3).
tf_env_init_backend() {
  local env="$1"
  local backend_file expected file_key
  tf_env_refuse_tf_overrides
  tf_env_acquire_lock "${env}"
  backend_file="$(tf_env_backend_file "${env}")"
  expected="$(tf_env_expected_key "${env}")"
  if [[ ! -f "${backend_file}" ]]; then
    echo "ERROR: backend config not found: ${backend_file}" >&2
    exit 3
  fi
  file_key="$(tf_env_hcl_attr "${backend_file}" key)"
  if [[ "${file_key}" != "${expected}" ]]; then
    echo "ERROR: ${backend_file} has key '${file_key}', expected '${expected}'." >&2
    exit 3
  fi
  if [[ ! -f "$(tf_env_tfvars_file "${env}")" ]]; then
    echo "ERROR: tfvars not found: $(tf_env_tfvars_file "${env}")" >&2
    exit 3
  fi

  tf_env_refuse_legacy_state "${backend_file}"

  echo "=== STEP: terraform init (backend ${expected}) ==="
  (cd "${TF_DIR}" && terraform init -reconfigure -input=false -lockfile=readonly -backend-config="${backend_file}")
  tf_env_verify_backend "${env}"
}

# Before R1 both environments shared the key scholarchain/terraform.tfstate.
# If that object still exists, it belongs to whichever environment was
# deployed first; a human must migrate it to the correct per-env key before
# any script touches state. Read-only head-object.
tf_env_refuse_legacy_state() {
  local backend_file="$1"
  local bucket region
  local err rc
  bucket="$(tf_env_hcl_attr "${backend_file}" bucket)"
  region="$(tf_env_hcl_attr "${backend_file}" region)"
  rc=0
  err="$(aws s3api head-object --bucket "${bucket}" --key "${TF_ENV_LEGACY_KEY}" --region "${region}" 2>&1 >/dev/null)" || rc=$?
  if [[ ${rc} -eq 0 ]]; then
    echo "ERROR: legacy shared state object s3://${bucket}/${TF_ENV_LEGACY_KEY} exists." >&2
    echo "  It predates per-environment state (R1) and may hold dev or prod resources." >&2
    echo "  A human must identify its environment (terraform state list / outputs)," >&2
    echo "  migrate it to scholarchain/<env>/terraform.tfstate, and archive the legacy" >&2
    echo "  object before any plan, deploy, rollback or cleanup proceeds." >&2
    exit 3
  fi
  if [[ "${err}" != *"Not Found"* && "${err}" != *"404"* ]]; then
    echo "ERROR: could not confirm that legacy state s3://${bucket}/${TF_ENV_LEGACY_KEY} is absent:" >&2
    echo "  ${err}" >&2
    exit 3
  fi
}

# Print and return the AWS account and the deployment region for <env>.
# Region comes from the tfvars (the region Terraform will act in); the
# caller's CLI region must equal it, so read-only CLI inventory hits the same
# region. Exits on any mismatch or STS failure.
tf_env_require_aws_target() {
  local env="$1"
  local tfvars_region cli_region
  tfvars_region="$(tf_env_hcl_attr "$(tf_env_tfvars_file "${env}")" aws_region)"
  if [[ -z "${tfvars_region}" ]]; then
    echo "ERROR: aws_region not set in $(tf_env_tfvars_file "${env}")." >&2
    exit 4
  fi
  if ! TF_ENV_AWS_ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"; then
    echo "ERROR: aws sts get-caller-identity failed; no valid credentials." >&2
    exit 4
  fi
  cli_region="${AWS_REGION:-${AWS_DEFAULT_REGION:-$(aws configure get region 2>/dev/null || true)}}"
  if [[ "${cli_region}" != "${tfvars_region}" ]]; then
    echo "ERROR: AWS CLI region '${cli_region}' does not match ${env} aws_region '${tfvars_region}'." >&2
    echo "  Export AWS_REGION=${tfvars_region} and re-run." >&2
    exit 4
  fi
  TF_ENV_AWS_REGION="${tfvars_region}"
  export TF_ENV_AWS_ACCOUNT TF_ENV_AWS_REGION
  echo "  AWS account: ${TF_ENV_AWS_ACCOUNT}"
  echo "  AWS region:  ${TF_ENV_AWS_REGION}"
  echo "  Environment: ${env}"
}

# Refuse (exit 3) unless tf_env_init_backend verified <env> in this shell AND
# the initialised backend on disk still has <env>'s key (re-read on every
# call, so a re-init by another process in between is caught).
tf_env_require_verified() {
  local env="$1"
  local expected actual
  expected="$(tf_env_expected_key "${env}")"
  actual="$(tf_env_backend_attr key)"
  if [[ "${TF_ENV_VERIFIED_ENV}" != "${env}" || "${actual}" != "${expected}" ]]; then
    echo "ERROR: backend not verified for '${env}'; refusing to read Terraform outputs." >&2
    echo "  verified env: '${TF_ENV_VERIFIED_ENV}', state key on disk: '${actual}', expected: '${expected}'" >&2
    echo "  Call tf_env_init_backend ${env} first." >&2
    exit 3
  fi
  # N24: the run lock must still be ours (or our parent's).
  if [[ -z "${TF_ENV_LOCK_TOKEN:-}" || "$(cat "${TF_ENV_LOCK_DIR}/token" 2>/dev/null || true)" != "${TF_ENV_LOCK_TOKEN}" ]]; then
    echo "ERROR: Terraform run lock ${TF_ENV_LOCK_DIR} is not held by this script; refusing to read outputs." >&2
    exit 3
  fi
}

# Print one Terraform output from the guarded state of <env>.
#   tf_env_output      <env> <name>   -> terraform output -raw  <name>
#   tf_env_output_json <env> <name>   -> terraform output -json <name>
# Use as a plain assignment (X="$(tf_env_output ...)") under set -e so that a
# guard failure (exit 3 in the substitution) or a missing output stops the
# caller.
tf_env_output() {
  tf_env_require_verified "$1"
  (cd "${TF_DIR}" && terraform output -raw "$2")
}

tf_env_output_json() {
  tf_env_require_verified "$1"
  (cd "${TF_DIR}" && terraform output -json "$2")
}

# Require that the verified state really describes <env>: its `environment`
# output must equal <env> and its `aws_region` output must equal the target
# region from tf_env_require_aws_target. An empty or never-applied state has
# neither output and is refused. Exits 3 on any mismatch.
tf_env_require_state_identity() {
  local env="$1"
  local state_env state_region
  tf_env_require_verified "${env}"
  state_env="$(tf_env_output "${env}" environment 2>/dev/null || true)"
  state_region="$(tf_env_output "${env}" aws_region 2>/dev/null || true)"
  if [[ "${state_env}" != "${env}" ]]; then
    echo "ERROR: state ${TF_ENV_STATE_KEY} reports environment '${state_env}', expected '${env}' (empty means not deployed or unreadable)." >&2
    exit 3
  fi
  if [[ "${state_region}" != "${TF_ENV_AWS_REGION:-}" ]]; then
    echo "ERROR: state ${TF_ENV_STATE_KEY} reports aws_region '${state_region}', target region is '${TF_ENV_AWS_REGION:-}'." >&2
    exit 3
  fi
  echo "  State identity verified: environment=${state_env} aws_region=${state_region}"
}
