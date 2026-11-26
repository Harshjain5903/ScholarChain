#!/usr/bin/env bash
set -euo pipefail

################################################################################
# check.sh - Single non-cloud validation gate for the repository (Phase 13).
#
# Usage: ./scripts/check.sh [options]
#
# Runs every local gate below, continues after a failure so that one run shows
# every broken gate, prints a summary table and exits nonzero if any gate
# failed. Nothing here calls AWS, initialises a Terraform backend, deploys,
# pushes images or calls a paid model.
#
# Gates (each prints "=== GATE <name> ===" and then exactly one result line
# "GATE <name> PASS|FAIL|SKIPPED ..."; the summary repeats them):
#   constitution    .claude/hooks/validate-agent-files.sh (agent frontmatter,
#                   unique names, executable hooks) and a jq/python3 parse of
#                   .claude/settings.json and .mcp.json: each must hold
#                   exactly ONE JSON object (an empty file, an array or several
#                   concatenated documents fail).
#   hooks           bash -n on .claude/hooks/*.sh; no hook files FAILS.
#   shell           bash -n on scripts/*.sh and datasets/**/*.sh (raw/,
#                   derived/ and cache/ excluded).
#   shellcheck      shellcheck -x on the same files if shellcheck is installed;
#                   otherwise "GATE shellcheck SKIPPED (shellcheck not
#                   installed)". Absence never fails the run. Severity:
#                   SHELLCHECK_SEVERITY (default warning).
#   script-tests    scripts/test-pipeline-lib.sh and
#                   scripts/test-phase13-scripts.sh (offline; no AWS, no sbt).
#   schemas         offline schema/manifest validators that already exist:
#                   prompts/schemas/*.json must each hold one JSON object (jq),
#                   the dataset script
#                   unit tests (python3 -m unittest, datasets/scripts/tests) and
#                   datasets/scripts/validate_manifest.py (dataset-manifest.yaml
#                   against files on disk; gitignored raw data reports SKIP).
#                   A missing input (no prompt schemas, no dataset tests
#                   directory, no validator) FAILS the gate.
#   sbt             `sbt -batch check` = scalafmtCheckAll, Test/compile, test
#                   (unit + the *ContractSpec contract tests + schema specs such
#                   as GraphSchemaSpec / SynthesisSpec, which load the
#                   classpath copies of prompts/schemas) and markTestRun
#                   (writes .claude/.last-test-run only when all tests pass).
#   terraform-fmt   terraform fmt -check -recursive on infra/terraform and
#                   infra/bootstrap. A missing directory FAILS both terraform
#                   gates.
#   terraform-validate
#                   terraform init -backend=false -lockfile=readonly in a
#                   throw-away TF_DATA_DIR, then terraform validate, for
#                   infra/terraform and infra/bootstrap. The S3 backend is never
#                   initialised, the committed .terraform.lock.hcl is never
#                   rewritten, and the shared infra/terraform/.terraform (state
#                   pointer and tf-env.sh run lock) is never written. Every
#                   terraform command runs with a generated CLI config
#                   (TF_CLI_CONFIG_FILE, so ~/.terraformrc does not apply),
#                   TF_PLUGIN_CACHE_DIR unset and Terraform/AWS overrides
#                   removed. When infra/terraform/.terraform/providers exists
#                   the config is a filesystem_mirror on it with NO direct
#                   fallback: providers are linked read-only from it, and a
#                   provider missing there fails init clearly instead of being
#                   downloaded into shared state. Otherwise providers are
#                   downloaded from the public Terraform registry into the
#                   private local-runs/check/terraform-plugin-cache
#                   (gitignored). AWS credentials/config are blanked.
#
# Options:
#   --skip-constitution  --skip-hooks  --skip-shell  --skip-shellcheck
#   --skip-script-tests  --skip-schemas  --skip-sbt  --skip-terraform
#   --fast               same as --skip-sbt --skip-terraform
#   -h, --help           print this text
#
# Exit codes: 0 every gate passed or was skipped; 1 at least one gate failed;
#             2 usage error.
#
# Prerequisites (README "Prerequisites"): bash, git, jq or python3, python3
# (/usr/bin/python3 is preferred, as in the README), sbt + JDK for the sbt
# gate, terraform >= 1.10 for the terraform gates. A missing prerequisite for
# a gate that is not skipped FAILS that gate (it is not silently skipped);
# only shellcheck is optional.
#
# The script-tests gate is skipped automatically inside a nested check.sh run
# (SCHOLARCHAIN_CHECK_DEPTH is set), so the tests can call check.sh without
# recursing.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  sed -n '/^# Usage:/,/^####/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//'
}

SKIP_CONSTITUTION=0
SKIP_HOOKS=0
SKIP_SHELL=0
SKIP_SHELLCHECK=0
SKIP_SCRIPT_TESTS=0
SKIP_SCHEMAS=0
SKIP_SBT=0
SKIP_TERRAFORM=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-constitution) SKIP_CONSTITUTION=1 ;;
    --skip-hooks) SKIP_HOOKS=1 ;;
    --skip-shell) SKIP_SHELL=1 ;;
    --skip-shellcheck) SKIP_SHELLCHECK=1 ;;
    --skip-script-tests) SKIP_SCRIPT_TESTS=1 ;;
    --skip-schemas) SKIP_SCHEMAS=1 ;;
    --skip-sbt) SKIP_SBT=1 ;;
    --skip-terraform) SKIP_TERRAFORM=1 ;;
    --fast)
      SKIP_SBT=1
      SKIP_TERRAFORM=1
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option '$1'." >&2
      echo "Usage: check.sh [--skip-constitution] [--skip-hooks] [--skip-shell] [--skip-shellcheck]" >&2
      echo "                [--skip-script-tests] [--skip-schemas] [--skip-sbt] [--skip-terraform] [--fast]" >&2
      exit 2
      ;;
  esac
  shift
done

CHECK_DEPTH="${SCHOLARCHAIN_CHECK_DEPTH:-0}"
case "${CHECK_DEPTH}" in '' | *[!0-9]*) CHECK_DEPTH=0 ;; esac
export SCHOLARCHAIN_CHECK_DEPTH=$((CHECK_DEPTH + 1))

TMP_BASE="${TMPDIR:-/tmp}"
WORK_DIR="$(mktemp -d "${TMP_BASE%/}/scholarchain-check.XXXXXX")"
cleanup_work() { rm -rf "${WORK_DIR}"; }
trap cleanup_work EXIT

# Results, one entry per gate (bash 3.2: parallel indexed arrays).
GATE_NAMES=()
GATE_RESULTS=()
GATE_SECONDS=()
GATE_NOTES=()
FAILED=0

record_gate() { # record_gate <name> <PASS|FAIL|SKIPPED> <seconds> <note>
  GATE_NAMES+=("$1")
  GATE_RESULTS+=("$2")
  GATE_SECONDS+=("$3")
  GATE_NOTES+=("$4")
  if [[ "$2" == FAIL ]]; then FAILED=$((FAILED + 1)); fi
  if [[ "$2" == SKIPPED ]]; then
    echo "GATE $1 SKIPPED ($4)"
  else
    echo "GATE $1 $2 (${3}s)${4:+ - $4}"
  fi
}

# GATE_NOTE may be set by a gate function to annotate its result line.
GATE_NOTE=""
run_gate() { # run_gate <name> <skip 0|1> <skip-reason> <function>
  local name="$1" skip="$2" reason="$3" fn="$4" start rc=0
  echo ""
  echo "=== GATE ${name} ==="
  if [[ "${skip}" == 1 ]]; then
    record_gate "${name}" SKIPPED 0 "${reason}"
    return 0
  fi
  GATE_NOTE=""
  start="$(date +%s)"
  # The function runs in an if-context (set -e is off inside it), so every
  # gate function checks each command explicitly and returns nonzero itself.
  if "${fn}"; then rc=0; else rc=$?; fi
  if [[ ${rc} -eq 0 ]]; then
    record_gate "${name}" PASS $(($(date +%s) - start)) "${GATE_NOTE}"
  else
    record_gate "${name}" FAIL $(($(date +%s) - start)) "${GATE_NOTE:-rc ${rc}}"
  fi
}

json_parse() { # json_parse <file>: 0 only if the file holds exactly one JSON object
  if command -v jq >/dev/null 2>&1; then
    jq -s -e 'length == 1 and (.[0] | type == "object")' "$1" >/dev/null 2>&1
  elif command -v python3 >/dev/null 2>&1; then
    python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if isinstance(d, dict) else 1)' "$1" >/dev/null 2>&1
  else
    echo "  ERROR: neither jq nor python3 is installed; cannot parse JSON." >&2
    return 1
  fi
}

pick_python() {
  if [[ -x /usr/bin/python3 ]]; then
    echo /usr/bin/python3
  elif command -v python3 >/dev/null 2>&1; then
    command -v python3
  else
    return 1
  fi
}

# Shell files covered by the shell and shellcheck gates, one per line.
list_shell_files() {
  local f
  for f in "${REPO_ROOT}"/scripts/*.sh; do
    [[ -f "${f}" ]] && echo "${f}"
  done
  if [[ -d "${REPO_ROOT}/datasets" ]]; then
    find "${REPO_ROOT}/datasets" \
      \( -path "${REPO_ROOT}/datasets/raw" -o -path "${REPO_ROOT}/datasets/derived" -o -path "${REPO_ROOT}/datasets/cache" \) -prune \
      -o -type f -name '*.sh' -print | LC_ALL=C sort
  fi
}

gate_constitution() {
  local rc=0 f validator="${REPO_ROOT}/.claude/hooks/validate-agent-files.sh"
  if [[ ! -f "${validator}" ]]; then
    echo "  ERROR: ${validator} not found." >&2
    return 1
  fi
  # The validator locates the repository with `git rev-parse --show-toplevel`
  # from the working directory, so run it from the repository root.
  if ! (cd "${REPO_ROOT}" && bash "${validator}"); then
    echo "  ERROR: validate-agent-files.sh failed." >&2
    rc=1
  fi
  for f in .claude/settings.json .mcp.json; do
    if [[ ! -f "${REPO_ROOT}/${f}" ]]; then
      echo "  ERROR: ${f} is missing." >&2
      rc=1
    elif json_parse "${REPO_ROOT}/${f}"; then
      echo "  ${f}: valid JSON"
    else
      echo "  ERROR: ${f} is not valid JSON (exactly one JSON object required)." >&2
      rc=1
    fi
  done
  return ${rc}
}

gate_hooks() {
  local rc=0 n=0 f
  for f in "${REPO_ROOT}"/.claude/hooks/*.sh; do
    [[ -f "${f}" ]] || continue
    n=$((n + 1))
    if bash -n "${f}"; then
      echo "  bash -n OK   ${f#"${REPO_ROOT}"/}"
    else
      echo "  bash -n FAIL ${f#"${REPO_ROOT}"/}" >&2
      rc=1
    fi
  done
  GATE_NOTE="${n} hook(s)"
  if [[ ${n} -eq 0 ]]; then
    echo "  ERROR: MISSING INPUT: no .claude/hooks/*.sh found." >&2
    GATE_NOTE="MISSING INPUT: no hooks"
    return 1
  fi
  return ${rc}
}

gate_shell() {
  local rc=0 n=0 f
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    n=$((n + 1))
    if bash -n "${f}"; then
      echo "  bash -n OK   ${f#"${REPO_ROOT}"/}"
    else
      echo "  bash -n FAIL ${f#"${REPO_ROOT}"/}" >&2
      rc=1
    fi
  done < <(list_shell_files)
  GATE_NOTE="${n} file(s)"
  return ${rc}
}

gate_shellcheck() {
  local files=() f
  while IFS= read -r f; do
    [[ -n "${f}" ]] && files+=("${f}")
  done < <(list_shell_files)
  GATE_NOTE="${#files[@]} file(s), severity ${SHELLCHECK_SEVERITY:-warning}"
  [[ ${#files[@]} -gt 0 ]] || return 0
  (cd "${REPO_ROOT}" && shellcheck -x -S "${SHELLCHECK_SEVERITY:-warning}" "${files[@]}")
}

gate_script_tests() {
  local rc=0 t
  for t in test-pipeline-lib.sh test-phase13-scripts.sh; do
    if [[ ! -f "${SCRIPT_DIR}/${t}" ]]; then
      echo "  ERROR: scripts/${t} not found." >&2
      rc=1
      continue
    fi
    echo "--- scripts/${t} ---"
    if bash "${SCRIPT_DIR}/${t}"; then
      echo "  scripts/${t}: PASS"
    else
      echo "  scripts/${t}: FAIL" >&2
      rc=1
    fi
  done
  return ${rc}
}

gate_schemas() {
  local rc=0 n=0 f py
  for f in "${REPO_ROOT}"/prompts/schemas/*.json; do
    [[ -f "${f}" ]] || continue
    n=$((n + 1))
    if json_parse "${f}"; then
      echo "  valid JSON   ${f#"${REPO_ROOT}"/}"
    else
      echo "  INVALID JSON ${f#"${REPO_ROOT}"/} (exactly one JSON object required)" >&2
      rc=1
    fi
  done
  if [[ ${n} -eq 0 ]]; then
    echo "  ERROR: MISSING INPUT: no prompts/schemas/*.json found." >&2
    rc=1
  fi
  if ! py="$(pick_python)"; then
    echo "  ERROR: python3 is required for the dataset validators." >&2
    return 1
  fi
  if [[ -d "${REPO_ROOT}/datasets/scripts/tests" ]]; then
    echo "--- dataset script unit tests (${py} -m unittest) ---"
    if ! (cd "${REPO_ROOT}" && "${py}" -I -B -m unittest discover -s datasets/scripts/tests -p 'test_*.py'); then
      echo "  ERROR: dataset script unit tests failed." >&2
      rc=1
    fi
  else
    echo "  ERROR: MISSING INPUT: datasets/scripts/tests not found." >&2
    rc=1
  fi
  if [[ -f "${REPO_ROOT}/datasets/scripts/validate_manifest.py" ]]; then
    echo "--- datasets/scripts/validate_manifest.py ---"
    if ! (cd "${REPO_ROOT}" && "${py}" -I -B datasets/scripts/validate_manifest.py); then
      echo "  ERROR: dataset manifest validation failed." >&2
      rc=1
    fi
  else
    echo "  ERROR: MISSING INPUT: datasets/scripts/validate_manifest.py not found." >&2
    rc=1
  fi
  GATE_NOTE="${n} prompt schema(s) parsed; dataset tests + manifest validator"
  return ${rc}
}

gate_sbt() {
  if ! command -v sbt >/dev/null 2>&1; then
    echo "  ERROR: sbt is not installed (prerequisite; use --skip-sbt to skip this gate)." >&2
    GATE_NOTE="sbt not installed"
    return 1
  fi
  (cd "${REPO_ROOT}" && sbt -batch check)
}

# Run terraform with Terraform overrides removed, a generated CLI config
# (TF_CLI_CONFIG_FILE, so ~/.terraformrc and TF_PLUGIN_CACHE_DIR never apply)
# and AWS access blanked.
tf_isolated() { # tf_isolated <dir> <data-dir> <cli-config> terraform-args...
  local dir="$1" data="$2" cfg="$3"
  shift 3
  (
    for v in $(compgen -e); do
      case "${v}" in
        TF_WORKSPACE | TF_CLI_ARGS | TF_CLI_ARGS_* | TF_VAR_* | TF_CLI_CONFIG_FILE | TERRAFORM_CONFIG | TF_REATTACH_PROVIDERS | TF_PLUGIN_CACHE_DIR | TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE | TF_DATA_DIR | TF_TOKEN_* | TF_LOG | TF_LOG_* | AWS_*)
          unset "${v}"
          ;;
      esac
    done
    export TF_DATA_DIR="${data}" TF_CLI_CONFIG_FILE="${cfg}" TF_IN_AUTOMATION=1 TF_INPUT=0
    export AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true
    cd "${dir}" && terraform "$@"
  )
}

TF_REQUIRED_DIRS="infra/terraform infra/bootstrap"
# terraform_dirs: prints the existing Terraform roots; returns 1 (after a
# MISSING INPUT message) if any required root is absent.
terraform_dirs() {
  local d rc=0
  for d in ${TF_REQUIRED_DIRS}; do
    if [[ -d "${REPO_ROOT}/${d}" ]]; then
      echo "${d}"
    else
      echo "  ERROR: MISSING INPUT: ${d} not found." >&2
      rc=1
    fi
  done
  return ${rc}
}

# hcl_path_ok <path>: 0 if the path can be embedded in an HCL string as is.
hcl_path_ok() {
  case "$1" in
    *'"'* | *'\'* | *'$'* | *'%'* | *$'\n'*) return 1 ;;
  esac
  return 0
}

gate_terraform_fmt() {
  local rc=0 d dirs cfg="${WORK_DIR}/terraformrc-fmt"
  if ! command -v terraform >/dev/null 2>&1; then
    GATE_NOTE="terraform not installed"
    echo "  ERROR: terraform is not installed (use --skip-terraform to skip)." >&2
    return 1
  fi
  if ! dirs="$(terraform_dirs)"; then
    GATE_NOTE="MISSING INPUT"
    rc=1
  fi
  : >"${cfg}"
  mkdir -p "${WORK_DIR}/tf-data-fmt"
  while IFS= read -r d; do
    [[ -n "${d}" ]] || continue
    if tf_isolated "${REPO_ROOT}" "${WORK_DIR}/tf-data-fmt" "${cfg}" fmt -check -recursive -no-color "${d}"; then
      echo "  fmt OK   ${d}"
    else
      echo "  fmt FAIL ${d} (run: terraform fmt -recursive ${d})" >&2
      rc=1
    fi
  done <<<"${dirs}"
  return ${rc}
}

gate_terraform_validate() {
  local rc=0 d data dirs src mirror=0 cfg="${WORK_DIR}/terraformrc"
  if ! command -v terraform >/dev/null 2>&1; then
    GATE_NOTE="terraform not installed"
    echo "  ERROR: terraform is not installed (use --skip-terraform to skip)." >&2
    return 1
  fi
  if [[ -d "${REPO_ROOT}/infra/terraform/.terraform/providers" ]]; then
    src="${REPO_ROOT}/infra/terraform/.terraform/providers"
    mirror=1
    if ! hcl_path_ok "${src}"; then
      echo "  ERROR: cannot embed ${src} in a Terraform CLI config." >&2
      return 1
    fi
    # Read-only filesystem mirror, no direct fallback: nothing is downloaded
    # and nothing is written into the shared directory.
    printf 'provider_installation {\n  filesystem_mirror {\n    path = "%s"\n  }\n}\n' "${src}" >"${cfg}"
    GATE_NOTE="providers from the filesystem mirror infra/terraform/.terraform/providers (no registry fallback)"
  else
    src="${REPO_ROOT}/local-runs/check/terraform-plugin-cache"
    if ! hcl_path_ok "${src}"; then
      echo "  ERROR: cannot embed ${src} in a Terraform CLI config." >&2
      return 1
    fi
    mkdir -p "${src}"
    printf 'plugin_cache_dir = "%s"\nprovider_installation {\n  direct {}\n}\n' "${src}" >"${cfg}"
    GATE_NOTE="providers from the public registry (private cache local-runs/check/terraform-plugin-cache)"
  fi
  echo "  CLI config (TF_CLI_CONFIG_FILE):"
  sed 's/^/    /' "${cfg}"
  if ! dirs="$(terraform_dirs)"; then
    GATE_NOTE="MISSING INPUT; ${GATE_NOTE}"
    rc=1
  fi
  while IFS= read -r d; do
    [[ -n "${d}" ]] || continue
    data="${WORK_DIR}/tf-data-$(echo "${d}" | tr '/' '-')"
    mkdir -p "${data}"
    echo "--- ${d}: terraform init -backend=false -lockfile=readonly (TF_DATA_DIR=${data}) ---"
    if ! tf_isolated "${REPO_ROOT}/${d}" "${data}" "${cfg}" init -backend=false -lockfile=readonly -input=false -no-color >"${WORK_DIR}/init.log" 2>&1; then
      cat "${WORK_DIR}/init.log" >&2
      echo "  init FAIL ${d}" >&2
      if [[ "${mirror}" -eq 1 ]]; then
        echo "  HINT: the filesystem mirror infra/terraform/.terraform/providers may lack a provider this root needs." >&2
        echo "        Re-init via scripts/tf-env.sh, or remove infra/terraform/.terraform/providers to use the private-cache fallback." >&2
      fi
      rc=1
      continue
    fi
    echo "--- ${d}: terraform validate ---"
    if tf_isolated "${REPO_ROOT}/${d}" "${data}" "${cfg}" validate -no-color; then
      echo "  validate OK   ${d}"
    else
      echo "  validate FAIL ${d}" >&2
      rc=1
    fi
  done <<<"${dirs}"
  return ${rc}
}

echo "ScholarChain check.sh (local only; no AWS) at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "  Repository: ${REPO_ROOT}"

run_gate constitution "${SKIP_CONSTITUTION}" "--skip-constitution" gate_constitution
run_gate hooks "${SKIP_HOOKS}" "--skip-hooks" gate_hooks
run_gate shell "${SKIP_SHELL}" "--skip-shell" gate_shell
if [[ ${SKIP_SHELL} -eq 1 || ${SKIP_SHELLCHECK} -eq 1 ]]; then
  run_gate shellcheck 1 "--skip-shellcheck or --skip-shell" gate_shellcheck
elif ! command -v shellcheck >/dev/null 2>&1; then
  run_gate shellcheck 1 "shellcheck not installed" gate_shellcheck
else
  run_gate shellcheck 0 "" gate_shellcheck
fi
if [[ ${CHECK_DEPTH} -gt 0 ]]; then
  run_gate script-tests 1 "nested check.sh run" gate_script_tests
else
  run_gate script-tests "${SKIP_SCRIPT_TESTS}" "--skip-script-tests" gate_script_tests
fi
run_gate schemas "${SKIP_SCHEMAS}" "--skip-schemas" gate_schemas
run_gate sbt "${SKIP_SBT}" "--skip-sbt" gate_sbt
run_gate terraform-fmt "${SKIP_TERRAFORM}" "--skip-terraform" gate_terraform_fmt
run_gate terraform-validate "${SKIP_TERRAFORM}" "--skip-terraform" gate_terraform_validate

echo ""
echo "=== SUMMARY ==="
printf '%-20s %-8s %8s  %s\n' GATE RESULT SECONDS NOTE
i=0
while [[ ${i} -lt ${#GATE_NAMES[@]} ]]; do
  printf '%-20s %-8s %8s  %s\n' "${GATE_NAMES[${i}]}" "${GATE_RESULTS[${i}]}" "${GATE_SECONDS[${i}]}" "${GATE_NOTES[${i}]}"
  i=$((i + 1))
done
if [[ ${FAILED} -gt 0 ]]; then
  echo "CHECK RESULT: FAIL (${FAILED} gate(s) failed)"
  exit 1
fi
echo "CHECK RESULT: PASS"
exit 0
