# ScholarChain

**Author:** `<student name>`, `<university email>` (the human architect fills these in)

Status legend used throughout this file:

- **VERIFIED**: observed in this repository or in archived evidence, with a link.
- **PENDING**: the work is not done yet.
- **NOT YET RUN**: the command exists but nobody has run it against the named target.

Every number in this README comes from a linked evidence file. Nothing is estimated.

---

## Problem statement, architecture and agentic workflow

### Problem statement

Bibliographic sources describe the same researchers and papers in different ways. DBLP, Crossref, OpenAlex and ORCID each spell names differently, use different identifiers and cover different parts of the literature. ScholarChain merges these sources into one temporal, weighted coauthorship knowledge graph. It does this in four steps:

1. Hadoop MapReduce jobs normalize the records.
2. An entity-resolution stage decides which author mentions belong to the same researcher. Each decision is explicit and can be audited.
3. Spark computes graph analytics: components, communities, bridges, k-core, shortest paths and temporal windows.
4. A Graph-RAG query layer answers natural-language collaboration questions. Every claim in an answer is tied to graph evidence.

Every record carries provenance back to its source dataset, its transformation step and the component that produced it ([CLAUDE.md](CLAUDE.md), "Provenance required").

### Architecture summary

Full diagrams are in [docs/architecture.md](docs/architecture.md), and the data lineage is in [docs/data-lineage.md](docs/data-lineage.md).

- **Build:** sbt multi-project build ([build.sbt](build.sbt)) with seven modules. Each module may depend only on modules above it:
  `core` → `hadoop`, `entity-resolution`, `spark`, `storage` → `rag` → `services`.
- **Batch path:**
  1. DBLP XML goes through Hadoop jobs J01–J08 (`modules/hadoop`).
  2. `EntityResolutionRunner` (`modules/entity-resolution`) writes MatchDecision JSON lines.
  3. `ScholarChainPipeline` (`modules/spark`) writes Parquet analytics and the Neptune openCypher bulk-load CSV.
- **Serving path:** the REST routes in `modules/services` call the runtime workflow in `modules/rag`. That workflow reads through the storage adapters in `modules/storage` for S3, Neptune, DynamoDB and S3 Vectors.
- **Cloud target:** defined only in Terraform under [infra/terraform/](infra/terraform/main.tf). It covers VPC, S3, DynamoDB, S3 Vectors, EMR, Neptune, ECS Fargate behind an ALB, ECR, Step Functions, SQS, CloudWatch and IAM. **Nothing has been deployed (PENDING).**

### Agentic workflow summary

The repository is built by Claude Code subagents. The rules are:

- There are 18 agents. Each owns exactly one artifact class ([docs/agents.md](docs/agents.md)).
- A human architect orchestrates the work. Subagents never invoke one another; all routing goes through the orchestrator.
- Each phase is recorded under [docs/agent-runs/](docs/agent-runs/README.md) as a set of files: the work order (`prompt.md`), the agent report, the test-engineer report, the code-reviewer report, repair records, and a human gate decision.
- Hooks in [.claude/settings.json](.claude/settings.json) enforce three rules:
  - destructive commands are blocked before they run;
  - Scala files are formatted after every edit;
  - an agent cannot stop with modified Scala sources unless `sbt check` has refreshed the test marker.

---

## Required local software

| Tool | Version | Source of truth |
|---|---|---|
| Java (JDK) | 17. Archived runs used OpenJDK 17.0.21 ([phase-02 test report](docs/agent-runs/phase-02/test-report.md)). | Evidence files |
| sbt | 1.10.6 | [project/build.properties](project/build.properties) |
| Scala | 3.3.4 | `V.scala3` in [project/Dependencies.scala](project/Dependencies.scala) |
| sbt plugins | sbt-scalafmt 2.5.2, sbt-native-packager 1.10.4, sbt-assembly 2.3.0 | [project/plugins.sbt](project/plugins.sbt) |
| Hadoop / Spark (Provided scope) | Hadoop 3.3.6, Spark 3.5.3 | [project/Dependencies.scala](project/Dependencies.scala) |
| Terraform | 1.17.2 was observed. The configuration accepts `>= 1.9.0, < 2.0.0`. | [infra/terraform/versions.tf](infra/terraform/versions.tf), [phase-12 review](docs/agent-runs/phase-12/review-report.md) |
| AWS provider | `hashicorp/aws` = 6.26.0, exact pin, lock file committed | [infra/terraform/versions.tf](infra/terraform/versions.tf), [.terraform.lock.hcl](infra/terraform/.terraform.lock.hcl) |
| Python | System Python 3 at `/usr/bin/python3`, run with `-I -B`. The manifest notes that the pyenv 3.8.13 sha256 is broken. | [datasets/dataset-manifest.yaml](datasets/dataset-manifest.yaml) |
| AWS CLI v2, `jq`, `curl`, bash | Required by `scripts/*.sh`. No version is pinned in the repository. | [scripts/](scripts/plan.sh) |
| Claude Code | `<Claude Code version>` (the human fills this in) | – |
| Docker | Needed only for `sbt dockerLocal`. Base image `eclipse-temurin:21.0.5_11-jre-jammy`. | [build.sbt](build.sbt) |

---

## AWS prerequisites and environment variables

### AWS prerequisites (PENDING; nothing has been provisioned)

Before any cloud step, you need:

- An AWS account and a CLI profile with valid credentials. The scripts call `aws sts get-caller-identity`.
- The AWS CLI region must equal `aws_region` in `infra/terraform/environments/<env>.tfvars`. In the committed tfvars this is `us-east-2`.
- An S3 state bucket matching [environments/dev.backend.hcl](infra/terraform/environments/dev.backend.hcl) (or the prod file). The state key must be `scholarchain/<env>/terraform.tfstate`.
- Access to the Bedrock models named in the tfvars (for example `amazon.titan-embed-text-v2:0`).
- Human approval of account, region, cost and plan before any `apply` ([docs/agents.md](docs/agents.md), escalation rules).

### Environment variable names

Names only. Never commit values. Secrets come only from AWS Secrets Manager. IAM grants `secretsmanager:GetSecretValue` on `scholarchain/<env>/*`.

**Read by `scripts/*.sh`** (found with `grep -ohE '\$\{[A-Z][A-Z0-9_]+:[-=?]' scripts/*.sh` and a manual read):

| Name | Used by | Meaning |
|---|---|---|
| `ALB_INGRESS_CIDRS` | `plan.sh` (required) | Comma-separated IPv4 CIDRs from /16 to /32 that may reach the ALB on HTTP/80 |
| `PLAN_SCOPE` | `plan.sh` | `full` (default) or `ecr-bootstrap` |
| `IMAGE_TAG` | `plan.sh` | A 40-hex Git SHA. Defaults to `HEAD`. |
| `AWS_REGION`, `AWS_DEFAULT_REGION` | `tf-env.sh` | Must equal the environment's `aws_region` |
| `CONFIRM_CLEANUP` | `cleanup.sh` (required) | Must be `yes` |
| `ARCHIVE_MAX_AGE_HOURS` | `cleanup.sh` | Maximum age of the evidence archive, 1–168 (default 24) |
| `HOME`, `TMPDIR` | `tf-env.sh`, `smoke-test.sh` | Used to check `~/.terraformrc` and to hold the per-run temp dir |
| `TF_ENV_LOCK_TOKEN` | `tf-env.sh` | Set internally and inherited by child scripts. Do not set it by hand. |

The scripts **refuse to run** when any of these are exported: `TF_WORKSPACE`, `TF_DATA_DIR`, `TF_CLI_ARGS*`, `TF_VAR_*`, `TF_CLI_CONFIG_FILE`, `TERRAFORM_CONFIG`, `TF_REATTACH_PROVIDERS`, `TF_PLUGIN_CACHE_DIR`, `TF_PLUGIN_CACHE_MAY_BREAK_DEPENDENCY_LOCK_FILE`, `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE`, `GIT_COMMON_DIR`, `GIT_OBJECT_DIRECTORY`, `GIT_ALTERNATE_OBJECT_DIRECTORIES`.

**Read by the config loaders under `modules/*/src/main`:**

| Name | Where |
|---|---|
| `SCHOLARCHAIN_NEPTUNE_ENDPOINT`, `SCHOLARCHAIN_NEPTUNE_PORT`, `SCHOLARCHAIN_NEPTUNE_REGION` | [core reference.conf](modules/core/src/main/resources/reference.conf), [storage reference.conf](modules/storage/src/main/resources/reference.conf) |
| `SCHOLARCHAIN_NEPTUNE_QUERY_TIMEOUT_MS`, `SCHOLARCHAIN_DYNAMODB_REGION`, `SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX`, `SCHOLARCHAIN_EMBEDDING_QUEUE_URL` | storage `reference.conf` |
| `SCHOLARCHAIN_ER_BLOCKING_KEY_FIELDS`, `SCHOLARCHAIN_ER_MATCH_THRESHOLD`, `SCHOLARCHAIN_ER_REJECT_THRESHOLD`, `SCHOLARCHAIN_ER_POLICY_VERSION` | core `reference.conf` |
| `SCHOLARCHAIN_RETRIEVAL_TOP_K`, `SCHOLARCHAIN_RETRIEVAL_MAX_HOPS`, `SCHOLARCHAIN_RETRIEVAL_MIN_SCORE` | core `reference.conf` |
| `SCHOLARCHAIN_EMBEDDING_MODEL_ID`, `SCHOLARCHAIN_GENERATION_MODEL_ID`, `SCHOLARCHAIN_MODELS_REGION` | core `reference.conf` |
| `SCHOLARCHAIN_MAX_CONCURRENCY`, `SCHOLARCHAIN_TIMEOUT_SECONDS`, `SCHOLARCHAIN_RETRY_COUNT` | core `reference.conf` |
| `PORT`, `HOST`, `SYNC_DEADLINE_MS`, `MAX_REQUEST_BODY_BYTES`, `MAX_GRAPH_DEPTH`, `MAX_GRAPH_LIMIT` | [ServiceConfig.scala](modules/services/src/main/scala/edu/scholarchain/services/ServiceConfig.scala) |
| `SCHOLARCHAIN_GIT_SHA`, `SCHOLARCHAIN_GIT_DIRTY` | Spark run manifest fallback when `--git-sha`/`--git-dirty` are absent ([PipelineConfig.scala](modules/spark/src/main/scala/edu/scholarchain/spark/PipelineConfig.scala)) |

**Read by `datasets/scripts`:**

| Name | Where |
|---|---|
| `SCHOLARCHAIN_CONTACT_EMAIL` | [enrich_fetch.py](datasets/scripts/enrich_fetch.py). It is sent only in the User-Agent and never logged. Needed only with `--confirm-network`. |

**Read by Claude Code configuration:**

| Name | Where |
|---|---|
| `GITHUB_TOKEN`, `AWS_PROFILE`, `AWS_REGION` | [.mcp.json](.mcp.json) |
| `CLAUDE_AGENT` | [guard-dangerous.sh](.claude/hooks/guard-dangerous.sh), which uses it to protect constitutional files |

---

## Build, test and validation commands

Run every command from the repository root.

| Purpose | Command |
|---|---|
| Format all Scala sources | `sbt -batch scalafmtAll` (alias `fmt`) |
| Format when the scalafmt cache is stale. Use this when `check` reports files that `scalafmtAll` did not change. | `sbt -batch clean scalafmtAll` |
| Compile, including test sources | `sbt -batch Test/compile` |
| Run all tests | `sbt -batch test` |
| **Definition of done.** Runs format check, compile, tests and `markTestRun`, which writes `.claude/.last-test-run`. | `sbt -batch check` |
| Build the Hadoop and Spark assembly jars | `sbt -batch packageJobs` (runs `hadoop/assembly` and `sparkAnalytics/assembly`) |
| Build the services image locally (NOT YET RUN) | `sbt -batch dockerLocal` |
| Dataset script unit tests | `/usr/bin/python3 -I -B -m unittest discover -s datasets/scripts/tests -p 'test_*.py'` |
| Dataset manifest validation | `/usr/bin/python3 -I -B datasets/scripts/validate_manifest.py` |

Aliases are defined at the end of [build.sbt](build.sbt). The scalafmt config sets `project.git = true`, so files that Git does not track yet are not format-checked.

The latest archived results are in [phase-12/test-report.md](docs/agent-runs/phase-12/test-report.md):

| Command | Recorded result |
|---|---|
| `sbt -batch check` (row 41) | Passed, 828 tests per pass |
| Dataset unit tests (rows 35–36) | 55 tests OK |
| `validate_manifest.py` (rows 35–36) | `status=PASS pass=106 fail=0 skip=0` |

---

## Bounded local real-data run (Hadoop → entity resolution → Spark)

### Getting the data

Raw and derived data are **not in Git**. [.gitignore](.gitignore) excludes `datasets/raw/`, `datasets/derived/`, `datasets/cache/` and `local-runs/`. Two options:

**Without network.** Use the committed 50-record CC0 DBLP sample [datasets/samples/dblp/dblp-sample-50.xml](datasets/samples/dblp/dblp-sample-50.xml). The full chain below was replayed on this sample while writing this README. Every step exited 0.

**Full snapshot.** This is a human-approved network download of about 1.1 GB:

```bash
bash datasets/scripts/download-dblp.sh --i-understand-full-download --snapshot-date 2026-11-16
/usr/bin/python3 -I -B datasets/scripts/dblp_select.py \
  --raw-dir datasets/raw/dblp/snapshot=2026-11-16 --out-dir datasets/derived/dblp
```

`dblp_select.py` writes `datasets/derived/dblp/selection-10k.xml` and `selection-50k.xml`. Their checksums are in the [manifest](datasets/dataset-manifest.yaml).

For the enrichment sources (Crossref, OpenAlex, ORCID), use [enrich_fetch.py](datasets/scripts/enrich_fetch.py). It is a dry run unless you pass `--confirm-network`, and it needs `SCHOLARCHAIN_CONTACT_EMAIL`. The recorded per-source status is in the manifest.

### Entry points

| Stage | Main class | Arguments |
|---|---|---|
| Hadoop | `edu.scholarchain.hadoop.driver.HadoopJobDriver` | `<job-name> [-D key=value ...] <input> <output>` |
| Hadoop export check | `edu.scholarchain.hadoop.driver.ExportVerifier` | `<export-dir>`. Prints a JSON report and exits 1 on any failure. |
| Entity resolution | `edu.scholarchain.entityresolution.EntityResolutionRunner` | `--work-records <dir> --output <dir> --run-id <id> [--input-run-id <id>] [--label <text>] [--max-block-size <n>]` |
| Spark | `edu.scholarchain.spark.ScholarChainPipeline` | See the flag list below. |

**`HadoopJobDriver` job names:** `dblp-extraction` (J01), `openalex-normalization` (J02a), `orcid-normalization` (J02b), `crossref-normalization` (J02c), `identifier-extraction` (J03), `blocking` (J04), `feature-construction` (J05), `team-extraction` (J06), `pair-expansion` (J07), `graph-export` (J08).

**`HadoopJobDriver` run-level `-D` options:**

- `scholarchain.pipeline.run-id`
- `scholarchain.pipeline.schema-version`
- `scholarchain.run.timestamp`: fixed ISO-8601. Reruns are byte-reproducible when it is set.
- `scholarchain.counters.output`: local path for the counters JSON.

**`ScholarChainPipeline` flags** (from [PipelineConfig.scala](modules/spark/src/main/scala/edu/scholarchain/spark/PipelineConfig.scala); each flag takes exactly one value; unknown or repeated flags are errors):

- **Required:** `--hadoop-output <uri>`, `--match-decisions <uri>`, `--output <uri>`
- **Optional, with defaults:**
  - `--window-start` 1900, `--window-end` 2100
  - `--run-id` (generated when absent)
  - `--partitions` 200
  - `--betweenness-samples` 50, `--max-betweenness-graph-size` 100000, `--betweenness-seed`
  - `--checkpoint-dir` (default `<output>/_checkpoints`), `--pregel-checkpoint-interval` 10, `--keep-checkpoints` false
  - `--master`, `--run-timestamp`
  - `--path-pairs src|tgt,...`, `--path-pairs-file`, `--path-sample-count` 5, `--max-path-hops` 10
  - `--temporal-window-size` 5, `--temporal-step` 5
  - `--neptune-csv` true
  - `--schema-version` 1.0.0
  - `--policy-version`: derived from the decisions; if given, it must equal the derived value
  - `--kcore-max-iterations`, `--kcore-checkpoint-interval`, `--lpa-max-iterations`
  - `--allow-unlinked-er-input` false
  - `--git-sha`, `--git-dirty`
  - `--input-checksums` true

### Commands

The Hadoop and Spark dependencies are `Provided`, so run the stages from the module's sbt **Compile** classpath. `sbt run` will not work for them. The stage layout matches [run-bounded-pipeline.sh](scripts/run-bounded-pipeline.sh). Give each stage its own run ID. Pass the Hadoop run ID to entity resolution as `--input-run-id`; Spark refuses unlinked decisions by default.

```bash
OUT=local-runs/real-small/<run-name>              # gitignored
IN=datasets/samples/dblp/dblp-sample-50.xml       # or datasets/derived/dblp/selection-10k.xml
TS=2026-11-23T00:00:00Z
HRID=<run-name>-hadoop; ERID=<run-name>-er; SRID=<run-name>-spark

HCP=$(sbt -batch -error "export hadoop/Compile/fullClasspath")
ECP=$(sbt -batch -error "export entityResolution/Runtime/fullClasspath")
SCP=$(sbt -batch -error "export sparkAnalytics/Compile/fullClasspath")

hj() { java -cp "$HCP" edu.scholarchain.hadoop.driver.HadoopJobDriver "$1" \
  -D mapreduce.framework.name=local -D fs.defaultFS=file:/// \
  -D scholarchain.pipeline.run-id="$HRID" -D scholarchain.run.timestamp="$TS" \
  -D scholarchain.counters.output="$OUT/logs/$1.json" "$2" "$3"; }

hj dblp-extraction       "$IN"                     "$OUT/stages/j01-work_records"
hj team-extraction       "$OUT/stages/j01-work_records" "$OUT/stages/j06-teams"
hj pair-expansion        "$OUT/stages/j06-teams"   "$OUT/stages/j07-pair_candidates"
hj graph-export          "$OUT/stages/j01-work_records,$OUT/stages/j06-teams,$OUT/stages/j07-pair_candidates" "$OUT/export"
hj identifier-extraction "$OUT/stages/j01-work_records" "$OUT/stages/j03-identifiers"
hj blocking              "$OUT/stages/j01-work_records" "$OUT/stages/j04-blocks"
hj feature-construction  "$OUT/stages/j04-blocks"  "$OUT/stages/j05-features"
java -cp "$HCP" edu.scholarchain.hadoop.driver.ExportVerifier "$OUT/export"

java -cp "$ECP" edu.scholarchain.entityresolution.EntityResolutionRunner \
  --work-records "$OUT/export/work_records" --output "$OUT/er" \
  --run-id "$ERID" --input-run-id "$HRID"

java --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
     --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp "$SCP" edu.scholarchain.spark.ScholarChainPipeline \
  --hadoop-output "file://$PWD/$OUT/export" \
  --match-decisions "file://$PWD/$OUT/er/match-decisions" \
  --output "file://$PWD/$OUT/spark" \
  --run-id "$SRID" --master 'local[*]' --partitions 4 --run-timestamp "$TS"
```

The `--add-opens` flags are the ones [build.sbt](build.sbt) sets for Spark tests on Java 17.

Outputs:

- **Hadoop export:** `export/{work_records,teams,pair_candidates,author_paper}`
- **Entity resolution:** `er/match-decisions/part-00000`, `er/review-queue.json`, `er/RUN-SUMMARY.json`
- **Spark:** `spark/{edges,metrics,summaries,paths,components,communities,temporal,manifest,run-stats}` and `spark/neptune/{nodes/{researcher,work},relationships/{coauthor,authored}}`

See [docs/data-lineage.md](docs/data-lineage.md) for details.

**Archived real-data result.** The 500-record DBLP development slice is recorded in [repair-08.md §4.2](docs/agent-runs/phase-12/repair-08.md) and in [phase-12 test report](docs/agent-runs/phase-12/test-report.md) rows 37–40. That run used sbt class directories from an uncommitted tree (finding N55). It is a development slice, not an approved scale.

---

## Terraform and cloud operations

All cloud commands are **NOT YET RUN**. `terraform apply` and `terraform destroy` are denied to every agent by [.claude/settings.json](.claude/settings.json) and [guard-dangerous.sh](.claude/hooks/guard-dangerous.sh). Only a human, or `deploy-engineer` at a human's direction, runs `deploy.sh` and `cleanup.sh`.

### Local static checks

These need no AWS. They were recorded passing in the [phase-12 test report](docs/agent-runs/phase-12/test-report.md), rows 27–28.

```bash
(cd infra/terraform && terraform fmt -check -recursive)
(cd infra/terraform && terraform init -backend=false -input=false -lockfile=readonly && terraform validate)
for f in scripts/*.sh; do /bin/bash -n "$f"; done
```

### Plan

`plan.sh` runs `terraform fmt -check`, `init` (per-environment backend, verified state key), `validate` and `plan`. It refuses to run on a dirty Git tree and never applies.

```bash
ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh dev
# first deployment of an environment (ECR repositories only):
PLAN_SCOPE=ecr-bootstrap ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh dev
```

The plan and its `.meta` file (checksum, account, region, commit) are written to `local-runs/plans/<env>/`.

### Deploy, smoke test, bounded pipeline, rollback, evidence, cleanup

| Script | Usage, as defined in the script | What it does |
|---|---|---|
| [deploy.sh](scripts/deploy.sh) | `./scripts/deploy.sh <dev\|prod> <plan-file>`, e.g. `./scripts/deploy.sh dev local-runs/plans/dev/dev.tfplan` | Checks the plan against its metadata, the clean tree at the planned commit, account and region. Requires all six ECR images before applying (except for an ECR bootstrap plan). Asks for typed confirmation, applies the saved plan, then runs `smoke-test.sh`. |
| [smoke-test.sh](scripts/smoke-test.sh) | `./scripts/smoke-test.sh <dev\|prod>` | Reads the ALB DNS name from verified state only. Tests: `GET /healthz`, `GET /readyz`, `POST /v1/query`, `GET /v1/evidence/<id>`, idempotent re-POST, and 400 on a body without `question`. |
| [run-bounded-pipeline.sh](scripts/run-bounded-pipeline.sh) | `./scripts/run-bounded-pipeline.sh <dev\|prod> [timeout-minutes]` (default 60, maximum 1440) | Submits EMR steps: Hadoop J01, J06, J07, J08, J03, J04, J05, then ExportVerifier, entity resolution and Spark. Each stage gets its own run ID. Requires `scholarchain-hadoop.jar`, `scholarchain-entity-resolution.jar` and `scholarchain-spark.jar` in `s3://<prefix>-artifacts/jars/`. |
| [rollback.sh](scripts/rollback.sh) | `./scripts/rollback.sh <dev\|prod>` | Rolls ECS services back to the previous task-definition revision, with a 300 s timeout per service. |
| [archive-evidence.sh](scripts/archive-evidence.sh) | `./scripts/archive-evidence.sh <dev\|prod>` | Read-only export to `local-runs/evidence/<env>/<UTC>/` with `SHA256SUMS` and an `ARCHIVE_COMPLETE` marker. |
| [cleanup.sh](scripts/cleanup.sh) | `CONFIRM_CLEANUP=yes ./scripts/cleanup.sh <dev\|prod>` | Runs 10 phases. It requires a recent complete evidence archive, builds and checks a destroy plan before deleting anything, and asks for two typed confirmations. The logs bucket is retained. |

`deploy.sh`, `run-bounded-pipeline.sh` and `cleanup.sh` had uncommitted changes when this README was written, and `scripts/pipeline-lib.sh` was untracked. Re-read the scripts before you run them.

---

## Reproducing the agentic workflow

1. **Clone and check out the tag.**
   ```bash
   git clone <repository-url> scholarchain && cd scholarchain
   git checkout <release-tag>      # PENDING: no Git tag exists yet
   ```
2. **Install tools.** Install the versions in "Required local software".
3. **Set environment variables.** "AWS prerequisites and environment variables" lists the names. Local, sbt-only and dataset-validation steps need none. Set `SCHOLARCHAIN_CONTACT_EMAIL` only for network dataset fetches, and the AWS variables only for cloud steps.
4. **Start Claude Code from the repository root.** Run `claude`. It then loads [CLAUDE.md](CLAUDE.md), which imports [docs/agents.md](docs/agents.md), and also loads [.claude/settings.json](.claude/settings.json) and [.mcp.json](.mcp.json).
5. **Confirm that the agents and hooks are loaded.**
   - Run `/agents`. It should list the 18 agents in [.claude/agents/](.claude/agents/factory-engineer.md).
   - Run `/hooks`. It should show:
     - PreToolUse `Bash` → `.claude/hooks/guard-dangerous.sh`
     - PostToolUse `Write|Edit` → `.claude/hooks/format-scala.sh`
     - Stop → `.claude/hooks/verify-tests-ran.sh`
6. **Run the agent validation script and a dangerous-command probe.**
   ```bash
   bash .claude/hooks/validate-agent-files.sh      # expect: VALIDATION PASSED: all checks OK.
   printf '{"tool_name":"Bash","tool_input":{"command":"echo terraform destroy"}}' \
     | .claude/hooks/guard-dangerous.sh; echo "rc=$?"   # expect: BLOCKED: terraform apply/destroy ... rc=2
   ```
   Then, inside `claude`, ask: *"Run `echo 'terraform destroy'`."* The PreToolUse hook should block it with the same `BLOCKED:` message. Nothing is destroyed, because the probe only echoes text. Both checks were run while writing this README: the validation passed, and the probe returned rc=2 with the `BLOCKED` message.
7. **Replay a phase in a fresh session.** Paste the work order from `docs/agent-runs/phase-NN/prompt.md` (phases 00–11 have one) into a new `claude` session.
8. **Run the test-engineer and code-reviewer gates.**
   - *"Use the test-engineer agent to run `sbt -batch check` and report exact pass/fail results."*
   - *"Use the code-reviewer agent to review the diff of this phase and return APPROVE or REQUEST_CHANGES with an owning agent per finding."*
9. **Route BLOCKED-ON findings.** For every report with `STATUS: BLOCKED-ON`, or every review finding, send the item to the agent named in `BLOCKED-ON OWNER` or in the finding, using the ownership table in [docs/agents.md](docs/agents.md). Subagents never call each other. Escalation items go to the human: constitutional, security, apply/destroy, deploy, cost and data destruction.
10. **Compare the result with the archived evidence.** Check against `agent-report.md`, `test-report.md`, `review-report.md` and `human-gate.md` in the same phase folder, and against the accepted commit in `commit.txt`.
11. **Start a fresh session for each phase.** This keeps context from one phase out of the next. Record the human decision in a new `human-gate.md`.

### Bounded reproduction exercise (no cloud)

This exercise replays Phase 02 (sbt build and repository skeleton). It needs no AWS and no datasets.

```bash
git switch -c replay-phase-02 102595d^        # parent of the accepted Phase 02 commit (09d728f)
claude                                         # fresh session at the repo root
#   paste the work order from docs/agent-runs/phase-02/prompt.md
sbt -batch check                               # after the agent reports COMPLETE
git diff --stat 102595d                        # compare with the accepted commit
```

Expected comparison points:

- [phase-02/test-report.md](docs/agent-runs/phase-02/test-report.md) records `sbt -batch check` exit 0 on `102595d066251346a1e34be1d0db82c3766fa40b`.
- [phase-02/human-gate.md](docs/agent-runs/phase-02/human-gate.md) records ACCEPTED, with code-reviewer APPROVE.

Model output is not deterministic. Compare structure (modules, aliases, pinned ledger, `check` result), not byte equality. This exercise has **NOT YET RUN** as a documented replay.

---

## Agent catalog

The rows come from the frontmatter and bodies of [.claude/agents/](.claude/agents/factory-engineer.md). Every agent reads [CLAUDE.md](CLAUDE.md) and [docs/agents.md](docs/agents.md) first.

**Report fields.** 16 agents use the same 14-field report contract: `STATUS`, `OWNER`, `WORK ORDER`, `FILES CREATED`, `FILES MODIFIED`, `COMMANDS RUN`, `TEST RESULTS`, `VALIDATION RESULTS`, `ASSUMPTIONS`, `RISKS`, `MANUAL ACTION REQUIRED`, `BLOCKED-ON OWNER`, `BLOCKED-ON EVIDENCE`, `NEXT GATE`. Two agents extend it:

- `code-reviewer` adds `VERDICT` and `FINDINGS` (16 fields).
- `incident-responder` adds `INCIDENT ID`, `TIMELINE`, `ROOT CAUSE`, `RECOVERY` and `DATA INTEGRITY` (19 fields).

| Agent | Purpose | Owned paths | Tool fence | Prerequisites | Example orchestrator instruction | Verification command | Report fields |
|---|---|---|---|---|---|---|---|
| [factory-engineer](.claude/agents/factory-engineer.md) | Agent system: subagents, hooks, permissions, MCP | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/*`, `.claude/hooks/*`, `.claude/commands/*`, `.claude/settings.json`, `.mcp.json`, `docs/agent-runs/README.md` | Read, Grep, Glob, Write, Edit, Bash | Human ratification and session restart for every change | "Use the factory-engineer agent to add a hook that …" | `bash .claude/hooks/validate-agent-files.sh` | 14-field contract |
| [build-engineer](.claude/agents/build-engineer.md) | sbt multi-project build, formatting, packaging | `build.sbt`, `project/*`, `.scalafmt.conf`, `docker-compose*.yml`, `.gitignore` | Read, Grep, Glob, Write, Edit, Bash | – | "Use the build-engineer agent to add an assembly for entityResolution." | `sbt -batch check` | 14-field contract |
| [domain-engineer](.claude/agents/domain-engineer.md) | Shared domain records, stable IDs, serialization, config contracts | `modules/core/**` | Read, Grep, Glob, Write, Edit, Bash | – | "Use the domain-engineer agent to add a field to Provenance." | `sbt -batch core/test` then `sbt -batch check` | 14-field contract |
| [dataset-engineer](.claude/agents/dataset-engineer.md) | Dataset manifest, download/sampling scripts, schemas, licences | `datasets/**` | Read, Grep, Glob, Write, Edit, Bash | Human approval for network fetches | "Use the dataset-engineer agent to reconcile the Crossref retry list." | `/usr/bin/python3 -I -B datasets/scripts/validate_manifest.py` | 14-field contract |
| [hadoop-engineer](.claude/agents/hadoop-engineer.md) | MapReduce jobs J01–J08, counters, reject formats | `modules/hadoop/**` | Read, Grep, Glob, Write, Edit, Bash | `core` contracts | "Use the hadoop-engineer agent to fix the work-ID prefix once the human decides N50." | `sbt -batch hadoop/test`; `ExportVerifier <export-dir>` | 14-field contract |
| [entity-resolution-engineer](.claude/agents/entity-resolution-engineer.md) | Blocking, match features, thresholds, calibration | `modules/entity-resolution/**` | Read, Grep, Glob, Write, Edit, Bash | Gold set (PENDING) | "Use the entity-resolution-engineer agent to evaluate the mention policy against a gold set." | `sbt -batch entityResolution/test` | 14-field contract |
| [spark-engineer](.claude/agents/spark-engineer.md) | Graph construction, analytics, evidence paths | `modules/spark/**` | Read, Grep, Glob, Write, Edit, Bash | Hadoop export and ER decisions | "Use the spark-engineer agent to add an output invariant check." | `sbt -batch sparkAnalytics/test` | 14-field contract |
| [storage-engineer](.claude/agents/storage-engineer.md) | S3, Neptune, DynamoDB, S3 Vectors adapters and loaders | `modules/storage/**` | Read, Grep, Glob, Write, Edit, Bash | `core` contracts | "Use the storage-engineer agent to add a Neptune bulk-load status poller." | `sbt -batch storage/test` | 14-field contract |
| [rag-engineer](.claude/agents/rag-engineer.md) | Query plans, retrieval, evidence, prompts, claim verification | `modules/rag/**`, `prompts/runtime/**`, `prompts/schemas/**` | Read, Grep, Glob, Write, Edit, Bash | Storage ports | "Use the rag-engineer agent to add a groundedness check to ClaimVerificationAgent." | `sbt -batch rag/test` | 14-field contract |
| [service-engineer](.claude/agents/service-engineer.md) | REST models, routes, health, runtime composition | `modules/services/**` | Read, Grep, Glob, Write, Edit, Bash | RAG workflow port | "Use the service-engineer agent to move query results to shared storage." | `sbt -batch services/test` | 14-field contract |
| [test-engineer](.claude/agents/test-engineer.md) | Adversarial and cross-module tests; never edits production code | `tests/**` and designated test trees | Read, Grep, Glob, Write, Edit, Bash; `model: sonnet` | Code under test committed or staged | "Use the test-engineer agent to run `sbt -batch check` and report exact results." | `sbt -batch check` | 14-field contract |
| [code-reviewer](.claude/agents/code-reviewer.md) | Read-only review, APPROVE / REQUEST_CHANGES | None (reports only) | Read, Grep, Glob, Bash (no Write/Edit) | A diff or file list | "Use the code-reviewer agent to review the phase diff." | `git diff --stat` (read-only) | 14 fields + `VERDICT`, `FINDINGS` |
| [infra-engineer](.claude/agents/infra-engineer.md) | Terraform and operational scripts; never applies | `infra/terraform/**`, `scripts/*.sh`, `.github/**` | Read, Grep, Glob, Write, Edit, Bash | – | "Use the infra-engineer agent to add an ER assembly upload to the pipeline script." | `terraform fmt -check -recursive`; `bash -n scripts/*.sh` | 14-field contract |
| [deploy-engineer](.claude/agents/deploy-engineer.md) | Executes approved plans through approved scripts | Deployment execution reports, release records | Read, Grep, Glob, Bash | Human-verified plan checksum, account, region, cost; clean tree; `sbt check` passed | "Use the deploy-engineer agent to deploy the approved dev plan." | `./scripts/smoke-test.sh <env>` (NOT YET RUN) | 14-field contract |
| [experiment-engineer](.claude/agents/experiment-engineer.md) | Experiment manifests, raw results, load tests | `experiments/**`, `load-tests/**` | Read, Grep, Glob, Write, Edit, Bash | Committed experiment manifest | "Use the experiment-engineer agent to commit the manifest for experiment A." | PENDING (no experiment exists) | 14-field contract |
| [documentation-engineer](.claude/agents/documentation-engineer.md) | README, docs, final report, demo script | `README.md`, `docs/**` (except `docs/agents.md`, `docs/agent-runs/**`, `docs/incidents/**`), `reports/**` | Read, Grep, Glob, Write, Edit, Bash | Evidence to cite | "Use the documentation-engineer agent to update the README deliverable index." | Relative-link check (see "Link check") | 14-field contract |
| [incident-responder](.claude/agents/incident-responder.md) | Diagnosis reports, approved bounded recovery | `docs/incidents/**` | Read, Grep, Glob, Write, Edit, Bash | Human notified; human approval for data, security or destructive actions | "Use the incident-responder agent to diagnose the failed smoke test." | Human review of the report | 14 fields + `INCIDENT ID`, `TIMELINE`, `ROOT CAUSE`, `RECOVERY`, `DATA INTEGRITY` |
| [dependency-updater](.claude/agents/dependency-updater.md) | Version bumps in the dependency ledger | Version values in `project/Dependencies.scala` | Read, Grep, Glob, Write, Edit, Bash | Human approval for major upgrades | "Use the dependency-updater agent to apply the logback patch release." | `sbt -batch check` | 14-field contract |

Project commands in [.claude/commands/](.claude/commands/deploy.md) (`deploy`, `rollback`, `run-pipeline`, `experiment`, `incident`) route to `deploy-engineer`, `experiment-engineer` and `incident-responder`, each with stated gates.

---

## API query examples

Routes are registered in [Routes.scala](modules/services/src/main/scala/edu/scholarchain/services/Routes.scala), and request models are in [JsonCodecs.scala](modules/services/src/main/scala/edu/scholarchain/services/JsonCodecs.scala).

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/query` | Body `QueryRequest`. Only `question` is required. Optional fields: `constraints`, `analysis`, `retrieval`, `output`. An optional `Idempotency-Key` header is supported. |
| GET | `/v1/jobs/{jobId}` | |
| GET | `/v1/evidence/{evidenceId}` | |
| GET | `/v1/graph/nodes/{nodeId}/neighbors?depth=&limit=&edgeTypes=` | `depth` 1..`MAX_GRAPH_DEPTH` (default 3); `limit` 1..`MAX_GRAPH_LIMIT` (default 200) |
| GET | `/v1/explain/{queryId}` | |
| GET | `/healthz`, `/readyz` | |

### Running the server locally

`ScholarChainServer` wires **stub** components (`RuntimeComposer.composeStubs()`), so local answers are placeholders and not graph results. Start it like this:

```bash
CP=$(sbt -batch -error "export services/Runtime/fullClasspath")
PORT=8080 HOST=127.0.0.1 java -cp "$CP" edu.scholarchain.services.scholarChainServer
```

### Example requests

```bash
B=http://127.0.0.1:8080
curl -s $B/healthz                                   # {"status":"ok"}
curl -s $B/readyz                                    # {"status":"ready"}
curl -s -X POST $B/v1/query \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"question":"Who are the most frequent collaborators of researcher X since 2015?","constraints":{"yearFrom":2015}}'
curl -s "$B/v1/graph/nodes/<nodeId>/neighbors?depth=1&limit=10"
curl -s $B/v1/explain/<queryId>
curl -s $B/v1/evidence/<evidenceId>
curl -s -X POST $B/v1/query -d '{}'                  # 400 BAD_REQUEST
```

### Observed responses (local stub, while writing this README)

| Request | Status | Response |
|---|---|---|
| `/healthz` | 200 | `{"status":"ok"}` |
| `/readyz` | 200 | `{"status":"ready"}` |
| `POST /v1/query` | 200 | `{"queryId":"q-…","mode":"synchronous","answer":{"summary":"Stub answer for: …","researchers":[]},"claims":[],"links":{"evidence":"/v1/evidence/q-…","explain":"/v1/explain/q-…"}}` |
| neighbors | 200 | `[]` |
| `/v1/explain/<queryId>` | 404 | `NOT_FOUND` |
| `/v1/jobs/<queryId>` | 404 | `NOT_FOUND` |
| `POST /v1/query` with `{}` | 400 | `BAD_REQUEST` |

On AWS, the ALB sends these paths to the `query-api` service: `/v1/query*`, `/v1/jobs*`, `/v1/explain*`, `/v1/evidence*`, `/healthz` and `/readyz`. It sends `/v1/graph*` to `graph-retriever` ([ecs/main.tf](infra/terraform/modules/ecs/main.tf)). **NOT YET RUN.**

---

## Experiments, final report and video

| Item | Status |
|---|---|
| Experiments A–F (manifests, raw results) | **PENDING.** [experiments/](experiments/.gitkeep) and [load-tests/](load-tests/.gitkeep) contain only `.gitkeep`. |
| Final report | **PENDING.** [reports/](reports/.gitkeep) contains only `.gitkeep`. |
| Video / demo | **PENDING.** `<video URL>` |

---

## Known limitations

- **No AWS deployment yet.** No `terraform plan` against AWS, `apply`, deploy, smoke test, EMR run or cleanup has been run ([phase-12 test report](docs/agent-runs/phase-12/test-report.md), "Results — cloud (not run)").
- **Entity resolution is uncalibrated, and there is no gold set yet.**
  - The policy version is `er-mention-v1-uncalibrated`, and LLM adjudication is disabled in `EntityResolutionRunner`.
  - On the 500-record slice, 13 of the 19 cross-paper persons in the ground truth are still split (finding N64, [phase-12 review](docs/agent-runs/phase-12/review-report.md)).
  - `GoldSetEvaluator` exists, but no gold set has been committed.
- **Enrichment datasets are fetched but not yet joined in the bounded run.**
  - Crossref is FETCHED-WITH-GAPS and OpenAlex is FETCHED-WITH-8-TRUNCATED-PAGES. ORCID is PENDING human fetch ([manifest](datasets/dataset-manifest.yaml)).
  - Normalization jobs J02a–c exist, but neither the local chain nor `run-bounded-pipeline.sh` runs them. The bounded run uses DBLP only.
- **Work-ID doubled prefix (N50) is pending a human decision.** Work IDs carry `work:dblp:dblp:…`. The local sample replay shows the same prefix, for example `work:dblp:dblp:journals/corr/abs-2603-21466`.
- **The entity-resolution assembly jar is missing.** `run-bounded-pipeline.sh` requires `scholarchain-entity-resolution.jar`, which does not exist yet. The Hadoop assembly has no `Main-Class` (N42 follow-up).
- **Outputs J03–J05 are not consumed downstream.** `EntityResolutionRunner` blocks mentions itself from `work_records`.
- **The local server runs on stubs.** The production composition against Neptune, S3 Vectors and Bedrock is not exercised locally.
- **Encryption and secrets.**
  - S3 buckets use `aws:kms` with no customer-managed key, and the S3 Vectors bucket uses SSE-S3.
  - No `aws_kms_key` or `aws_secretsmanager_*` resource is defined in Terraform. IAM only grants `GetSecretValue` on `scholarchain/<env>/*`.
- **Scale.** The 10K, 50K and 500-record runs are development runs. No reduced-final-scale approval exists.

---

## Deliverable index (D01–D20)

The course specification that defines deliverables D01–D20 is **not in this repository**, so no D-number can be matched to an artifact from repository evidence. Each row stays PENDING until the human architect fills in the title and location from the course specification.

| ID | Deliverable (from course specification) | Location | Status |
|---|---|---|---|
| D01 | `<fill from course specification>` | PENDING | PENDING |
| D02 | `<fill from course specification>` | PENDING | PENDING |
| D03 | `<fill from course specification>` | PENDING | PENDING |
| D04 | `<fill from course specification>` | PENDING | PENDING |
| D05 | `<fill from course specification>` | PENDING | PENDING |
| D06 | `<fill from course specification>` | PENDING | PENDING |
| D07 | `<fill from course specification>` | PENDING | PENDING |
| D08 | `<fill from course specification>` | PENDING | PENDING |
| D09 | `<fill from course specification>` | PENDING | PENDING |
| D10 | `<fill from course specification>` | PENDING | PENDING |
| D11 | `<fill from course specification>` | PENDING | PENDING |
| D12 | `<fill from course specification>` | PENDING | PENDING |
| D13 | `<fill from course specification>` | PENDING | PENDING |
| D14 | `<fill from course specification>` | PENDING | PENDING |
| D15 | `<fill from course specification>` | PENDING | PENDING |
| D16 | `<fill from course specification>` | PENDING | PENDING |
| D17 | `<fill from course specification>` | PENDING | PENDING |
| D18 | `<fill from course specification>` | PENDING | PENDING |
| D19 | `<fill from course specification>` | PENDING | PENDING |
| D20 | `<fill from course specification>` | PENDING | PENDING |

### Existing artifacts available for mapping

| Artifact | Path |
|---|---|
| Agent constitution and ownership map | [CLAUDE.md](CLAUDE.md), [docs/agents.md](docs/agents.md) |
| Subagent definitions | [.claude/agents/](.claude/agents/factory-engineer.md) |
| Hooks and permissions | [.claude/hooks/guard-dangerous.sh](.claude/hooks/guard-dangerous.sh), [.claude/hooks/format-scala.sh](.claude/hooks/format-scala.sh), [.claude/hooks/verify-tests-ran.sh](.claude/hooks/verify-tests-ran.sh), [.claude/hooks/validate-agent-files.sh](.claude/hooks/validate-agent-files.sh), [.claude/settings.json](.claude/settings.json) |
| MCP configuration | [.mcp.json](.mcp.json) |
| Project commands | [.claude/commands/deploy.md](.claude/commands/deploy.md), [rollback.md](.claude/commands/rollback.md), [run-pipeline.md](.claude/commands/run-pipeline.md), [experiment.md](.claude/commands/experiment.md), [incident.md](.claude/commands/incident.md) |
| Phase evidence | [docs/agent-runs/README.md](docs/agent-runs/README.md), phases 00–12 |
| Build definition | [build.sbt](build.sbt), [project/Dependencies.scala](project/Dependencies.scala) |
| Dataset manifest and scripts | [datasets/dataset-manifest.yaml](datasets/dataset-manifest.yaml), [datasets/scripts/validate_manifest.py](datasets/scripts/validate_manifest.py) |
| Hadoop jobs | [HadoopJobDriver.scala](modules/hadoop/src/main/scala/edu/scholarchain/hadoop/driver/HadoopJobDriver.scala) |
| Entity resolution | [EntityResolutionRunner.scala](modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/EntityResolutionRunner.scala) |
| Spark analytics | [ScholarChainPipeline.scala](modules/spark/src/main/scala/edu/scholarchain/spark/ScholarChainPipeline.scala) |
| Storage adapters | [NeptuneClient.scala](modules/storage/src/main/scala/edu/scholarchain/storage/NeptuneClient.scala), [S3VectorsClient.scala](modules/storage/src/main/scala/edu/scholarchain/storage/S3VectorsClient.scala) |
| RAG runtime and prompts | [RuntimeWorkflow.scala](modules/rag/src/main/scala/edu/scholarchain/rag/RuntimeWorkflow.scala), [prompts/runtime/synthesis-v1.txt](prompts/runtime/synthesis-v1.txt), [prompts/schemas/answer-claim-v1.json](prompts/schemas/answer-claim-v1.json) |
| REST service | [Routes.scala](modules/services/src/main/scala/edu/scholarchain/services/Routes.scala) |
| Terraform | [infra/terraform/main.tf](infra/terraform/main.tf) |
| Operational scripts | [scripts/plan.sh](scripts/plan.sh), [scripts/deploy.sh](scripts/deploy.sh), [scripts/smoke-test.sh](scripts/smoke-test.sh), [scripts/run-bounded-pipeline.sh](scripts/run-bounded-pipeline.sh), [scripts/rollback.sh](scripts/rollback.sh), [scripts/archive-evidence.sh](scripts/archive-evidence.sh), [scripts/cleanup.sh](scripts/cleanup.sh) |
| Architecture and lineage docs | [docs/architecture.md](docs/architecture.md), [docs/data-lineage.md](docs/data-lineage.md) |
| Experiments, final report, video, threat model, runbook, service limits, demo script | PENDING |

### Link check

Every relative link in this README is checked with a small script. It extracts the target of each Markdown link, drops `http(s)`, `mailto:` and `#anchor` links, strips any `#fragment`, and tests that the path exists relative to the repository root.
