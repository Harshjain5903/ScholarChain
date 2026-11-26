#!/usr/bin/env bash
# pipeline-lib.sh - pure helpers for run-bounded-pipeline.sh (N42).
# Sourced only. No side effects at source time, no AWS calls, no git calls.
# Bash 3.2 compatible.

# Print one run id per stage from a base id: <base>-hadoop|er|spark.
# The base id must be run-<UTC yyyymmddThhmmssZ>-<8 lowercase hex>.
pipeline_validate_base_id() {
  if [[ ! "${1:-}" =~ ^run-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]]; then
    echo "ERROR: base run id '${1:-}' is not run-<UTC timestamp>-<8 hex>." >&2
    return 2
  fi
}

# Refuse an argument that cannot be safely interpolated into a generated shell
# script (ECS path: values go through printf %q after this check). Not used for
# EMR step JSON any more (N65): that is built with jq/python, which escape any
# string, so a quoted `bash -c` script is a legal step argument.
pipeline_check_arg() {
  case "$1" in
    *'"'* | *'\'* | *$'\n'* | *$'\r'* | *$'\t'*)
      echo "ERROR: refusing argument with a quote, backslash or control character." >&2
      return 2
      ;;
  esac
  return 0
}

# pipeline_step_json <name> <action-on-failure> <jar> <arg>...
# Prints one EMR step as a JSON array element (HadoopJarStep). N65: the JSON is
# produced by `jq --args` (python3 fallback), never by string concatenation, so
# arguments containing quotes, backslashes, `$`, `;` or newlines (the ER
# `bash -c` script) are escaped correctly instead of being refused or breaking
# out of the JSON string.
pipeline_step_json() {
  local name="$1" action="$2" jar="$3"
  shift 3
  case "${action}" in
    CONTINUE | CANCEL_AND_WAIT | TERMINATE_CLUSTER) ;;
    *)
      echo "ERROR: invalid ActionOnFailure '${action}'." >&2
      return 2
      ;;
  esac
  if command -v jq >/dev/null 2>&1; then
    jq -cn --arg n "${name}" --arg a "${action}" --arg j "${jar}" \
      '[{Type:"CUSTOM_JAR",Name:$n,ActionOnFailure:$a,Jar:$j,Args:$ARGS.positional}]' --args -- "$@"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; n,a,j=sys.argv[1:4]; print(json.dumps([{"Type":"CUSTOM_JAR","Name":n,"ActionOnFailure":a,"Jar":j,"Args":sys.argv[4:]}],separators=(",",":")))' "${name}" "${action}" "${jar}" "$@"
  else
    echo "ERROR: jq or python3 is required to build EMR step JSON." >&2
    return 2
  fi
}

# Hadoop job step: command-runner.jar -> hadoop jar <jar> <driver class> <job> -D... <in> <out>
# pipeline_hadoop_args <jar-s3-uri> <run-id> <timestamp> <job> <in> <out> [<key>=<value>]
pipeline_hadoop_args() {
  local jar="$1" rid="$2" ts="$3" job="$4" in="$5" out="$6" extra="${7:-}"
  if [[ -n "${extra}" && ! "${extra}" =~ ^[A-Za-z0-9._-]+=[A-Za-z0-9._-]+$ ]]; then
    echo "ERROR: extra -D property must be key=value, got '${extra}'." >&2
    return 2
  fi
  printf '%s\n' hadoop jar "${jar}" edu.scholarchain.hadoop.driver.HadoopJobDriver "${job}" \
    "-Dscholarchain.pipeline.run-id=${rid}" \
    "-Dscholarchain.pipeline.schema-version=1.0.0" \
    "-Dscholarchain.run.timestamp=${ts}"
  if [[ -n "${extra}" ]]; then printf '%s\n' "-D${extra}"; fi
  printf '%s\n' "${in}" "${out}"
}

# pipeline_spark_args <jar> <hadoop-out> <decisions> <out> <run-id> <ts> <sha> <dirty> <ckpt> [orcid-profiles]
# No --policy-version: it is derived from the ER decisions.
pipeline_spark_args() {
  local jar="$1" hout="$2" dec="$3" out="$4" rid="$5" ts="$6" sha="$7" dirty="$8" ckpt="$9" orcid="${10:-}"
  if [[ "${dirty}" != "true" && "${dirty}" != "false" ]]; then
    echo "ERROR: git-dirty must be true or false, got '${dirty}'." >&2
    return 2
  fi
  printf '%s\n' spark-submit --deploy-mode cluster \
    --class edu.scholarchain.spark.ScholarChainPipeline "${jar}" \
    --hadoop-output "${hout}" --match-decisions "${dec}" --output "${out}" \
    --window-start 1900 --window-end 2100 --run-id "${rid}" \
    --partitions 16 --betweenness-samples 32 --betweenness-seed 42 \
    --run-timestamp "${ts}" --git-sha "${sha}" --git-dirty "${dirty}" \
    --checkpoint-dir "${ckpt}"
  if [[ -n "${orcid}" ]]; then printf '%s\n' --orcid-profiles "${orcid}"; fi
}

# ER step body. EntityResolutionRunner reads/writes java.nio local paths only,
# so inputs are staged from S3 to the master's local disk and results copied
# back. Prints the bash -c script text.
# pipeline_er_script <jar-s3-uri> <work-records-s3> <out-s3> <er-run-id> <hadoop-run-id>
pipeline_er_script() {
  local jar="$1" wr="$2" out="$3" rid="$4" hrid="$5"
  printf 'set -euo pipefail; W=/mnt/scholarchain/%s; rm -rf "$W"; mkdir -p "$W/in"; ' "${rid}"
  printf 'hadoop fs -copyToLocal %s "$W/in/work_records"; hadoop fs -copyToLocal %s "$W/er.jar"; ' "${wr}" "${jar}"
  printf 'java -cp "$W/er.jar" edu.scholarchain.entityresolution.EntityResolutionRunner '
  printf -- '--work-records "$W/in/work_records" --output "$W/out" --run-id %s --input-run-id %s; ' "${rid}" "${hrid}"
  printf 'hadoop fs -copyFromLocal "$W/out" %s; rm -rf "$W"' "${out}"
}

################################################################################
# Jar provenance (N68). A "jar spec" is  s3://<bucket>/<key>|<VersionId>|<sha256>
# recorded from head-object plus a download hash by run-bounded-pipeline.sh and
# pinned in the ECS task (aws s3api get-object --version-id, then sha256sum -c).
################################################################################

# pipeline_jar_spec <s3-uri> <version-id> <sha256>: validate and print the spec.
pipeline_jar_spec() {
  local uri="$1" ver="$2" sha="$3"
  if [[ ! "${uri}" =~ ^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]/[A-Za-z0-9._/-]+\.jar$ ]]; then
    echo "ERROR: jar URI '${uri}' is not s3://<bucket>/<key>.jar." >&2
    return 2
  fi
  if [[ ! "${ver}" =~ ^[A-Za-z0-9._-]{1,128}$ ]]; then
    echo "ERROR: jar S3 VersionId '${ver}' is empty or malformed for ${uri}." >&2
    return 2
  fi
  if [[ ! "${sha}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "ERROR: jar sha256 '${sha}' is not 64 lowercase hex characters for ${uri}." >&2
    return 2
  fi
  printf '%s|%s|%s' "${uri}" "${ver}" "${sha}"
}

# pipeline_jar_field <spec> <uri|bucket|key|version|sha256>
pipeline_jar_field() {
  local spec="$1" f="$2" uri ver sha rest
  uri="${spec%%|*}"
  rest="${spec#*|}"
  ver="${rest%%|*}"
  sha="${rest#*|}"
  case "${f}" in
    uri) printf '%s' "${uri}" ;;
    bucket) rest="${uri#s3://}"; printf '%s' "${rest%%/*}" ;;
    key) rest="${uri#s3://}"; printf '%s' "${rest#*/}" ;;
    version) printf '%s' "${ver}" ;;
    sha256) printf '%s' "${sha}" ;;
    *)
      echo "ERROR: unknown jar field '${f}'." >&2
      return 2
      ;;
  esac
}

# pipeline_jar_check <spec>: rc 2 unless the spec is well formed.
pipeline_jar_check() {
  pipeline_jar_spec "$(pipeline_jar_field "$1" uri)" "$(pipeline_jar_field "$1" version)" "$(pipeline_jar_field "$1" sha256)" >/dev/null
}

# pipeline_s3_prefix_empty <KeyCount text>: 0 -> rc 0 (prefix empty), >0 -> rc 1,
# anything else (empty output, error text) -> rc 2. Fail closed (N74): an
# error from the listing is never read as "empty".
pipeline_s3_prefix_empty() {
  case "${1:-}" in
    0) return 0 ;;
    [1-9] | [1-9][0-9]*) return 1 ;;
    *) return 2 ;;
  esac
}

################################################################################
# ECS Fargate path (batch_compute = "ecs"). Same assembly jars, same stage run
# ids, no EMR: Hadoop local mode + s3a, ER on local disk, Spark local[*].
# Each *_script function prints the text of a `bash -c` script that the task
# definition's fixed entry point (bash -c) runs as its command override. Values
# interpolated from the caller go through printf %q after pipeline_check_arg;
# $W / $SCHOLARCHAIN_TASK_MEMORY_MB are expanded INSIDE the container.
################################################################################

# s3://b/k -> s3a://b/k in a (comma-separated) list of URIs.
pipeline_s3a() {
  local v="${1:-}"
  printf '%s' "${v//s3:\/\//s3a://}"
}

# pipeline_ecs_overrides_json <container-name> <script>: run-task --overrides.
# The script is passed to jq/python as an argument, never interpolated.
pipeline_ecs_overrides_json() {
  local name="$1" script="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -cn --arg n "${name}" --arg s "${script}" '{containerOverrides:[{name:$n,command:[$s]}]}'
  else
    python3 -c 'import json,sys; print(json.dumps({"containerOverrides":[{"name":sys.argv[1],"command":[sys.argv[2]]}]},separators=(",",":")))' "${name}" "${script}"
  fi
}

# pipeline_ecs_network_json <subnet-csv> <security-group>: awsvpc config, never a public IP.
pipeline_ecs_network_json() {
  local subnets="$1" sg="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -cn --arg s "${subnets}" --arg g "${sg}" \
      '{awsvpcConfiguration:{subnets:($s|split(",")),securityGroups:[$g],assignPublicIp:"DISABLED"}}'
  else
    python3 -c 'import json,sys; print(json.dumps({"awsvpcConfiguration":{"subnets":sys.argv[1].split(","),"securityGroups":[sys.argv[2]],"assignPublicIp":"DISABLED"}},separators=(",",":")))' "${subnets}" "${sg}"
  fi
}

# pipeline_ecs_validate <prefix> <region> <account> <cluster-arn> <family> <subnet-csv> <sg>
# Refuses (rc 2) any value from Terraform outputs that is not shaped like this
# environment's own resources, before anything is run.
pipeline_ecs_validate() {
  local prefix="$1" region="$2" account="$3" cluster="$4" family="$5" subnets="$6" sg="$7" s
  if [[ "${cluster}" != "arn:aws:ecs:${region}:${account}:cluster/${prefix}-cluster" ]]; then
    echo "ERROR: ECS cluster '${cluster}' is not ${prefix}-cluster in ${account}/${region}." >&2
    return 2
  fi
  if [[ ! "${family}" =~ ^${prefix}-batch-(hadoop|er|spark)$ ]]; then
    echo "ERROR: task definition family '${family}' is not ${prefix}-batch-<hadoop|er|spark>." >&2
    return 2
  fi
  if [[ ! "${sg}" =~ ^sg-[0-9a-f]{8,17}$ ]]; then
    echo "ERROR: security group '${sg}' is not an sg- id." >&2
    return 2
  fi
  if [[ -z "${subnets}" ]]; then
    echo "ERROR: no subnet ids." >&2
    return 2
  fi
  local IFS=','
  for s in ${subnets}; do
    if [[ ! "${s}" =~ ^subnet-[0-9a-f]{8,17}$ ]]; then
      echo "ERROR: subnet '${s}' is not a subnet- id." >&2
      return 2
    fi
  done
  return 0
}

# Preflight text shared by every stage. Fails closed (exit 97) when the image
# lacks a tool or, for the Hadoop and Spark stages, the hadoop-aws and AWS SDK
# bundle jars (the assemblies mark Hadoop/Spark Provided and do not carry
# hadoop-aws, so the image must).
#
# N66: the stage is bounded INSIDE the container, independently of the client
# that started it. `bound <cmd>...` runs a command under `timeout -k 30` with
# whatever is left of the stage deadline (pipeline timeout in seconds); once the
# deadline has passed the task exits 124. If the client dies, the task still
# ends by itself.
# pipeline_ecs_preflight <hadoop|er|spark> <timeout-seconds>
pipeline_ecs_preflight() {
  local stage="$1" tmo="${2:-}"
  if [[ ! "${tmo}" =~ ^[1-9][0-9]{1,5}$ ]]; then
    echo "ERROR: in-container timeout '${tmo}' is not a positive number of seconds." >&2
    return 2
  fi
  printf '%s' 'set -euo pipefail; '
  printf '%s' 'need() { command -v "$1" >/dev/null 2>&1 || { echo "preflight: $1 not found in image" >&2; exit 97; }; }; '
  printf '%s' 'needjar() { f="$(ls $1 2>/dev/null | head -n 1 || true)"; [ -n "$f" ] || { echo "preflight: no jar matching $1 in image" >&2; exit 97; }; echo "preflight: $f"; }; '
  printf '%s' 'need timeout; need sha256sum; need aws; need java; : "${SCHOLARCHAIN_TASK_MEMORY_MB:?}"; HEAP=$((SCHOLARCHAIN_TASK_MEMORY_MB * 75 / 100)); '
  printf 'DEADLINE=$((SECONDS + %s)); ' "${tmo}"
  printf '%s' 'bound() { local rem=$((DEADLINE - SECONDS)); [ "$rem" -gt 0 ] || { echo "stage deadline exceeded" >&2; exit 124; }; timeout -k 30 "$rem" "$@"; }; '
  case "${stage}" in
    hadoop)
      printf '%s' 'need hadoop; : "${HADOOP_HOME:?}"; T="$HADOOP_HOME/share/hadoop/tools/lib"; '
      printf '%s' 'needjar "$T/hadoop-aws-*.jar"; needjar "$T/aws-java-sdk-bundle-*.jar"; '
      ;;
    spark)
      printf '%s' 'need spark-submit; : "${SPARK_HOME:?}"; '
      printf '%s' 'needjar "$SPARK_HOME/jars/hadoop-aws-*.jar"; needjar "$SPARK_HOME/jars/aws-java-sdk-bundle-*.jar"; '
      ;;
    er) ;;
    *)
      echo "ERROR: unknown stage '${stage}'." >&2
      return 2
      ;;
  esac
}

# Stage a jar from S3 into $W at the EXACT recorded object version and require
# its sha256 to equal the recorded one (N68). Needs `bound` (preflight).
# pipeline_ecs_stage_jar <jar-spec> <local-name>
pipeline_ecs_stage_jar() {
  local spec="$1" name="$2"
  pipeline_check_arg "${name}" || return 2
  printf 'bound aws s3api get-object --bucket %q --key %q --version-id %q "$W/%s" >/dev/null; ' \
    "$(pipeline_jar_field "${spec}" bucket)" "$(pipeline_jar_field "${spec}" key)" "$(pipeline_jar_field "${spec}" version)" "${name}"
  printf 'test -s "$W/%s"; ' "${name}"
  printf 'printf "%%s  %%s\\n" %q "$W/%s" | sha256sum -c - >/dev/null || { echo "jar %s: sha256 differs from the recorded value" >&2; exit 99; }; ' \
    "$(pipeline_jar_field "${spec}" sha256)" "${name}" "${name}"
}

# Hadoop stage: every job, then ExportVerifier, in one task, JVM local mode.
# pipeline_ecs_hadoop_script <timeout-s> <jar-spec> <run-id> <timestamp> <region> <export-dir-s3> <spec>...
# <spec> = "<job>|<input-s3-csv>|<output-s3>[|<key>=<value>]"; the optional
# fourth field is one extra -D property for that job (e.g. the J02 normalizers'
# scholarchain.input.layout=json-lines).
pipeline_ecs_hadoop_script() {
  local tmo="$1" jar="$2" rid="$3" ts="$4" region="$5" export="$6" spec job in out extra a
  shift 6
  for a in "${rid}" "${ts}" "${region}" "${export}" "$@"; do
    pipeline_check_arg "${a}" || return 2
  done
  pipeline_jar_check "${jar}" || return 2
  pipeline_ecs_preflight hadoop "${tmo}" || return 2
  printf 'W=/work/%q; rm -rf "$W"; mkdir -p "$W/tmp" "$W/conf"; ' "${rid}"
  pipeline_ecs_stage_jar "${jar}" hadoop.jar
  # Private conf dir: s3a region and local scratch for every tool (the jobs and
  # ExportVerifier alike), and local MapReduce.
  printf "printf '%%s' '<configuration><property><name>fs.s3a.endpoint.region</name><value>%s</value></property><property><name>hadoop.tmp.dir</name><value>/work/%s/tmp</value></property><property><name>mapreduce.framework.name</name><value>local</value></property></configuration>' > \"\$W/conf/core-site.xml\"; export HADOOP_CONF_DIR=\"\$W/conf\"; " "${region}" "${rid}"
  printf '%s' 'export HADOOP_CLASSPATH="$(ls $T/hadoop-aws-*.jar | head -n 1):$(ls $T/aws-java-sdk-bundle-*.jar | head -n 1)"; export HADOOP_HEAPSIZE_MAX="${HEAP}m"; '
  for spec in "$@"; do
    extra=""
    IFS='|' read -r job in out extra <<<"${spec}"
    if [[ -n "${extra}" && ! "${extra}" =~ ^[A-Za-z0-9._-]+=[A-Za-z0-9._-]+$ ]]; then
      echo "ERROR: extra -D property must be key=value (letters, digits, . _ -), got '${extra}'." >&2
      return 2
    fi
    printf 'bound hadoop jar "$W/hadoop.jar" edu.scholarchain.hadoop.driver.HadoopJobDriver %q ' "${job}"
    printf -- '-Dmapreduce.framework.name=local '
    if [[ -n "${extra}" ]]; then printf -- '-D%q ' "${extra}"; fi
    printf -- '-Dscholarchain.pipeline.run-id=%q -Dscholarchain.pipeline.schema-version=1.0.0 -Dscholarchain.run.timestamp=%q ' "${rid}" "${ts}"
    printf '%q %q; ' "$(pipeline_s3a "${in}")" "$(pipeline_s3a "${out}")"
  done
  printf -- 'bound hadoop jar "$W/hadoop.jar" edu.scholarchain.hadoop.driver.ExportVerifier %q; ' "$(pipeline_s3a "${export}")"
  printf '%s' 'rm -rf "$W"'
}

# ER stage: EntityResolutionRunner reads/writes local paths only, so stage
# from S3 with `aws s3 cp` and copy results back.
# pipeline_ecs_er_script <timeout-s> <jar-spec> <work-records-s3> <out-s3> <er-run-id> <hadoop-run-id>
pipeline_ecs_er_script() {
  local tmo="$1" jar="$2" wr="$3" out="$4" rid="$5" hrid="$6" a
  for a in "${wr}" "${out}" "${rid}" "${hrid}"; do
    pipeline_check_arg "${a}" || return 2
  done
  pipeline_jar_check "${jar}" || return 2
  pipeline_ecs_preflight er "${tmo}" || return 2
  printf 'W=/work/%q; rm -rf "$W"; mkdir -p "$W/in"; ' "${rid}"
  pipeline_ecs_stage_jar "${jar}" er.jar
  printf 'bound aws s3 cp --only-show-errors --recursive %q "$W/in/work_records"; ' "${wr}"
  printf '%s' 'test -n "$(ls -A "$W/in/work_records")" || { echo "no work_records staged" >&2; exit 98; }; '
  printf '%s' 'bound java -Xmx${HEAP}m -cp "$W/er.jar" edu.scholarchain.entityresolution.EntityResolutionRunner '
  printf -- '--work-records "$W/in/work_records" --output "$W/out" --run-id %q --input-run-id %q; ' "${rid}" "${hrid}"
  printf 'test -d "$W/out"; bound aws s3 cp --only-show-errors --recursive "$W/out" %q; rm -rf "$W"' "${out}"
}

# Spark stage: --master local[*], checkpoints on the task's local disk, S3 via
# s3a. No --policy-version (derived from the ER decisions).
# pipeline_ecs_spark_script <timeout-s> <jar-spec> <hadoop-out-s3> <decisions-s3> <out-s3> <run-id> <ts> <sha> <dirty> <region> [orcid-profiles-s3]
# The optional last argument passes the J02b ORCID output as --orcid-profiles.
pipeline_ecs_spark_script() {
  local tmo="$1" jar="$2" hout="$3" dec="$4" out="$5" rid="$6" ts="$7" sha="$8" dirty="$9" region="${10}" orcid="${11:-}" a
  if [[ "${dirty}" != "true" && "${dirty}" != "false" ]]; then
    echo "ERROR: git-dirty must be true or false, got '${dirty}'." >&2
    return 2
  fi
  for a in "${hout}" "${dec}" "${out}" "${rid}" "${ts}" "${sha}" "${region}" "${orcid}"; do
    pipeline_check_arg "${a}" || return 2
  done
  pipeline_jar_check "${jar}" || return 2
  pipeline_ecs_preflight spark "${tmo}" || return 2
  printf 'W=/work/%q; rm -rf "$W"; mkdir -p "$W/tmp" "$W/ckpt"; ' "${rid}"
  pipeline_ecs_stage_jar "${jar}" spark.jar
  printf '%s' 'bound spark-submit --master "local[*]" --driver-memory "${HEAP}m" --conf spark.local.dir="$W/tmp" '
  printf -- '--conf spark.hadoop.fs.s3a.endpoint.region=%q ' "${region}"
  printf '%s' '--class edu.scholarchain.spark.ScholarChainPipeline "$W/spark.jar" '
  printf -- '--hadoop-output %q --match-decisions %q --output %q ' \
    "$(pipeline_s3a "${hout}")" "$(pipeline_s3a "${dec}")" "$(pipeline_s3a "${out}")"
  printf -- '--window-start 1900 --window-end 2100 --run-id %q ' "${rid}"
  printf -- '--partitions 16 --betweenness-samples 32 --betweenness-seed 42 '
  printf -- '--run-timestamp %q --git-sha %q --git-dirty %q ' "${ts}" "${sha}" "${dirty}"
  if [[ -n "${orcid}" ]]; then printf -- '--orcid-profiles %q ' "$(pipeline_s3a "${orcid}")"; fi
  printf '%s' '--checkpoint-dir "$W/ckpt"; rm -rf "$W"'
}

# pipeline_ecs_capacity_json <FARGATE_SPOT|FARGATE>: run-task --capacity-provider-strategy.
pipeline_ecs_capacity_json() {
  case "$1" in
    FARGATE_SPOT | FARGATE) ;;
    *)
      echo "ERROR: unknown capacity provider '$1'." >&2
      return 2
      ;;
  esac
  printf '[{"capacityProvider":"%s","weight":1}]' "$1"
}

# Print "<field>" of the first task in `aws ecs describe-tasks/run-task` JSON on stdin.
# Fields: arn status exit_code stop_code stopped_reason created_at started_at stopped_at failures capacity_provider failure_reasons
pipeline_ecs_task_field() {
  local f="$1" q
  case "${f}" in
    arn) q='.tasks[0].taskArn // ""' ;;
    status) q='.tasks[0].lastStatus // ""' ;;
    exit_code) q='(.tasks[0].containers[0].exitCode // "") | tostring' ;;
    stop_code) q='.tasks[0].stopCode // ""' ;;
    stopped_reason) q='.tasks[0].stoppedReason // ""' ;;
    created_at) q='.tasks[0].createdAt // ""' ;;
    started_at) q='.tasks[0].startedAt // ""' ;;
    stopped_at) q='.tasks[0].stoppedAt // ""' ;;
    failures) q='(.failures // []) | length' ;;
    failure_reasons) q='[(.failures // [])[] | (.reason // "unknown")] | join(",")' ;;
    capacity_provider) q='.tasks[0].capacityProviderName // ""' ;;
    *)
      echo "ERROR: unknown task field '${f}'." >&2
      return 2
      ;;
  esac
  jq -r "${q}"
}
