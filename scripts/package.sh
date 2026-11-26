#!/usr/bin/env bash
set -euo pipefail

################################################################################
# package.sh - Build the job jars and service images LOCALLY, tagged by commit.
#
# Usage: ./scripts/package.sh [--dry-run] [--jars-only|--skip-images]
#                             [--with-standalone] [--allow-dirty]
#                             [--allow-non-amd64]
#
# Builds, from the repository root:
#   jars    `sbt -batch packageJobs` (hadoop/assembly, entityResolution/assembly,
#           sparkAnalytics/assembly: the Hadoop/Spark-Provided assemblies that
#           EMR and the batch-runner image run). With --with-standalone also
#           `sbt -batch packageStandaloneJobs` (java -jar assemblies; not used
#           by the deployed pipeline today). Every expected jar output path
#           (<module>/target/scala-*/<base>-<version>.jar) is DELETED before
#           sbt runs, so a jar found afterwards was written by this build
#           (sbt-assembly may skip rewriting an up-to-date jar, which made an
#           mtime check unreliable). Each fresh jar is copied to
#           <out>/jars/<name>.jar under the name run-bounded-pipeline.sh expects
#           in the artifacts bucket (scholarchain-hadoop.jar,
#           scholarchain-entity-resolution.jar, scholarchain-spark.jar).
#   images  `sbt -batch dockerLocal` (services/Docker/publishLocal ->
#           scholarchain-services:<build version>), re-tagged
#           scholarchain-services:<tag>; this one image serves the six ECS
#           services (query-api, graph-retriever, vector-retriever,
#           synthesis-worker, verification-worker, embedding-worker).
#           `docker build --platform linux/amd64 docker/batch-runner` ->
#           scholarchain-batch-runner:<tag> (ECR repository batch-runner).
#
# Platform: ECS Fargate task definitions run linux/amd64 (X86_64). Every built
# image is inspected; if any is not linux/amd64 the run FAILS (exit 6, no
# manifest written) unless --allow-non-amd64 is given, in which case the
# manifest records fargateCompatible=false for that image and at top level.
# The services image platform is set in build.sbt (build-engineer).
#
# Tag: the full 40-hex Git commit SHA of HEAD (the image_tag plan.sh and
# deploy.sh require). Dirty tree (deploy.sh precedent): REFUSED (exit 3),
# also in --dry-run so that the dry run predicts the real run. With
# --allow-dirty the tag becomes <sha>-dirty, gitDirty=true is recorded, and
# such images can never be deployed (plan.sh/deploy.sh accept only 40 hex).
# Tracked files flagged assume-unchanged or skip-worktree count as dirty.
# Tree attribution: a fingerprint of HEAD, `git status --porcelain`, flagged
# index entries, `git diff HEAD --binary` and every untracked file's blob is
# taken before and after the build; any difference fails the run (exit 5).
#
# Output (gitignored, under local-runs/): local-runs/package/<tag>/<UTC
# timestamp>/ with jars/ and package-manifest.json (read-only after writing):
#   schemaVersion, createdAt, commitSha, gitDirty, treeFingerprint, tag,
#   buildVersion, fargateCompatible (null with --jars-only), host {os, arch}, jars[] {name, sbtTask, source, path, sha256, sizeBytes},
#   images[] {name, ref, imageId, os, architecture, fargateCompatible,
#   ecrRepositories, builtBy}, pushed=false, provenance {component, commit}.
# An existing output directory is never overwritten.
#
# NEVER pushes: no docker push, no ECR login, no AWS call of any kind.
# Pushing (and uploading jars) is deploy-engineer's job after human approval.
#
# Options:
#   --dry-run            inspect the tree, print the tag, commands and outputs;
#                        build nothing, write nothing.
#   --jars-only, --skip-images
#                        build the jars only (no Docker needed).
#   --with-standalone    also build packageStandaloneJobs.
#   --allow-dirty        package a dirty tree with tag <sha>-dirty.
#   --allow-non-amd64    HUMAN OVERRIDE: accept images that are not
#                        linux/amd64 (local experiments only; they cannot run
#                        on the ECS task definitions). Recorded as
#                        fargateCompatible=false.
#   -h, --help           print this text.
#
# Exit codes: 0 success; 1 build step failed; 2 usage; 3 dirty tree or Git
# inspection failed; 4 missing prerequisite (jq, sbt, docker, Docker daemon);
# 5 tree changed during the build, a fresh jar is missing/ambiguous, or the
# output directory already exists; 6 a built image is not linux/amd64 and
# --allow-non-amd64 was not given (no manifest written).
#
# Environment: SBT (default: sbt), DOCKER (default: docker) - command names,
# used by the offline tests to substitute stubs.
################################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=scripts/release-lib.sh
source "${SCRIPT_DIR}/release-lib.sh"

usage() {
  sed -n '/^# Usage:/,/^####/p' "${BASH_SOURCE[0]}" | sed -e '$d' -e 's/^# \{0,1\}//'
}

DRY_RUN=0
JARS_ONLY=0
WITH_STANDALONE=0
ALLOW_DIRTY=0
ALLOW_NON_AMD64=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --jars-only | --skip-images) JARS_ONLY=1 ;;
    --with-standalone) WITH_STANDALONE=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --allow-non-amd64) ALLOW_NON_AMD64=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "ERROR: unknown option '$1'." >&2
      echo "Usage: package.sh [--dry-run] [--jars-only|--skip-images] [--with-standalone] [--allow-dirty] [--allow-non-amd64]" >&2
      exit 2
      ;;
  esac
  shift
done

SBT="${SBT:-sbt}"
DOCKER="${DOCKER:-docker}"
SERVICE_REPOS="query-api graph-retriever vector-retriever synthesis-worker verification-worker embedding-worker"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required to write the package manifest." >&2
  exit 4
fi

echo "=== STEP: Inspect Git tree ==="
if ! rl_git_inspect "${REPO_ROOT}"; then
  echo "ERROR: cannot establish the commit to package; nothing was built." >&2
  exit 3
fi
COMMIT_SHA="${RL_COMMIT_SHA}"
GIT_DIRTY="${RL_GIT_DIRTY}"
if ! TREE_FP="$(rl_tree_fingerprint "${REPO_ROOT}")"; then
  echo "ERROR: cannot fingerprint the working tree; nothing was built." >&2
  exit 3
fi
if [[ "${GIT_DIRTY}" == true ]]; then
  TAG="${COMMIT_SHA}-dirty"
else
  TAG="${COMMIT_SHA}"
fi
echo "  Commit:    ${COMMIT_SHA}"
echo "  Git dirty: ${GIT_DIRTY}"
echo "  Tag:       ${TAG}"
if [[ "${GIT_DIRTY}" == true && ${ALLOW_DIRTY} -ne 1 ]]; then
  echo "ERROR: Git tree is dirty; refusing to package (images would not match commit ${COMMIT_SHA})." >&2
  printf '%s\n' "${RL_GIT_STATUS}" | sed 's/^/    /' >&2
  echo "  Commit or stash the changes, or pass --allow-dirty to build tag ${TAG} (never deployable)." >&2
  exit 3
fi

BUILD_VERSION="$(sed -n -E 's/^ThisBuild \/ version[[:space:]]*:=[[:space:]]*"([^"]+)".*$/\1/p' "${REPO_ROOT}/build.sbt" | head -n 1)"
if [[ ! "${BUILD_VERSION}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "ERROR: could not read 'ThisBuild / version' from build.sbt (got '${BUILD_VERSION}')." >&2
  exit 4
fi

# name|sbt task|module target dir|assembly base name
JOB_JARS="scholarchain-hadoop|hadoop/assembly|modules/hadoop/target|scholarchain-hadoop-assembly
scholarchain-entity-resolution|entityResolution/assembly|modules/entity-resolution/target|scholarchain-entity-resolution-assembly
scholarchain-spark|sparkAnalytics/assembly|modules/spark/target|scholarchain-spark-assembly"
if [[ ${WITH_STANDALONE} -eq 1 ]]; then
  JOB_JARS="${JOB_JARS}
scholarchain-hadoop-standalone|hadoopStandalone/assembly|packaging/hadoop-standalone/target|scholarchain-hadoop-standalone-assembly
scholarchain-spark-standalone|sparkStandalone/assembly|packaging/spark-standalone/target|scholarchain-spark-standalone-assembly"
fi

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${REPO_ROOT}/local-runs/package/${TAG}/${TIMESTAMP}"
SERVICES_REF="scholarchain-services:${TAG}"
RUNNER_REF="scholarchain-batch-runner:${TAG}"

echo "=== STEP: Package plan ==="
echo "  Build version: ${BUILD_VERSION}"
echo "  Output:        ${OUT_DIR#"${REPO_ROOT}"/}"
echo "  Jars:          ${SBT} -batch packageJobs$([[ ${WITH_STANDALONE} -eq 1 ]] && echo " ; ${SBT} -batch packageStandaloneJobs")"
while IFS='|' read -r jname jtask jdir jbase; do
  echo "    ${jtask} -> ${jdir}/scala-*/${jbase}-${BUILD_VERSION}.jar -> jars/${jname}.jar"
done <<<"${JOB_JARS}"
if [[ ${JARS_ONLY} -eq 1 ]]; then
  echo "  Images:        skipped (--jars-only/--skip-images)"
else
  echo "  Images:"
  echo "    ${SBT} -batch dockerLocal ; ${DOCKER} tag scholarchain-services:${BUILD_VERSION} ${SERVICES_REF}"
  echo "      (ECR repositories: ${SERVICE_REPOS})"
  echo "    ${DOCKER} build --platform linux/amd64 -t ${RUNNER_REF} docker/batch-runner"
  echo "      (ECR repository: batch-runner)"
fi
echo "  Push:          never (deploy-engineer pushes after human approval)"
if [[ ${JARS_ONLY} -ne 1 ]]; then
  if [[ ${ALLOW_NON_AMD64} -eq 1 ]]; then
    echo "  Platform:      --allow-non-amd64 (non-amd64 images accepted, recorded fargateCompatible=false)"
  else
    echo "  Platform:      every image must be linux/amd64 (else exit 6)"
  fi
fi

if [[ ${DRY_RUN} -eq 1 ]]; then
  echo ""
  echo "DRY RUN: nothing was built or written. Tag would be ${TAG}."
  exit 0
fi

echo "=== STEP: Check prerequisites ==="
if ! command -v "${SBT}" >/dev/null 2>&1; then
  echo "ERROR: ${SBT} is not installed." >&2
  exit 4
fi
if [[ ${JARS_ONLY} -ne 1 ]]; then
  if ! command -v "${DOCKER}" >/dev/null 2>&1; then
    echo "ERROR: ${DOCKER} is not installed (use --jars-only to skip images)." >&2
    exit 4
  fi
  if ! "${DOCKER}" info >/dev/null 2>&1; then
    echo "ERROR: the Docker daemon is not reachable (use --jars-only to skip images)." >&2
    exit 4
  fi
fi
if [[ -e "${OUT_DIR}" ]]; then
  echo "ERROR: output ${OUT_DIR} already exists; refusing to overwrite." >&2
  exit 5
fi
mkdir -p "$(dirname "${OUT_DIR}")"
if ! mkdir "${OUT_DIR}"; then
  echo "ERROR: could not create ${OUT_DIR} (exists?); refusing to overwrite." >&2
  exit 5
fi
mkdir "${OUT_DIR}/jars"

echo "=== STEP: Remove previous jar outputs ==="
# A jar present after the build must have been written by this build.
while IFS='|' read -r jname jtask jdir jbase; do
  [[ -d "${REPO_ROOT}/${jdir}" ]] || continue
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    echo "  rm ${f#"${REPO_ROOT}"/}"
    if ! rm -f -- "${f}"; then
      echo "ERROR: cannot remove the previous jar ${f}." >&2
      exit 1
    fi
  done < <(find "${REPO_ROOT}/${jdir}" -maxdepth 2 \( -type f -o -type l \) -path "*/scala-*/${jbase}-${BUILD_VERSION}.jar")
done <<<"${JOB_JARS}"

echo "=== STEP: Build jars (sbt packageJobs) ==="
if ! (cd "${REPO_ROOT}" && "${SBT}" -batch packageJobs); then
  echo "ERROR: sbt packageJobs failed." >&2
  exit 1
fi
if [[ ${WITH_STANDALONE} -eq 1 ]]; then
  echo "=== STEP: Build standalone jars (sbt packageStandaloneJobs) ==="
  if ! (cd "${REPO_ROOT}" && "${SBT}" -batch packageStandaloneJobs); then
    echo "ERROR: sbt packageStandaloneJobs failed." >&2
    exit 1
  fi
fi

echo "=== STEP: Record jars ==="
JARS_JSON="[]"
while IFS='|' read -r jname jtask jdir jbase; do
  found=""
  count=0
  if [[ -d "${REPO_ROOT}/${jdir}" ]]; then
    while IFS= read -r f; do
      [[ -n "${f}" ]] || continue
      found="${f}"
      count=$((count + 1))
    done < <(find "${REPO_ROOT}/${jdir}" -maxdepth 2 -type f -path "*/scala-*/${jbase}-${BUILD_VERSION}.jar")
  fi
  if [[ ${count} -ne 1 ]]; then
    echo "ERROR: expected exactly one fresh ${jbase}-${BUILD_VERSION}.jar under ${jdir}/scala-*, found ${count}." >&2
    exit 5
  fi
  dest="${OUT_DIR}/jars/${jname}.jar"
  cp "${found}" "${dest}"
  sha="$(rl_sha256 "${dest}")"
  size="$(rl_file_size "${dest}")"
  echo "  ${jname}: sha256 ${sha}  ${size} bytes  (${found#"${REPO_ROOT}"/})"
  JARS_JSON="$(jq -c --arg n "${jname}" --arg t "${jtask}" --arg s "${found#"${REPO_ROOT}"/}" \
    --arg p "${dest#"${REPO_ROOT}"/}" --arg h "${sha}" --arg z "${size}" \
    '. + [{name:$n,sbtTask:$t,source:$s,path:$p,sha256:$h,sizeBytes:($z|tonumber)}]' <<<"${JARS_JSON}")"
done <<<"${JOB_JARS}"

image_json() { # image_json <name> <ref> <builtBy> <repos...>
  local name="$1" ref="$2" built="$3" id os arch compat
  shift 3
  if ! id="$("${DOCKER}" image inspect --format '{{.Id}}' "${ref}")" || [[ -z "${id}" ]]; then
    echo "ERROR: cannot inspect local image ${ref}." >&2
    return 1
  fi
  os="$("${DOCKER}" image inspect --format '{{.Os}}' "${ref}")" || return 1
  arch="$("${DOCKER}" image inspect --format '{{.Architecture}}' "${ref}")" || return 1
  compat=false
  if [[ "${os}" == linux && "${arch}" == amd64 ]]; then compat=true; fi
  echo "  ${ref}: ${id} (${os}/${arch})" >&2
  jq -cn --arg n "${name}" --arg r "${ref}" --arg i "${id}" --arg o "${os}" --arg a "${arch}" \
    --arg b "${built}" --argjson c "${compat}" \
    '{name:$n,ref:$r,imageId:$i,os:$o,architecture:$a,fargateCompatible:$c,ecrRepositories:$ARGS.positional,builtBy:$b}' \
    --args -- "$@"
}

IMAGES_JSON="[]"
if [[ ${JARS_ONLY} -ne 1 ]]; then
  echo "=== STEP: Build services image (sbt dockerLocal) ==="
  if ! (cd "${REPO_ROOT}" && "${SBT}" -batch dockerLocal); then
    echo "ERROR: sbt dockerLocal failed." >&2
    exit 1
  fi
  if ! "${DOCKER}" tag "scholarchain-services:${BUILD_VERSION}" "${SERVICES_REF}"; then
    echo "ERROR: docker tag scholarchain-services:${BUILD_VERSION} ${SERVICES_REF} failed." >&2
    exit 1
  fi
  echo "=== STEP: Build batch-runner image (docker build) ==="
  if ! (cd "${REPO_ROOT}" && "${DOCKER}" build --platform linux/amd64 \
    --label "org.opencontainers.image.revision=${COMMIT_SHA}" \
    -t "${RUNNER_REF}" docker/batch-runner); then
    echo "ERROR: docker build docker/batch-runner failed." >&2
    exit 1
  fi
  echo "=== STEP: Record images ==="
  # shellcheck disable=SC2086 # SERVICE_REPOS is a fixed word list
  SVC_JSON="$(image_json scholarchain-services "${SERVICES_REF}" "sbt dockerLocal" ${SERVICE_REPOS})" || exit 1
  RUN_JSON="$(image_json scholarchain-batch-runner "${RUNNER_REF}" "docker build docker/batch-runner" batch-runner)" || exit 1
  IMAGES_JSON="$(jq -cn --argjson a "${SVC_JSON}" --argjson b "${RUN_JSON}" '[$a,$b]')"
fi
# null when no image was built (--jars-only), else true only if all are amd64.
FARGATE_COMPATIBLE="$(jq -r 'if length == 0 then null else all(.[]; .fargateCompatible == true) end' <<<"${IMAGES_JSON}")"
if [[ "${FARGATE_COMPATIBLE}" == false ]]; then
  echo "=== STEP: Platform check ==="
  jq -r '.[] | select(.fargateCompatible != true) | "  \(.ref) is \(.os)/\(.architecture); the ECS task definitions run linux/amd64 (X86_64)."' <<<"${IMAGES_JSON}" >&2
  if [[ ${ALLOW_NON_AMD64} -ne 1 ]]; then
    echo "ERROR: built image(s) are not linux/amd64; refusing to record them as a deployable package." >&2
    echo "  The services image platform is configured in build.sbt (build-engineer)." >&2
    echo "  A human may pass --allow-non-amd64 for local-only use (recorded fargateCompatible=false)." >&2
    echo "  No manifest written. Partial output: ${OUT_DIR}" >&2
    exit 6
  fi
  echo "  WARNING: --allow-non-amd64 given; recording fargateCompatible=false. These images cannot run on ECS Fargate." >&2
fi

echo "=== STEP: Verify tree unchanged during build ==="
TREE_FP_AFTER=""
if ! rl_git_inspect "${REPO_ROOT}" || [[ "${RL_COMMIT_SHA}" != "${COMMIT_SHA}" || "${RL_GIT_DIRTY}" != "${GIT_DIRTY}" ]] \
  || ! TREE_FP_AFTER="$(rl_tree_fingerprint "${REPO_ROOT}")" || [[ "${TREE_FP_AFTER}" != "${TREE_FP}" ]]; then
  echo "ERROR: HEAD or the working tree changed during the build; the artifacts cannot be attributed to ${TAG}." >&2
  echo "  No manifest written. Partial output: ${OUT_DIR}" >&2
  exit 5
fi

echo "=== STEP: Write package manifest ==="
MANIFEST="${OUT_DIR}/package-manifest.json"
jq -n --arg created "$(rl_utc_now)" --arg sha "${COMMIT_SHA}" --argjson dirty "${GIT_DIRTY}" \
  --arg fp "${TREE_FP}" --arg tag "${TAG}" --arg ver "${BUILD_VERSION}" --arg os "$(uname -s)" --arg arch "$(uname -m)" \
  --argjson jars "${JARS_JSON}" --argjson images "${IMAGES_JSON}" --argjson fc "${FARGATE_COMPATIBLE}" \
  '{schemaVersion:"scholarchain.package/v1",createdAt:$created,commitSha:$sha,gitDirty:$dirty,treeFingerprint:$fp,
    tag:$tag,buildVersion:$ver,fargateCompatible:$fc,host:{os:$os,arch:$arch},jars:$jars,images:$images,pushed:false,
    provenance:{component:"scripts/package.sh",commit:$sha}}' >"${MANIFEST}.tmp"
mv "${MANIFEST}.tmp" "${MANIFEST}"
chmod a-w "${MANIFEST}"

cat <<EOF

================================================================================
PACKAGE COMPLETE (local only; nothing pushed)$([[ "${FARGATE_COMPATIBLE}" != false ]] || echo " - NOT FARGATE-COMPATIBLE (--allow-non-amd64)")
  Commit:    ${COMMIT_SHA}
  Git dirty: ${GIT_DIRTY}
  Tag:       ${TAG}
  Manifest:  ${MANIFEST#"${REPO_ROOT}"/}
================================================================================
EOF
