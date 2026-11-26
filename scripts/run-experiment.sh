#!/usr/bin/env bash
set -euo pipefail

################################################################################
# run-experiment.sh - Run ONE approved experiment manifest and record the run.
#
# Usage: ./scripts/run-experiment.sh [--dry-run] [--run-id <id>]
#                                    [--allow-dirty] [--approve-aws] <manifest.json>
#        ./scripts/run-experiment.sh --print-digest <manifest.json>
#
# Only manifests that a human approved, unchanged since approval, may run, and
# a manifest can only start an allowlisted entry point with an argument list:
# no shell string is ever evaluated.
#
# MANIFEST CONTRACT (JSON, schemaVersion "scholarchain.experiment/v1"):
#   {
#     "schemaVersion": "scholarchain.experiment/v1",
#     "id":            "exp-a-local-scale",      ^[a-z0-9][a-z0-9-]{2,63}$
#     "description":   "optional free text",
#     "entryPoint":    "load-tests/a/run.sh",    see ENTRY POINTS
#     "args":          ["--rows", "1000"],       optional; strings, no control chars
#     "touchesAws":    false,                    required boolean
#     "region":        "us-east-2",              required; AWS-form Region name;
#                                                must be us-east-2 if touchesAws
#     "resourceLimits": {                        required object, recorded verbatim
#       "timeoutSeconds": 600,                   required integer 1..86400 (enforced:
#                                                when it runs over, the entry point's
#                                                process group gets TERM, then KILL;
#                                                see PROCESS GROUP)
#       "maxCostUsd": 0,                         required number >= 0; must be 0 when
#                                                touchesAws is false
#       "...": "any further limits (maxTasks, cpu, memoryMiB, ...) are recorded"
#     },
#     "metrics": [                               required, at least one
#       {"name": "latency", "location": "metrics/latency.csv"}
#     ],                                         name ^[A-Za-z0-9._-]{1,64}$; location
#                                                is relative to the run's output/
#                                                directory (SCHOLARCHAIN_RUN_DIR; no
#                                                "..", no leading /), or s3://...
#                                                (no control characters) only when
#                                                touchesAws is true
#     "approval": {                              HUMAN approval record
#       "approvedBy":     "<human name>",        not an agent name (.claude/agents),
#                                                not "claude"/"agent"/"orchestrator"
#       "approvedAt":     "2026-11-26T10:00:00Z", UTC, not in the future
#       "manifestSha256": "<64 hex>"             digest of the manifest WITHOUT the
#                                                approval block (below)
#     }
#   }
# Digest: sha256 of the output of `jq -cS 'del(.approval)' <manifest>` (sorted
# keys, compact, trailing newline). `--print-digest <manifest>` prints it for
# the human approving the manifest. Any later edit to any field outside
# "approval" changes the digest, and the manifest is refused as tampered.
# jq caveat: jq versions differ in how they print some numbers (1.0 vs 1,
# 1e2 vs 100, integers beyond 2^53; jq >= 1.7 keeps the literal). Use plain
# integer literals in manifests and compute the digest with the same jq that
# runs the experiment; a digest from another jq may not match (exit 6).
#
# ENTRY POINTS (allowlist): a *.sh file whose bytes equal its committed blob
# (`git hash-object` == `git rev-parse <commit>:<path>`, committed with mode
# 100644/100755, i.e. not committed as a symlink), that is not a symlink on
# disk and has no symlinked parent directory below the repository root:
#   load-tests/**/<name>.sh                (experiment-engineer's load tests)
#   scripts/run-bounded-pipeline.sh        (AWS; requires touchesAws=true)
#   scripts/smoke-test.sh                  (AWS; requires touchesAws=true)
# The blob comparison ignores the index, so assume-unchanged / skip-worktree
# flags cannot hide an edit. The runner repeats it immediately before the
# spawn (a change there: nothing is spawned, status entry-changed, exit 7,
# final record written), and the spawned child repeats it once more right
# before exec (a change in that last window: not executed, the child exits 7,
# which is recorded as an ordinary failure, status failed, exit 11; the
# reason is in stderr.log). It cannot stop a file being rewritten while bash
# is already executing it.
# Run as `/bin/bash <entry point> <args...>` from the repository root, with
# SCHOLARCHAIN_EXPERIMENT_ID, SCHOLARCHAIN_RUN_ID, SCHOLARCHAIN_RUN_DIR (the
# run's output/ directory) and SCHOLARCHAIN_EXPERIMENT_REGION set, and with
# TMPDIR pointing at an empty temporary directory of its own (created with
# mktemp, outside the runner's private work directory; removed afterwards).
# touchesAws=false is ENVIRONMENT SCRUBBING, NOT A SANDBOX: every AWS_*
# variable is removed, AWS_CONFIG_FILE / AWS_SHARED_CREDENTIALS_FILE point at
# /dev/null, AWS_EC2_METADATA_DISABLED=true, and HOME (plus XDG_CONFIG_HOME /
# XDG_CACHE_HOME) point at another empty temporary directory of its own
# (also outside the runner's private work directory), so the default
# AWS credential discovery paths (environment, ~/.aws, IMDS via SDK default)
# find nothing. The entry point still runs as the invoking user with network
# access, can read or write any file it names by absolute path and can send
# signals to the runner's processes; the measures below make the RECORD
# independent of what it does to such files. No runner verdict (timeout,
# entry-changed) is carried through a file: both live in the runner's memory
# or in a helper process's exit status (see TIMEOUT).
#
# AWS: a manifest with touchesAws=true runs only with --approve-aws AND an
# interactive confirmation (type <id>/<region>) on a clean tree; never in an
# automated or agent session.
#
# MANIFEST INTEGRITY: the manifest must be a regular file, not a symlink, with
# no symlinked parent directory below the repository root (exit 3, also in
# --dry-run). It is copied ONCE into a private snapshot; every field is read
# from the snapshot only, all values are captured in the runner before the
# entry point is spawned, and the snapshot is DELETED before the spawn: the
# post-run metric check and every record field come from the captured values.
# REAL RUN preconditions (besides the above): the manifest lies under
# experiments/ (path chars [A-Za-z0-9._/-]) and the snapshot's blob equals
# `git rev-parse <commit>:<path>` (committed as a regular file); the Git tree
# is clean, where assume-unchanged or skip-worktree entries count as dirty
# (--allow-dirty permits a dirty tree for touchesAws=false runs only, and
# records gitDirty=true). Immediately before the run directory is created
# (i.e. also after the interactive AWS confirmation) the runner re-checks
# HEAD, the entry point's blob, the full Git inspection (status and
# assume-unchanged/skip-worktree flags) and a tree fingerprint (HEAD, status,
# flagged entries' blobs, `git diff HEAD`, untracked files' blobs); any
# change from the first inspection refuses the run (exit 10; 7 for the entry
# point). The entry point's blob is checked twice more (see ENTRY POINTS):
# by the runner immediately before the spawn, and in the child before exec.
#
# RUN RECORDS: local-runs/experiments/<id>/<run-id>/ (gitignored):
#   output/                    SCHOLARCHAIN_RUN_DIR: the entry point's outputs;
#                              metric relative paths resolve here.
#   records/                   the runner's files; the entry point is NOT told
#                              this path, and the directory is read-only while
#                              the entry point runs:
#     stdout.log, stderr.log   the entry point's output streams.
#     run-record.started.json  PROVISIONAL, written before the entry point is
#                              spawned: status "started", commit, region,
#                              startedAt, resource limits, metric locations,
#                              manifest and entry digests.
#     run-record.json          FINAL, written after the entry point and its
#                              process group ended.
#   Both records are written once with noclobber and made read-only. If a
#   foreign file already occupies records/run-record.json when the final
#   record is written (the entry point re-enabled write access and planted
#   it), that file is moved aside to records/run-record.json.FOREIGN-<epoch>,
#   the runner's record is written anyway (field foreignRecordMovedTo) and
#   the runner exits 8.
# FINAL RECORD FIELDS: schemaVersion "scholarchain.experiment-run/v1",
#   experimentId, runId, manifestPath, manifestSha256, manifestFileSha256,
#   manifestBlob, approval, commitSha, gitDirty, entryPoint, entryPointSha256,
#   entryPointBlob, args, touchesAws, region, resourceLimits, startedAt,
#   endedAt (UTC), durationSeconds, exitStatus (null if nothing was spawned:
#   a signal arrived before the spawn, or status entry-changed), timedOut,
#   signal, lateSignals, status
#   (succeeded|failed|timed-out|missing-metrics|interrupted|entry-changed),
#   metrics[] {name, location, present, sha256}, logs, provisionalRecord
#   {path, sha256 (taken before the spawn), unchangedAtEnd}, processGroup,
#   foreignRecordMovedTo, host, provenance.
# A directory with records/run-record.started.json but no run-record.json
# means the runner was killed by SIGKILL or the host died; the entry point's
# process group may then still be running.
#
# PROCESS GROUP: the entry point runs as the leader of its own process group
# (job control). After the leader has been reaped, on EVERY path (normal exit,
# failure, timeout, forwarded signal), the runner probes the group with
# `kill -0 -- -<pgid>`; while members are alive it sends TERM, waits up to
# 5 s, then KILL and waits up to 5 s more. The final record carries
# processGroup {pgid, aliveAfterLeaderExit, signalsSent, membersKilled,
# remainingAtEnd, emptyAtEnd}; emptyAtEnd=false is also printed as a
# WARNING. signalsSent names who sent what, in this order:
# "watchdog:TERM" / "watchdog:KILL" (timeout watchdog), "killer:KILL" (the
# forwarded-signal killer, see SIGNALS), then plain "TERM" / "KILL" from the
# post-reap drain. A KILL is listed only if it reached at least one group
# member; "watchdog:TERM" whenever the watchdog fired. The status still
# describes the leader (a run whose leader succeeded stays "succeeded" when
# stray members had to be killed). Not covered: processes that leave the
# group (setsid, their own job control) or that are in uninterruptible sleep.
# TIMEOUT: when timeoutSeconds elapse while the leader is still running, the
# watchdog sends the group TERM and, 5 s later, KILL; then the drain above.
# The verdict is not carried through any file: the watchdog reports whether
# it fired through its own exit status (no other process can set it;
# signalling the watchdog can only make it report "not fired"), read by the
# runner with `wait`. As a backstop the runner also labels the run timed-out
# when the leader was reaped more than timeoutSeconds (whole seconds, strictly
# greater) after the spawn, so an entry point that signals or kills the
# watchdog cannot hide an overrun (it can, however, escape being terminated).
# SIGNALS: INT, TERM or HUP before the traps are installed (that is, before
# the "Run experiment" step) terminates the runner with the shell's default
# status (129 HUP, 130 INT, 143 TERM); nothing has been created then. The
# traps are installed before the run directory is created; a signal between
# the two: nothing is created, exit 15. After it, while the leader runs: the
# signal is forwarded to the group (SIGKILL to the group after 10 s if the
# leader is still alive), the runner reaps the leader, drains the group,
# writes the final record with status "interrupted" and exits 15. A signal
# that arrives after the leader was reaped does not change the status; it is
# listed in lateSignals. A signal that was already ignored when the runner
# started (e.g. SIGINT for a background job of a non-interactive shell)
# cannot be trapped by bash and is neither handled nor forwarded.
# WRITE ACCESS: the run and records directories are made writable again by
# the EXIT trap on every exit path except SIGKILL (the runner cannot act
# then; `chmod -R u+w <run dir>` before deleting such a directory).
# Default run id: run-<UTC yyyymmddThhmmssZ>-<8 hex>; --run-id must match
# ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$. An existing run directory is refused.
#
# --dry-run validates the manifest, approval, digest, entry point, run id and
# AWS flag status, prints the plan and executes nothing. Tree/commit
# preconditions of a real run are reported as "REAL RUN WOULD REFUSE" lines
# (they do not change the dry-run exit code).
#
# Exit codes:
#    0 success (or dry-run plan valid)      8 run directory already exists, or
#    2 usage error                            a foreign run-record.json was
#    3 manifest missing, unreadable, a        moved aside (record written)
#      symlink or under a symlinked dir     9 touchesAws without --approve-aws
#    4 manifest invalid (JSON or contract)    and interactive confirmation
#    5 manifest not approved                  (also: no answer / EOF)
#    6 manifest tampered (digest mismatch) 10 Git tree dirty / manifest not
#    7 entry point not allowlisted, or        committed under experiments/ /
#      changed after verification             HEAD or tree changed / Git
#   11 entry point exited nonzero             inspection failed
#   12 entry point timed out
#   13 entry point succeeded but an expected local metric is missing
#   14 missing prerequisite (jq)
#   15 interrupted (INT/TERM/HUP forwarded to the entry point)
# Exit codes 11-13 and 15 (and 7 with status entry-changed, and 8 after a
# foreign record was moved aside) are returned after the final run record was
# written.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/release-lib.sh
source "${SCRIPT_DIR}/release-lib.sh"

usage() {
  sed -n '/^# Usage:/,/^####/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//'
}
short_usage() {
  echo "Usage: run-experiment.sh [--dry-run] [--run-id <id>] [--allow-dirty] [--approve-aws] <manifest.json>" >&2
  echo "       run-experiment.sh --print-digest <manifest.json>" >&2
}

DRY_RUN=0
RUN_ID=""
ALLOW_DIRTY=0
APPROVE_AWS=0
PRINT_DIGEST=0
MANIFEST_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --approve-aws) APPROVE_AWS=1 ;;
    --print-digest) PRINT_DIGEST=1 ;;
    --run-id)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: --run-id needs a value." >&2
        short_usage
        exit 2
      fi
      RUN_ID="$2"
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      echo "ERROR: unknown option '$1'." >&2
      short_usage
      exit 2
      ;;
    *)
      if [[ -n "${MANIFEST_ARG}" ]]; then
        echo "ERROR: exactly one manifest may be given." >&2
        short_usage
        exit 2
      fi
      MANIFEST_ARG="$1"
      ;;
  esac
  shift
done
if [[ -z "${MANIFEST_ARG}" ]]; then
  echo "ERROR: no manifest given." >&2
  short_usage
  exit 2
fi
if [[ -n "${RUN_ID}" && ! "${RUN_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]]; then
  echo "ERROR: --run-id '${RUN_ID}' must match ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}\$." >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required." >&2
  exit 14
fi

AWS_REGION_ALLOWLIST="us-east-2"
AWS_ENTRY_POINTS="scripts/run-bounded-pipeline.sh scripts/smoke-test.sh"
REPO_PHYS="$(cd "${REPO_ROOT}" && pwd -P)"

TMP_BASE="${TMPDIR:-/tmp}"
WORK_DIR="$(mktemp -d "${TMP_BASE%/}/scholarchain-exp.XXXXXX")"
chmod 700 "${WORK_DIR}"
ENTRY_HOME=""
ENTRY_TMP=""
RUN_DIR=""
REC_DIR=""
RUN_DIR_MADE=0
cleanup_dirs() {
  # Best effort, on every exit path (early `set -e` exits, the move_aside
  # failure, signals): the run and records directories this runner created
  # are made writable again so that they can be deleted. Never followed
  # through a symlink, never applied to a directory this runner did not make.
  if [[ ${RUN_DIR_MADE} -eq 1 ]]; then
    if [[ -d "${RUN_DIR}" && ! -L "${RUN_DIR}" ]]; then chmod u+w "${RUN_DIR}" 2>/dev/null || true; fi
    if [[ -d "${REC_DIR}" && ! -L "${REC_DIR}" ]]; then chmod u+w "${REC_DIR}" 2>/dev/null || true; fi
  fi
  rm -rf "${WORK_DIR}"
  local d
  for d in "${ENTRY_HOME}" "${ENTRY_TMP}"; do
    if [[ -n "${d}" ]]; then
      chmod -R u+w "${d}" 2>/dev/null || true
      rm -rf "${d}"
    fi
  done
}
trap cleanup_dirs EXIT
SNAPSHOT="${WORK_DIR}/manifest.json"

# canonical_digest <manifest>: sha256 of `jq -cS 'del(.approval)'`.
canonical_digest() {
  jq -cS 'del(.approval)' "$1" >"${WORK_DIR}/canonical.json" || return 1
  rl_sha256 "${WORK_DIR}/canonical.json"
}

# path_has_symlink <absolute path>: 0 if the path, or any parent directory of
# it below the repository root, is a symlink. Paths outside the repository
# return 1 (they are refused elsewhere).
path_has_symlink() {
  local p="$1" base rest cur part
  if [[ "${p}" == "${REPO_PHYS}/"* ]]; then
    base="${REPO_PHYS}"
  elif [[ "${p}" == "${REPO_ROOT}/"* ]]; then
    base="${REPO_ROOT}"
  else
    return 1
  fi
  rest="${p#"${base}"/}"
  cur="${base}"
  while [[ -n "${rest}" ]]; do
    part="${rest%%/*}"
    if [[ "${rest}" == */* ]]; then rest="${rest#*/}"; else rest=""; fi
    [[ -n "${part}" ]] || continue
    cur="${cur}/${part}"
    if [[ -L "${cur}" ]]; then
      return 0
    fi
  done
  return 1
}

# committed_blob_matches <commit> <repo-relative path> <file>: 0 only if
# <path> is a regular file (mode 100644/100755) in <commit> and the bytes of
# <file> hash to exactly that blob. The index (and its assume-unchanged /
# skip-worktree flags) plays no part. Sets CBM_REASON and CBM_BLOB.
CBM_REASON=""
CBM_BLOB=""
committed_blob_matches() {
  local commit="$1" rel="$2" file="$3" entry mode want have
  CBM_REASON=""
  CBM_BLOB=""
  if ! entry="$(git -C "${REPO_ROOT}" --literal-pathspecs ls-tree "${commit}" -- "${rel}" 2>/dev/null)" || [[ -z "${entry}" ]]; then
    CBM_REASON="not committed at ${commit}"
    return 1
  fi
  if [[ "${entry}" == *$'\n'* || "${entry}" != *$'\t'"${rel}" ]]; then
    CBM_REASON="ambiguous tree entry at ${commit}"
    return 1
  fi
  mode="${entry%% *}"
  case "${mode}" in
    100644 | 100755) ;;
    120000)
      CBM_REASON="committed as a symlink (mode 120000)"
      return 1
      ;;
    *)
      CBM_REASON="committed with mode ${mode}, not a regular file"
      return 1
      ;;
  esac
  if ! want="$(git -C "${REPO_ROOT}" rev-parse --verify --quiet "${commit}:${rel}" 2>/dev/null)" || [[ -z "${want}" ]]; then
    CBM_REASON="cannot resolve ${commit}:${rel}"
    return 1
  fi
  if ! have="$(git -C "${REPO_ROOT}" hash-object --stdin <"${file}" 2>/dev/null)" || [[ -z "${have}" ]]; then
    CBM_REASON="cannot hash ${file}"
    return 1
  fi
  if [[ "${have}" != "${want}" ]]; then
    CBM_REASON="working copy blob ${have} differs from the committed blob ${want} (uncommitted change, possibly hidden by assume-unchanged/skip-worktree)"
    return 1
  fi
  CBM_BLOB="${want}"
  return 0
}

echo "=== STEP: Read manifest ==="
if [[ -L "${MANIFEST_ARG}" ]]; then
  echo "ERROR: manifest is a symlink: ${MANIFEST_ARG}; only a regular file may be run." >&2
  exit 3
fi
if [[ ! -e "${MANIFEST_ARG}" ]]; then
  echo "ERROR: manifest not found: ${MANIFEST_ARG}" >&2
  exit 3
fi
if [[ ! -f "${MANIFEST_ARG}" || ! -r "${MANIFEST_ARG}" ]]; then
  echo "ERROR: manifest is not a readable regular file: ${MANIFEST_ARG}" >&2
  exit 3
fi
MANIFEST="$(cd "$(dirname "${MANIFEST_ARG}")" && pwd -P)/$(basename "${MANIFEST_ARG}")"
case "${MANIFEST_ARG}" in
  /*) MANIFEST_LOGICAL="${MANIFEST_ARG}" ;;
  *) MANIFEST_LOGICAL="${PWD}/${MANIFEST_ARG}" ;;
esac
if path_has_symlink "${MANIFEST_LOGICAL}" || path_has_symlink "${MANIFEST}"; then
  echo "ERROR: manifest path ${MANIFEST_ARG} is, or lies under, a symlink below the repository root; refused." >&2
  exit 3
fi
# Snapshot: the only copy read from here on (closes check/use gaps).
if ! cat -- "${MANIFEST}" >"${SNAPSHOT}" 2>/dev/null; then
  echo "ERROR: cannot read manifest ${MANIFEST}." >&2
  exit 3
fi
if [[ -L "${MANIFEST}" ]]; then
  echo "ERROR: manifest ${MANIFEST} became a symlink while it was read; refused." >&2
  exit 3
fi
if ! jq -e 'type == "object"' "${SNAPSHOT}" >/dev/null 2>&1; then
  echo "ERROR: manifest is not a JSON object: ${MANIFEST}" >&2
  exit 4
fi
MANIFEST_FILE_SHA="$(rl_sha256 "${SNAPSHOT}")"
echo "  Manifest: ${MANIFEST}"

if [[ ${PRINT_DIGEST} -eq 1 ]]; then
  if ! DIGEST="$(canonical_digest "${SNAPSHOT}")"; then
    echo "ERROR: cannot compute the manifest digest." >&2
    exit 4
  fi
  echo "${DIGEST}"
  exit 0
fi

echo "=== STEP: Verify approval ==="
AGENT_NAMES=()
for f in "${REPO_ROOT}"/.claude/agents/*.md; do
  [[ -f "${f}" ]] && AGENT_NAMES+=("$(basename "${f}" .md)")
done
APPROVAL_ERRORS="$(jq -r --argjson now "$(date -u +%s)" '
  def str: type == "string";
  if (.approval | type) != "object" then "approval block is missing"
  else .approval as $a
  | [ (if ($a.approvedBy | str) and ($a.approvedBy | test("^\\P{Cc}{1,128}$")) and ($a.approvedBy | test("\\S")) then empty
       else "approval.approvedBy must be a non-empty name (max 128 chars)" end),
      (if ($a.approvedAt | str) and ($a.approvedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
          and (try ($a.approvedAt | fromdateiso8601 | . <= ($now + 300)) catch false) then empty
       else "approval.approvedAt must be a UTC timestamp YYYY-MM-DDTHH:MM:SSZ that is not in the future" end),
      (if ($a.manifestSha256 | str) and ($a.manifestSha256 | test("^[0-9a-f]{64}$")) then empty
       else "approval.manifestSha256 must be 64 lowercase hex characters" end)
    ][]
  end' "${SNAPSHOT}")"
if [[ -n "${APPROVAL_ERRORS}" ]]; then
  printf '  %s\n' "${APPROVAL_ERRORS}" >&2
  echo "ERROR: manifest is not approved." >&2
  exit 5
fi
APPROVED_BY="$(jq -r '.approval.approvedBy' "${SNAPSHOT}")"
APPROVED_BY_LC="$(printf '%s' "${APPROVED_BY}" | tr '[:upper:]' '[:lower:]' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
for a in ${AGENT_NAMES[@]+"${AGENT_NAMES[@]}"}; do
  if [[ "${APPROVED_BY_LC}" == "${a}" ]]; then
    echo "ERROR: approval.approvedBy '${APPROVED_BY}' is an agent; only a human may approve an experiment." >&2
    exit 5
  fi
done
if printf '%s' "${APPROVED_BY_LC}" | grep -E -q '(^|[^a-z])(claude|agent|orchestrator|assistant)([^a-z]|$)|-(engineer|reviewer|responder|updater)$'; then
  echo "ERROR: approval.approvedBy '${APPROVED_BY}' names an agent or assistant; only a human may approve an experiment." >&2
  exit 5
fi
APPROVED_AT="$(jq -r '.approval.approvedAt' "${SNAPSHOT}")"
APPROVED_SHA="$(jq -r '.approval.manifestSha256' "${SNAPSHOT}")"
APPROVAL_JSON="$(jq -c '.approval' "${SNAPSHOT}")"
if ! DIGEST="$(canonical_digest "${SNAPSHOT}")"; then
  echo "ERROR: cannot compute the manifest digest." >&2
  exit 4
fi
if [[ "${DIGEST}" != "${APPROVED_SHA}" ]]; then
  echo "ERROR: manifest digest ${DIGEST} does not equal the approved digest ${APPROVED_SHA}." >&2
  echo "  The manifest was edited after approval (tampered). A human must re-approve it." >&2
  exit 6
fi
echo "  Approved by ${APPROVED_BY} at ${APPROVED_AT}; digest ${DIGEST} matches."

echo "=== STEP: Validate manifest contract ==="
CONTRACT_ERRORS="$(jq -r '
  def str: type == "string";
  def rel_ok: str and test("^[A-Za-z0-9._/-]{1,512}$") and (startswith("/") | not)
    and ((split("/") | map(select(. == ".." or . == "." or . == "")) | length) == 0);
  [ (if .schemaVersion == "scholarchain.experiment/v1" then empty else "schemaVersion must be \"scholarchain.experiment/v1\"" end),
    (if (.id | str) and (.id | test("^[a-z0-9][a-z0-9-]{2,63}$")) then empty else "id must match ^[a-z0-9][a-z0-9-]{2,63}$" end),
    (if (.description == null) or (.description | str) then empty else "description must be a string" end),
    (if (.entryPoint | str) then empty else "entryPoint must be a string" end),
    (if (.args == null) or ((.args | type) == "array" and all(.args[]; str and (test("\\p{Cc}") | not)))
     then empty else "args must be an array of strings without control characters" end),
    (if (.touchesAws | type) == "boolean" then empty else "touchesAws must be true or false" end),
    (if (.region | str) and (.region | test("^[a-z]{2}(-[a-z]+)+-[0-9]$")) then empty else "region must be an AWS Region name such as us-east-2" end),
    (if (.resourceLimits | type) == "object" then
       (if (.resourceLimits.timeoutSeconds | type) == "number" and (.resourceLimits.timeoutSeconds | . == floor and . >= 1 and . <= 86400)
        then empty else "resourceLimits.timeoutSeconds must be an integer 1..86400" end),
       (if (.resourceLimits.maxCostUsd | type) == "number" and .resourceLimits.maxCostUsd >= 0
        then empty else "resourceLimits.maxCostUsd must be a number >= 0" end),
       (if .touchesAws == false and (.resourceLimits.maxCostUsd | type) == "number" and .resourceLimits.maxCostUsd != 0
        then "resourceLimits.maxCostUsd must be 0 when touchesAws is false" else empty end)
     else "resourceLimits must be an object" end),
    (if (.metrics | type) == "array" and (.metrics | length) >= 1 then
       (.touchesAws as $aws | .metrics[] |
         if type != "object" then "each metrics entry must be an object"
         else
           (if (.name | str) and (.name | test("^[A-Za-z0-9._-]{1,64}$")) then empty else "metrics[].name must match ^[A-Za-z0-9._-]{1,64}$" end),
           (if (.location | str) and (.location | startswith("s3://")) then
              (if (.location | test("\\p{Cc}")) or (.location | length) > 1024
               then "metrics[].location s3://... must not contain control characters (max 1024 chars)"
               elif $aws == true then empty
               else "metrics location \(.location) is s3:// but touchesAws is false" end)
            elif (.location | rel_ok) then empty
            else "metrics[].location must be a relative path (no .., no leading /) or s3://..." end)
         end)
     else "metrics must be a non-empty array" end)
  ][]' "${SNAPSHOT}")"
if [[ -n "${CONTRACT_ERRORS}" ]]; then
  printf '  %s\n' "${CONTRACT_ERRORS}" >&2
  echo "ERROR: manifest violates the experiment manifest contract." >&2
  exit 4
fi
EXP_ID="$(jq -r '.id' "${SNAPSHOT}")"
ENTRY="$(jq -r '.entryPoint' "${SNAPSHOT}")"
TOUCHES_AWS="$(jq -r '.touchesAws' "${SNAPSHOT}")"
REGION="$(jq -r '.region' "${SNAPSHOT}")"
TIMEOUT_SECONDS="$(jq -r '.resourceLimits.timeoutSeconds' "${SNAPSHOT}")"
MAX_COST="$(jq -r '.resourceLimits.maxCostUsd' "${SNAPSHOT}")"
LIMITS_JSON="$(jq -c '.resourceLimits' "${SNAPSHOT}")"
METRIC_LOCATIONS_JSON="$(jq -c '[.metrics[] | {name, location}]' "${SNAPSHOT}")"
ARGS=()
while IFS= read -r line; do
  ARGS+=("${line}")
done < <(jq -r '.args // [] | .[]' "${SNAPSHOT}")
if [[ "${TOUCHES_AWS}" == true ]]; then
  case " ${AWS_REGION_ALLOWLIST} " in
    *" ${REGION} "*) ;;
    *)
      echo "ERROR: region '${REGION}' is not allowed for AWS experiments (allowed: ${AWS_REGION_ALLOWLIST})." >&2
      exit 4
      ;;
  esac
fi
echo "  id=${EXP_ID} touchesAws=${TOUCHES_AWS} region=${REGION} timeoutSeconds=${TIMEOUT_SECONDS}"

echo "=== STEP: Inspect Git tree ==="
if ! rl_git_inspect "${REPO_ROOT}"; then
  echo "ERROR: cannot establish the commit; nothing was run." >&2
  exit 10
fi
COMMIT_SHA="${RL_COMMIT_SHA}"
GIT_DIRTY="${RL_GIT_DIRTY}"
GIT_STATUS_0="${RL_GIT_STATUS}"
if ! TREE_FP_0="$(rl_tree_fingerprint "${REPO_ROOT}")" || [[ ! "${TREE_FP_0}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "ERROR: cannot fingerprint the Git tree; nothing was run." >&2
  exit 10
fi
echo "  Commit: ${COMMIT_SHA}  dirty: ${GIT_DIRTY}  tree fingerprint: ${TREE_FP_0}"

echo "=== STEP: Verify entry point allowlist ==="
refuse_entry() {
  echo "ERROR: entry point '${ENTRY}' is not allowed: $1" >&2
  echo "  Allowed: load-tests/**/*.sh, ${AWS_ENTRY_POINTS} (identical to the committed blob, not a symlink)." >&2
  exit 7
}
if [[ ! "${ENTRY}" =~ ^[A-Za-z0-9._/-]+\.sh$ || "${ENTRY}" == /* ]]; then
  refuse_entry "must be a relative path to a .sh file using only [A-Za-z0-9._/-]"
fi
case "/${ENTRY}/" in
  */../* | */./* | *//*) refuse_entry "must not contain '.', '..' or empty path segments" ;;
esac
ENTRY_IS_AWS=0
case "${ENTRY}" in
  load-tests/*) ;;
  *)
    case " ${AWS_ENTRY_POINTS} " in
      *" ${ENTRY} "*) ENTRY_IS_AWS=1 ;;
      *) refuse_entry "not under load-tests/ and not one of the allowlisted scripts" ;;
    esac
    ;;
esac
if [[ ! -e "${REPO_ROOT}/${ENTRY}" && ! -L "${REPO_ROOT}/${ENTRY}" ]]; then
  refuse_entry "file does not exist"
fi
if path_has_symlink "${REPO_PHYS}/${ENTRY}" || [[ -L "${REPO_ROOT}/${ENTRY}" || ! -f "${REPO_ROOT}/${ENTRY}" ]]; then
  refuse_entry "must be a regular file, not a symlink, with no symlinked parent directory"
fi
ENTRY_DIR_PHYS="$(cd "${REPO_ROOT}/$(dirname "${ENTRY}")" && pwd -P)"
if [[ ${ENTRY_IS_AWS} -eq 1 ]]; then
  [[ "${ENTRY_DIR_PHYS}" == "${REPO_PHYS}/scripts" ]] || refuse_entry "resolves outside scripts/"
else
  case "${ENTRY_DIR_PHYS}/" in
    "${REPO_PHYS}/load-tests/"*) ;;
    *) refuse_entry "resolves outside load-tests/ (symlinked directory?)" ;;
  esac
fi
if ! git -C "${REPO_ROOT}" --literal-pathspecs ls-files --error-unmatch -- "${ENTRY}" >/dev/null 2>&1; then
  refuse_entry "not tracked by Git (commit it first)"
fi
if ! committed_blob_matches "${COMMIT_SHA}" "${ENTRY}" "${REPO_ROOT}/${ENTRY}"; then
  refuse_entry "${CBM_REASON}"
fi
ENTRY_BLOB="${CBM_BLOB}"
if [[ ${ENTRY_IS_AWS} -eq 1 && "${TOUCHES_AWS}" != true ]]; then
  echo "ERROR: ${ENTRY} calls AWS; the manifest must declare touchesAws=true." >&2
  exit 4
fi
ENTRY_SHA="$(rl_sha256 "${REPO_ROOT}/${ENTRY}")"
echo "  ${ENTRY} (blob ${ENTRY_BLOB}, sha256 ${ENTRY_SHA}) is allowlisted."

echo "=== STEP: Check run record ==="
if [[ -z "${RUN_ID}" ]]; then
  RUN_ID="run-$(date -u +%Y%m%dT%H%M%SZ)-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
fi
RUN_DIR="${REPO_ROOT}/local-runs/experiments/${EXP_ID}/${RUN_ID}"
if [[ -e "${RUN_DIR}" || -L "${RUN_DIR}" ]]; then
  echo "ERROR: run record ${RUN_DIR#"${REPO_ROOT}"/} already exists; refusing to overwrite it." >&2
  exit 8
fi
echo "  Run directory: ${RUN_DIR#"${REPO_ROOT}"/}"

echo "=== STEP: Check real-run preconditions ==="
WOULD_REFUSE=()
WOULD_REFUSE_RC=0
MANIFEST_BLOB=""
case "${MANIFEST}" in
  "${REPO_PHYS}/experiments/"*)
    MANIFEST_REL="${MANIFEST#"${REPO_PHYS}"/}"
    if [[ ! "${MANIFEST_REL}" =~ ^[A-Za-z0-9._/-]+$ ]]; then
      WOULD_REFUSE+=("manifest path ${MANIFEST_REL} must use only [A-Za-z0-9._/-]")
      WOULD_REFUSE_RC=10
    elif ! committed_blob_matches "${COMMIT_SHA}" "${MANIFEST_REL}" "${SNAPSHOT}"; then
      WOULD_REFUSE+=("manifest ${MANIFEST_REL} is not committed or differs from its committed blob: ${CBM_REASON}")
      WOULD_REFUSE_RC=10
    else
      MANIFEST_BLOB="${CBM_BLOB}"
    fi
    ;;
  *)
    MANIFEST_REL="${MANIFEST}"
    WOULD_REFUSE+=("manifest is not under experiments/ in this repository")
    WOULD_REFUSE_RC=10
    ;;
esac
if [[ "${GIT_DIRTY}" == true ]]; then
  if [[ ${ALLOW_DIRTY} -ne 1 || "${TOUCHES_AWS}" == true ]]; then
    WOULD_REFUSE+=("Git tree is dirty (--allow-dirty is accepted only for touchesAws=false)")
    WOULD_REFUSE_RC=10
  fi
fi
if [[ "${TOUCHES_AWS}" == true && ${APPROVE_AWS} -ne 1 ]]; then
  WOULD_REFUSE+=("touchesAws=true requires --approve-aws and an interactive confirmation")
  [[ ${WOULD_REFUSE_RC} -ne 0 ]] || WOULD_REFUSE_RC=9
fi

echo "=== STEP: Experiment plan ==="
echo "  Experiment:   ${EXP_ID}"
echo "  Manifest:     ${MANIFEST_REL} (digest ${DIGEST})"
CMD_TEXT="/bin/bash ${ENTRY}"
for a in ${ARGS[@]+"${ARGS[@]}"}; do
  CMD_TEXT="${CMD_TEXT} $(printf '%q' "${a}")"
done
echo "  Command:      ${CMD_TEXT}"
echo "  Working dir:  ${REPO_ROOT}"
echo "  Region:       ${REGION}  (touchesAws=${TOUCHES_AWS})"
echo "  Limits:       ${LIMITS_JSON}"
echo "  Metrics:"
jq -r '.metrics[] | "    \(.name): \(.location)"' "${SNAPSHOT}"
echo "  Outputs:      ${RUN_DIR#"${REPO_ROOT}"/}/output/ (SCHOLARCHAIN_RUN_DIR)"
echo "  Run records:  ${RUN_DIR#"${REPO_ROOT}"/}/records/run-record.started.json, run-record.json"

if [[ ${DRY_RUN} -eq 1 ]]; then
  for r in ${WOULD_REFUSE[@]+"${WOULD_REFUSE[@]}"}; do
    echo "  REAL RUN WOULD REFUSE: ${r}"
  done
  echo ""
  echo "DRY RUN: manifest ${EXP_ID} is approved and valid; nothing was executed."
  exit 0
fi

if [[ ${WOULD_REFUSE_RC} -ne 0 ]]; then
  for r in ${WOULD_REFUSE[@]+"${WOULD_REFUSE[@]}"}; do
    echo "ERROR: ${r}" >&2
  done
  echo "  Nothing was run." >&2
  exit "${WOULD_REFUSE_RC}"
fi


if [[ "${TOUCHES_AWS}" == true ]]; then
  echo "=== STEP: Human confirmation (AWS experiment) ==="
  if [[ ! -t 0 ]]; then
    echo "ERROR: an AWS experiment requires an interactive terminal for confirmation. Nothing was run." >&2
    exit 9
  fi
  EXPECTED_CONFIRM="${EXP_ID}/${REGION}"
  TYPED=""
  if ! read -r -p "  Type ${EXPECTED_CONFIRM} to run this AWS experiment (max cost USD ${MAX_COST}): " TYPED; then
    echo "" >&2
    echo "ERROR: no confirmation was given (end of input). Nothing was run." >&2
    exit 9
  fi
  if [[ "${TYPED}" != "${EXPECTED_CONFIRM}" ]]; then
    echo "ERROR: confirmation did not match. Nothing was run." >&2
    exit 9
  fi
fi

echo "=== STEP: Re-verify committed inputs ==="
# Everything checked before (the interactive confirmation can wait without
# bound) is checked again: HEAD, the entry point blob, the full Git inspection
# and the tree fingerprint (which covers sourced libraries and every other
# tracked or untracked file).
if ! HEAD_NOW="$(git -C "${REPO_ROOT}" rev-parse --verify HEAD 2>/dev/null)" || [[ "${HEAD_NOW}" != "${COMMIT_SHA}" ]]; then
  echo "ERROR: HEAD moved from ${COMMIT_SHA} to ${HEAD_NOW:-<unreadable>} during the checks. Nothing was run." >&2
  exit 10
fi
if ! committed_blob_matches "${COMMIT_SHA}" "${ENTRY}" "${REPO_ROOT}/${ENTRY}" || [[ "${CBM_BLOB}" != "${ENTRY_BLOB}" ]]; then
  echo "ERROR: entry point ${ENTRY} changed after verification (${CBM_REASON:-blob changed}). Nothing was run." >&2
  exit 7
fi
if ! rl_git_inspect "${REPO_ROOT}"; then
  echo "ERROR: Git inspection failed during re-verification. Nothing was run." >&2
  exit 10
fi
if [[ "${RL_COMMIT_SHA}" != "${COMMIT_SHA}" || "${RL_GIT_DIRTY}" != "${GIT_DIRTY}" || "${RL_GIT_STATUS}" != "${GIT_STATUS_0}" ]]; then
  echo "ERROR: the Git tree changed after the checks (status or index flags differ). Nothing was run." >&2
  exit 10
fi
TREE_FP_1=""
if ! TREE_FP_1="$(rl_tree_fingerprint "${REPO_ROOT}")" || [[ "${TREE_FP_1}" != "${TREE_FP_0}" ]]; then
  echo "ERROR: the Git tree changed after the checks (fingerprint ${TREE_FP_0} -> ${TREE_FP_1:-<failed>}). Nothing was run." >&2
  exit 10
fi
echo "  HEAD ${COMMIT_SHA}; ${ENTRY} blob ${ENTRY_BLOB}; manifest snapshot blob ${MANIFEST_BLOB}; tree fingerprint unchanged."

# Signals: the traps are installed BEFORE the run directory exists, so no
# signal can leave an unexplained directory behind.
INTERRUPTED=""
LATE_SIGNALS=""
ENTRY_PID=""
ENTRY_REAPED=0
KILLER_PID=""
on_signal() {
  local sig="$1"
  if [[ ${ENTRY_REAPED} -eq 1 ]]; then
    # The leader already ended: the signal did not affect it, so it must not
    # relabel the run. The group drain below is bounded on its own.
    LATE_SIGNALS="${LATE_SIGNALS:+${LATE_SIGNALS},}${sig}"
    echo "  Received SIG${sig} after the entry point ended; finishing the record (status unchanged)." >&2
    return 0
  fi
  [[ -n "${INTERRUPTED}" ]] || INTERRUPTED="${sig}"
  if [[ -z "${ENTRY_PID}" ]]; then
    echo "  Received SIG${sig} before the entry point started; it will not be run." >&2
    return 0
  fi
  echo "  Received SIG${sig}; forwarding it to the entry point's process group." >&2
  kill -"${sig}" -- "-${ENTRY_PID}" 2>/dev/null || kill -"${sig}" "${ENTRY_PID}" 2>/dev/null || true
  if [[ -z "${KILLER_PID}" ]]; then
    # Exit status 20: its KILL reached the group; 0: it sent nothing.
    (
      set +e
      s=""
      trap '[[ -z "${s}" ]] || kill "${s}" 2>/dev/null; exit 0' TERM
      sleep 10 &
      s=$!
      wait "${s}"
      s=""
      if kill -KILL -- "-${ENTRY_PID}" 2>/dev/null; then exit 20; fi
      exit 0
    ) >/dev/null 2>&1 &
    KILLER_PID=$!
  fi
}
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

echo "=== STEP: Run experiment ==="
if [[ -n "${INTERRUPTED}" ]]; then
  echo "  Interrupted (SIG${INTERRUPTED}) before the run directory was created; nothing was run." >&2
  exit 15
fi
mkdir -p "$(dirname "${RUN_DIR}")"
if ! mkdir "${RUN_DIR}" 2>/dev/null; then
  echo "ERROR: run record ${RUN_DIR#"${REPO_ROOT}"/} already exists; refusing to overwrite it." >&2
  exit 8
fi
RUN_DIR_MADE=1
OUT_DIR="${RUN_DIR}/output"
REC_DIR="${RUN_DIR}/records"
mkdir "${OUT_DIR}" "${REC_DIR}"
RUN_REL="${RUN_DIR#"${REPO_ROOT}"/}"
REC_REL="${RUN_REL}/records"
STARTED_AT="$(rl_utc_now)"
START_EPOCH="$(date -u +%s)"
# The entry point's HOME and TMPDIR are temporary directories of its own, NOT
# inside WORK_DIR. No runner verdict is ever stored in a file.
ENTRY_HOME="$(mktemp -d "${TMP_BASE%/}/scholarchain-exp-home.XXXXXX")"
ENTRY_TMP="$(mktemp -d "${TMP_BASE%/}/scholarchain-exp-tmp.XXXXXX")"
chmod 700 "${ENTRY_HOME}" "${ENTRY_TMP}"
ARGS_JSON="$(jq -cn '$ARGS.positional' --args -- ${ARGS[@]+"${ARGS[@]}"})"
STDOUT_LOG="${REC_DIR}/stdout.log"
STDERR_LOG="${REC_DIR}/stderr.log"
: >"${STDOUT_LOG}"
: >"${STDERR_LOG}"

# write_once <path> <json>: noclobber write, then read-only. Never overwrites.
write_once() {
  if ! (set -C && printf '%s\n' "$2" >"$1") 2>/dev/null; then
    echo "ERROR: $1 already exists; refusing to overwrite it." >&2
    return 1
  fi
  chmod a-w "$1"
}
# sha_or_empty <file>: sha256, or "" if the file is missing or unreadable.
sha_or_empty() {
  if [[ -f "$1" && ! -L "$1" ]]; then rl_sha256 "$1" 2>/dev/null || true; fi
}

# Common record fields (provisional and final), all from values captured
# before the spawn.
BASE_JSON="$(jq -cn \
  --arg id "${EXP_ID}" --arg run "${RUN_ID}" --arg mpath "${MANIFEST_REL}" --arg digest "${DIGEST}" \
  --arg mfile "${MANIFEST_FILE_SHA}" --arg mblob "${MANIFEST_BLOB}" --argjson approval "${APPROVAL_JSON}" \
  --arg sha "${COMMIT_SHA}" --argjson dirty "${GIT_DIRTY}" --arg entry "${ENTRY}" --arg esha "${ENTRY_SHA}" \
  --arg eblob "${ENTRY_BLOB}" --argjson args "${ARGS_JSON}" --argjson aws "${TOUCHES_AWS}" --arg region "${REGION}" \
  --argjson limits "${LIMITS_JSON}" --arg start "${STARTED_AT}" \
  --arg os "$(uname -s)" --arg arch "$(uname -m)" \
  '{schemaVersion:"scholarchain.experiment-run/v1",experimentId:$id,runId:$run,manifestPath:$mpath,
    manifestSha256:$digest,manifestFileSha256:$mfile,manifestBlob:$mblob,approval:$approval,commitSha:$sha,
    gitDirty:$dirty,entryPoint:$entry,entryPointSha256:$esha,entryPointBlob:$eblob,args:$args,touchesAws:$aws,
    region:$region,resourceLimits:$limits,startedAt:$start,host:{os:$os,arch:$arch},
    provenance:{component:"scripts/run-experiment.sh",commit:$sha,manifestSha256:$digest}}')"
PROVISIONAL="${REC_DIR}/run-record.started.json"
PROVISIONAL_JSON="$(jq -c --argjson metrics "${METRIC_LOCATIONS_JSON}" --arg rr "${REC_REL}" --arg od "${RUN_REL}/output" \
  '. + {status:"started",metrics:$metrics,outputDir:$od,
        logs:{stdout:($rr + "/stdout.log"),stderr:($rr + "/stderr.log")},
        finalRecord:($rr + "/run-record.json")}' <<<"${BASE_JSON}")"

if ! write_once "${PROVISIONAL}" "${PROVISIONAL_JSON}"; then
  exit 8
fi
PROVISIONAL_SHA="$(rl_sha256 "${PROVISIONAL}")"
echo "  Provisional record: ${PROVISIONAL#"${REPO_ROOT}"/} (status started)"
# Nothing reads the manifest snapshot after this point: delete it, so an entry
# point that finds the runner's work directory cannot influence the record.
rm -f "${SNAPSHOT}" "${WORK_DIR}/canonical.json"
# The records directory (and the run directory holding it) stay read-only
# while the entry point runs; output/ is the entry point's.
chmod a-w "${REC_DIR}" "${RUN_DIR}"

run_entry() {
  cd "${REPO_ROOT}"
  # Last check, immediately before exec. Its verdict is only this exit status
  # (recorded as an ordinary failure): no marker file, which the entry point
  # could forge or delete.
  if ! committed_blob_matches "${COMMIT_SHA}" "${ENTRY}" "${REPO_ROOT}/${ENTRY}" || [[ "${CBM_BLOB}" != "${ENTRY_BLOB}" ]]; then
    echo "ERROR: entry point ${ENTRY} changed after verification (${CBM_REASON:-blob changed}); not executed." >&2
    exit 7
  fi
  export TMPDIR="${ENTRY_TMP}"
  if [[ "${TOUCHES_AWS}" != true ]]; then
    for v in $(compgen -e); do
      case "${v}" in AWS_*) unset "${v}" ;; esac
    done
    mkdir -p "${ENTRY_HOME}/.config" "${ENTRY_HOME}/.cache"
    export AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true \
      HOME="${ENTRY_HOME}" XDG_CONFIG_HOME="${ENTRY_HOME}/.config" XDG_CACHE_HOME="${ENTRY_HOME}/.cache"
  else
    export AWS_REGION="${REGION}" AWS_DEFAULT_REGION="${REGION}"
  fi
  export SCHOLARCHAIN_EXPERIMENT_ID="${EXP_ID}" SCHOLARCHAIN_RUN_ID="${RUN_ID}" \
    SCHOLARCHAIN_RUN_DIR="${OUT_DIR}" SCHOLARCHAIN_EXPERIMENT_REGION="${REGION}"
  exec /bin/bash "${REPO_ROOT}/${ENTRY}" ${ARGS[@]+"${ARGS[@]}"}
}

# Process-group helpers (see PROCESS GROUP in the header).
GROUP_TERM_GRACE=5
GROUP_KILL_GRACE=5
group_alive() { kill -0 -- "-$1" 2>/dev/null; }
# group_count <pgid>: number of live processes in the group ("" if unknown).
group_count() {
  local n=""
  n="$(ps -A -o pgid= -o pid= 2>/dev/null | awk -v g="$1" '$1 == g {n++} END {print n+0}')" || n=""
  printf '%s' "${n}"
}
# wait_group_gone <pgid> <seconds>: 0 as soon as the group is empty.
wait_group_gone() {
  local n=$(($2 * 10))
  while [[ ${n} -gt 0 ]]; do
    group_alive "$1" || return 0
    sleep 0.1
    n=$((n - 1))
  done
  ! group_alive "$1"
}
# wait_status <pid>: reaps a child of this shell and sets WS_RC to its exit
# status. A trapped signal interrupts `wait` (status > 128) while the child may
# still run: wait again until it is gone. 127 (no longer known) keeps the last
# status seen ("" if none).
WS_RC=""
wait_status() {
  local rc
  WS_RC=""
  while :; do
    rc=0
    wait "$1" 2>/dev/null || rc=$?
    [[ ${rc} -ne 127 ]] || break
    WS_RC="${rc}"
    kill -0 "$1" 2>/dev/null || break
  done
}
PG_ALIVE_AFTER=0
PG_REMAINING=0
PG_SIGNALS=""
PG_EMPTY=true
# drain_group <pgid>: after the leader was reaped, TERM then KILL the group
# until it is empty (bounded: GROUP_TERM_GRACE + GROUP_KILL_GRACE seconds).
drain_group() {
  local pg="$1"
  group_alive "${pg}" || return 0
  PG_ALIVE_AFTER="$(group_count "${pg}")"
  echo "  ${PG_ALIVE_AFTER:-Some} process(es) of the entry point's group (pgid ${pg}) outlived it; sending TERM." >&2
  kill -TERM -- "-${pg}" 2>/dev/null || true
  PG_SIGNALS="TERM"
  if ! wait_group_gone "${pg}" "${GROUP_TERM_GRACE}"; then
    echo "  Group ${pg} still alive after ${GROUP_TERM_GRACE} s; sending KILL." >&2
    kill -KILL -- "-${pg}" 2>/dev/null || true
    PG_SIGNALS="TERM,KILL"
    wait_group_gone "${pg}" "${GROUP_KILL_GRACE}" || true
  fi
  if group_alive "${pg}"; then
    PG_EMPTY=false
    PG_REMAINING="$(group_count "${pg}")"
    echo "WARNING: process group ${pg} is still not empty (${PG_REMAINING:-unknown} process(es)) after TERM and KILL." >&2
  else
    PG_REMAINING=0
  fi
}

EXIT_STATUS=0
TIMED_OUT=false
ENTRY_CHANGED=0
ENTRY_PGID=""
WATCHDOG_PID=""
ESCALATIONS=""
if [[ -n "${INTERRUPTED}" ]]; then
  # Signalled after the provisional record but before the spawn: never start.
  echo "  Interrupted (SIG${INTERRUPTED}) before the entry point started; it was not run." >&2
  EXIT_STATUS=null
elif ! committed_blob_matches "${COMMIT_SHA}" "${ENTRY}" "${REPO_ROOT}/${ENTRY}" || [[ "${CBM_BLOB}" != "${ENTRY_BLOB}" ]]; then
  # The runner's own re-verify immediately before the spawn; the verdict stays
  # in this shell's memory.
  ENTRY_CHANGED=1
  echo "ERROR: entry point ${ENTRY} changed after verification (${CBM_REASON:-blob changed}); it was not run." >&2
  EXIT_STATUS=null
else
  # Job control gives the entry point (and the watchdog) their own process
  # groups, so a timeout or a forwarded signal reaches every process the entry
  # point started in its group.
  SPAWN_EPOCH="$(date -u +%s)"
  set -m
  run_entry </dev/null >"${STDOUT_LOG}" 2>"${STDERR_LOG}" &
  ENTRY_PID=$!
  ENTRY_PGID="${ENTRY_PID}"
  # Watchdog. Its verdict is its exit status: 0 did not fire, 20 fired (TERM
  # sent), 21 fired and its KILL reached the group. TERM from the runner (or
  # anyone) stops it and it exits with what it has done so far.
  (
    set +e
    fired=0
    s=""
    trap '[[ -z "${s}" ]] || kill "${s}" 2>/dev/null; exit "${fired}"' TERM
    sleep "${TIMEOUT_SECONDS}" &
    s=$!
    wait "${s}"
    if kill -0 "${ENTRY_PID}" 2>/dev/null; then
      fired=20
      kill -TERM -- "-${ENTRY_PID}" 2>/dev/null || kill -TERM "${ENTRY_PID}" 2>/dev/null
      sleep 5 &
      s=$!
      wait "${s}"
      s=""
      if kill -KILL -- "-${ENTRY_PID}" 2>/dev/null; then fired=21; fi
    fi
    exit "${fired}"
  ) >/dev/null 2>&1 &
  WATCHDOG_PID=$!
  set +m
  # A signal that arrived between the spawn and this line is forwarded now.
  if [[ -n "${INTERRUPTED}" ]]; then
    on_signal "${INTERRUPTED}"
  fi
  wait "${ENTRY_PID}" 2>/dev/null || EXIT_STATUS=$?
  # A trapped signal interrupts `wait` (status > 128) while the entry point may
  # still be running (or be an unreaped zombie): wait again until it is gone.
  while kill -0 "${ENTRY_PID}" 2>/dev/null; do
    EXIT_STATUS=0
    wait "${ENTRY_PID}" 2>/dev/null || EXIT_STATUS=$?
  done
  if [[ -n "${INTERRUPTED}" ]]; then
    # The job may have been reaped while the trap ran; bash keeps its status.
    rc2=0
    wait "${ENTRY_PID}" 2>/dev/null || rc2=$?
    [[ ${rc2} -eq 127 ]] || EXIT_STATUS=${rc2}
  fi
  # The leader is reaped: later signals no longer relabel the run (the
  # watchdog cannot fire for a reaped leader).
  ENTRY_REAPED=1
  REAP_EPOCH="$(date -u +%s)"
  # Members of the group that outlived the leader are terminated before the
  # watchdog and the forwarded-signal killer are stopped.
  drain_group "${ENTRY_PGID}"
  kill -TERM -- "-${WATCHDOG_PID}" 2>/dev/null || kill -TERM "${WATCHDOG_PID}" 2>/dev/null || true
  wait_status "${WATCHDOG_PID}"
  WATCHDOG_RC="${WS_RC}"
  case "${WATCHDOG_RC}" in
    20) ESCALATIONS="watchdog:TERM" ;;
    21) ESCALATIONS="watchdog:TERM,watchdog:KILL" ;;
  esac
  [[ -z "${ESCALATIONS}" ]] || TIMED_OUT=true
  # Backstop: a leader reaped more than timeoutSeconds after the spawn ran
  # over its limit, whatever happened to the watchdog.
  if [[ "${TIMED_OUT}" != true && $((REAP_EPOCH - SPAWN_EPOCH)) -gt ${TIMEOUT_SECONDS} ]]; then
    TIMED_OUT=true
    echo "WARNING: the entry point ran $((REAP_EPOCH - SPAWN_EPOCH)) s (limit ${TIMEOUT_SECONDS} s) but the watchdog did not report firing (status ${WATCHDOG_RC:-unknown}); recorded as timed out." >&2
  fi
  if [[ -n "${KILLER_PID}" ]]; then
    kill -TERM "${KILLER_PID}" 2>/dev/null || true
    wait_status "${KILLER_PID}"
    [[ "${WS_RC}" != 20 ]] || ESCALATIONS="${ESCALATIONS:+${ESCALATIONS},}killer:KILL"
  fi
  ENTRY_PID=""
fi
PG_SIGNALS_ALL="${ESCALATIONS}${ESCALATIONS:+${PG_SIGNALS:+,}}${PG_SIGNALS}"
ENDED_AT="$(rl_utc_now)"
END_EPOCH="$(date -u +%s)"
echo "  Exit status: ${EXIT_STATUS}  timed out: ${TIMED_OUT}  signal: ${INTERRUPTED:-none}"
if [[ -n "${ENTRY_PGID}" ]]; then
  echo "  Process group ${ENTRY_PGID}: alive after leader exit ${PG_ALIVE_AFTER:-unknown}, signals ${PG_SIGNALS_ALL:-none}, empty at end ${PG_EMPTY}"
fi

echo "=== STEP: Collect metrics ==="
# Metric names and locations come ONLY from the values captured before the
# spawn (the snapshot no longer exists).
METRICS_JSON="[]"
MISSING_METRICS=0
while IFS=$'\t' read -r mname mloc; do
  present=null
  msha=""
  case "${mloc}" in
    s3://*) echo "  ${mname}: ${mloc} (S3; not checked locally)" ;;
    *)
      if [[ -f "${OUT_DIR}/${mloc}" && ! -L "${OUT_DIR}/${mloc}" ]]; then
        present=true
        msha="$(rl_sha256 "${OUT_DIR}/${mloc}")"
        echo "  ${mname}: ${mloc} present (sha256 ${msha})"
      else
        present=false
        MISSING_METRICS=$((MISSING_METRICS + 1))
        echo "  ${mname}: ${mloc} MISSING" >&2
      fi
      ;;
  esac
  METRICS_JSON="$(jq -c --arg n "${mname}" --arg l "${mloc}" --argjson p "${present}" --arg s "${msha}" \
    '. + [{name:$n,location:$l,present:$p,sha256:(if $s == "" then null else $s end)}]' <<<"${METRICS_JSON}")"
done < <(jq -r '.[] | [.name, .location] | @tsv' <<<"${METRIC_LOCATIONS_JSON}")

STATUS=succeeded
RC=0
if [[ -n "${INTERRUPTED}" ]]; then
  STATUS=interrupted
  RC=15
elif [[ ${ENTRY_CHANGED} -eq 1 ]]; then
  STATUS=entry-changed
  RC=7
elif [[ "${TIMED_OUT}" == true ]]; then
  STATUS=timed-out
  RC=12
elif [[ "${EXIT_STATUS}" != 0 ]]; then
  STATUS=failed
  RC=11
elif [[ ${MISSING_METRICS} -gt 0 ]]; then
  STATUS=missing-metrics
  RC=13
fi

echo "=== STEP: Write run record ==="
chmod u+w "${RUN_DIR}" 2>/dev/null || true
FOREIGN_MOVED=""
# move_aside <path>: rename a foreign file or directory to <path>.FOREIGN-<epoch>.
move_aside() {
  local dst n=0
  dst="$1.FOREIGN-$(date -u +%s)"
  while [[ -e "${dst}" || -L "${dst}" ]]; do
    n=$((n + 1))
    dst="$1.FOREIGN-$(date -u +%s)-${n}"
  done
  if ! mv -- "$1" "${dst}"; then
    echo "ERROR: cannot move the foreign $1 aside; no final record was written." >&2
    exit 8
  fi
  FOREIGN_MOVED="${dst#"${REPO_ROOT}"/}"
  echo "ERROR: a foreign ${1#"${REPO_ROOT}"/} was found (not written by this runner); moved it to ${FOREIGN_MOVED}." >&2
}
if [[ -L "${REC_DIR}" || ! -d "${REC_DIR}" ]]; then
  move_aside "${REC_DIR}"
  mkdir "${REC_DIR}"
fi
chmod u+w "${REC_DIR}"
RECORD="${REC_DIR}/run-record.json"
if [[ -e "${RECORD}" || -L "${RECORD}" ]]; then
  move_aside "${RECORD}"
fi
PROV_UNCHANGED=false
[[ "$(sha_or_empty "${PROVISIONAL}")" != "${PROVISIONAL_SHA}" ]] || PROV_UNCHANGED=true
RECORD_JSON="$(jq -c \
  --arg end "${ENDED_AT}" --argjson dur "$((END_EPOCH - START_EPOCH))" --argjson rc "${EXIT_STATUS}" \
  --argjson tout "${TIMED_OUT}" --arg sig "${INTERRUPTED}" --arg late "${LATE_SIGNALS}" --arg status "${STATUS}" \
  --argjson metrics "${METRICS_JSON}" --arg od "${RUN_REL}/output" \
  --arg out "${REC_REL}/stdout.log" --arg err "${REC_REL}/stderr.log" \
  --arg outsha "$(sha_or_empty "${STDOUT_LOG}")" --arg errsha "$(sha_or_empty "${STDERR_LOG}")" \
  --arg ppath "${REC_REL}/run-record.started.json" --arg psha "${PROVISIONAL_SHA}" --argjson punch "${PROV_UNCHANGED}" \
  --arg pgid "${ENTRY_PGID}" --arg pgalive "${PG_ALIVE_AFTER}" --arg pgsig "${PG_SIGNALS_ALL}" \
  --arg pgrem "${PG_REMAINING}" --argjson pgempty "${PG_EMPTY}" --arg foreign "${FOREIGN_MOVED}" \
  'def num: if . == "" then null else tonumber end;
   def list: if . == "" then [] else split(",") end;
   . + {endedAt:$end,durationSeconds:$dur,exitStatus:$rc,timedOut:$tout,
        signal:(if $sig == "" then null else $sig end),lateSignals:($late | list),status:$status,
        outputDir:$od,metrics:$metrics,
        logs:{stdout:$out,stdoutSha256:(if $outsha == "" then null else $outsha end),
              stderr:$err,stderrSha256:(if $errsha == "" then null else $errsha end)},
        provisionalRecord:{path:$ppath,sha256:$psha,unchangedAtEnd:$punch},
        processGroup:(if $pgid == "" then null else
          {pgid:($pgid | num),aliveAfterLeaderExit:($pgalive | num),signalsSent:($pgsig | list),
           membersKilled:(if ($pgalive | num) == null or ($pgrem | num) == null then null
                          else ([($pgalive | num) - ($pgrem | num), 0] | max) end),
           remainingAtEnd:($pgrem | num),emptyAtEnd:$pgempty} end),
        foreignRecordMovedTo:(if $foreign == "" then null else $foreign end)}' <<<"${BASE_JSON}")"
if ! write_once "${RECORD}" "${RECORD_JSON}"; then
  exit 8
fi
chmod a-w "${STDOUT_LOG}" "${STDERR_LOG}" 2>/dev/null || true
if [[ -n "${FOREIGN_MOVED}" ]]; then
  RC=8
fi

cat <<EOF

================================================================================
EXPERIMENT ${STATUS}
  Experiment:  ${EXP_ID}
  Run:         ${RUN_ID}
  Commit:      ${COMMIT_SHA} (dirty: ${GIT_DIRTY})
  Region:      ${REGION} (touchesAws=${TOUCHES_AWS})
  Started:     ${STARTED_AT}
  Ended:       ${ENDED_AT}
  Exit status: ${EXIT_STATUS}
  Record:      ${RECORD#"${REPO_ROOT}"/}
EOF
if [[ -n "${FOREIGN_MOVED}" ]]; then
  echo "  FOREIGN RECORD MOVED ASIDE: ${FOREIGN_MOVED} (exit 8)"
fi
if [[ "${PG_EMPTY}" != true ]]; then
  echo "  WARNING: the entry point's process group was not empty at the end."
fi
echo "================================================================================"
exit "${RC}"
