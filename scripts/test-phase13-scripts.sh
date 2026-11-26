#!/usr/bin/env bash
set -euo pipefail
# Local, offline tests for check.sh, package.sh and run-experiment.sh (Phase 13).
# No AWS, no network, no sbt, no docker: each script is copied into a throw-away
# Git repository under a temp dir, and sbt/docker are replaced by stubs that
# log their arguments (and fail loudly on push/login). The temp repositories
# are committed with a fixed local identity; the real repository is never
# staged or committed.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
FAILS=0
ok() { echo "PASS $1"; }
bad() {
  echo "FAIL $1"
  FAILS=$((FAILS + 1))
}
# run_rc <cmd>...: runs the command, stores output in OUT and status in RC.
OUT=""
RC=0
run_rc() {
  RC=0
  OUT="$("$@" 2>&1)" || RC=$?
}
expect() { # expect <name> <want-rc> [<substring>...] (uses OUT/RC)
  local name="$1" want="$2" s
  shift 2
  if [[ "${RC}" != "${want}" ]]; then
    bad "${name} (rc ${RC}, want ${want})"
    printf '%s\n' "${OUT}" | tail -n 15 | sed 's/^/    | /'
    return 0
  fi
  for s in "$@"; do
    if [[ "${OUT}" != *"${s}"* ]]; then
      bad "${name} (output lacks '${s}')"
      printf '%s\n' "${OUT}" | tail -n 15 | sed 's/^/    | /'
      return 0
    fi
  done
  ok "${name}"
}

for v in $(compgen -e); do
  case "${v}" in GIT_DIR | GIT_WORK_TREE | GIT_INDEX_FILE | GIT_COMMON_DIR | GIT_OBJECT_DIRECTORY | GIT_ALTERNATE_OBJECT_DIRECTORIES | GIT_CONFIG_*) unset "${v}" ;; esac
done

TMP_BASE="${TMPDIR:-/tmp}"
T="$(mktemp -d "${TMP_BASE%/}/scholarchain-p13test.XXXXXX")"
T="$(cd "${T}" && pwd -P)"
cleanup_tmp() {
  chmod -R u+w "${T}" 2>/dev/null || true
  rm -rf "${T}"
}
trap cleanup_tmp EXIT

gitq() { git -C "$1" -c user.name=fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false -c core.hooksPath=/dev/null "${@:2}"; }

# mkrepo <dir>: fixture repository with the Phase 13 scripts.
mkrepo() {
  mkdir -p "$1/scripts"
  cp "${SCRIPT_DIR}/check.sh" "${SCRIPT_DIR}/package.sh" "${SCRIPT_DIR}/run-experiment.sh" "${SCRIPT_DIR}/release-lib.sh" "$1/scripts/"
  printf 'local-runs/\ntarget/\n' >"$1/.gitignore"
  git init -q "$1"
}
commit_all() {
  gitq "$1" add -A
  gitq "$1" commit -q -m fixture
}

################################################################################
# check.sh
################################################################################
C="${T}/check"
mkrepo "${C}"
cat >"${C}/scripts/good.sh" <<'EOF'
#!/usr/bin/env bash
echo ok
EOF
ALL_BUT_SHELL=(--skip-constitution --skip-hooks --skip-shellcheck --skip-script-tests --skip-schemas --skip-sbt --skip-terraform)

run_rc bash "${C}/scripts/check.sh" --help
expect check-help 0 "Usage: ./scripts/check.sh"
run_rc bash "${C}/scripts/check.sh" --no-such-flag
expect check-unknown-flag 2 "unknown option '--no-such-flag'"

run_rc bash "${C}/scripts/check.sh" "${ALL_BUT_SHELL[@]}"
expect check-skip-flags 0 "GATE shell PASS" "GATE sbt SKIPPED (--skip-sbt)" \
  "GATE terraform-fmt SKIPPED (--skip-terraform)" "GATE terraform-validate SKIPPED (--skip-terraform)" \
  "GATE constitution SKIPPED" "=== SUMMARY ===" "CHECK RESULT: PASS"
run_rc bash "${C}/scripts/check.sh" --fast --skip-constitution --skip-hooks --skip-shellcheck --skip-script-tests --skip-schemas
expect check-fast-flag 0 "GATE sbt SKIPPED (--skip-sbt)" "GATE terraform-validate SKIPPED (--skip-terraform)"
run_rc env SCHOLARCHAIN_CHECK_DEPTH=1 bash "${C}/scripts/check.sh" --skip-constitution --skip-hooks --skip-shellcheck --skip-schemas --skip-sbt --skip-terraform
expect check-nested-skips-script-tests 0 "GATE script-tests SKIPPED (nested check.sh run)"
if ! command -v shellcheck >/dev/null 2>&1; then
  run_rc bash "${C}/scripts/check.sh" --skip-constitution --skip-hooks --skip-script-tests --skip-schemas --skip-sbt --skip-terraform
  expect check-shellcheck-absent-skipped 0 "GATE shellcheck SKIPPED (shellcheck not installed)"
fi

printf '#!/usr/bin/env bash\nif then fi\n' >"${C}/scripts/bad.sh"
run_rc bash "${C}/scripts/check.sh" "${ALL_BUT_SHELL[@]}"
expect check-bad-script-fails 1 "GATE shell FAIL" "bash -n FAIL scripts/bad.sh" "CHECK RESULT: FAIL"
rm -f "${C}/scripts/bad.sh"

mkdir -p "${C}/datasets/scripts"
printf '#!/usr/bin/env bash\ncase x in\n' >"${C}/datasets/scripts/broken.sh"
run_rc bash "${C}/scripts/check.sh" "${ALL_BUT_SHELL[@]}"
expect check-bad-dataset-script-fails 1 "bash -n FAIL datasets/scripts/broken.sh"
rm -f "${C}/datasets/scripts/broken.sh"

# Constitution gate: the real validator against a fixture .claude/ tree.
mkdir -p "${C}/.claude/agents" "${C}/.claude/hooks"
cp "${REAL_ROOT}/.claude/hooks/validate-agent-files.sh" "${C}/.claude/hooks/"
chmod +x "${C}/.claude/hooks/validate-agent-files.sh"
printf -- '---\nname: fixture-agent\ndescription: d\ntools: Read\n---\nbody\n' >"${C}/.claude/agents/fixture-agent.md"
echo '{}' >"${C}/.mcp.json"
echo '{"hooks": {}}' >"${C}/.claude/settings.json"
CONST_ONLY=(--skip-shell --skip-script-tests --skip-schemas --skip-sbt --skip-terraform)
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-pass 0 "GATE constitution PASS" "GATE hooks PASS"
echo '{"hooks": ' >"${C}/.claude/settings.json"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-bad-json-fails 1 "GATE constitution FAIL" ".claude/settings.json is not valid JSON"
# Exactly one JSON object: empty, concatenated and non-object documents fail.
: >"${C}/.claude/settings.json"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-empty-json-fails 1 "GATE constitution FAIL" ".claude/settings.json is not valid JSON"
printf '{"hooks": {}}\n{"hooks": {}}\n' >"${C}/.claude/settings.json"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-concatenated-json-fails 1 "GATE constitution FAIL" ".claude/settings.json is not valid JSON"
echo '[]' >"${C}/.mcp.json"
echo '{"hooks": {}}' >"${C}/.claude/settings.json"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-array-json-fails 1 "GATE constitution FAIL" ".mcp.json is not valid JSON"
echo '{}' >"${C}/.mcp.json"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-constitution-pass-again 0 "GATE constitution PASS"
# No hook files: the hooks gate FAILS (it does not report PASS on nothing).
mv "${C}/.claude/hooks/validate-agent-files.sh" "${T}/validator.sh.saved"
run_rc bash "${C}/scripts/check.sh" --skip-constitution --skip-shell --skip-script-tests --skip-schemas --skip-sbt --skip-terraform
expect check-no-hooks-fails 1 "GATE hooks FAIL" "MISSING INPUT"
mv "${T}/validator.sh.saved" "${C}/.claude/hooks/validate-agent-files.sh"
printf '#!/usr/bin/env bash\nwhile do\n' >"${C}/.claude/hooks/broken-hook.sh"
chmod +x "${C}/.claude/hooks/broken-hook.sh"
run_rc bash "${C}/scripts/check.sh" "${CONST_ONLY[@]}"
expect check-bad-hook-fails 1 "GATE hooks FAIL" "bash -n FAIL .claude/hooks/broken-hook.sh"
rm -f "${C}/.claude/hooks/broken-hook.sh"

# Schemas gate: an invalid prompt schema fails it (dataset validators absent).
mkdir -p "${C}/prompts/schemas"
echo '{"type": "object"}' >"${C}/prompts/schemas/ok.json"
echo '{"type": ' >"${C}/prompts/schemas/broken.json"
run_rc bash "${C}/scripts/check.sh" --skip-constitution --skip-hooks --skip-shell --skip-script-tests --skip-sbt --skip-terraform
expect check-bad-schema-fails 1 "GATE schemas FAIL" "INVALID JSON prompts/schemas/broken.json"
rm -f "${C}/prompts/schemas/broken.json"
run_rc bash "${C}/scripts/check.sh" --skip-constitution --skip-hooks --skip-shell --skip-script-tests --skip-sbt --skip-terraform
expect check-missing-dataset-inputs-fail 1 "GATE schemas FAIL" "MISSING INPUT: datasets/scripts/tests" "MISSING INPUT: datasets/scripts/validate_manifest.py"

# sbt and terraform gates with stub tools on PATH (no real sbt/terraform).
TSTUB_FAIL="${T}/tstub-fail"
TSTUB_OK="${T}/tstub-ok"
STUB_TF_LOG="${T}/tf-env.log"
mkdir -p "${TSTUB_FAIL}" "${TSTUB_OK}"
# The stub records only variable NAMES under TF_/AWS_ plus the values of the
# non-secret variables the gate is expected to set.
cat >"${T}/tf-stub-body" <<'EOF'
{
  echo "ARGS $*"
  env | sed -n -E 's/^((TF|AWS)_[A-Za-z0-9_]*)=.*/NAME \1/p'
  echo "TF_CLI_CONFIG_FILE=${TF_CLI_CONFIG_FILE:-}"
  echo "AWS_CONFIG_FILE=${AWS_CONFIG_FILE:-}"
  echo "AWS_SHARED_CREDENTIALS_FILE=${AWS_SHARED_CREDENTIALS_FILE:-}"
  [[ -n "${TF_CLI_CONFIG_FILE:-}" && -f "${TF_CLI_CONFIG_FILE}" ]] && sed 's/^/CFG /' "${TF_CLI_CONFIG_FILE}"
} >>"${STUB_TF_LOG}"
EOF
{ echo '#!/usr/bin/env bash'; cat "${T}/tf-stub-body"; echo 'echo "stub terraform failure" >&2; exit 1'; } >"${TSTUB_FAIL}/terraform"
{ echo '#!/usr/bin/env bash'; cat "${T}/tf-stub-body"; echo 'exit 0'; } >"${TSTUB_OK}/terraform"
printf '#!/usr/bin/env bash\necho "stub sbt failure" >&2\nexit 1\n' >"${TSTUB_FAIL}/sbt"
printf '#!/usr/bin/env bash\necho "stub sbt $*"\nexit 0\n' >"${TSTUB_OK}/sbt"
chmod +x "${TSTUB_FAIL}/terraform" "${TSTUB_OK}/terraform" "${TSTUB_FAIL}/sbt" "${TSTUB_OK}/sbt"
TOOL_ONLY=(--skip-constitution --skip-hooks --skip-shell --skip-script-tests --skip-schemas)
# Terraform roots absent: both terraform gates FAIL with MISSING INPUT.
run_rc env PATH="${TSTUB_OK}:${PATH}" STUB_TF_LOG="${STUB_TF_LOG}" bash "${C}/scripts/check.sh" "${TOOL_ONLY[@]}"
expect check-missing-terraform-roots-fail 1 "GATE terraform-fmt FAIL" "GATE terraform-validate FAIL" "MISSING INPUT: infra/terraform not found"
mkdir -p "${C}/infra/terraform/.terraform/providers" "${C}/infra/bootstrap"
: >"${STUB_TF_LOG}"
run_rc env PATH="${TSTUB_FAIL}:${PATH}" STUB_TF_LOG="${STUB_TF_LOG}" AWS_PROFILE=leak-check AWS_ACCESS_KEY_ID=fixture-not-a-key \
  TF_CLI_CONFIG_FILE=/nonexistent/user.tfrc TF_PLUGIN_CACHE_DIR=/nonexistent/shared-cache TF_CLI_ARGS=-no-color \
  bash "${C}/scripts/check.sh" "${TOOL_ONLY[@]}"
expect check-failing-sbt-and-terraform 1 "GATE sbt FAIL" "GATE terraform-fmt FAIL" "GATE terraform-validate FAIL" "CHECK RESULT: FAIL (3 gate(s) failed)" "Re-init via scripts/tf-env.sh, or remove infra/terraform/.terraform/providers"
if grep -q 'ARGS init -backend=false -lockfile=readonly' "${STUB_TF_LOG}" \
  && ! grep -q -E '^NAME (AWS_PROFILE|AWS_ACCESS_KEY_ID|TF_PLUGIN_CACHE_DIR|TF_CLI_ARGS)$' "${STUB_TF_LOG}" \
  && ! grep -q 'TF_CLI_CONFIG_FILE=/nonexistent' "${STUB_TF_LOG}" \
  && grep -q -E '^TF_CLI_CONFIG_FILE=/.+/terraformrc$' "${STUB_TF_LOG}" \
  && grep -q '^AWS_CONFIG_FILE=/dev/null$' "${STUB_TF_LOG}" && grep -q '^AWS_SHARED_CREDENTIALS_FILE=/dev/null$' "${STUB_TF_LOG}" \
  && grep -q "^CFG     path = \"${C}/infra/terraform/.terraform/providers\"" "${STUB_TF_LOG}" \
  && grep -q '^CFG   filesystem_mirror {' "${STUB_TF_LOG}" && ! grep -q 'direct' "${STUB_TF_LOG}"; then
  ok check-tf-isolated-env
else
  bad check-tf-isolated-env
  sed 's/^/    | /' "${STUB_TF_LOG}" | head -n 40
fi
# The same gates pass with succeeding stubs (the failures above come from the tools).
run_rc env PATH="${TSTUB_OK}:${PATH}" STUB_TF_LOG="${STUB_TF_LOG}" bash "${C}/scripts/check.sh" "${TOOL_ONLY[@]}"
expect check-passing-sbt-and-terraform 0 "GATE sbt PASS" "GATE terraform-fmt PASS" "GATE terraform-validate PASS" "CHECK RESULT: PASS"
# Without the shared providers directory: private cache and registry config.
rm -rf "${C}/infra/terraform/.terraform"
: >"${STUB_TF_LOG}"
run_rc env PATH="${TSTUB_OK}:${PATH}" STUB_TF_LOG="${STUB_TF_LOG}" bash "${C}/scripts/check.sh" "${TOOL_ONLY[@]}"
if [[ ${RC} -eq 0 ]] && grep -q "^CFG plugin_cache_dir = \"${C}/local-runs/check/terraform-plugin-cache\"" "${STUB_TF_LOG}"; then
  ok check-tf-private-cache-fallback
else
  bad "check-tf-private-cache-fallback (rc ${RC})"
fi

################################################################################
# package.sh (stubbed sbt and docker)
################################################################################
P="${T}/package"
mkrepo "${P}"
echo 'ThisBuild / version      := "0.1.0-SNAPSHOT"' >"${P}/build.sbt"
echo tracked >"${P}/tracked.txt"
commit_all "${P}"
PSHA="$(git -C "${P}" rev-parse HEAD)"
STUBS="${T}/stubs"
mkdir -p "${STUBS}"
STUB_LOG="${T}/stub.log"
: >"${STUB_LOG}"
# Stub knobs (environment): STUB_SBT_FAIL, STUB_SBT_NOJAR (no hadoop jar),
# STUB_SBT_DUP (a second hadoop jar), STUB_SBT_SKIP_EXISTING (like
# sbt-assembly: do not rewrite an existing jar), STUB_SBT_TOUCH=<file> (append
# to a file during the build), STUB_ARCH (image architecture),
# STUB_DOCKER_FAIL=build.
cat >"${STUBS}/sbt" <<'EOF'
#!/usr/bin/env bash
echo "sbt $*" >>"${STUB_LOG}"
if [[ -n "${STUB_SBT_FAIL:-}" ]]; then echo "stub sbt failure" >&2; exit 1; fi
mk() {
  if [[ -n "${STUB_SBT_SKIP_EXISTING:-}" && -f "$1" ]]; then return 0; fi
  mkdir -p "$(dirname "$1")"; printf 'fake jar %s\n' "$1" >"$1"
}
for a in "$@"; do
  case "${a}" in
    packageJobs)
      [[ -n "${STUB_SBT_NOJAR:-}" ]] || mk modules/hadoop/target/scala-3.3.4/scholarchain-hadoop-assembly-0.1.0-SNAPSHOT.jar
      [[ -z "${STUB_SBT_DUP:-}" ]] || mk modules/hadoop/target/scala-3.5.0/scholarchain-hadoop-assembly-0.1.0-SNAPSHOT.jar
      mk modules/entity-resolution/target/scala-3.3.4/scholarchain-entity-resolution-assembly-0.1.0-SNAPSHOT.jar
      mk modules/spark/target/scala-3.3.4/scholarchain-spark-assembly-0.1.0-SNAPSHOT.jar
      [[ -z "${STUB_SBT_TOUCH:-}" ]] || echo "changed during the build" >>"${STUB_SBT_TOUCH}"
      ;;
  esac
done
exit 0
EOF
cat >"${STUBS}/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >>"${STUB_LOG}"
case "$1" in
  push | login) echo "FORBIDDEN docker $1" >>"${STUB_LOG}"; exit 99 ;;
  build) if [[ "${STUB_DOCKER_FAIL:-}" == build ]]; then echo "stub docker build failure" >&2; exit 1; fi ;;
  image)
    case "$4" in
      '{{.Id}}') echo "sha256:$(printf 'a%.0s' $(seq 1 64))" ;;
      '{{.Os}}') echo linux ;;
      '{{.Architecture}}') echo "${STUB_ARCH:-amd64}" ;;
    esac
    ;;
esac
exit 0
EOF
chmod +x "${STUBS}/sbt" "${STUBS}/docker"
export STUB_LOG
PKG_ENV=(env SBT="${STUBS}/sbt" DOCKER="${STUBS}/docker" STUB_LOG="${STUB_LOG}")
# package.sh names its output directory by the UTC second: wait for a new
# second before each real run so that runs never collide by accident.
next_second() {
  local s
  s="$(date +%s)"
  while [[ "$(date +%s)" == "${s}" ]]; do sleep 0.1; done
}
newest_manifest() { # newest package-manifest.json under the tag directory
  find "${P}/local-runs/package/$1" -name package-manifest.json 2>/dev/null | LC_ALL=C sort | tail -n 1
}
count_manifests() {
  find "${P}/local-runs/package" -name package-manifest.json 2>/dev/null | wc -l | tr -d ' '
}

run_rc bash "${P}/scripts/package.sh" --bogus
expect package-unknown-flag 2 "unknown option"
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --dry-run
expect package-dry-run-sha-tag 0 "Tag:       ${PSHA}" "scholarchain-services:${PSHA}" "scholarchain-batch-runner:${PSHA}" "DRY RUN"
[[ ! -e "${P}/local-runs" && ! -s "${STUB_LOG}" ]] && ok package-dry-run-builds-nothing || bad package-dry-run-builds-nothing

echo scratch >"${P}/untracked.txt"
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --dry-run
expect package-dirty-refused 3 "${PSHA}-dirty" "Git tree is dirty"
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --dry-run --allow-dirty
expect package-dirty-allowed-tag 0 "Tag would be ${PSHA}-dirty"
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --jars-only
expect package-dirty-real-refused 3 "Git tree is dirty"
rm -f "${P}/untracked.txt"
# Index flags hide edits from git status: such a tree is dirty.
gitq "${P}" update-index --skip-worktree tracked.txt
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --dry-run
expect package-skip-worktree-dirty 3 "Git tree is dirty" "!! skip-worktree tracked.txt"
gitq "${P}" update-index --no-skip-worktree tracked.txt
gitq "${P}" update-index --assume-unchanged tracked.txt
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --dry-run
expect package-assume-unchanged-dirty 3 "Git tree is dirty" "!! assume-unchanged tracked.txt"
gitq "${P}" update-index --no-assume-unchanged tracked.txt

next_second
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh"
expect package-stub-run 0 "PACKAGE COMPLETE" "Tag:       ${PSHA}"
PM="$(newest_manifest "${PSHA}")"
if [[ -n "${PM}" ]] && jq -e --arg s "${PSHA}" '
    .schemaVersion == "scholarchain.package/v1" and .commitSha == $s and .gitDirty == false and .tag == $s
    and .fargateCompatible == true and all(.images[]; .fargateCompatible == true and .architecture == "amd64")
    and (.treeFingerprint | test("^[0-9a-f]{64}$"))
    and .pushed == false and (.createdAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and (.jars | length) == 3 and all(.jars[]; (.sha256 | test("^[0-9a-f]{64}$")) and .sizeBytes > 0)
    and ([.jars[].name] == ["scholarchain-hadoop","scholarchain-entity-resolution","scholarchain-spark"])
    and (.images | length) == 2 and all(.images[]; (.imageId | startswith("sha256:")) and (.ref | endswith(":" + $s)))
    and (.images[0].ecrRepositories | length) == 6 and .images[1].ecrRepositories == ["batch-runner"]
    and .provenance.commit == $s' "${PM}" >/dev/null; then
  ok package-manifest-shape
else
  bad "package-manifest-shape (${PM:-no manifest})"
fi
if [[ -n "${PM}" ]]; then
  J0="$(jq -r '.jars[0].path' "${PM}")"
  [[ "$(jq -r '.jars[0].sha256' "${PM}")" == "$(shasum -a 256 "${P}/${J0}" | awk '{print $1}')" ]] && ok package-jar-sha256-matches || bad package-jar-sha256-matches
  [[ ! -w "${PM}" ]] && ok package-manifest-read-only || bad package-manifest-read-only
fi
if grep -q -E 'FORBIDDEN|docker (push|login)' "${STUB_LOG}"; then bad package-never-pushes; else ok package-never-pushes; fi
grep -q "docker tag scholarchain-services:0.1.0-SNAPSHOT scholarchain-services:${PSHA}" "${STUB_LOG}" && ok package-services-retagged || bad package-services-retagged
grep -q "docker build --platform linux/amd64 --label org.opencontainers.image.revision=${PSHA} -t scholarchain-batch-runner:${PSHA} docker/batch-runner" "${STUB_LOG}" && ok package-batch-runner-built || bad package-batch-runner-built

: >"${STUB_LOG}"
next_second
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --skip-images
expect package-jars-only 0 "PACKAGE COMPLETE"
if grep -q '^docker' "${STUB_LOG}"; then bad package-jars-only-no-docker; else ok package-jars-only-no-docker; fi
PM2="$(newest_manifest "${PSHA}")"
[[ "${PM2}" != "${PM}" && "$(jq -c '.images' "${PM2}")" == "[]" && "$(jq '.jars | length' "${PM2}")" == 3 && "$(jq -c '.fargateCompatible' "${PM2}")" == null ]] && ok package-jars-only-manifest || bad package-jars-only-manifest

# m2: like sbt-assembly, the stub does not rewrite an up-to-date jar. The old
# mtime check failed this second run (exit 5); outputs are now deleted first.
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_SKIP_EXISTING=1 bash "${P}/scripts/package.sh" --jars-only
expect package-up-to-date-jar-rebuilt 0 "PACKAGE COMPLETE" "rm modules/hadoop/target/scala-3.3.4/scholarchain-hadoop-assembly-0.1.0-SNAPSHOT.jar"
# A stale jar from an earlier build is never picked up.
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_NOJAR=1 bash "${P}/scripts/package.sh" --jars-only
expect package-missing-jar 5 "expected exactly one fresh scholarchain-hadoop-assembly-0.1.0-SNAPSHOT.jar" "found 0"
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_DUP=1 bash "${P}/scripts/package.sh" --jars-only
expect package-ambiguous-jar 5 "found 2"
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_FAIL=1 bash "${P}/scripts/package.sh" --jars-only
expect package-sbt-failure 1 "sbt packageJobs failed"
next_second
run_rc "${PKG_ENV[@]}" STUB_DOCKER_FAIL=build bash "${P}/scripts/package.sh"
expect package-docker-failure 1 "docker build docker/batch-runner failed"

# M5: a non-amd64 image fails closed; the human override records it.
N_BEFORE="$(count_manifests)"
next_second
run_rc "${PKG_ENV[@]}" STUB_ARCH=arm64 bash "${P}/scripts/package.sh"
expect package-non-amd64-refused 6 "linux/arm64" "not linux/amd64" "No manifest written"
[[ "$(count_manifests)" == "${N_BEFORE}" ]] && ok package-non-amd64-no-manifest || bad package-non-amd64-no-manifest
if [[ "${OUT}" == *"PACKAGE COMPLETE"* ]]; then bad package-non-amd64-not-complete; else ok package-non-amd64-not-complete; fi
next_second
run_rc "${PKG_ENV[@]}" STUB_ARCH=arm64 bash "${P}/scripts/package.sh" --allow-non-amd64
expect package-non-amd64-override 0 "PACKAGE COMPLETE" "NOT FARGATE-COMPATIBLE"
PM3="$(newest_manifest "${PSHA}")"
if jq -e '.fargateCompatible == false and all(.images[]; .fargateCompatible == false and .architecture == "arm64")' "${PM3}" >/dev/null 2>&1; then
  ok package-non-amd64-override-recorded
else
  bad "package-non-amd64-override-recorded (${PM3})"
fi

# An existing output directory is never reused.
NOW="$(date -u +%s)"
PRE=()
for i in 0 1 2 3 4 5; do
  d="$(date -u -r $((NOW + i)) +%Y%m%dT%H%M%SZ 2>/dev/null || date -u -d "@$((NOW + i))" +%Y%m%dT%H%M%SZ)"
  mkdir -p "${P}/local-runs/package/${PSHA}/${d}"
  PRE+=("${P}/local-runs/package/${PSHA}/${d}")
done
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --jars-only
expect package-existing-output-refused 5 "already exists; refusing to overwrite"
rmdir "${PRE[@]}" 2>/dev/null || true

# m3: the tree changing during the build is detected, also when it was dirty
# before (the dirty flag alone does not change).
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_TOUCH="${P}/tracked.txt" bash "${P}/scripts/package.sh" --jars-only
expect package-tree-changed-clean 5 "changed during the build"
gitq "${P}" checkout -q -- tracked.txt
echo scratch >"${P}/untracked.txt"
next_second
run_rc "${PKG_ENV[@]}" bash "${P}/scripts/package.sh" --jars-only --allow-dirty
expect package-dirty-allowed-stable 0 "PACKAGE COMPLETE"
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_TOUCH="${P}/untracked.txt" bash "${P}/scripts/package.sh" --jars-only --allow-dirty
expect package-tree-changed-dirty-untracked 5 "changed during the build"
echo tracked-edit >>"${P}/tracked.txt"
next_second
run_rc "${PKG_ENV[@]}" STUB_SBT_TOUCH="${P}/tracked.txt" bash "${P}/scripts/package.sh" --jars-only --allow-dirty
expect package-tree-changed-dirty-tracked 5 "changed during the build"
gitq "${P}" checkout -q -- tracked.txt
rm -f "${P}/untracked.txt"
if grep -q -E 'FORBIDDEN|docker (push|login)' "${STUB_LOG}"; then bad package-never-pushes-final; else ok package-never-pushes-final; fi

# release-lib: a failing `git ls-files --others` fails the fingerprint closed.
REAL_GIT="$(command -v git)"
GSTUB="${T}/gitstub"
mkdir -p "${GSTUB}"
cat >"${GSTUB}/git" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do
  if [[ "\${a}" == --others ]]; then echo "stub: ls-files --others failure" >&2; exit 128; fi
done
exec "${REAL_GIT}" "\$@"
EOF
chmod +x "${GSTUB}/git"
run_rc env PATH="${GSTUB}:${PATH}" bash -c 'source "$1/scripts/release-lib.sh" && rl_tree_fingerprint "$1"' _ "${P}"
expect release-lib-untracked-listing-failure-fails-closed 3 "'git ls-files --others' failed" "cannot fingerprint the Git tree"
run_rc bash -c 'source "$1/scripts/release-lib.sh" && rl_tree_fingerprint "$1"' _ "${P}"
[[ ${RC} -eq 0 && "${OUT}" =~ ^[0-9a-f]{64}$ ]] && ok release-lib-fingerprint-ok || bad "release-lib-fingerprint-ok (rc ${RC}: ${OUT})"

################################################################################
# run-experiment.sh
################################################################################
E="${T}/exp"
mkrepo "${E}"
mkdir -p "${E}/load-tests" "${E}/experiments" "${E}/.claude/agents"
printf -- '---\nname: build-engineer\n---\n' >"${E}/.claude/agents/build-engineer.md"
cat >"${E}/load-tests/fixture.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
printf '{"rows":%s,"aws_profile":"%s","aws_config":"%s","home":"%s"}\n' "${1:-0}" "${AWS_PROFILE:-unset}" "${AWS_CONFIG_FILE:-unset}" "${HOME:-unset}" >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
echo "fixture ran with $# arg(s)"
EOF
printf '#!/usr/bin/env bash\nsleep 30\n' >"${E}/load-tests/slow.sh"
# Signal fixture: records its PID and its child's PID, then waits.
cat >"${E}/load-tests/sigslow.sh" <<'EOF'
#!/usr/bin/env bash
sleep 60 &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
echo $$ >"${SCHOLARCHAIN_RUN_DIR}/entry.pid"
wait
EOF
# R2-M1 fixtures. tignore.sh: a background child that ignores TERM/INT/HUP.
cat >"${E}/load-tests/tignore.sh" <<'EOF'
#!/usr/bin/env bash
( trap '' TERM INT HUP; exec sleep 45 ) &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
echo $$ >"${SCHOLARCHAIN_RUN_DIR}/entry.pid"
wait
EOF
# orphan.sh: succeeds (metric written) but leaves a group member behind.
cat >"${E}/load-tests/orphan.sh" <<'EOF'
#!/usr/bin/env bash
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
echo '{}' >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
sleep 60 &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
echo $$ >"${SCHOLARCHAIN_RUN_DIR}/entry.pid"
exit 0
EOF
# orphan-ign.sh: succeeds but leaves a TERM-ignoring member (slow drain).
cat >"${E}/load-tests/orphan-ign.sh" <<'EOF'
#!/usr/bin/env bash
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
echo '{}' >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
( trap '' TERM INT HUP; exec sleep 45 ) &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
echo $$ >"${SCHOLARCHAIN_RUN_DIR}/entry.pid"
exit 0
EOF
# R2-M2 fixture: writes no metric, but empties the metrics list of every
# manifest snapshot it can find (via HOME/.. and the runner's TMPDIR).
cat >"${E}/load-tests/tamper.sh" <<'EOF'
#!/usr/bin/env bash
n=0
for f in "${HOME}/../manifest.json" "${TMPDIR:-/tmp}"/scholarchain-exp.*/manifest.json; do
  if [[ -f "${f}" ]]; then
    chmod u+w "${f}" 2>/dev/null || true
    if jq '.metrics = []' "${f}" >"${f}.t" 2>/dev/null && cat "${f}.t" >"${f}"; then n=$((n + 1)); fi
  fi
done
echo "tampered=${n}"
EOF
# R2-m1 fixture: probes the records directory, then forges run-record.json.
cat >"${E}/load-tests/forge.sh" <<'EOF'
#!/usr/bin/env bash
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
echo '{}' >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
rec="${SCHOLARCHAIN_RUN_DIR}/../records"
if (printf 'x\n' >"${rec}/probe") 2>/dev/null; then r=writable; else r=readonly; fi
echo "${r}" >"${SCHOLARCHAIN_RUN_DIR}/probe-result"
chmod u+w "${SCHOLARCHAIN_RUN_DIR}/.." "${rec}"
printf '{"forged":true,"status":"succeeded"}\n' >"${rec}/run-record.json"
EOF
# R3-MED fixtures. hidetimeout.sh: on TERM (the watchdog), deletes every
# `timed-out` marker it can find by glob in its TMPDIR and the directory
# holding it (the runner's TMPDIR), then exits 0.
cat >"${E}/load-tests/hidetimeout.sh" <<'EOF'
#!/usr/bin/env bash
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
echo '{}' >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
hide() {
  local base f
  for base in "${TMPDIR:-/tmp}" "$(dirname "${TMPDIR:-/tmp}")"; do
    for f in "${base}"/scholarchain-exp*/timed-out "${base}/timed-out"; do
      if [[ -e "${f}" ]]; then rm -f "${f}" && echo "removed ${f}"; fi
    done
  done
  exit 0
}
trap hide TERM
sleep 30 &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
wait
EOF
# plant.sh <rc>: plants `timed-out` and `entry-changed` markers in its own
# TMPDIR, the runner's TMPDIR and every scholarchain-exp* directory in them,
# writes the metric and exits <rc>.
cat >"${E}/load-tests/plant.sh" <<'EOF'
#!/usr/bin/env bash
mkdir -p "${SCHOLARCHAIN_RUN_DIR}/metrics"
echo '{}' >"${SCHOLARCHAIN_RUN_DIR}/metrics/result.json"
printf '%s\n' "${TMPDIR:-unset}" >"${SCHOLARCHAIN_RUN_DIR}/tmpdir"
n=0
for base in "${TMPDIR:-/tmp}" "$(dirname "${TMPDIR:-/tmp}")"; do
  for d in "${base}" "${base}"/scholarchain-exp*; do
    [[ -d "${d}" ]] || continue
    for m in timed-out entry-changed; do
      if : >"${d}/${m}" 2>/dev/null; then n=$((n + 1)); fi
    done
  done
done
echo "planted=${n}"
exit "${1:-0}"
EOF
# lignore.sh: the LEADER ignores TERM/INT/HUP, so only the forwarded-signal
# killer (SIGKILL 10 s after forwarding) ends it.
cat >"${E}/load-tests/lignore.sh" <<'EOF'
#!/usr/bin/env bash
trap '' TERM INT HUP
sleep 60 &
echo $! >"${SCHOLARCHAIN_RUN_DIR}/sleep.pid"
echo $$ >"${SCHOLARCHAIN_RUN_DIR}/entry.pid"
wait
EOF
# Committed symlinks (M1): an entry point and manifests.
ln -s fixture.sh "${E}/load-tests/clink.sh"
ln -s ok.json "${E}/experiments/mlink.json"
ln -s ../local-runs/evil.json "${E}/experiments/olink.json"
ln -s ../local-runs/mdir "${E}/experiments/dlink"
printf '#!/usr/bin/env bash\necho failing >&2\nexit 7\n' >"${E}/load-tests/fail.sh"
printf '#!/usr/bin/env bash\necho no metrics\n' >"${E}/load-tests/nometrics.sh"
# Stand-in for an AWS script: must never run in these tests.
printf '#!/usr/bin/env bash\ntouch "%s/AWS-SCRIPT-RAN"\n' "${T}" >"${E}/scripts/smoke-test.sh"
printf '#!/usr/bin/env bash\necho cleanup\n' >"${E}/scripts/cleanup.sh"
X="${E}/scripts/run-experiment.sh"

# mkmanifest <file> <entryPoint> <touchesAws> [jq filter applied last]
mkmanifest() {
  jq -n --arg e "$2" --argjson aws "$3" '{
    schemaVersion: "scholarchain.experiment/v1", id: "exp-fixture", description: "offline fixture",
    entryPoint: $e, args: ["42"], touchesAws: $aws, region: "us-east-2",
    resourceLimits: {timeoutSeconds: 20, maxCostUsd: 0, maxTasks: 1},
    metrics: [{name: "result", location: "metrics/result.json"}]}' | jq "${4:-.}" >"$1"
}
# approve <file> [approvedBy] [approvedAt]
approve() {
  local d
  d="$(bash "${X}" --print-digest "$1" | tail -n 1)"
  jq --arg d "${d}" --arg by "${2:-Human Architect}" --arg at "${3:-2026-11-26T09:00:00Z}" \
    '. + {approval: {approvedBy: $by, approvedAt: $at, manifestSha256: $d}}' "$1" >"$1.tmp"
  mv "$1.tmp" "$1"
}
M="${E}/experiments"
mkmanifest "${M}/ok.json" load-tests/fixture.sh false
approve "${M}/ok.json"
mkmanifest "${M}/unapproved.json" load-tests/fixture.sh false
mkmanifest "${M}/agent.json" load-tests/fixture.sh false && approve "${M}/agent.json" build-engineer
mkmanifest "${M}/claude.json" load-tests/fixture.sh false && approve "${M}/claude.json" "Claude"
mkmanifest "${M}/future.json" load-tests/fixture.sh false && approve "${M}/future.json" "Human Architect" 2099-01-01T00:00:00Z
mkmanifest "${M}/tampered.json" load-tests/fixture.sh false && approve "${M}/tampered.json"
jq '.args = ["42", "; rm -rf /"]' "${M}/tampered.json" >"${M}/t.tmp" && mv "${M}/t.tmp" "${M}/tampered.json"
mkmanifest "${M}/cleanup.json" scripts/cleanup.sh false && approve "${M}/cleanup.json"
mkmanifest "${M}/dotdot.json" load-tests/../scripts/cleanup.sh false && approve "${M}/dotdot.json"
mkmanifest "${M}/abs.json" /bin/sh false && approve "${M}/abs.json"
mkmanifest "${M}/shell.json" 'load-tests/fixture.sh;id' false && approve "${M}/shell.json"
mkmanifest "${M}/untracked-entry.json" load-tests/new.sh false && approve "${M}/untracked-entry.json"
mkmanifest "${M}/symlink.json" load-tests/link.sh false && approve "${M}/symlink.json"
mkmanifest "${M}/aws-undeclared.json" scripts/smoke-test.sh false && approve "${M}/aws-undeclared.json"
mkmanifest "${M}/aws.json" scripts/smoke-test.sh true '.args = ["dev"] | .resourceLimits.maxCostUsd = 5' && approve "${M}/aws.json"
mkmanifest "${M}/aws-region.json" scripts/smoke-test.sh true '.region = "eu-west-1"' && approve "${M}/aws-region.json"
mkmanifest "${M}/bad-timeout.json" load-tests/fixture.sh false '.resourceLimits.timeoutSeconds = 0' && approve "${M}/bad-timeout.json"
mkmanifest "${M}/bad-metric.json" load-tests/fixture.sh false '.metrics = [{name: "m", location: "../escape.json"}]' && approve "${M}/bad-metric.json"
mkmanifest "${M}/slow.json" load-tests/slow.sh false '.resourceLimits.timeoutSeconds = 1' && approve "${M}/slow.json"
mkmanifest "${M}/fail.json" load-tests/fail.sh false && approve "${M}/fail.json"
mkmanifest "${M}/nometrics.json" load-tests/nometrics.sh false && approve "${M}/nometrics.json"
mkmanifest "${M}/sigslow.json" load-tests/sigslow.sh false '.resourceLimits.timeoutSeconds = 60' && approve "${M}/sigslow.json"
mkmanifest "${M}/tignore.json" load-tests/tignore.sh false '.resourceLimits.timeoutSeconds = 2' && approve "${M}/tignore.json"
mkmanifest "${M}/tignore-long.json" load-tests/tignore.sh false '.resourceLimits.timeoutSeconds = 60' && approve "${M}/tignore-long.json"
mkmanifest "${M}/orphan.json" load-tests/orphan.sh false && approve "${M}/orphan.json"
mkmanifest "${M}/orphan-ign.json" load-tests/orphan-ign.sh false && approve "${M}/orphan-ign.json"
mkmanifest "${M}/tamper.json" load-tests/tamper.sh false && approve "${M}/tamper.json"
mkmanifest "${M}/forge.json" load-tests/forge.sh false && approve "${M}/forge.json"
mkmanifest "${M}/hidetimeout.json" load-tests/hidetimeout.sh false '.resourceLimits.timeoutSeconds = 2' && approve "${M}/hidetimeout.json"
mkmanifest "${M}/faketimeout.json" load-tests/plant.sh false '.args = ["0"]' && approve "${M}/faketimeout.json"
mkmanifest "${M}/plantchanged.json" load-tests/plant.sh false '.args = ["7"]' && approve "${M}/plantchanged.json"
mkmanifest "${M}/lignore.json" load-tests/lignore.sh false '.resourceLimits.timeoutSeconds = 60' && approve "${M}/lignore.json"
mkmanifest "${M}/sigslow-int.json" load-tests/sigslow.sh false '.resourceLimits.timeoutSeconds = 15' && approve "${M}/sigslow-int.json"
mkmanifest "${M}/clink-entry.json" load-tests/clink.sh false && approve "${M}/clink-entry.json"
mkmanifest "${M}/s3-ctrl.json" scripts/smoke-test.sh true '.metrics = [{name: "m", location: "s3://bucket/a\u001b[2Jb"}]' && approve "${M}/s3-ctrl.json"
echo '[1, 2]' >"${M}/not-object.json"
commit_all "${E}"
ESHA="$(git -C "${E}" rev-parse HEAD)"
[[ "$(git -C "${E}" ls-tree HEAD -- experiments/olink.json | cut -c1-6)" == 120000 && "$(git -C "${E}" ls-tree HEAD -- load-tests/clink.sh | cut -c1-6)" == 120000 ]] \
  && ok exp-fixture-symlinks-committed || bad exp-fixture-symlinks-committed
# Targets of the committed symlinks live in the gitignored local-runs/: the
# tree stays clean while their contents can be rewritten at will.
mkdir -p "${E}/local-runs/mdir"
mkmanifest "${E}/local-runs/evil.json" load-tests/fixture.sh false '.args = ["666"]' && approve "${E}/local-runs/evil.json"
cp "${E}/local-runs/evil.json" "${E}/local-runs/mdir/m.json"
# Created after the commit: untracked entry point and a symlink (both refused).
printf '#!/usr/bin/env bash\necho new\n' >"${E}/load-tests/new.sh"
ln -s fixture.sh "${E}/load-tests/link.sh"
printf 'load-tests/new.sh\nload-tests/link.sh\n' >>"${E}/.git/info/exclude"

run_rc bash "${X}"
expect exp-no-args 2 "no manifest given"
run_rc bash "${X}" --bogus "${M}/ok.json"
expect exp-unknown-flag 2 "unknown option"
run_rc bash "${X}" --run-id 'bad id' "${M}/ok.json"
expect exp-bad-run-id 2 "--run-id"
run_rc bash "${X}" --dry-run "${M}/ok.json"
expect exp-approved-dry-run 0 "matches" "is allowlisted" "Command:      /bin/bash load-tests/fixture.sh 42" "DRY RUN"
[[ ! -e "${E}/local-runs/experiments" ]] && ok exp-dry-run-executes-nothing || bad exp-dry-run-executes-nothing
run_rc bash "${X}" --dry-run "${M}/missing.json"
expect exp-missing-manifest 3 "manifest not found"
run_rc bash "${X}" --dry-run "${M}/not-object.json"
expect exp-not-object 4 "not a JSON object"
run_rc bash "${X}" --dry-run "${M}/unapproved.json"
expect exp-unapproved 5 "approval block is missing"
run_rc bash "${X}" --dry-run "${M}/agent.json"
expect exp-agent-approver-refused 5 "only a human may approve"
run_rc bash "${X}" --dry-run "${M}/claude.json"
expect exp-claude-approver-refused 5 "only a human may approve"
run_rc bash "${X}" --dry-run "${M}/future.json"
expect exp-future-approval-refused 5 "not in the future"
run_rc bash "${X}" --dry-run "${M}/tampered.json"
expect exp-tampered 6 "tampered"
for c in cleanup dotdot abs shell untracked-entry symlink; do
  run_rc bash "${X}" --dry-run "${M}/${c}.json"
  expect "exp-entry-refused-${c}" 7 "is not allowed"
done
run_rc bash "${X}" --dry-run "${M}/aws-undeclared.json"
expect exp-aws-script-needs-touchesaws 4 "must declare touchesAws=true"
run_rc bash "${X}" --dry-run "${M}/aws-region.json"
expect exp-aws-region-refused 4 "not allowed for AWS experiments"
run_rc bash "${X}" --dry-run "${M}/bad-timeout.json"
expect exp-bad-timeout 4 "timeoutSeconds"
run_rc bash "${X}" --dry-run "${M}/bad-metric.json"
expect exp-bad-metric-location 4 "metrics[].location"
run_rc bash "${X}" --dry-run "${M}/s3-ctrl.json"
expect exp-s3-metric-control-chars 4 "must not contain control characters"
if [[ "${OUT}" == *$'\033'* ]]; then bad exp-s3-metric-not-printed; else ok exp-s3-metric-not-printed; fi

# M1: committed symlinks are refused (tree is clean at this point).
[[ -z "$(git -C "${E}" status --porcelain)" ]] && ok exp-symlink-tests-tree-clean || bad exp-symlink-tests-tree-clean
run_rc bash "${X}" --dry-run "${M}/clink-entry.json"
expect exp-committed-symlink-entry-refused 7 "is not allowed" "symlink"
run_rc bash "${X}" --dry-run "${M}/mlink.json"
expect exp-committed-symlink-manifest-refused-dry-run 3 "manifest is a symlink"
run_rc bash "${X}" --run-id ml1 "${M}/mlink.json"
expect exp-committed-symlink-manifest-refused 3 "manifest is a symlink"
run_rc bash "${X}" --run-id ol1 "${M}/olink.json"
expect exp-committed-symlink-outside-target-refused 3 "manifest is a symlink"
run_rc bash "${X}" --run-id dl1 "${M}/dlink/m.json"
expect exp-symlinked-manifest-dir-refused 3 "symlink below the repository root"
[[ ! -e "${E}/local-runs/experiments/exp-fixture/ml1" && ! -e "${E}/local-runs/experiments/exp-fixture/ol1" && ! -e "${E}/local-runs/experiments/exp-fixture/dl1" ]] \
  && ok exp-symlink-manifests-ran-nothing || bad exp-symlink-manifests-ran-nothing

# M2: a tracked entry point with an uncommitted change is refused.
echo 'touch "${SCHOLARCHAIN_RUN_DIR}/../../INJECTED"' >>"${E}/load-tests/fixture.sh"
run_rc bash "${X}" --dry-run "${M}/ok.json"
expect exp-modified-entry-refused 7 "differs from the committed blob"
# M2: the same edit hidden by assume-unchanged (git status shows nothing).
gitq "${E}" update-index --assume-unchanged load-tests/fixture.sh
[[ -z "$(git -C "${E}" status --porcelain)" ]] && ok exp-assume-unchanged-hides-edit || bad exp-assume-unchanged-hides-edit
run_rc bash "${X}" --run-id au1 "${M}/ok.json"
expect exp-assume-unchanged-entry-refused 7 "differs from the committed blob"
[[ ! -e "${E}/local-runs/experiments/exp-fixture/au1" && ! -e "${E}/local-runs/experiments/INJECTED" ]] && ok exp-assume-unchanged-entry-ran-nothing || bad exp-assume-unchanged-entry-ran-nothing
gitq "${E}" update-index --no-assume-unchanged load-tests/fixture.sh
gitq "${E}" checkout -q -- load-tests/fixture.sh
# M2: an assume-unchanged manifest rewritten (and re-digested) after commit.
cp "${M}/ok.json" "${T}/ok.json.saved"
gitq "${E}" update-index --assume-unchanged experiments/ok.json
mkmanifest "${M}/ok.json" load-tests/fixture.sh false '.args = ["666"]' && approve "${M}/ok.json"
[[ -z "$(git -C "${E}" status --porcelain)" ]] && ok exp-assume-unchanged-hides-manifest-edit || bad exp-assume-unchanged-hides-manifest-edit
run_rc bash "${X}" --run-id am1 --allow-dirty "${M}/ok.json"
expect exp-assume-unchanged-manifest-refused 10 "experiments/ok.json is not committed or differs from its committed blob"
[[ ! -e "${E}/local-runs/experiments/exp-fixture/am1" ]] && ok exp-assume-unchanged-manifest-ran-nothing || bad exp-assume-unchanged-manifest-ran-nothing
gitq "${E}" update-index --no-assume-unchanged experiments/ok.json
gitq "${E}" checkout -q -- experiments/ok.json
cmp -s "${M}/ok.json" "${T}/ok.json.saved" && ok exp-manifest-restored || bad exp-manifest-restored
# release-lib: an (unmodified) assume-unchanged file makes the tree dirty.
gitq "${E}" update-index --assume-unchanged scripts/cleanup.sh
run_rc bash "${X}" --run-id ad1 "${M}/ok.json"
expect exp-assume-unchanged-tree-dirty 10 "Git tree is dirty"
gitq "${E}" update-index --no-assume-unchanged scripts/cleanup.sh
# skip-worktree: the same three cases (entry edit, manifest edit, flag alone).
gitq "${E}" update-index --skip-worktree load-tests/fixture.sh
echo 'touch "${SCHOLARCHAIN_RUN_DIR}/../../INJECTED"' >>"${E}/load-tests/fixture.sh"
[[ -z "$(git -C "${E}" status --porcelain)" ]] && ok exp-skip-worktree-hides-edit || bad exp-skip-worktree-hides-edit
run_rc bash "${X}" --run-id sw1 --allow-dirty "${M}/ok.json"
expect exp-skip-worktree-entry-refused 7 "differs from the committed blob"
[[ ! -e "${E}/local-runs/experiments/exp-fixture/sw1" && ! -e "${E}/local-runs/experiments/INJECTED" ]] && ok exp-skip-worktree-entry-ran-nothing || bad exp-skip-worktree-entry-ran-nothing
gitq "${E}" update-index --no-skip-worktree load-tests/fixture.sh
gitq "${E}" checkout -q -- load-tests/fixture.sh
gitq "${E}" update-index --skip-worktree experiments/ok.json
mkmanifest "${M}/ok.json" load-tests/fixture.sh false '.args = ["666"]' && approve "${M}/ok.json"
[[ -z "$(git -C "${E}" status --porcelain)" ]] && ok exp-skip-worktree-hides-manifest-edit || bad exp-skip-worktree-hides-manifest-edit
run_rc bash "${X}" --run-id sm1 --allow-dirty "${M}/ok.json"
expect exp-skip-worktree-manifest-refused 10 "experiments/ok.json is not committed or differs from its committed blob"
[[ ! -e "${E}/local-runs/experiments/exp-fixture/sm1" ]] && ok exp-skip-worktree-manifest-ran-nothing || bad exp-skip-worktree-manifest-ran-nothing
gitq "${E}" update-index --no-skip-worktree experiments/ok.json
gitq "${E}" checkout -q -- experiments/ok.json
cmp -s "${M}/ok.json" "${T}/ok.json.saved" && ok exp-manifest-restored-sw || bad exp-manifest-restored-sw
gitq "${E}" update-index --skip-worktree scripts/cleanup.sh
run_rc bash "${X}" --run-id sd1 "${M}/ok.json"
expect exp-skip-worktree-tree-dirty 10 "Git tree is dirty"
gitq "${E}" update-index --no-skip-worktree scripts/cleanup.sh
[[ -z "$(git -C "${E}" ls-files -v | grep -v '^H ' || true)" ]] && ok exp-index-flags-cleared || bad exp-index-flags-cleared
run_rc bash "${X}" --dry-run "${M}/aws.json"
expect exp-aws-dry-run-reports 0 "REAL RUN WOULD REFUSE: touchesAws=true requires --approve-aws"
run_rc bash "${X}" "${M}/aws.json"
expect exp-aws-without-flag-refused 9 "requires --approve-aws"
run_rc bash "${X}" --approve-aws "${M}/aws.json" </dev/null
expect exp-aws-flag-needs-tty 9 "interactive terminal"
# Nit: end of input at the confirmation prompt is a refusal (exit 9), not a
# `set -e` exit 1. Needs a pty: script(1) in its BSD or util-linux form. Typed
# input races script's EOF handling, so only the EOF case is deterministic.
PTY=""
if script -q /dev/null /bin/bash -c '[[ -t 0 ]]' </dev/null >/dev/null 2>&1; then
  PTY=bsd
elif script -qec "/bin/bash -c '[[ -t 0 ]]'" /dev/null </dev/null >/dev/null 2>&1; then
  PTY=util-linux
fi
if [[ "${PTY}" == bsd ]]; then
  RC=0
  OUT="$(printf '' | script -q /dev/null bash "${X}" --approve-aws "${M}/aws.json" 2>&1)" || RC=$?
  expect exp-aws-confirm-eof-refused 9 "no confirmation was given"
elif [[ "${PTY}" == util-linux ]]; then
  RC=0
  OUT="$(printf '' | script -qec "bash $(printf '%q' "${X}") --approve-aws $(printf '%q' "${M}/aws.json")" /dev/null 2>&1)" || RC=$?
  expect exp-aws-confirm-eof-refused 9 "no confirmation was given"
else
  echo "SKIP exp-aws-confirm-eof-refused (no usable script(1) for a pty)"
fi
[[ ! -e "${T}/AWS-SCRIPT-RAN" ]] && ok exp-aws-script-never-ran || bad exp-aws-script-never-ran

# Real run of the harmless fixture; AWS_PROFILE must not reach it.
run_rc env AWS_PROFILE=should-not-leak bash "${X}" --run-id r1 "${M}/ok.json"
expect exp-run-writes-record 0 "EXPERIMENT succeeded" "Exit status: 0"
R="${E}/local-runs/experiments/exp-fixture/r1/records/run-record.json"
if [[ -f "${R}" ]] && jq -e --arg s "${ESHA}" '
    .schemaVersion == "scholarchain.experiment-run/v1" and .experimentId == "exp-fixture" and .runId == "r1"
    and .commitSha == $s and .gitDirty == false and .region == "us-east-2" and .touchesAws == false
    and .resourceLimits == {timeoutSeconds: 20, maxCostUsd: 0, maxTasks: 1}
    and (.startedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and (.endedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and .exitStatus == 0 and .timedOut == false and .status == "succeeded"
    and .metrics == [.metrics[0]] and .metrics[0].location == "metrics/result.json" and .metrics[0].present == true
    and (.metrics[0].sha256 | test("^[0-9a-f]{64}$")) and .args == ["42"] and .entryPoint == "load-tests/fixture.sh"
    and (.manifestSha256 == .approval.manifestSha256) and .manifestPath == "experiments/ok.json"
    and .provenance.component == "scripts/run-experiment.sh" and .outputDir == "local-runs/experiments/exp-fixture/r1/output"
    and .logs.stdout == "local-runs/experiments/exp-fixture/r1/records/stdout.log" and .lateSignals == []
    and .provisionalRecord.unchangedAtEnd == true and .foreignRecordMovedTo == null
    and .processGroup.emptyAtEnd == true and .processGroup.aliveAfterLeaderExit == 0 and .processGroup.signalsSent == []' "${R}" >/dev/null; then
  ok exp-run-record-fields
else
  bad "exp-run-record-fields ($(cat "${R}" 2>/dev/null | head -c 400))"
fi
[[ -f "${R}" && ! -w "${R}" ]] && ok exp-run-record-read-only || bad exp-run-record-read-only
RP="${E}/local-runs/experiments/exp-fixture/r1/records/run-record.started.json"
if [[ -f "${RP}" && ! -w "${RP}" ]] && jq -e --arg s "${ESHA}" '.status == "started" and .commitSha == $s and .region == "us-east-2"
    and .resourceLimits.timeoutSeconds == 20 and .metrics == [{name: "result", location: "metrics/result.json"}]
    and (.startedAt | test("Z$")) and (has("endedAt") | not)' "${RP}" >/dev/null \
  && [[ "$(jq -r '.provisionalRecord.sha256' "${R}")" == "$(shasum -a 256 "${RP}" | awk '{print $1}')" ]]; then
  ok exp-provisional-record
else
  bad "exp-provisional-record ($(head -c 300 "${RP}" 2>/dev/null))"
fi
jq -e '(.entryPointBlob | test("^[0-9a-f]{40}$")) and (.manifestBlob | test("^[0-9a-f]{40}$")) and .signal == null' "${R}" >/dev/null 2>&1 \
  && ok exp-run-record-blobs || bad exp-run-record-blobs
RES="${E}/local-runs/experiments/exp-fixture/r1/output/metrics/result.json"
[[ "$(jq -r '.aws_profile + " " + .aws_config + " " + (.rows|tostring)' "${RES}" 2>/dev/null)" == "unset /dev/null 42" ]] && ok exp-run-aws-env-scrubbed || bad "exp-run-aws-env-scrubbed ($(cat "${RES}" 2>/dev/null))"
EHOME="$(jq -r '.home' "${RES}" 2>/dev/null)"
[[ -n "${EHOME}" && "${EHOME}" != "${HOME}" && "${EHOME}" == */scholarchain-exp-home.* && "${EHOME}" != */scholarchain-exp.*/* ]] && ok exp-run-home-isolated || bad "exp-run-home-isolated (${EHOME})"
RSUM="$(shasum -a 256 "${R}" | awk '{print $1}')"
run_rc bash "${X}" --run-id r1 "${M}/ok.json"
expect exp-existing-record-refused 8 "already exists"
run_rc bash "${X}" --dry-run --run-id r1 "${M}/ok.json"
expect exp-existing-record-refused-dry-run 8 "already exists"
[[ "$(shasum -a 256 "${R}" | awk '{print $1}')" == "${RSUM}" ]] && ok exp-existing-record-unchanged || bad exp-existing-record-unchanged

run_rc bash "${X}" --run-id t1 "${M}/slow.json"
expect exp-timeout 12 "EXPERIMENT timed-out"
[[ "$(jq -r '.status + " " + (.timedOut|tostring)' "${E}/local-runs/experiments/exp-fixture/t1/records/run-record.json" 2>/dev/null)" == "timed-out true" ]] && ok exp-timeout-recorded || bad exp-timeout-recorded
# Nit: the watchdog's escalation is in the provenance.
jq -e '.processGroup.signalsSent == ["watchdog:TERM"]' "${E}/local-runs/experiments/exp-fixture/t1/records/run-record.json" >/dev/null 2>&1 \
  && ok exp-timeout-watchdog-signal-recorded \
  || bad "exp-timeout-watchdog-signal-recorded ($(jq -c '.processGroup' "${E}/local-runs/experiments/exp-fixture/t1/records/run-record.json" 2>/dev/null))"
run_rc bash "${X}" --run-id f1 "${M}/fail.json"
expect exp-failure 11 "EXPERIMENT failed" "Exit status: 7"
[[ "$(jq -r '.exitStatus' "${E}/local-runs/experiments/exp-fixture/f1/records/run-record.json" 2>/dev/null)" == 7 ]] && ok exp-failure-recorded || bad exp-failure-recorded
run_rc bash "${X}" --run-id n1 "${M}/nometrics.json"
expect exp-missing-metric 13 "MISSING" "EXPERIMENT missing-metrics"

# Uncommitted manifest and dirty tree: real runs refused, dry run reports it.
cp "${M}/ok.json" "${M}/uncommitted.json"
run_rc bash "${X}" --run-id u1 "${M}/uncommitted.json"
expect exp-uncommitted-manifest-refused 10 "not committed"
run_rc bash "${X}" --dry-run "${M}/uncommitted.json"
expect exp-uncommitted-manifest-dry-run 0 "REAL RUN WOULD REFUSE: manifest experiments/uncommitted.json is not committed"
cp "${M}/ok.json" "${T}/outside.json"
run_rc bash "${X}" --run-id o1 "${T}/outside.json"
expect exp-manifest-outside-experiments-refused 10 "not under experiments/"
rm -f "${M}/uncommitted.json"
echo dirty >"${E}/dirty.txt"
run_rc bash "${X}" --run-id d1 "${M}/ok.json"
expect exp-dirty-tree-refused 10 "Git tree is dirty"
run_rc bash "${X}" --run-id d2 --allow-dirty "${M}/ok.json"
expect exp-dirty-tree-allowed 0 "EXPERIMENT succeeded"
[[ "$(jq -r '.gitDirty' "${E}/local-runs/experiments/exp-fixture/d2/records/run-record.json" 2>/dev/null)" == true ]] && ok exp-dirty-recorded || bad exp-dirty-recorded
run_rc bash "${X}" --allow-dirty --approve-aws "${M}/aws.json" </dev/null
expect exp-aws-dirty-refused 10 "Git tree is dirty"
rm -f "${E}/dirty.txt"
[[ ! -e "${T}/AWS-SCRIPT-RAN" ]] && ok exp-aws-script-never-ran-final || bad exp-aws-script-never-ran-final

# R2-M2: the entry point empties the metrics list of every manifest snapshot
# it can reach (HOME/.. and the runner's TMPDIR). The record is still built
# from the values captured before the spawn: missing-metrics, exit 13.
mkdir -p "${T}/tmpx"
run_rc env TMPDIR="${T}/tmpx" bash "${X}" --run-id tp1 "${M}/tamper.json"
expect exp-snapshot-tamper-missing-metrics 13 "MISSING" "EXPERIMENT missing-metrics"
TPR="${E}/local-runs/experiments/exp-fixture/tp1/records/run-record.json"
jq -e '.status == "missing-metrics" and .metrics == [{name: "result", location: "metrics/result.json", present: false, sha256: null}]' "${TPR}" >/dev/null 2>&1 \
  && ok exp-snapshot-tamper-record || bad "exp-snapshot-tamper-record ($(head -c 300 "${TPR}" 2>/dev/null))"
grep -q '^tampered=0$' "${E}/local-runs/experiments/exp-fixture/tp1/records/stdout.log" 2>/dev/null \
  && ok exp-snapshot-unreachable || bad "exp-snapshot-unreachable ($(cat "${E}/local-runs/experiments/exp-fixture/tp1/records/stdout.log" 2>/dev/null))"
[[ -z "$(ls -A "${T}/tmpx" 2>/dev/null)" ]] && ok exp-work-dirs-removed || bad "exp-work-dirs-removed ($(ls -A "${T}/tmpx"))"

# R2-m1: the records directory is read-only for the run; a forged
# run-record.json is moved aside and the runner's record written (exit 8).
run_rc bash "${X}" --run-id fg1 "${M}/forge.json"
expect exp-forged-record-exit-8 8 "FOREIGN RECORD MOVED ASIDE"
FGD="${E}/local-runs/experiments/exp-fixture/fg1"
if jq -e '.runId == "fg1" and .provenance.component == "scripts/run-experiment.sh" and (has("forged") | not)
    and .status == "succeeded" and (.foreignRecordMovedTo | test("/records/run-record.json.FOREIGN-[0-9]+$"))' "${FGD}/records/run-record.json" >/dev/null 2>&1 \
  && [[ ! -w "${FGD}/records/run-record.json" ]]; then
  ok exp-forged-record-authoritative
else
  bad "exp-forged-record-authoritative ($(head -c 300 "${FGD}/records/run-record.json" 2>/dev/null))"
fi
FGF="$(find "${FGD}/records" -name 'run-record.json.FOREIGN-*' 2>/dev/null | head -n 1)"
[[ -n "${FGF}" ]] && jq -e '.forged == true' "${FGF}" >/dev/null 2>&1 && ok exp-forged-record-quarantined || bad exp-forged-record-quarantined
[[ "$(cat "${FGD}/output/probe-result" 2>/dev/null)" == readonly && ! -e "${FGD}/records/probe" ]] && ok exp-records-dir-read-only-during-run || bad exp-records-dir-read-only-during-run
# Nit: write access on the run and records directories is restored on exit.
[[ -w "${FGD}" && -w "${FGD}/records" ]] && ok exp-run-dirs-writable-after-run || bad exp-run-dirs-writable-after-run

# R3-MED: runner verdicts are not carried through files the entry can reach.
# Each case uses its own runner TMPDIR so that its globs reach nothing else.
# (1) Deleting `timed-out` markers on the watchdog's TERM and exiting 0 does
# not hide the timeout.
mkdir -p "${T}/tmph"
run_rc env TMPDIR="${T}/tmph" bash "${X}" --run-id ht1 "${M}/hidetimeout.json"
expect exp-hide-timeout-still-timed-out 12 "EXPERIMENT timed-out"
HTD="${E}/local-runs/experiments/exp-fixture/ht1"
jq -e '.status == "timed-out" and .timedOut == true and .exitStatus == 0 and (.processGroup.signalsSent | index("watchdog:TERM") != null)' \
  "${HTD}/records/run-record.json" >/dev/null 2>&1 && ok exp-hide-timeout-recorded \
  || bad "exp-hide-timeout-recorded ($(jq -c '{status, timedOut, exitStatus, processGroup}' "${HTD}/records/run-record.json" 2>/dev/null))"
# (2) Planting `timed-out` / `entry-changed` markers (runner TMPDIR, its own
# TMPDIR, every scholarchain-exp* directory) and exiting 0 is a success.
mkdir -p "${T}/tmpf"
run_rc env TMPDIR="${T}/tmpf" bash "${X}" --run-id ft1 "${M}/faketimeout.json"
expect exp-fake-timeout-succeeded 0 "EXPERIMENT succeeded"
FTD="${E}/local-runs/experiments/exp-fixture/ft1"
jq -e '.status == "succeeded" and .timedOut == false and .exitStatus == 0' "${FTD}/records/run-record.json" >/dev/null 2>&1 \
  && ok exp-fake-timeout-recorded || bad "exp-fake-timeout-recorded ($(jq -c '{status, timedOut, exitStatus}' "${FTD}/records/run-record.json" 2>/dev/null))"
FT_PLANTED="$(sed -n 's/^planted=//p' "${FTD}/records/stdout.log" 2>/dev/null)"
[[ "${FT_PLANTED:-0}" -ge 2 ]] && ok exp-fake-timeout-markers-planted || bad "exp-fake-timeout-markers-planted (${FT_PLANTED:-none})"
# Defence in depth: the entry point had its own TMPDIR (not the runner's, not
# its work directory), removed after the run.
FT_TMP="$(cat "${FTD}/output/tmpdir" 2>/dev/null || true)"
[[ "${FT_TMP}" == "${T}/tmpf/scholarchain-exp-tmp."* && ! -e "${FT_TMP}" ]] && ok exp-entry-tmpdir-private || bad "exp-entry-tmpdir-private (${FT_TMP})"
[[ -z "$(find "${T}/tmpf" -mindepth 1 -maxdepth 1 -name 'scholarchain-exp*' -type d 2>/dev/null)" ]] && ok exp-fake-timeout-work-dirs-removed \
  || bad "exp-fake-timeout-work-dirs-removed ($(ls -A "${T}/tmpf"))"
# (3) Planting an `entry-changed` marker and exiting 7 is an ordinary failure.
mkdir -p "${T}/tmpc"
run_rc env TMPDIR="${T}/tmpc" bash "${X}" --run-id pc1 "${M}/plantchanged.json"
expect exp-planted-entry-changed-is-failure 11 "EXPERIMENT failed" "Exit status: 7"
jq -e '.status == "failed" and .exitStatus == 7 and .timedOut == false' "${E}/local-runs/experiments/exp-fixture/pc1/records/run-record.json" >/dev/null 2>&1 \
  && ok exp-planted-entry-changed-recorded \
  || bad "exp-planted-entry-changed-recorded ($(jq -c '{status, exitStatus}' "${E}/local-runs/experiments/exp-fixture/pc1/records/run-record.json" 2>/dev/null))"

# R2-M1: the entry point's process group is emptied on every path.
alive() { kill -0 "$1" 2>/dev/null; }
# gone_soon <pid>...: 0 if every pid is gone within 1 s (slack for the
# reparented zombie to be reaped). A survivor (sleep 45/60) never passes.
gone_soon() {
  local i p any
  for i in 1 2 3 4 5 6 7 8 9 10; do
    any=0
    for p in "$@"; do
      if alive "${p}"; then any=1; fi
    done
    [[ ${any} -eq 1 ]] || return 0
    sleep 0.1
  done
  return 1
}
# start_runner <run-id> <manifest>: starts the runner in the background with
# job control on (its own process group, so this shell does not set SIGINT to
# ignored as it would for a plain background job) and waits for the entry
# point to record its PIDs. Sets SD, RPID, EPID, SPID. RUNNER_PREFIX (an
# optional exec wrapper) is put in front of the runner command.
SD="" RPID="" EPID="" SPID="" RRC=0
RUNNER_PREFIX=()
start_runner() {
  local i
  SD="${E}/local-runs/experiments/exp-fixture/$1"
  set -m
  ${RUNNER_PREFIX[@]+"${RUNNER_PREFIX[@]}"} bash "${X}" --run-id "$1" "${M}/$2.json" >"${T}/run-$1.out" 2>&1 &
  RPID=$!
  set +m
  for i in $(seq 1 150); do
    [[ -s "${SD}/output/entry.pid" ]] && break
    sleep 0.1
  done
  EPID="$(cat "${SD}/output/entry.pid" 2>/dev/null || true)"
  SPID="$(cat "${SD}/output/sleep.pid" 2>/dev/null || true)"
}
finish_runner() {
  RRC=0
  wait "${RPID}" || RRC=$?
}
# check_group_gone <name>: the entry point and its child are gone right after
# the runner returned, and the record says the group was emptied.
check_group_gone() {
  if gone_soon "${EPID}" "${SPID}"; then
    ok "$1-group-gone"
  else
    bad "$1-group-gone (entry ${EPID} alive: $(alive "${EPID}" && echo yes || echo no), child ${SPID} alive: $(alive "${SPID}" && echo yes || echo no))"
    kill -KILL "${EPID}" "${SPID}" 2>/dev/null || true
  fi
  if jq -e '.processGroup.emptyAtEnd == true and .processGroup.remainingAtEnd == 0' "${SD}/records/run-record.json" >/dev/null 2>&1; then
    ok "$1-record-group-empty"
  else
    bad "$1-record-group-empty ($(jq -c '.processGroup' "${SD}/records/run-record.json" 2>/dev/null))"
  fi
}
# signal_test <SIG> <run-id> <manifest>: signal the runner during the run.
signal_test() {
  local sig="$1" rid="$2" name="exp-signal-$1-$3"
  start_runner "${rid}" "$3"
  if [[ -z "${EPID}" || -z "${SPID}" ]]; then
    bad "${name}-entry-started"
    kill -KILL "${RPID}" 2>/dev/null || true
    return 0
  fi
  [[ -f "${SD}/records/run-record.started.json" && ! -e "${SD}/records/run-record.json" ]] && ok "${name}-provisional-before-end" || bad "${name}-provisional-before-end"
  kill -"${sig}" "${RPID}"
  finish_runner
  [[ ${RRC} -eq 15 ]] && ok "${name}-exit-15" || {
    bad "${name}-exit-15 (rc ${RRC})"
    tail -n 8 "${T}/run-${rid}.out" | sed 's/^/    | /'
  }
  check_group_gone "${name}"
  if jq -e --arg sig "${sig}" '.status == "interrupted" and .signal == $sig and .exitStatus != 0' "${SD}/records/run-record.json" >/dev/null 2>&1 \
    && jq -e '.status == "started"' "${SD}/records/run-record.started.json" >/dev/null 2>&1 && [[ ! -w "${SD}/records/run-record.json" ]]; then
    ok "${name}-record-interrupted"
  else
    bad "${name}-record-interrupted ($(head -c 300 "${SD}/records/run-record.json" 2>/dev/null))"
  fi
}
signal_test TERM sig-term sigslow
signal_test HUP sig-hup sigslow
# SIGINT: the entry point's background `sleep 60` ignores INT (non-interactive
# bash), so only the post-reap group drain removes it.
# If this suite was itself started with SIGINT ignored (e.g. as a background
# job of a non-interactive shell), bash cannot un-ignore it for the runner.
# Probe first: start a job the way start_runner does and see whether SIGINT
# ends it. If not, reset the disposition with a python3 exec wrapper (probed
# the same way); without one, SKIP. The INT manifest's timeout is 15 s, so a
# signal that is not delivered fails within ~25 s instead of ~70 s.
# sigint_reaches [<wrapper>...]: 0 if SIGINT ends a `sleep` job started with
# the given wrapper.
sigint_reaches() {
  local p i
  set -m
  "$@" sleep 5 >/dev/null 2>&1 &
  p=$!
  set +m
  # Re-sent for up to 2 s: a wrapper needs time to reset the disposition.
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    sleep 0.1
    kill -INT "${p}" 2>/dev/null || true
    alive "${p}" || break
  done
  sleep 0.1
  if alive "${p}"; then
    kill -KILL "${p}" 2>/dev/null || true
    wait "${p}" 2>/dev/null || true
    return 1
  fi
  wait "${p}" 2>/dev/null || true
  return 0
}
SIGINT_RESET=(python3 -I -c 'import os, signal, sys; signal.signal(signal.SIGINT, signal.SIG_DFL); os.execvp(sys.argv[1], sys.argv[1:])')
if sigint_reaches; then
  signal_test INT sig-int sigslow-int
elif command -v python3 >/dev/null 2>&1 && sigint_reaches "${SIGINT_RESET[@]}"; then
  echo "NOTE SIGINT is ignored by the launch environment; the runner is started through a python3 wrapper that resets it"
  RUNNER_PREFIX=("${SIGINT_RESET[@]}")
  signal_test INT sig-int sigslow-int
  RUNNER_PREFIX=()
else
  echo "SKIP exp-signal-INT-* (SIGINT ignored by launch environment)"
fi
# Forwarded TERM with a TERM-ignoring background child.
signal_test TERM sig-term-ign tignore-long
# Nit: a leader that ignores the forwarded TERM is ended by the killer's
# SIGKILL (10 s later), and the record says who sent it.
signal_test TERM sig-term-lign lignore
jq -e '.processGroup.signalsSent == ["killer:KILL"]' "${SD}/records/run-record.json" >/dev/null 2>&1 \
  && ok exp-killer-signal-recorded || bad "exp-killer-signal-recorded ($(jq -c '.processGroup' "${SD}/records/run-record.json" 2>/dev/null))"

# Timeout with a TERM-ignoring child: gone once the record is written.
start_runner tm1 tignore
finish_runner
[[ ${RRC} -eq 12 ]] && ok exp-timeout-ignoring-child-exit-12 || bad "exp-timeout-ignoring-child-exit-12 (rc ${RRC})"
if [[ -n "${EPID}" && -n "${SPID}" ]]; then
  check_group_gone exp-timeout-ignoring-child
else
  bad exp-timeout-ignoring-child-entry-started
fi
jq -e '.status == "timed-out" and .timedOut == true and .processGroup.aliveAfterLeaderExit >= 1 and .processGroup.membersKilled >= 1
    and .processGroup.signalsSent[0] == "watchdog:TERM"' \
  "${SD}/records/run-record.json" >/dev/null 2>&1 && ok exp-timeout-ignoring-child-recorded \
  || bad "exp-timeout-ignoring-child-recorded ($(head -c 400 "${SD}/records/run-record.json" 2>/dev/null))"

# Normal exit that leaves a group member behind: killed and recorded; the
# status still describes the leader.
run_rc bash "${X}" --run-id or1 "${M}/orphan.json"
expect exp-orphan-succeeded 0 "EXPERIMENT succeeded" "outlived it"
SD="${E}/local-runs/experiments/exp-fixture/or1"
EPID="$(cat "${SD}/output/entry.pid" 2>/dev/null || true)"
SPID="$(cat "${SD}/output/sleep.pid" 2>/dev/null || true)"
if [[ -n "${SPID}" ]]; then
  check_group_gone exp-orphan
else
  bad exp-orphan-entry-started
fi
jq -e '.status == "succeeded" and .processGroup.aliveAfterLeaderExit >= 1 and .processGroup.membersKilled >= 1
    and .processGroup.signalsSent == ["TERM"]' "${SD}/records/run-record.json" >/dev/null 2>&1 \
  && ok exp-orphan-recorded || bad "exp-orphan-recorded ($(jq -c '.processGroup' "${SD}/records/run-record.json" 2>/dev/null))"

# R2-m2: a signal that arrives after the leader was reaped (here: during the
# drain of a TERM-ignoring member) does not relabel the run.
start_runner late1 orphan-ign
for i in $(seq 1 50); do
  grep -q 'outlived it' "${T}/run-late1.out" 2>/dev/null && break
  sleep 0.1
done
if grep -q 'outlived it' "${T}/run-late1.out" 2>/dev/null; then
  kill -TERM "${RPID}" 2>/dev/null || true
  finish_runner
  [[ ${RRC} -eq 0 ]] && ok exp-late-signal-exit-0 || bad "exp-late-signal-exit-0 (rc ${RRC})"
  check_group_gone exp-late-signal
  jq -e '.status == "succeeded" and .signal == null and .lateSignals == ["TERM"] and .processGroup.signalsSent == ["TERM","KILL"]' \
    "${SD}/records/run-record.json" >/dev/null 2>&1 && ok exp-late-signal-status-unchanged \
    || bad "exp-late-signal-status-unchanged ($(jq -c '{status, signal, lateSignals, processGroup}' "${SD}/records/run-record.json" 2>/dev/null))"
else
  bad exp-late-signal-drain-started
  kill -KILL "${RPID}" "${SPID}" 2>/dev/null || true
  finish_runner
fi

[[ ${FAILS} -eq 0 ]] || {
  echo "${FAILS} test(s) failed" >&2
  exit 1
}
echo "all passed"
