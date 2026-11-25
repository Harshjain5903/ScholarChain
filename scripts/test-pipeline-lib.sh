#!/usr/bin/env bash
set -euo pipefail
# Local, offline tests for pipeline-lib.sh, tf-env.sh's ~/.terraformrc check,
# and deploy.sh's Git override/toplevel refusals. No AWS or terraform calls.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/pipeline-lib.sh
source "${SCRIPT_DIR}/pipeline-lib.sh"
FAILS=0
ok() { echo "PASS $1"; }
bad() { echo "FAIL $1"; FAILS=$((FAILS + 1)); }
expect_rc() { # expect_rc <name> <want-rc> <cmd>...
  local name="$1" want="$2" got=0
  shift 2
  "$@" >/dev/null 2>&1 || got=$?
  if [[ "${got}" == "${want}" ]]; then ok "${name}"; else bad "${name} (rc ${got}, want ${want})"; fi
}

expect_rc base-id-valid 0 pipeline_validate_base_id run-20261124T101500Z-0a1b2c3d
expect_rc base-id-bad 2 pipeline_validate_base_id run-1
expect_rc arg-quote-refused 2 pipeline_check_arg 'a"b'
expect_rc arg-backslash-refused 2 pipeline_check_arg 'a\b'
expect_rc step-bad-action 2 pipeline_step_json n BOGUS j a
expect_rc spark-bad-dirty 2 pipeline_spark_args j h d o r t s maybe c

J="$(pipeline_step_json n CONTINUE command-runner.jar a 'x,y' )"
[[ "${J}" == '[{"Type":"CUSTOM_JAR","Name":"n","ActionOnFailure":"CONTINUE","Jar":"command-runner.jar","Args":["a","x,y"]}]' ]] && ok step-json-commas || bad step-json-commas
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${J}" | python3 -c 'import json,sys; json.load(sys.stdin)' && ok step-json-valid || bad step-json-valid
fi

# N65: the ER script is a quoted `bash -c` body; it must pass through the step
# builder (jq --args) and come back byte-identical as the last Args element.
ER_EMR="$(pipeline_er_script s3://a/er.jar s3://c/r/hadoop/export/work_records s3://c/r/er run-1-er run-1-hadoop)"
case "${ER_EMR}" in *'"'*) ok emr-er-script-has-quotes ;; *) bad emr-er-script-has-quotes ;; esac
ERJ="$(pipeline_step_json ScholarChain-EntityResolution-run-1 CONTINUE command-runner.jar bash -c "${ER_EMR}")" && ok emr-er-step-built || bad emr-er-step-built
if command -v jq >/dev/null 2>&1; then
  printf '%s' "${ERJ}" | jq -e . >/dev/null && ok emr-er-step-valid-json || bad emr-er-step-valid-json
  [[ "$(printf '%s' "${ERJ}" | jq -r '.[0].Args[2]')" == "${ER_EMR}" && "$(printf '%s' "${ERJ}" | jq -r '.[0].Args[0]+" "+.[0].Args[1]')" == "bash -c" ]] && ok emr-er-step-roundtrip || bad emr-er-step-roundtrip
  # Arguments that start with - and contain quotes, backslashes and newlines.
  AJ="$(pipeline_step_json n CONTINUE j -Dx=1 --flag 'a"b\c' $'l1\nl2')"
  [[ "$(printf '%s' "${AJ}" | jq -c '.[0].Args')" == '["-Dx=1","--flag","a\"b\\c","l1\nl2"]' ]] && ok emr-step-awkward-args || bad "emr-step-awkward-args: ${AJ}"
fi
if command -v python3 >/dev/null 2>&1; then
  PYJ="$(PATH=/nonexistent-jq:$PATH python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)[0]["Args"][2]))' <<<"${ERJ}")"
  [[ "${PYJ}" == "$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${ER_EMR}")" ]] && ok emr-er-step-python-roundtrip || bad emr-er-step-python-roundtrip
fi

HA="$(pipeline_hadoop_args s3://a/j.jar rid-hadoop 2026-11-24T10:15:00Z graph-export s3://c/1,s3://c/2 s3://c/out | tr '\n' ' ')"
case "${HA}" in
  "hadoop jar s3://a/j.jar edu.scholarchain.hadoop.driver.HadoopJobDriver graph-export -Dscholarchain.pipeline.run-id=rid-hadoop -Dscholarchain.pipeline.schema-version=1.0.0 -Dscholarchain.run.timestamp=2026-11-24T10:15:00Z s3://c/1,s3://c/2 s3://c/out ") ok hadoop-args ;;
  *) bad "hadoop-args: ${HA}" ;;
esac

SA="$(pipeline_spark_args s3://a/s.jar s3://h s3://d s3://o rid-spark 2026-11-24T10:15:00Z abc false s3://ck | tr '\n' ' ')"
case "${SA}" in *--policy-version*) bad spark-no-policy-version ;; *) ok spark-no-policy-version ;; esac
case "${SA}" in *"--class edu.scholarchain.spark.ScholarChainPipeline s3://a/s.jar --hadoop-output s3://h --match-decisions s3://d --output s3://o "*"--run-id rid-spark"*"--checkpoint-dir s3://ck "*) ok spark-args ;; *) bad "spark-args: ${SA}" ;; esac

# N46: ~/.terraformrc keywords. Source only the function from tf-env.sh.
FUNC="$(sed -n '/^tf_env_refuse_default_cli_config() {/,/^}/p' "${SCRIPT_DIR}/tf-env.sh")"
TMPH="$(mktemp -d)"
trap 'rm -rf "${TMPH}"' EXIT
for kw in plugin_cache_dir plugin_cache_may_break_dependency_lock_file provider_installation dev_overrides; do
  printf '%s = "x"\n' "${kw}" >"${TMPH}/.terraformrc"
  got=0
  (HOME="${TMPH}"; eval "${FUNC}"; tf_env_refuse_default_cli_config) >/dev/null 2>&1 || got=$?
  [[ "${got}" == 3 ]] && ok "terraformrc-${kw}" || bad "terraformrc-${kw} (rc ${got})"
done
printf 'credentials "x" {}\n' >"${TMPH}/.terraformrc"
got=0
(HOME="${TMPH}"; eval "${FUNC}"; tf_env_refuse_default_cli_config) >/dev/null 2>&1 || got=$?
[[ "${got}" == 0 ]] && ok terraformrc-clean || bad "terraformrc-clean (rc ${got})"

# N48: deploy.sh refuses GIT_CONFIG_* before touching git. A plan file and
# .meta are required by earlier checks, so supply dummies; the script must
# stop at the Git override refusal (rc 1) and never reach terraform/aws.
echo dummy >"${TMPH}/p.tfplan"
got=0
out="$(GIT_CONFIG_COUNT=0 PATH="${PATH}" /bin/bash "${SCRIPT_DIR}/deploy.sh" dev "${TMPH}/p.tfplan" 2>&1)" || got=$?
if [[ "${got}" != 0 && "${out}" == *"Git override variable(s) set: GIT_CONFIG_COUNT"* ]]; then ok deploy-git-config-refused; else bad "deploy-git-config-refused (rc ${got}): ${out}"; fi

# ---------------------------------------------------------------------------
# ECS Fargate path (batch_compute = "ecs")
# ---------------------------------------------------------------------------
[[ "$(pipeline_s3a 's3://a/x,s3://b/y s3a://c/z')" == "s3a://a/x,s3a://b/y s3a://c/z" ]] && ok s3a-conversion || bad s3a-conversion

R=run-20261124T101500Z-0a1b2c3d
H="s3://scholarchain-dev-curated/${R}/hadoop"
SHA_H=$(printf 'h' | shasum -a 256 | cut -d' ' -f1)
SHA_E=$(printf 'e' | shasum -a 256 | cut -d' ' -f1)
SHA_S=$(printf 's' | shasum -a 256 | cut -d' ' -f1)
SPEC_H="$(pipeline_jar_spec s3://scholarchain-dev-artifacts/jars/h.jar 3sL4kqtJlcpXroDTDmJ.rVSZg_example "${SHA_H}")"
SPEC_E="$(pipeline_jar_spec s3://scholarchain-dev-artifacts/jars/er.jar v2.example-version "${SHA_E}")"
SPEC_S="$(pipeline_jar_spec s3://scholarchain-dev-artifacts/jars/s.jar null "${SHA_S}")"
expect_rc jarspec-empty-version 2 pipeline_jar_spec s3://b/jars/x.jar '' "${SHA_H}"
expect_rc jarspec-bad-sha 2 pipeline_jar_spec s3://b/jars/x.jar v1 abc
expect_rc jarspec-bad-uri 2 pipeline_jar_spec 'http://b/x.jar' v1 "${SHA_H}"
expect_rc jarspec-version-injection 2 pipeline_jar_spec s3://b/jars/x.jar 'v1;rm -rf /' "${SHA_H}"
[[ "$(pipeline_jar_field "${SPEC_H}" bucket)" == scholarchain-dev-artifacts && "$(pipeline_jar_field "${SPEC_H}" key)" == jars/h.jar && "$(pipeline_jar_field "${SPEC_H}" sha256)" == "${SHA_H}" && "$(pipeline_jar_field "${SPEC_H}" version)" == 3sL4kqtJlcpXroDTDmJ.rVSZg_example ]] && ok jarspec-fields || bad jarspec-fields
expect_rc s3-prefix-empty-zero 0 pipeline_s3_prefix_empty 0
expect_rc s3-prefix-nonempty 1 pipeline_s3_prefix_empty 1
expect_rc s3-prefix-error-text-fails-closed 2 pipeline_s3_prefix_empty 'An error occurred (AccessDenied)'
expect_rc s3-prefix-empty-output-fails-closed 2 pipeline_s3_prefix_empty ''
expect_rc s3-prefix-none-fails-closed 2 pipeline_s3_prefix_empty None
[[ "$(pipeline_ecs_capacity_json FARGATE_SPOT)" == '[{"capacityProvider":"FARGATE_SPOT","weight":1}]' ]] && ok capacity-json-spot || bad capacity-json-spot
expect_rc capacity-json-bad 2 pipeline_ecs_capacity_json EC2

HS="$(pipeline_ecs_hadoop_script 3600 "${SPEC_H}" ${R}-hadoop 2026-11-24T10:15:00Z us-east-2 "${H}/export" \
  "dblp-extraction|s3://scholarchain-dev-raw/dblp/dblp.xml|${H}/stages/j01" "graph-export|${H}/j1,${H}/j2|${H}/export")"
bash -n <<<"${HS}" && ok ecs-hadoop-syntax || bad ecs-hadoop-syntax
case "${HS}" in *"-Dmapreduce.framework.name=local"*"s3a://scholarchain-dev-raw/dblp/dblp.xml"*) ok ecs-hadoop-local-s3a ;; *) bad ecs-hadoop-local-s3a ;; esac
HSN="${HS//\\/}" # printf %q may write a comma as \, (same shell meaning)
case "${HSN}" in *"HadoopJobDriver graph-export"*"s3a://scholarchain-dev-curated/${R}/hadoop/j1,s3a://scholarchain-dev-curated/${R}/hadoop/j2"*) ok ecs-hadoop-csv-inputs ;; *) bad ecs-hadoop-csv-inputs ;; esac
case "${HS}" in *"ExportVerifier s3a://scholarchain-dev-curated/${R}/hadoop/export"*) ok ecs-hadoop-verifier ;; *) bad ecs-hadoop-verifier ;; esac
case "${HS}" in *"exit 97"*'hadoop-aws-*.jar'*'aws-java-sdk-bundle-*.jar'*) ok ecs-hadoop-preflight-jars ;; *) bad ecs-hadoop-preflight-jars ;; esac
case "${HS}" in *"need timeout"*"DEADLINE=\$((SECONDS + 3600))"*"bound() {"*) ok ecs-hadoop-deadline ;; *) bad ecs-hadoop-deadline ;; esac
case "${HS}" in *"bound hadoop jar"*"ExportVerifier"*) ok ecs-hadoop-bounded-jobs ;; *) bad ecs-hadoop-bounded-jobs ;; esac
# every hadoop invocation is under bound
UNB="$(printf '%s' "${HS}" | tr ';' '\n' | grep -E '^ *hadoop jar' || true)"
[[ -z "${UNB}" ]] && ok ecs-hadoop-no-unbounded-jobs || bad "ecs-hadoop-no-unbounded-jobs: ${UNB}"
case "${HS}" in *"-Dscholarchain.pipeline.run-id=${R}-hadoop"*) ok ecs-hadoop-run-id ;; *) bad ecs-hadoop-run-id ;; esac
case "${HS}" in *"bound aws s3api get-object --bucket scholarchain-dev-artifacts --key jars/h.jar --version-id 3sL4kqtJlcpXroDTDmJ.rVSZg_example"*'test -s "$W/hadoop.jar"'*"${SHA_H}"*"sha256sum -c"*) ok ecs-hadoop-stages-pinned-jar ;; *) bad "ecs-hadoop-stages-pinned-jar: ${HS}" ;; esac
expect_rc ecs-hadoop-quote-refused 2 pipeline_ecs_hadoop_script 3600 "${SPEC_H}" 'a"b' t us-east-2 e 'j|i|o'
expect_rc ecs-hadoop-bad-timeout 2 pipeline_ecs_hadoop_script abc "${SPEC_H}" r t us-east-2 e 'j|i|o'
expect_rc ecs-hadoop-bad-spec 2 pipeline_ecs_hadoop_script 3600 'junk' r t us-east-2 e 'j|i|o'

ES="$(pipeline_ecs_er_script 3600 "${SPEC_E}" "${H}/export/work_records" "s3://scholarchain-dev-curated/${R}/er" ${R}-er ${R}-hadoop)"
bash -n <<<"${ES}" && ok ecs-er-syntax || bad ecs-er-syntax
case "${ES}" in *"bound aws s3 cp --only-show-errors --recursive s3://scholarchain-dev-curated/${R}/hadoop/export/work_records"*"EntityResolutionRunner"*"--run-id ${R}-er --input-run-id ${R}-hadoop"*) ok ecs-er-args ;; *) bad "ecs-er-args: ${ES}" ;; esac
case "${ES}" in *'--work-records "$W/in/work_records" --output "$W/out"'*) ok ecs-er-local-paths ;; *) bad ecs-er-local-paths ;; esac
case "${ES}" in *"hadoop jar"* | *"hadoop fs"* | *HADOOP_*) bad ecs-er-no-hadoop ;; *) ok ecs-er-no-hadoop ;; esac
case "${ES}" in *"bound java -Xmx"*"EntityResolutionRunner"*"--version-id v2.example-version"*) bad ecs-er-order ;; *) ok ecs-er-order ;; esac
case "${ES}" in *"--version-id v2.example-version"*"${SHA_E}"*"bound java"*) ok ecs-er-pinned-jar-before-run ;; *) bad ecs-er-pinned-jar-before-run ;; esac

SS="$(pipeline_ecs_spark_script 5400 "${SPEC_S}" "${H}/export" "s3://c/${R}/er/match-decisions" "s3://g/${R}/spark" ${R}-spark 2026-11-24T10:15:00Z abc123 false us-east-2)"
bash -n <<<"${SS}" && ok ecs-spark-syntax || bad ecs-spark-syntax
case "${SS}" in *"bound spark-submit"*"DEADLINE"*) bad ecs-spark-bound-order ;; *) ok ecs-spark-bound-order ;; esac
case "${SS}" in *"--version-id null"*"${SHA_S}"*) ok ecs-spark-pinned-null-version ;; *) bad ecs-spark-pinned-null-version ;; esac
case "${SS}" in *'--master "local[*]"'*) ok ecs-spark-local-master ;; *) bad ecs-spark-local-master ;; esac
case "${SS}" in *"--deploy-mode"* | *"--policy-version"*) bad ecs-spark-flags ;; *) ok ecs-spark-flags ;; esac
case "${SS}" in *"--output s3a://g/${R}/spark"*"--run-id ${R}-spark"*"--git-sha abc123 --git-dirty false"*'--checkpoint-dir "$W/ckpt"'*) ok ecs-spark-args ;; *) bad "ecs-spark-args: ${SS}" ;; esac
case "${SS}" in *'aws-java-sdk-bundle-*.jar'*) ok ecs-spark-preflight ;; *) bad ecs-spark-preflight ;; esac
expect_rc ecs-spark-bad-dirty 2 pipeline_ecs_spark_script 3600 "${SPEC_S}" h d o r t s maybe us-east-2

# Fail closed: a task image without the tools stops with 97 before any work.
got=0
env -i PATH=/nonexistent /bin/bash -c "${HS}" >/dev/null 2>&1 || got=$?
[[ "${got}" == 97 ]] && ok ecs-preflight-fails-closed || bad "ecs-preflight-fails-closed (rc ${got})"
# A missing hadoop-aws jar is refused even when the tools exist.
STUB="$(mktemp -d)"
for t in aws java hadoop timeout sha256sum; do printf '#!/bin/sh\nexit 0\n' >"${STUB}/${t}"; chmod +x "${STUB}/${t}"; done
mkdir -p "${STUB}/hh/share/hadoop/tools/lib"
got=0
env -i PATH="${STUB}:/usr/bin:/bin" HADOOP_HOME="${STUB}/hh" SCHOLARCHAIN_TASK_MEMORY_MB=8192 /bin/bash -c "${HS}" >/dev/null 2>&1 || got=$?
[[ "${got}" == 97 ]] && ok ecs-preflight-missing-hadoop-aws || bad "ecs-preflight-missing-hadoop-aws (rc ${got})"
# N66: `bound` really bounds. A stub `timeout` that honours "-k N <secs> cmd"
# lets this run on macOS (no GNU timeout); the deadline logic is the script's.
printf '#!/bin/sh\nshift 2\nsecs="$1"; shift\necho "timeout-called secs=$secs cmd=$*" >&2\nexec "$@"\n' >"${STUB}/timeout"
printf '#!/bin/sh\nexit 0\n' >"${STUB}/aws"
printf '#!/bin/sh\nexec /usr/bin/shasum -a 256 "$@"\n' >"${STUB}/sha256sum"
BS="$(pipeline_ecs_preflight er 3600) bound echo inside-bound"
got=0
out="$(env -i PATH="${STUB}:/usr/bin:/bin" SCHOLARCHAIN_TASK_MEMORY_MB=1024 /bin/bash -c "${BS}" 2>&1)" || got=$?
case "${out}" in *"timeout-called secs="*" cmd=echo inside-bound"*"inside-bound"*) [[ "${got}" == 0 ]] && ok ecs-bound-runs-under-timeout || bad "ecs-bound-runs-under-timeout (rc ${got})" ;; *) bad "ecs-bound-runs-under-timeout: ${out}" ;; esac
BS="$(pipeline_ecs_preflight er 60) sleep 2; DEADLINE=0; bound echo never"
got=0
out="$(env -i PATH="${STUB}:/usr/bin:/bin" SCHOLARCHAIN_TASK_MEMORY_MB=1024 /bin/bash -c "${BS}" 2>&1)" || got=$?
[[ "${got}" == 124 && "${out}" != *never* ]] && ok ecs-bound-deadline-exits-124 || bad "ecs-bound-deadline-exits-124 (rc ${got}): ${out}"
# preflight refuses (97) a missing timeout or sha256sum.
rm -f "${STUB}/timeout"
got=0
env -i PATH="${STUB}:/bin" SCHOLARCHAIN_TASK_MEMORY_MB=1024 /bin/bash -c "$(pipeline_ecs_preflight er 3600)" >/dev/null 2>&1 || got=$?
[[ "${got}" == 97 ]] && ok ecs-preflight-needs-timeout || bad "ecs-preflight-needs-timeout (rc ${got})"
# sha256 mismatch exits 99: stub get-object writes a file whose hash differs.
printf '#!/bin/sh\nfor a in "$@"; do last="$a"; done\nprintf x >"$last"\n' >"${STUB}/aws"
printf '#!/bin/sh\nshift 2\nshift\nexec "$@"\n' >"${STUB}/timeout"; chmod +x "${STUB}/timeout"
SJ="$(pipeline_ecs_preflight er 3600) W=$(mktemp -d); $(pipeline_ecs_stage_jar "${SPEC_E}" er.jar) echo staged-ok"
got=0
out="$(env -i PATH="${STUB}:/usr/bin:/bin" SCHOLARCHAIN_TASK_MEMORY_MB=1024 /bin/bash -c "${SJ}" 2>&1)" || got=$?
[[ "${got}" == 99 && "${out}" == *"sha256 differs"* && "${out}" != *staged-ok* ]] && ok ecs-jar-sha-mismatch-exits-99 || bad "ecs-jar-sha-mismatch-exits-99 (rc ${got}): ${out}"
rm -rf "${STUB}"

if command -v jq >/dev/null 2>&1; then
  OV="$(pipeline_ecs_overrides_json runner "${HS}"$'\n''echo "q" '"'"'x'"'"'')"
  [[ "$(printf '%s' "${OV}" | jq -r '.containerOverrides[0].command[0]')" == "${HS}"$'\n''echo "q" '"'"'x'"'"'' ]] && ok ecs-overrides-roundtrip || bad ecs-overrides-roundtrip
  [[ "$(printf '%s' "${OV}" | jq -r '.containerOverrides[0].name')" == runner ]] && ok ecs-overrides-name || bad ecs-overrides-name
  OVL="$(pipeline_ecs_overrides_json runner "${HS}" | wc -c | tr -d ' ')"
  ((OVL < 8000)) && ok ecs-overrides-under-8192 || bad "ecs-overrides-under-8192 (${OVL})"
  # Worst case: the real seven-job Hadoop script must also fit (ECS limit 8192).
  HSF="$(pipeline_ecs_hadoop_script 86400 "${SPEC_H}" ${R}-hadoop 2026-11-24T10:15:00Z us-east-2 "${H}/export" "dblp-extraction|s3://scholarchain-dev-raw/dblp/dblp.xml|${H}/stages/j01-work_records" "team-extraction|${H}/stages/j01-work_records|${H}/stages/j06-teams" "pair-expansion|${H}/stages/j06-teams|${H}/stages/j07-pair_candidates" "graph-export|${H}/stages/j01-work_records,${H}/stages/j06-teams,${H}/stages/j07-pair_candidates|${H}/export" "identifier-extraction|${H}/stages/j01-work_records|${H}/stages/j03-identifiers" "blocking|${H}/stages/j01-work_records|${H}/stages/j04-blocks" "feature-construction|${H}/stages/j04-blocks|${H}/stages/j05-features")"
  OVF="$(pipeline_ecs_overrides_json runner "${HSF}" | wc -c | tr -d ' ')"
  ((OVF < 8192)) && ok ecs-overrides-full-hadoop-under-8192 || bad "ecs-overrides-full-hadoop-under-8192 (${OVF})"
  NJ="$(pipeline_ecs_network_json subnet-0123456789abcdef0,subnet-0fedcba9876543210 sg-0123456789abcdef0)"
  [[ "$(printf '%s' "${NJ}" | jq -r '.awsvpcConfiguration.assignPublicIp')" == DISABLED && "$(printf '%s' "${NJ}" | jq -r '.awsvpcConfiguration.subnets | length')" == 2 ]] && ok ecs-network-private || bad "ecs-network-private: ${NJ}"
  TJ='{"tasks":[{"taskArn":"arn:aws:ecs:us-east-2:111122223333:task/c/abc","lastStatus":"STOPPED","stopCode":"EssentialContainerExited","stoppedReason":"Essential container in task exited","createdAt":"c","startedAt":"s","stoppedAt":"e","containers":[{"exitCode":0}]}],"failures":[]}'
  [[ "$(printf '%s' "${TJ}" | pipeline_ecs_task_field exit_code)" == 0 && "$(printf '%s' "${TJ}" | pipeline_ecs_task_field status)" == STOPPED && "$(printf '%s' "${TJ}" | pipeline_ecs_task_field failures)" == 0 ]] && ok ecs-task-fields || bad ecs-task-fields
  NOEXIT='{"tasks":[{"lastStatus":"STOPPED","containers":[{"reason":"CannotPullContainerError"}]}],"failures":[]}'
  [[ "$(printf '%s' "${NOEXIT}" | pipeline_ecs_task_field exit_code)" == "" ]] && ok ecs-task-missing-exit-code-is-empty || bad ecs-task-missing-exit-code-is-empty
fi

CL="arn:aws:ecs:us-east-2:111122223333:cluster/scholarchain-dev-cluster"
expect_rc ecs-validate-ok 0 pipeline_ecs_validate scholarchain-dev us-east-2 111122223333 "${CL}" scholarchain-dev-batch-spark subnet-0123456789abcdef0,subnet-0fedcba9876543210 sg-0123456789abcdef0
expect_rc ecs-validate-wrong-cluster 2 pipeline_ecs_validate scholarchain-dev us-east-2 111122223333 "${CL/dev/prod}" scholarchain-dev-batch-spark subnet-0123456789abcdef0 sg-0123456789abcdef0
expect_rc ecs-validate-wrong-family 2 pipeline_ecs_validate scholarchain-dev us-east-2 111122223333 "${CL}" scholarchain-prod-batch-spark subnet-0123456789abcdef0 sg-0123456789abcdef0
expect_rc ecs-validate-bad-subnet 2 pipeline_ecs_validate scholarchain-dev us-east-2 111122223333 "${CL}" scholarchain-dev-batch-er 'subnet-0123456789abcdef0,evil' sg-0123456789abcdef0
expect_rc ecs-validate-bad-sg 2 pipeline_ecs_validate scholarchain-dev us-east-2 111122223333 "${CL}" scholarchain-dev-batch-er subnet-0123456789abcdef0 sg-x
expect_rc ecs-validate-wrong-account 2 pipeline_ecs_validate scholarchain-dev us-east-2 999999999999 "${CL}" scholarchain-dev-batch-er subnet-0123456789abcdef0 sg-0123456789abcdef0

# ---------------------------------------------------------------------------
# run-bounded-pipeline.sh contracts (N66, N74): extracted functions with a stub
# `aws`; no network.
# ---------------------------------------------------------------------------
RBP="${SCRIPT_DIR}/run-bounded-pipeline.sh"
STOPF="$(sed -n '/^stop_ecs_task() {/,/^}/p;/^cancel_emr_step() {/,/^}/p' "${RBP}")"
got=0
out="$( (ECS_CLUSTER=c; CLUSTER_ID=j-1; STOP_FAILED=""; sleep() { :; }; aws() { return 255; }; eval "${STOPF}"; stop_ecs_task arn:t reason) 2>&1)" || got=$?
[[ "${got}" == 1 && "${out}" == *"FAILED TO STOP ECS TASK arn:t"* && "${out}" == *"aws ecs stop-task --cluster c --task arn:t"* ]] && ok stop-task-failure-is-loud || bad "stop-task-failure-is-loud (rc ${got}): ${out}"
got=0
out="$( (ECS_CLUSTER=c; STOP_FAILED=""; sleep() { :; }; aws() { echo "$*" >&2; return 0; }; eval "${STOPF}"; stop_ecs_task arn:t reason) 2>&1)" || got=$?
[[ "${got}" == 0 && "${out}" == *"ecs stop-task --cluster c --task arn:t --reason reason"* ]] && ok stop-task-success || bad "stop-task-success (rc ${got}): ${out}"
got=0
out="$( (CLUSTER_ID=j-1; sleep() { :; }; aws() { echo FAILED; return 0; }; eval "${STOPF}"; cancel_emr_step s-1) 2>&1)" || got=$?
[[ "${got}" == 1 && "${out}" == *"COULD NOT CANCEL EMR STEP s-1"* ]] && ok cancel-steps-failure-is-loud || bad "cancel-steps-failure-is-loud (rc ${got}): ${out}"
got=0
out="$( (CLUSTER_ID=j-1; sleep() { :; }; aws() { echo "$*" >"${TMPH}/aws-args"; echo SUBMITTED; return 0; }; eval "${STOPF}"; cancel_emr_step s-1; cat "${TMPH}/aws-args") 2>&1)" || got=$?
[[ "${got}" == 0 && "${out}" == *"emr cancel-steps --cluster-id j-1 --step-ids s-1 --step-cancellation-option SEND_INTERRUPT"* ]] && ok cancel-steps-success || bad "cancel-steps-success (rc ${got}): ${out}"
# Fail closed on a listing error (N74).
S3F="$(sed -n '/^s3_prefix_must_be_empty() {/,/^}/p' "${RBP}")"
got=0
(TF_ENV_AWS_ACCOUNT=111122223333; aws() { echo "AccessDenied" >&2; return 254; }; eval "${S3F}"; s3_prefix_must_be_empty b p/) >/dev/null 2>&1 || got=$?
[[ "${got}" == 3 ]] && ok prefix-check-listing-error-fails-closed || bad "prefix-check-listing-error-fails-closed (rc ${got})"
got=0
(TF_ENV_AWS_ACCOUNT=111122223333; aws() { echo 0; }; eval "${S3F}"; s3_prefix_must_be_empty b p/) >/dev/null 2>&1 || got=$?
[[ "${got}" == 0 ]] && ok prefix-check-empty-passes || bad "prefix-check-empty-passes (rc ${got})"
got=0
(TF_ENV_AWS_ACCOUNT=111122223333; aws() { echo 1; }; eval "${S3F}"; s3_prefix_must_be_empty b p/) >/dev/null 2>&1 || got=$?
[[ "${got}" == 3 ]] && ok prefix-check-existing-refused || bad "prefix-check-existing-refused (rc ${got})"
# Static contract of the script itself.
grep -q 'tf_env_script_cleanup() {' "${RBP}" && sed -n '/^tf_env_script_cleanup() {/,/^}/p' "${RBP}" | grep -q 'stop_ecs_task "\${CURRENT_TASK_ARN}"' && ok cleanup-stops-current-task || bad cleanup-stops-current-task
sed -n '/^tf_env_script_cleanup() {/,/^}/p' "${RBP}" | grep -q 'cancel_emr_step "\${CURRENT_STEP_ID}"' && ok cleanup-cancels-current-step || bad cleanup-cancels-current-step
grep -q "|| echo 'unknown'" "${RBP}" && bad no-dataset-snapshot-swallow || ok no-dataset-snapshot-swallow
grep -q '2>/dev/null | head -n 1' "${RBP}" && bad no-swallowed-prefix-listing || ok no-swallowed-prefix-listing
grep -q -- '--version-id' "${SCRIPT_DIR}/pipeline-lib.sh" && ok jars-pinned-by-version || bad jars-pinned-by-version

# ---------------------------------------------------------------------------
# Terraform contracts that scripts depend on (static)
# ---------------------------------------------------------------------------
TFROOT="${SCRIPT_DIR}/../infra/terraform"
grep -q '^batch_compute *= *"ecs"' "${TFROOT}/environments/dev.tfvars" && ok dev-batch-compute-ecs || bad dev-batch-compute-ecs
grep -q '^create_budget *= *true' "${TFROOT}/environments/dev.tfvars" && grep -q '^create_budget *= *false' "${TFROOT}/environments/prod.tfvars" && ok budget-toggle-dev-true-prod-false || bad budget-toggle-dev-true-prod-false
grep -q 'command = \["-main", local.embedding_worker_main_class\]' "${TFROOT}/modules/ecs/main.tf" && ok embedding-worker-main-form || bad embedding-worker-main-form
grep -q 'notification_type *= "FORECASTED"' "${TFROOT}/main.tf" && ok budget-forecast-notification || bad budget-forecast-notification

# ---------------------------------------------------------------------------
# Backend verification (us-east-2 bucket, S3 native locking, no lock table)
# ---------------------------------------------------------------------------
BFUNCS="$(sed -n '/^tf_env_hcl_attr() {/,/^}/p;/^tf_env_resolve_state_bucket() {/,/^}/p;/^tf_env_backend_attr() {/,/^}/p;/^tf_env_expected_key() {/,/^}/p;/^tf_env_verify_backend() {/,/^}/p' "${SCRIPT_DIR}/tf-env.sh")"
BC='TF_ENV_STATE_REGION_REQUIRED=us-east-2'
mkbackend() { # mkbackend <file> <bucket> <region> <lock-line> [extra-line]
  printf 'bucket = "%s"\nkey = "scholarchain/dev/terraform.tfstate"\nregion = "%s"\n%s\n%s\n' "$2" "$3" "$4" "${5:-}" >"$1"
}
resolve() { (eval "${BC}; ${BFUNCS}"; unset SCHOLARCHAIN_STATE_BUCKET; [[ -n "${ENVB:-}" ]] && export SCHOLARCHAIN_STATE_BUCKET="${ENVB}"; tf_env_resolve_state_bucket "$1") 2>/dev/null; }
GOODB=scholarchain-tfstate-0a1b2c3d4e5f
mkbackend "${TMPH}/b.hcl" "${GOODB}" us-east-2 'use_lockfile = true'
[[ "$(resolve "${TMPH}/b.hcl")" == "${GOODB}" ]] && ok backend-good || bad backend-good
mkbackend "${TMPH}/b.hcl" scholarchain-tfstate-REPLACE_WITH_BUCKET_SUFFIX us-east-2 'use_lockfile = true'
got=0; resolve "${TMPH}/b.hcl" >/dev/null || got=$?; [[ "${got}" == 3 ]] && ok backend-placeholder-refused || bad "backend-placeholder-refused (${got})"
[[ "$(ENVB="${GOODB}" resolve "${TMPH}/b.hcl")" == "${GOODB}" ]] && ok backend-env-bucket-overrides-placeholder || bad backend-env-bucket-overrides-placeholder
got=0; ENVB='other-bucket' resolve "${TMPH}/b.hcl" >/dev/null || got=$?; [[ "${got}" == 3 ]] && ok backend-env-bucket-malformed-refused || bad "backend-env-bucket-malformed-refused (${got})"
mkbackend "${TMPH}/b.hcl" "${GOODB}" us-east-1 'use_lockfile = true'
got=0; resolve "${TMPH}/b.hcl" >/dev/null || got=$?; [[ "${got}" == 3 ]] && ok backend-wrong-region-refused || bad "backend-wrong-region-refused (${got})"
mkbackend "${TMPH}/b.hcl" "${GOODB}" us-east-2 'use_lockfile = false'
got=0; resolve "${TMPH}/b.hcl" >/dev/null || got=$?; [[ "${got}" == 3 ]] && ok backend-lockfile-false-refused || bad "backend-lockfile-false-refused (${got})"
mkbackend "${TMPH}/b.hcl" "${GOODB}" us-east-2 'use_lockfile = true' 'dynamodb_table = "t"'
got=0; resolve "${TMPH}/b.hcl" >/dev/null || got=$?; [[ "${got}" == 3 ]] && ok backend-dynamodb-table-refused || bad "backend-dynamodb-table-refused (${got})"
REPO_TF="${SCRIPT_DIR}/../infra/terraform/environments"
for e in dev prod; do
  f="${REPO_TF}/${e}.backend.hcl"
  if grep -q '^use_lockfile *= *true' "${f}" && ! grep -q '^dynamodb_table' "${f}" && grep -q '^region *= *"us-east-2"' "${f}" && grep -q "^key *= *\"scholarchain/${e}/terraform.tfstate\"" "${f}"; then ok "committed-backend-${e}"; else bad "committed-backend-${e}"; fi
done

mkstate() { # mkstate <bucket> <key> <region> <use_lockfile json> <ddb json fragment>
  mkdir -p "${TMPH}/tf/.terraform"
  printf '{"backend":{"type":"s3","config":{"bucket":"%s","key":"%s","region":"%s","use_lockfile":%s%s}}}' "$1" "$2" "$3" "$4" "$5" >"${TMPH}/tf/.terraform/terraform.tfstate"
}
verify() { (eval "${BC}; ${BFUNCS}"; TF_DIR="${TMPH}/tf"; TF_ENV_EXPECTED_BUCKET="${GOODB}"; tf_env_verify_backend dev) >/dev/null 2>&1; }
KEY=scholarchain/dev/terraform.tfstate
mkstate "${GOODB}" "${KEY}" us-east-2 true ''
got=0; verify || got=$?; [[ "${got}" == 0 ]] && ok verify-backend-good || bad "verify-backend-good (${got})"
mkstate scholarchain-tfstate-ffffffffffff "${KEY}" us-east-2 true ''
got=0; verify || got=$?; [[ "${got}" == 3 ]] && ok verify-backend-bucket-mismatch || bad "verify-backend-bucket-mismatch (${got})"
mkstate "${GOODB}" scholarchain/prod/terraform.tfstate us-east-2 true ''
got=0; verify || got=$?; [[ "${got}" == 3 ]] && ok verify-backend-key-mismatch || bad "verify-backend-key-mismatch (${got})"
mkstate "${GOODB}" "${KEY}" us-east-1 true ''
got=0; verify || got=$?; [[ "${got}" == 3 ]] && ok verify-backend-region-mismatch || bad "verify-backend-region-mismatch (${got})"
mkstate "${GOODB}" "${KEY}" us-east-2 false ''
got=0; verify || got=$?; [[ "${got}" == 3 ]] && ok verify-backend-no-lockfile || bad "verify-backend-no-lockfile (${got})"
mkstate "${GOODB}" "${KEY}" us-east-2 true ',"dynamodb_table":"locks"'
got=0; verify || got=$?; [[ "${got}" == 3 ]] && ok verify-backend-ddb-table-refused || bad "verify-backend-ddb-table-refused (${got})"

[[ ${FAILS} -eq 0 ]] || { echo "${FAILS} test(s) failed" >&2; exit 1; }
echo "all passed"
