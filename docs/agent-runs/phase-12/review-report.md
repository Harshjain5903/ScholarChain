# Phase 12 — Review report (code-reviewer, read-only)

Scope: Phase 12 repair-01 and repair-02 (Terraform, IAM, scripts/cleanup.sh) on branch `remediation`, working tree (infra/terraform untracked). Assembled verbatim by the orchestrator from the code-reviewer final report; scratchpad paths shortened to `<session-scratchpad>`. Earlier passes: none (Phase 12 had no prior recorded review).

## Pass 1 — 2026-11-22 — VERDICT: REQUEST_CHANGES

The S3 Vectors work itself is sound. Everything validates against provider 6.26.0, all five granted `s3vectors:*` actions exist, they are scoped to the index ARNs, and the runtime/loader split is correct. Changes are still needed for three reasons: dev and prod share one Terraform state file; the cleanup script reports success even when steps fail; and several roles and containers are missing permissions or settings they need to work.

**Scope:** everything under `infra/terraform/` (untracked), `scripts/cleanup.sh`, and both scratchpad diffs (repair-01 and repair-02). For cross-checking I read `modules/core/src/main/resources/reference.conf`, `modules/storage/src/main/resources/reference.conf`, `S3VectorsClient.scala`, `EmbeddingBatchWriter.scala`, `EmbeddingQueueManager.scala`, `StorageConfig.scala`, `DynamoDbClient.scala`, `NeptuneClient.scala`, and `AppConfig.scala`. In the table, "pre-existing" means the problem was not introduced by repair-01 or repair-02 but falls under review focus 1 or 6.

| ID | Severity | File:line | Concrete failing input | Impact | Owner | Verified |
|---|---|---|---|---|---|---|
| R1 | CRITICAL (pre-existing) | `infra/terraform/backend.tf:4` (`key = "scholarchain/terraform.tfstate"`); no workspace or `-backend-config` in `scripts/plan.sh`, `deploy.sh` or `cleanup.sh` | Dev is deployed, then someone runs `./scripts/plan.sh prod`, or `CONFIRM_CLEANUP=yes ./scripts/cleanup.sh prod` | dev and prod use the same state file. A prod plan would replace every dev resource because all names change (`scholarchain-dev-*` becomes `scholarchain-prod-*`). `cleanup.sh prod` runs `-auto-approve` and would delete the dev buckets, tables and vectors, while its inventory lists the `scholarchain-prod` prefix. | infra-engineer | Yes (grep found no workspace or backend-config anywhere) |
| R2 | MAJOR (pre-existing) | `modules/iam/main.tf:497-505` | The Step Functions `Plan` state calls `ecs:runTask.sync` | `ecs:RunTask` is authorized on the **task-definition** ARN (checked in the AWS Service Authorization Reference), but the policy scopes it to `task/...`, so every workflow task is denied. The `.sync` integration also needs `events:PutTargets`, `events:PutRule` and `events:DescribeRule` on `rule/StepFunctionsGetEventsForECSTaskRule`, which are missing. The query workflow cannot run. | infra-engineer | Yes (RunTask resource confirmed in SAR; the `.sync` requirement is from AWS docs) |
| R3 | MAJOR | `modules/iam/main.tf:347-424` (loader) and `modules/ecs/main.tf:534-567` | `EmbeddingJob` (`EmbeddingQueueManager.scala:9-20`) carries `textS3Uri` and no vector, so the worker has to call Titan to embed the text | The loader role has no `bedrock:InvokeModel`. Once `desired_count > 0`, the worker gets AccessDenied before it can call PutVectors. No role other than runtime can produce embeddings to populate the index. | infra-engineer | Yes |
| R4 | MAJOR | `modules/ecs/main.tf:45-54` vs. `DynamoDbClient.scala:77`, storage `reference.conf:10-11,16,28-29`, `EmbeddingBatchWriter.scala:59-61` | The task env does not set `SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX`, `SCHOLARCHAIN_S3_{RAW,CURATED,GRAPH,RAG}_BUCKET` or `SCHOLARCHAIN_NEPTUNE_ENDPOINT` | The app falls back to its defaults. Table names come out as `scholarchain-RunLedger`, but the real table is `scholarchain-dev-RunLedger`. `storeTextInS3` writes to `s3://scholarchain-rag/...`, which is not the deployed bucket, is outside the IAM pattern, and is a global name someone else could own. Neptune resolves to `localhost`. The eight variable names that *are* exported match the app exactly (core `reference.conf:8,28,30,35,60`; storage `reference.conf:34,36`). There is also no app key or env var for the embedding queue URL. | infra-engineer (env); storage-engineer (queue URL config key) | Yes |
| R5 | MAJOR (pre-existing) | `modules/iam/main.tf:245-324`; `modules/neptune/main.tf` (`iam_database_authentication_enabled = true`); `NeptuneClient.scala:17` | graph-retriever sends an openCypher request | No role grants any `neptune-db:*` action and the client does not sign requests, so every graph query is rejected. | infra-engineer (IAM); storage-engineer (SigV4 signing) | Yes |
| R6 | MAJOR | `modules/iam/main.tf:274-284` | Runtime calls `sqs:ReceiveMessage` + `DeleteMessage` on `scholarchain-dev-embedding-work-queue` or `-dead-letter-queue` | The runtime role is supposed to be read-only, but it can consume and delete embedding jobs and DLQ messages (which are failure evidence). The `-*` wildcard covers every project queue. Narrow it to `SendMessage` on the one queue that needs it. | infra-engineer | Yes |
| R7 | MAJOR | `modules/iam/main.tf:299` | Runtime calls `InvokeModel` on any foundation model, e.g. a high-cost model | `foundation-model/*` is not least privilege. Scope it to the ARNs for `var.embedding_model_id` and the generation model. | infra-engineer | Yes |
| R8 | MAJOR | `modules/iam/main.tf:175, 219, 291, 399` | A dev task role reads `scholarchain/prod/...` (same account, see R1) | `secret:scholarchain/*` is not scoped to the environment. The execution-role grant (line 219) is also unused, because no container definition has a `secrets` block. | infra-engineer | Yes |
| R9 | MAJOR | `scripts/cleanup.sh:63-139, 151-162` | prod: Neptune has `deletion_protection = true` (`prod.tfvars:20`), so Phase 4 fails, and Phases 7 and 8 fail on its dependents | Every phase ends in `\|\| echo WARNING` and the script finishes with "CLEANUP COMPLETE" and `exit 0`. `set -euo pipefail` is defeated, so failures are reported as success. | infra-engineer | Yes |
| R10 | MAJOR | `scripts/cleanup.sh:26-35` | `CONFIRM_CLEANUP=yes ./scripts/cleanup.sh prod` (or a typo'd env name) | The only gate is an environment variable. The env name is not validated, account and region are not shown, there is no plan review, and the destroy runs with `-auto-approve`. This conflicts with the `docs/agents.md` escalation rule for terraform destroy (human must review plan, account and region). | infra-engineer | Yes |
| R11 | MAJOR | `scripts/cleanup.sh:13-19, 69, 76` | Run cleanup after a demo | Phases 2 and 3 delete every CloudWatch log group (`/scholarchain/<env>/*`) along with its events. The data-loss header does not disclose this. Nothing requires `archive-evidence.sh` to run first, and that script does not export EvidenceBundle or the CloudWatch Logs anyway. Grading and provenance evidence would be lost. | infra-engineer | Yes |
| R12 | MAJOR | `modules/storage/src/main/scala/edu/scholarchain/storage/S3VectorsClient.scala:81-82, 104-113, 153-162` | `putVectors` sends an unsigned `PUT https://<bucket>.s3vectors.<region>.amazonaws.com/vectors/<index>` | That is not the S3 Vectors API (the real one is SigV4-signed `POST https://s3vectors.<region>.api.aws/PutVectors`). No vector is ever written or queried, so the Terraform IAM grants are never exercised. | storage-engineer | Code: yes. API shape is from AWS docs; I did not fetch it. |
| R13 | MINOR | `scripts/cleanup.sh:69` and `modules/ecs/main.tf:63-80, 487-503` | ECR repositories contain images after any deploy | No `force_delete`, so deleting a repository fails with RepositoryNotEmpty. The Phase 2 message ("Destroy ... ECR repositories") is untrue in that case. The post-cleanup inventory (lines 141-149) does not list ECR, ECS, log groups, SQS, EMR or Neptune. | infra-engineer | Yes |
| R14 | MINOR | `modules/ecs/main.tf:66,182` and `489,545` | Push `:latest` a second time | The repositories are `IMMUTABLE` but the task definitions pin `:latest`, so the second push is rejected. The new embedding-worker copies the same pattern. | infra-engineer | Yes |
| R15 | MINOR | `modules/iam/main.tf:318, 417-418` | — | No code path calls loader `GetVectors`/`ListVectors` or runtime `GetIndex`. Only `putVectors` (`EmbeddingBatchWriter.scala:41`) and `queryVectors` are called. This contradicts the policy's own reasoning at line 411. Runtime `GetVectors` is justified for QueryVectors with metadata. | infra-engineer | Yes |
| R16 | MINOR | `modules/iam/main.tf:84-85, 143-144, 260-261, 364-365` | Runtime reads `scholarchain-dev-logs`/`-raw`; EMR deletes objects in `-rag`; the S3 ARN pattern also matches same-named buckets in other accounts | Bucket access uses a `<prefix>-*` wildcard and is not least privilege. Scope it to the specific bucket ARNs from `module.storage.bucket_arns`. | infra-engineer | Yes |
| R17 | MINOR | `modules/iam/main.tf:557-566, 579-596` | Any principal in the account that has `sts:AssumeRole` | The deploy role trusts the account root with no MFA or principal condition. ECS actions use `Resource="*"`; `UpdateService`/`DescribeServices` can be scoped to service ARNs. | infra-engineer | Yes |
| R18 | MINOR | `providers.tf:20-28` | `extra_tags = { Environment = "prod" }` in the dev tfvars | `extra_tags` is merged last, so it can override `Project`/`Environment`/`ManagedBy`. No resource-level tag key (Name, Purpose, Service, Index) collides with default_tags. | infra-engineer | Yes |
| R19 | MINOR | `modules/storage/main.tf:195-231` | Someone edits `s3_vectors_dimension` or the index name set and applies | The indexes are immutable, so every changed index is replaced and its vectors are lost. Neither the indexes nor the vector bucket have `prevent_destroy` (at least for prod). The root `s3_vectors_index_names` (`variables.tf:146`) is not validated against the required five names. | infra-engineer | Yes |
| R20 | INFO | `scripts/cleanup.sh:108-110` | — | "All items are deleted with the table" overstates it. With PITR enabled, deleting a DynamoDB table creates a 35-day system backup. | infra-engineer | From AWS docs |
| R21 | INFO | `variables.tf:5-9, 36-44`; `backend.tf:5` | `aws_region = "us-west-2"` | The AZ default and the backend region are hardcoded to us-east-1, and no validation ties AZs to the region. The provider region does correctly come from `var.aws_region`, and the IAM module reads it via `data.aws_region.current.region` (the 6.x attribute). | infra-engineer | Yes |
| R22 | INFO | `.terraform/modules/modules.json` | — | The local init cache still lists the removed `module.provider`. Re-run init before planning. The removed module had no resources, so no state impact. | infra-engineer | Yes |
| R23 | INFO (pre-existing) | `modules/workflow/variables.tf:31-35`; `workflow/main.tf:344,358` | The workflow reaches `Audit` | `embedding_queue_url` defaults to `""` and root does not pass it. If it were wired, audit messages would land on the embedding job queue. | infra-engineer | Yes |

**Confirmed correct:**
- **S3 Vectors resources:**
  - Exactly 5 indexes, matching `S3VectorsIndexes.all` (`S3VectorsClient.scala:55-67`).
  - float32, dimension 1024, cosine (both tfvars).
  - The non-filterable keys `textS3Uri` and `textHash` match the `VectorMetadata` field names.
  - SSE-S3 on the vector bucket.
- **IAM separation:**
  - All `s3vectors` resources use `arn:...:bucket/<b>/index/<i>`.
  - The runtime role has no S3 Vectors write.
  - The loader role is attached only to `aws_ecs_task_definition.embedding_worker`.
  - Embedding-worker `desired_count = 0`, with no autoscaling and no load balancer.
- **PassRole:** scoped to explicit role ARNs; Step Functions correctly excludes the loader role.
- **Provider and diffs:**
  - Exactly one provider block.
  - The removed `modules/provider` had no resources. Before repair-02, the root had no region at all, so moving the provider to the root is a real fix.
  - The `aws_s3tables_table_bucket` removal will show as a destroy if it was ever applied remotely; no local state exists.

**Checks run (read-only):**
- `terraform fmt -check -recursive -diff`: exit 0.
- `terraform validate`: "Success! The configuration is valid." (against provider 6.26.0 in the lock file).
- `terraform providers schema -json` failed because it needs backend init, which I did not run.
- Fetched the AWS Service Authorization Reference JSON for s3vectors, ecs and bedrock (public URLs, no project data sent):
  - `PutVectors`, `GetVectors`, `ListVectors`, `QueryVectors` and `GetIndex` are all real actions on the Index resource.
  - `RunTask` is on `task-definition`.
- Grep and read across the Terraform files, scripts, app config and Scala sources.
- Confirmed there are no `prevent_destroy`, `force_delete` or `force_destroy` settings, and no `neptune-db` actions.
- No `*.tfstate` exists locally.
- One grep containing the literal destroy/apply strings was blocked by the `guard-dangerous.sh` hook; I reran it without that pattern.

**Ownership check:** no violations. Both diffs touch only `infra/terraform/**` and `scripts/cleanup.sh`, which belong to infra-engineer. Fixes are owned by infra-engineer, except R4 (queue-URL config key), R5 (signing) and R12, which belong to storage-engineer. No files were modified by this review.

---

## Pass 2 — 2026-11-22 — VERDICT: REQUEST_CHANGES

Scope: Phase 12 repair-03 on branch `remediation` (working tree). I checked every Pass 1 finding against the current files, not the trackers. Files reviewed:
- infra-engineer: everything under `infra/terraform/` (the root files, `environments/*.tfvars` and `*.backend.hcl`, and all modules), `scripts/tf-env.sh`, `plan.sh`, `deploy.sh`, `rollback.sh`, `cleanup.sh`, `archive-evidence.sh`, `smoke-test.sh`, `run-bounded-pipeline.sh`.
- storage-engineer: `modules/storage/**` (`AwsSigV4.scala`, `S3VectorsClient.scala`, `NeptuneClient.scala`, `StorageConfig.scala`, `EmbeddingQueueManager.scala`, `EmbeddingBatchWriter.scala`, storage `reference.conf`, `SignedStorageClientsSpec`, `StorageTestAws`, `logback-test.xml`). I also cross-read `DynamoDbClient.scala`.
- build-engineer: `project/Dependencies.scala`.
- service-engineer: `modules/services/src/main/resources/logback.xml` and `LoggingConfigSpec`.
- Also the repair-03 diff (<session-scratchpad>/tf-phase12-r3.diff), core `reference.conf`, and `.gitignore`.

All 23 Pass 1 findings are fixed or waiting on a human decision. Two new MAJOR findings block approval:
- **N1:** the evidence-bundle table's key does not match the application, so no evidence bundle can ever be stored.
- **N2:** two scripts still pick their target environment from whatever Terraform backend was last set up, so they can run against the wrong environment.

### Pass 1 findings status
| ID | Pass 1 severity | Status now | Evidence (file:line) | Note |
|---|---|---|---|---|
| R1 | CRITICAL | FIXED | `backend.tf:27-31`; `environments/dev.backend.hcl:9`, `prod.backend.hcl:9`; `scripts/tf-env.sh:82-135` (`-reconfigure`, then reads the key back from `.terraform/terraform.tfstate`), `141-162` (refuses to run if the old shared state object exists); called at `plan.sh:52`, `deploy.sh:85`, `cleanup.sh:127,369`, `archive-evidence.sh:60`, `rollback.sh:38` | Each environment now has its own state key, and scripts verify it. Two gaps remain: two scripts skip the guard (N2), and the check can be bypassed through environment variables (N3). |
| R2 | MAJOR | FIXED | `modules/iam/main.tf:544-556` (RunTask on `task-definition/<family>:*` with an `ecs:cluster` condition), `565-576` (the three events actions on `StepFunctionsGetEventsForECSTaskRule`) | |
| R3 | MAJOR | FIXED as approved; Region HUMAN-PENDING | `iam/main.tf:454-463`; `variables.tf:225-228` (the ARN verbatim, `us-east-2`); `main.tf:68-73` (a `check` block that warns on plan) | The loader role has only `bedrock:InvokeModel` on the exact approved ARN, and the runtime role is unaffected. The tfvars set `aws_region = "us-east-1"` (`dev.tfvars:6`, `prod.tfvars:6`) and the worker's model region follows it (`ecs/main.tf:80`), so the grant will not match until a human decides. Not an agent defect. |
| R4 | MAJOR | FIXED | `ecs/main.tf:67-96`; storage `reference.conf:45-49`; `StorageConfig.scala:101-104`; `EmbeddingQueueManager.scala:113-126` | Every exported name matches a `${?…}` key in core `reference.conf` or storage `reference.conf`. The queue URL is given to the worker only. |
| R5 | MAJOR | FIXED | `iam/main.tf:23`, `335-347` (`connect` and `ReadDataViaQuery` on `<cluster_resource_id>/*`); `NeptuneClient.scala:41-42,54-64,178` | Requests are SigV4-signed (signing name `neptune-db`) and form-encoded. |
| R6 | MAJOR | FIXED | `iam/main.tf:293-296` (the runtime role has no SQS access); `414-426` (the loader has the work queue only, not the dead-letter queue) | |
| R7 | MAJOR | OPEN-PENDING-HUMAN | `iam/main.tf:303-312` (`foundation-model/*`) | Left unchanged on purpose. |
| R8 | MAJOR | FIXED | `iam/main.tf:14`, `199`, `301`, `431` (`secret:scholarchain/<env>/*`); `205-234` (the execution-role grant is removed) | |
| R9 | MAJOR | FIXED | `cleanup.sh:71-103`, `370-376`, `415-418` | Every phase is recorded, failures give a non-zero exit, and "COMPLETE" is printed only when nothing failed. |
| R10 | MAJOR | FIXED | `cleanup.sh:109-119` (allowed-environment list, TTY required), `126-160` (account, region and state shown), `315-347` (saved `-destroy -out` plan; only delete actions allowed; checksum taken), `353-371` (typed `<account>/<region>/<env>`, checksum re-checked, apply of the saved plan) | No `-auto-approve` anywhere (grep). |
| R11 | MAJOR | FIXED | `cleanup.sh:41-42,165-194` (requires a recent, checksum-verified archive); `archive-evidence.sh:143-182` (scans of evidence-bundle and run-ledger, every log event under `/scholarchain/<env>/`) | Other provenance tables are still not exported; see N8. |
| R12 | MAJOR | FIXED | `S3VectorsClient.scala:301-302,305-333,347,359-361` | SigV4-signed `POST https://s3vectors.<region>.api.aws/<Operation>`. The topK limit and pagination are wrong; see N4. |
| R13 | MINOR | FIXED | `ecs/main.tf:104-110`; `cleanup.sh:43-48,204-210,265` | Documented, and ECR is now in the inventory. It is still not a blocker; see N9. |
| R14 | MINOR | FIXED | `variables.tf:143-151` (40-hex validation, no default); `ecs/main.tf:231,595`; `plan.sh:37-42` | See N7. |
| R15 | MINOR | FIXED | `iam/main.tf:322-334` (runtime: QueryVectors and GetVectors), `442-453` (loader: PutVectors only) | |
| R16 | MINOR | FIXED | `iam/main.tf:18-20,100,152,165,277-281,392,398,505` | Every grant names specific bucket ARNs. |
| R17 | MINOR | FIXED | `iam/main.tf:633-647` (MFA required), `660-718` (scoped to services and task-definition families) | See N12 (EMR `cluster/*`). |
| R18 | MINOR | FIXED | `providers.tf:24-32` (mandatory tags merged last); `variables.tf:273-276` | |
| R19 | MINOR | FIXED | `modules/storage/main.tf:224-226,255-257` (`prevent_destroy`); `variables.tf:180-191` (all five index names required) | |
| R20 | INFO | FIXED | `cleanup.sh:38-40,232-235,395-396` | |
| R21 | INFO | FIXED | `variables.tf:5-13` (no default), `47-50` (AZs must be in the chosen region); `*.backend.hcl:10` | |
| R22 | INFO | FIXED | `.terraform/modules/modules.json` | `module.provider` is gone. |
| R23 | INFO | FIXED; dedicated queue HUMAN-PENDING | `modules/workflow/main.tf:13-50` (Pass states when there is no queue); `main.tf:45,224`; `iam/main.tf:611-618` | Audit messages can no longer go to the embedding queue. No audit trail exists until a human approves an audit queue. |

### New findings
| ID | Severity | File:line | Concrete failing input | Impact | Owner | Verified |
|---|---|---|---|---|---|---|
| N1 | MAJOR | `infra/terraform/modules/storage/main.tf:153-161` (`hash_key = "evidenceId"`) vs `modules/storage/src/main/scala/edu/scholarchain/storage/DynamoDbClient.scala:172,184` (`"bundleId"`) | `putEvidenceBundle` writes the item `{bundleId, jsonData}` to `scholarchain-dev-evidence-bundle` | DynamoDB rejects it with ValidationException ("Missing the key evidenceId"), so no evidence bundle is ever stored. That breaks the CLAUDE.md provenance rule, and `archive-evidence.sh` would export an empty table. A table's key cannot change after creation, so this must be fixed before the first apply. The other four keys match: `runId`, `sourceId`/`targetId`, `queryId`, `artifactId`. The table-name rename itself matches `DynamoDbClient.scala:77,88-214`. | infra-engineer | Yes |
| N2 | MAJOR | `scripts/smoke-test.sh:22-28`; `scripts/run-bounded-pipeline.sh:16-35` | Run `./scripts/plan.sh dev`, then `./scripts/smoke-test.sh prod` or `./scripts/run-bounded-pipeline.sh prod` | Neither script sources `tf-env.sh`. They take `alb_dns_name` or `emr_cluster_id` from whichever backend is currently set up (dev), and they do not check the environment name. The smoke test then reports prod as passing using the dev ALB (false evidence). The pipeline sends EMR steps for prod buckets to the dev cluster; R16 bucket scoping makes those steps fail. Same class of bug as R1. | infra-engineer | Yes |
| N3 | MINOR | `scripts/tf-env.sh:60-107,133` | `TF_WORKSPACE=x` exported, or `TF_DATA_DIR`/`TF_CLI_ARGS_init` set | With a non-default workspace, the S3 backend stores state under `env:/x/<key>`, but the check reads only `config.key` and passes. With `TF_DATA_DIR` set, the script verifies a stale `${TF_DIR}/.terraform/terraform.tfstate`. Fix: refuse to run when `TF_WORKSPACE`, `TF_DATA_DIR` or `TF_CLI_ARGS*` is set. | infra-engineer | Code: yes. Terraform behaviour: from docs, not tested. |
| N4 | MINOR | `S3VectorsClient.scala:216-254,355-356`; the test at `SignedStorageClientsSpec.scala:262-291` | `queryVectors(..., topK = 500, ...)` | The client claims topK up to 10,000 with `nextToken` paging. As I understand the AWS QueryVectors API, topK is capped at 100 (30 in preview) and QueryVectors has no `nextToken`. The real service would return 400 ValidationException, and the test locks in an invented contract. Nothing calls this in production yet. | storage-engineer | Code: yes. AWS limit from documentation; not fetched this pass. |
| N5 | MINOR | `modules/storage/main.tf:105-130` vs `DynamoDbClient.scala:26,32,113` | entity-link-cache items written with `ttl` | The table has no `ttl { attribute_name = "ttl", enabled = true }` block, so cache entries never expire. | infra-engineer | Yes |
| N6 | MINOR | `scripts/plan.sh:36-64` vs `deploy.sh:40-66` | `plan.sh` run with uncommitted `.tf` edits, then `git stash` | The plan records HEAD as its commit even though it was built from the dirty tree. `deploy.sh` checks only that the tree is clean and HEAD matches, so it applies configuration that is not in that commit (a provenance gap). `plan.sh` should require a clean tree. | infra-engineer | Yes |
| N7 | MINOR | `deploy.sh:99-118`; `ecs/main.tf:131-149` and the embedding-worker lifecycle policy (`ecs/main.tf:555-572`) | Apply when `:<HEAD sha>` has not been pushed, or after more than 20 pushes | Images are checked only after apply, so services roll to a tag that does not exist. The lifecycle rule (`tagStatus any`, keep 20) can expire the image a running service uses, and the next task replacement then fails to pull. | infra-engineer | Yes |
| N8 | MINOR | `archive-evidence.sh:143-156`; `cleanup.sh:37-40` | Cleanup after a demo | `artifact-registry` (provenance), `query-session` and `entity-link-cache` are destroyed without being exported. Only the 35-day PITR system backups remain. | infra-engineer | Yes |
| N9 | MINOR | `cleanup.sh:255-265`; `archive-evidence.sh:28-29` | Any deployed environment (ECR always holds images; raw/graph/rag are versioned and non-empty) | Non-empty buckets and repositories only produce warnings. The apply therefore always ends as a partial destroy with exit 1. Bucket contents are never archived, and nothing tells the operator what to do next, so manually emptying the buckets would lose data with no archive. | infra-engineer | Yes |
| N10 | INFO | `SignedStorageClientsSpec.scala:162-169` | — | The test uses a fixed clock, so identical signatures on both attempts look the same as re-signing. The code itself re-signs on each attempt (`S3VectorsClient.scala:317`). | storage-engineer | Yes |
| N11 | INFO (pre-existing) | `NeptuneClient.scala:121-125` | `shortestPath(a,b,4)` | As far as I know, Neptune's openCypher does not support `shortestPath()`, so this query would fail. | storage-engineer | Code: yes. Neptune support from documentation; not fetched. |
| N12 | INFO | `iam/main.tf:641-645`, `737-747` | Assuming the deploy role from an IAM Identity Center or role-chained session | `aws:MultiFactorAuthPresent` is not present in those sessions, so the assume-role is denied. Human awareness only. EMR step actions are still granted on `cluster/*` rather than this environment's cluster. | infra-engineer | Yes |
| N13 | INFO (pre-existing) | `modules/neptune/main.tf:42-43` | A second prod cleanup after a redeploy | The fixed final snapshot ID `scholarchain-prod-neptune-final` already exists, so the destroy fails. `cleanup.sh` reports the failure. | infra-engineer | Yes |
| N14 | INFO | `ecs/main.tf:584-613`; the only entry points are `ScholarChainServer.scala` and `ScholarChainPipeline.scala` | — | No embedding-worker entry point exists. Which `logback.xml` (and so which WARN floor) the worker image would use is undetermined. | service-engineer or storage-engineer (human to assign) | Yes |
| N15 | INFO | `modules/storage/main.tf:84-193` | A state that was ever applied with the old names (`-RunLedger` and so on) | A plan would replace (destroy and create) all five tables, which have no `prevent_destroy`. No state exists today. Whoever reviews the first plan must check that no table shows as replaced. | human (plan review) | Yes |

**Confirmed correct (new code):**
- **SigV4 (`AwsSigV4.scala`):**
  - Signing names are `s3vectors` and `neptune-db`. Region is validated and comes from env vars Terraform sets to `var.aws_region`.
  - Host is signed by the SDK from the URI, including `:8182` for Neptune. Java's HTTP client derives the same Host, and the restricted headers are dropped (`AwsSigV4.scala:45,53-55`).
  - The payload hash is computed over the exact body bytes sent.
  - Credentials come from `DefaultCredentialsProvider` (container credentials on Fargate) and are resolved for every request. The session token is added for session credentials.
  - Retries re-sign each attempt (`S3VectorsClient.scala:317`).
- **Redaction:** the secret key, session token and `Signature=` are removed from every error and log path (`S3VectorsClient.scala:331`, `NeptuneClient.scala:67-79`). Tests at `SignedStorageClientsSpec.scala:440-507` cover this.
- **S3 Vectors:** request shapes for PutVectors, QueryVectors and GetVectors are correct, as are the 500-per-put and 100-per-get limits.
- **Neptune:** the form encoding is `query=` plus `parameters=` (JSON). All values are passed as parameters. Edge types are checked against an allowlist, and depth and limit are bounded integers.
- **logback:** the SDK, signer, Apache wire and Netty loggers have explicit WARN levels, which the root-level override does not lower. `LoggingConfigSpec` checks this in an isolated logging context.
- **Dependencies:** the four added SDK artifacts are on the existing `2.29.26`, and the other changes are whitespace only.
- **Cleanup safety:**
  - The destroy plan is saved, and only `["delete"]`, `no-op` and `read` actions are allowed.
  - Retained resources are left out of the targets and re-checked. The logs bucket and its sub-resources are excluded, and the vector bucket and indexes have `prevent_destroy` and are excluded.
  - Data sources are excluded from the targets.
- **Bash 3.2:** every `"${arr[@]}"` expansion is either on an array that is guaranteed non-empty or uses the `${arr[@]+…}` guard (`cleanup.sh:300`). `/bin/bash` 3.2.57 accepts every script with `-n`.
- **Dependency graph:** the Terraform module dependencies have no cycle (validate passes).

**Human-pending (not counted toward the verdict):**
- **R3 Region:** the approved ARN is in `us-east-2` but the deploy Region is `us-east-1` (`variables.tf:228`, `dev.tfvars:6`/`prod.tfvars:6`, warning at `main.tf:68-73`).
- **R7:** the runtime role can invoke `foundation-model/*`.
- **R23:** no dedicated audit queue has been approved, so the Audit states are no-ops.

Checks run (read-only):
- `terraform version`: v1.17.2, provider aws 6.26.0.
- `terraform fmt -check -recursive`: exit 0.
- `terraform validate`: "Success! The configuration is valid."
- `bash -n scripts/*.sh` and `/bin/bash -n scripts/*.sh` (bash 3.2.57): exit 0 for all 8 scripts.
- `/bin/bash -c 'set -u; declare -a A=(); …'`: confirmed that `${#A[@]}` and `${A[@]+…}` are safe on an empty array and that `"${A[@]}"` fails (the scripts avoid it).
- `shasum --help` (`-q`/`--quiet` exists) and `sort -z`: both work on macOS.
- `git status`, `git diff` (Dependencies.scala, test-engineer.md), `git diff --stat`, `stat` on file mtimes, `git check-ignore` (`local-runs/`, `*.tfplan` and `.terraform/` are ignored).
- grep and reads across Terraform, scripts, storage and services sources: no `-auto-approve`, no `force_delete`/`force_destroy`, only the two `prevent_destroy`, no non-storage callers of the storage clients.
- I did not run `sbt check`; I relied on the orchestrator's observed exit 0 with 614 tests.
- No terraform plan or apply, no scripts, no AWS calls, no `.env` reads, no network fetches.

Ownership check: none. Every repair-03 change is in files its owner owns:
- `infra/terraform/**` and `scripts/*.sh`: infra-engineer.
- `modules/storage/**`: storage-engineer.
- `project/Dependencies.scala`: build-engineer. The new artifacts are build structure, and the whitespace came from the format hook.
- `modules/services/**`: service-engineer.

One observation outside repair-03: `.claude/agents/test-engineer.md` has an uncommitted `model: sonnet` line (mtime Nov 22 11:54, before Phase 12 repairs began). It is a constitutional file, so it needs human ratification. I cannot tell from a read-only view who made the change.

To reach APPROVE:
- **N1** (infra-engineer): change the evidence-bundle `hash_key` and attribute to `bundleId` before the first apply.
- **N2** (infra-engineer): source `tf-env.sh` in `smoke-test.sh` and `run-bounded-pipeline.sh`, and add the environment-name and backend checks there.

No files were modified by this review.

## Pass 3 — 2026-11-23 — VERDICT: APPROVE (repair-04 scope; does not block a human-reviewed `plan.sh dev`). Apply is gated: see "Before the first apply".

STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 independent review, Pass 3, after repair-04 (N1, N2, us-east-2 region, Trivy triage, R7 proposal)
VERDICT: APPROVE

Scope: the repair-04 diff (`<session-scratchpad>/tf-phase12-r4.diff`, 9 files) checked against the live `infra/terraform/**` and `scripts/*.sh`. Read for cross-checking:
- `modules/storage/.../DynamoDbClient.scala`
- `modules/services/.../{JsonCodecs,Routes,RuntimeComposer,ScholarChainServer}.scala`
- `modules/rag/.../Ports.scala`
- core and storage `reference.conf`
- `<session-scratchpad>/trivy-r4-{dev,prod}.json`
- `<session-scratchpad>/r7-research.md`

**Summary:**
- N1 and N2 are fixed, and the region change is consistent.
- Nothing blocks running `plan.sh dev` for human review.
- One MAJOR item surfaced by Trivy (N16) must be decided by a human before any apply. It is pre-existing, not introduced by repair-04: the internet-facing ALB has no authentication.
- The R7 IAM proposal assigns Bedrock grants to the wrong services (N17) and needs correcting before anyone implements it.

### 1. N1: evidence-bundle key — FIXED
- The evidence-bundle table now uses `bundleId` (`infra/terraform/modules/storage/main.tf:161,164`). That matches `DynamoDbClient.scala:172` (put), `:184` (get key) and `:233-234` (`attribute_not_exists(<pk>)`).
- All five tables match the client:

| Table | Terraform (`modules/storage/main.tf`) | Client (`DynamoDbClient.scala`) |
|---|---|---|
| `<prefix>-run-ledger` | hash `runId` (:87) | :83, :95 |
| `<prefix>-entity-link-cache` | hash `sourceId`, range `targetId` (:108-109) | :110-111, :127-129 |
| `<prefix>-query-session` | hash `queryId` (:135) | :145, :157 |
| `<prefix>-evidence-bundle` | hash `bundleId` (:161) | :172, :184 |
| `<prefix>-artifact-registry` | hash `artifactId` (:182) | :199, :211 |

- No table has a GSI or LSI, and the client never uses `IndexName`, Query or Scan, so none is needed.
- Table names (`tableName(suffix)` at `:77`) match.
- `ttl` is written at `:113`, but the table has no TTL block. That is N5, still open.
- **The claim that `evidenceId` in `smoke-test.sh` is a REST field is CONFIRMED.**
  - `evidenceId` is a field of the REST `EvidenceResponse` (`JsonCodecs.scala:105`) and of the in-memory `EvidenceItem` (`core/Evidence.scala:7`).
  - `GET /v1/evidence/<id>` looks it up in process memory (`Routes.scala:212-221`), not in DynamoDB.
  - However, the smoke test's extraction never matches anything (see N18).

### 2. N2: guard in smoke-test and pipeline scripts — FIXED
- **Same guard as the other scripts.** Both scripts now run the same sequence as `plan.sh`, `deploy.sh` and `rollback.sh`, before reading any output:
  - `smoke-test.sh:32,35,42-44`
  - `run-bounded-pipeline.sh:26,29,45-47`
  - The sequence: source `tf-env.sh`, then `tf_env_validate_name` (dev/prod allowlist), `tf_env_require_aws_target` (STS, CLI region equals the tfvars region), `tf_env_init_backend` (`-reconfigure`, key check, legacy-object refusal), and finally the new `tf_env_require_state_identity` (the state's `environment` and `aws_region` outputs must equal the target; an empty state is refused).
- **No unguarded `terraform output`.**
  - In these two scripts, outputs are read only through `tf_env_output` / `tf_env_output_json` (`smoke-test.sh:48`; `run-bounded-pipeline.sh:53,76`). Both call `tf_env_require_verified` first, which re-reads the key from `.terraform/terraform.tfstate`.
  - The remaining raw `terraform output` calls (`cleanup.sh:137-139`, `rollback.sh:39`, `archive-evidence.sh:84`) all come after `tf_env_init_backend` in the same shell, as in Pass 2.
  - The old fallbacks are gone: `SCHOLARCHAIN_ALB_DNS` / `localhost:8080`, and `|| echo ""` on the cluster ID.
  - The outputs these scripts read (`environment`, `aws_region`, `alb_dns_name`, `emr_cluster_id`, and `s3_bucket_names` with keys artifacts, raw, curated, graph, logs) all exist (`outputs.tf:29-31,58-60,96-98,148-156`; `modules/storage/outputs.tf:6-8`).
- **Pre-seeding is blocked.** `tf-env.sh:41` resets `TF_ENV_VERIFIED_ENV=""` on every source, and the variable is set only at `:116`, after verification. Confirmed under `/bin/bash` 3.2.57: `env TF_ENV_VERIFIED_ENV=dev bash -c 'source scripts/tf-env.sh; X="$(tf_env_output dev environment)"'` printed `after source: []`, refused, and exited 3. Even a seeded value would fail the on-disk key re-check at `:211-212`.
- **Exit handling.** A guard `exit 3` inside `$(...)` propagates through the plain assignment under `set -e`, so the scripts stop. In `tf_env_require_state_identity`, the `|| true` is followed by explicit comparisons, so it still fails closed.
- **No regressions** in `plan.sh`, `deploy.sh`, `rollback.sh`, `cleanup.sh` or `archive-evidence.sh`:
  - The only change to existing functions is the single added line at `:116`.
  - No caller reads or writes `TF_ENV_VERIFIED_ENV`.
  - Each script sources `tf-env.sh` exactly once.
- **Bash 3.2.** `/bin/bash -n` passes on all 8 scripts. The new code uses no arrays, no `${var,,}`, no `mapfile` and no negative `head -n`, so it is safe. `smoke-test.sh:88` now uses `sed '$d'`, which also fixes BSD `head`. One trivial edge case is N25.
- **Residual gaps** (not regressions): N3 (`TF_WORKSPACE`, `TF_DATA_DIR`, `TF_CLI_ARGS*`) is still open, and there is a check-then-use window on the shared `.terraform` directory (N24).

### 3. Region — consistent
- **The deployment is us-east-2 throughout:**
  - `dev.tfvars:10-11` and `prod.tfvars:10-11`: `us-east-2`, AZs `us-east-2a` and `us-east-2b`. The validation at `variables.tf:48` enforces that the AZs are in that region.
  - `check "titan_grant_region"` (`main.tf:68-73`): `split(":", arn)[3]` is `"us-east-2"`, which equals `var.aws_region`, so the check now passes.
  - The provider takes its region from `var.aws_region` (`providers.tf:36`).
  - ECS env `AWS_REGION` and the `SCHOLARCHAIN_{DYNAMODB,NEPTUNE,MODELS,S3_VECTORS}_REGION` variables, plus `awslogs-region`, all come from `var.aws_region` (`modules/ecs/main.tf:69,76,79,80,82,248,608`).
  - VPC endpoint service names (`modules/network/main.tf:153,167,200,213,226`) and the observability widgets follow `var.aws_region`.
  - IAM ARNs use `data.aws_region.current.region` (`modules/iam/main.tf:10`), which is the provider region.
  - The loader's Titan ARN is the approved `us-east-2` ARN (`variables.tf:228`), and the in-Region support it relies on is confirmed by the research notes.
  - The app's `us-east-1` defaults (core `reference.conf:16,63`; storage `reference.conf:19,26,35`) are all overridden by those ECS env vars.
- **Keeping `backend.hcl` `region = "us-east-1"` is correct, not a defect.**
  - That setting is the region of the existing state bucket and lock table, and Terraform's S3 backend region is independent of the provider region.
  - `tf_env_refuse_legacy_state` passes `--region` from the backend file explicitly (`tf-env.sh:157-159`), so `AWS_REGION=us-east-2` cannot misdirect it.
  - The deploy role has no `aws:RequestedRegion` condition on any state-bucket access; it holds no state-bucket grants at all, because it is not the Terraform runner.
  - If the bucket is actually somewhere else, `terraform init` fails closed.
  - Two things are still unconfirmed (N23, MANUAL ACTION): that the bucket and lock table exist in us-east-1, and that `dynamodb_table` is accepted by Terraform 1.17. The orchestrator's validate ran with `-backend=false`, so the backend config has not been exercised.

### 4. Trivy triage (dev and prod: identical failure set, 47 each)

| ID | Sev | Location | Classification | Justification / action | Owner |
|---|---|---|---|---|---|
| AWS-0054 | CRITICAL | `modules/ecs/main.tf:284` (HTTP:80 listener, the only one) | TRUE POSITIVE. Acceptable for bounded dev only if combined with an ingress allowlist (N16); not acceptable for prod. | No TLS anywhere. Combined with no API authentication, query traffic is plaintext over the internet. No ACM certificate or domain exists. For dev, restrict ingress to operator CIDRs and accept HTTP. Prod needs an HTTPS listener and certificate. | infra-engineer; human decision (security-sensitive) |
| AWS-0053 | HIGH | `modules/ecs/main.tf:274` (`internal = false`) with `modules/network/main.tf:244-258` (0.0.0.0/0 on 80 and 443) | TRUE POSITIVE. Fix before the first apply (N16). | Anyone on the internet can call `/v1/query`, and there is no authentication. Once R7's Bedrock client exists, that means unbounded Bedrock and Neptune spend. Port 443 is open with no listener behind it. Fix: add a required `alb_ingress_cidrs` variable (no 0.0.0.0/0 default) and drop the 443 rule until HTTPS exists. | infra-engineer; human approval |
| AWS-0052 | HIGH | `modules/ecs/main.tf:272` | TRUE POSITIVE, trivial | Add `drop_invalid_header_fields = true`. It costs nothing and changes nothing else. Recommended before the first apply; not blocking. | infra-engineer |
| AWS-0104 ×5 | CRITICAL | `modules/network/main.tf:265` (alb), `:293` (app), `:321` (data/Neptune), `:341` (emr_master), `:361` (emr_core) | Mixed; see the per-security-group rows below. | | infra-engineer |
| AWS-0104 | CRITICAL | `:265` ALB | TRUE POSITIVE, easy fix | The ALB only needs tcp/8080 to the app security group. | infra-engineer |
| AWS-0104 | CRITICAL | `:321` data (Neptune) | TRUE POSITIVE, easy fix | Neptune needs no egress at all. Remove it. | infra-engineer |
| AWS-0104 | CRITICAL | `:293` app | ACCEPTABLE for dev; tighten later | Tasks reach Bedrock, S3 Vectors, Secrets Manager, STS and SQS through the NAT gateway, because only s3, dynamodb, ecr and logs have endpoints. They also need 8182 to the data security group. They could be narrowed to tcp/443 to 0.0.0.0/0 plus 8182 to the data security group. | infra-engineer |
| AWS-0104 | CRITICAL | `:341`, `:361` EMR | ACCEPTABLE, close to a false positive | EMR clusters need broad outbound access (S3, EMR control plane, package repositories), and AWS documents full egress for EMR managed security groups. | infra-engineer |
| AWS-0128 | HIGH | `modules/neptune/main.tf:32` | ACCEPTABLE for dev | Encryption is on with the AWS-managed key. On Neptune, `kms_key_arn` forces replacement, so a CMK decision for prod must be made before prod's first apply (N22). | infra-engineer; human (prod) |
| AWS-0132 | HIGH | `modules/storage/main.tf:37` | ACCEPTABLE for dev | SSE-KMS with the `aws/s3` managed key, bucket key enabled, public access fully blocked. Switching to a CMK later is an in-place change. | infra-engineer |
| AWS-0075 | MEDIUM | `modules/neptune/main.tf:32` | TRUE POSITIVE (N20) | The parameter group enables the audit log (`:22-25`), but `enable_cloudwatch_logs_exports = ["audit"]` is missing, so audit records are never exported. This leaves a provenance and evidence gap. Cheap to fix. | infra-engineer |
| AWS-0090 ×3 | MEDIUM | `modules/storage/main.tf:33` (curated, artifacts, logs suspended) | ACCEPTABLE | Curated data is per-run and reproducible, and logs expire. Artifacts versioning would help jar provenance, but jars are keyed by commit and run. | infra-engineer |
| AWS-0178 | MEDIUM | `modules/network/main.tf:5` | ACCEPTABLE for bounded dev | No VPC flow logs. Recommended for prod. | infra-engineer |
| LOW ×32 | LOW | — | ACCEPTABLE for dev | Covers AWS-0017 (log-group CMK, 5), 0025 (DynamoDB CMK, 5), 0033 (ECR CMK, 6), 0089 (S3 access logging, 1), 0099 (missing security-group descriptions, 6) and 0124 (missing rule descriptions, 9). The description findings are cosmetic. | infra-engineer |

There are no FALSE POSITIVES in the strict sense; the EMR egress findings are expected by design.

### 5. R7 proposal assessment (no IAM edits made; correct)
- **The policy shape is right:**
  - Titan gets an exact in-Region `foundation-model` ARN.
  - Claude via the US geo inference profile needs `bedrock:InvokeModel` on `arn:aws:bedrock:us-east-2:<account>:inference-profile/us.anthropic.<model>`, plus on `arn:aws:bedrock:{us-east-1,us-east-2,us-west-2}::foundation-model/anthropic.<model>`.
  - Add `InvokeModelWithResponseStream` only if `ConverseStream` or streaming is used. Plain `Converse` is authorized by `InvokeModel`.
- **Condition key: yes.** On the foundation-model statement, add `StringEquals { "bedrock:InferenceProfileArn": "<that exact profile ARN>" }`. That stops the role invoking the model directly in us-east-1 or us-west-2, so it can only reach the model through the approved profile.
  - Do not add `aws:RequestedRegion` to the Claude statements; it can break cross-Region routing.
  - Check that no SCP blocks us-east-1 or us-west-2 for Bedrock.
- **The service-to-role mapping is wrong (N17).** Both callers run in `query-api`, not in `vector-retriever` or `synthesis-worker`:
  - All five ECS services run the identical entry point, `scholarChainServer` (`ScholarChainServer.scala:4-6`).
  - Nothing dispatches on `SERVICE_NAME`; a grep found no reader of it.
  - The ALB routes `/v1/query*` to `query-api` (`modules/ecs/main.tf:18-20`), and that handler runs the whole `RuntimeWorkflow` in-process (`Routes.scala:117-127`). The `QueryEmbedder` call (`SemanticContextAgent`) and the `LlmClient` call (`AnswerSynthesisAgent`) both happen there.
  - If the split were applied as proposed, `query-api` would get AccessDenied and the two other services would hold grants they never use.
  - Today, `composeStubs()` is the only composition and no Bedrock client exists. So the least-privilege answer right now is no Bedrock permission on any service task role. The proposed per-service grants only make sense after service-engineer adds per-service composition.
  - Alternative: grant `query-api` alone and give it its own task role.
- **Other prerequisites:**
  - The generation model ID must become the profile ID, and Terraform must export it (N21).
  - Anthropic models need one-time account enablement (the use-case form or Marketplace subscription). That is a human or admin action; do not grant `aws-marketplace:*` to task roles.
  - The splitting role must also be added to the `PassRole` lists (deploy role `iam/main.tf:722-730`, and the Step Functions role).

### 6. Status of prior findings

| ID | Status | Evidence / note |
|---|---|---|
| N1 | FIXED | `modules/storage/main.tf:161,164` |
| N2 | FIXED | `smoke-test.sh:32-52`; `run-bounded-pipeline.sh:26-81`; `tf-env.sh:41,116,207-255` |
| N3 | OPEN (minor) | `tf-env.sh` has no `TF_WORKSPACE`, `TF_DATA_DIR` or `TF_CLI_ARGS*` refusal (grep finds none) |
| N4 | OPEN (minor) | storage-engineer; not in repair-04 |
| N5 | OPEN (minor) | No `ttl` block on `entity_link_cache` (`modules/storage/main.tf:105-130`) |
| N6 | OPEN (minor) | `plan.sh` still has no clean-tree check |
| N7 | OPEN (minor) | Unchanged |
| N8 | OPEN (minor) | Unchanged |
| N9 | OPEN (minor) | Unchanged |
| N10 | OPEN (info) | storage-engineer |
| N11 | OPEN (info) | storage-engineer |
| N12 | OPEN (info) | MFA in Identity Center sessions; EMR `cluster/*` (`iam/main.tf:746`) |
| N13 | OPEN (info) | Fixed final-snapshot ID |
| N14 | OPEN (info) | Human to assign |
| N15 | OPEN (info; human plan review) | Still applies. No state exists, so the N1 key change needs no migration. |
| R3 | FIXED | Region decision made; the `titan_grant_region` check passes |
| R7 | WAITING-human | Proposal needs N17 correction first; no IAM change made, as instructed |
| R23 | DEFERRED-by-human | Audit states remain Pass no-ops (`modules/workflow/main.tf:13-50`) |

### 7. New findings

| ID | Severity | File:line | Failing input / impact | Owner |
|---|---|---|---|---|
| N16 | MAJOR (pre-apply; pre-existing; surfaced by Trivy) | `infra/terraform/modules/network/main.tf:244-258`; `infra/terraform/modules/ecs/main.tf:272-284`; no authentication anywhere in `modules/services/**` (grep) | Any internet client can `POST http://<alb>/v1/query`. The API is public, plaintext, has no authentication, and port 443 is open with no listener. Today that exposes stub data. Once R7 lands, it allows unbounded Bedrock and Neptune spend and exposes data. Fix: a required operator-CIDR allowlist variable, remove the 443 rule, set `drop_invalid_header_fields`. HTTPS and authentication are required before prod. | infra-engineer (network/ALB); service-engineer (API auth, prod); human approval (security-sensitive) |
| N17 | MINOR (proposal only; becomes MAJOR if implemented as written) | R7 proposal vs `infra/terraform/modules/ecs/main.tf:8-20,227`; `modules/services/src/main/scala/edu/scholarchain/services/ScholarChainServer.scala:4-6`; `Routes.scala:117-127` | The Bedrock callers run in `query-api`, not in `vector-retriever` or `synthesis-worker`, so the proposed per-service grants would deny the real caller. See §5. | infra-engineer (proposal); service-engineer (per-service composition, if wanted) |
| N18 | MINOR (pre-existing) | `scripts/smoke-test.sh:80`, `:101` | The payload sends `"query"`, but `QueryRequest` requires `question` (`JsonCodecs.scala:7-8`). upickle rejects it with 400 (`Routes.scala:101-110`), so Tests 3 and 5 always FAIL. Test 4 greps for `"evidenceId":"…"`, but the query response only contains `claims[].evidenceIds` arrays (`JsonCodecs.scala:83-86`), so Test 4 always SKIPs. The smoke test can never pass, and it never exercises the evidence endpoint. | infra-engineer |
| N19 | MINOR (pre-existing) | `infra/terraform/modules/ecs/main.tf:30-33` (`/v1/evidence*` routed to `synthesis-worker`) vs `Routes.scala:212-221` (in-process `queryResults`) | `GET /v1/evidence/<id>` reaches a different task than the one that served `/v1/query`, so it always returns 404. `/v1/explain` and idempotency also break whenever `ecs_min_tasks` is greater than 1 (prod has 2). | service-engineer (shared state); infra-engineer (routing); human to assign |
| N20 | MINOR | `infra/terraform/modules/neptune/main.tf:22-25,32` | The audit log is enabled but never exported (Trivy AWS-0075), so no Neptune audit evidence ever reaches CloudWatch for `archive-evidence.sh`. | infra-engineer |
| N21 | INFO | `modules/core/src/main/resources/reference.conf:61-62`; no `SCHOLARCHAIN_GENERATION_MODEL_ID` in `modules/ecs/main.tf:67-87` | The default `anthropic.claude-3-sonnet-20240229-v1:0` is a legacy model that is not In-Region in us-east-2. It must become `us.anthropic.claude-<m>-5-5`, exported by Terraform, when R7 lands. | domain-engineer (default); infra-engineer (env) |
| N22 | INFO | `infra/terraform/modules/neptune/main.tf:32-40` | Changing `kms_key_arn` forces replacement of the Neptune cluster. A prod CMK decision (AWS-0128) must be made before prod's first apply. | human; infra-engineer |
| N23 | INFO | `infra/terraform/environments/{dev,prod}.backend.hcl:14-18` | The state bucket and lock table's existence and region have not been confirmed. `dynamodb_table` has been deprecated in favour of `use_lockfile` since Terraform 1.11; whether 1.17 still accepts it was not verified. Backend init has not been exercised (validate ran with `-backend=false`). Both fail closed at `plan.sh` init. | human (confirm); infra-engineer |
| N24 | INFO | `scripts/tf-env.sh:207-234` | Check-then-use on the shared `infra/terraform/.terraform`: another script re-initialising between `tf_env_require_verified` and `terraform output` could still redirect one read. The re-check narrows but does not close the window. Serialise runs, or use a per-run `TF_DATA_DIR` once N3 is done. | infra-engineer |
| N25 | INFO | `scripts/run-bounded-pipeline.sh:31,35` | Timeout `08` or `09`: bash treats these as invalid octal and errors out. This fails closed and is cosmetic. | infra-engineer |

Ownership check: every repair-04 change is in `infra/terraform/**` or `scripts/*.sh`, which infra-engineer owns. No violations. `.claude/agents/test-engineer.md` is out of scope, as the human decided.

### 8. Verdict
**APPROVE for repair-04.** The two Pass 2 blockers (N1 and N2) are fixed and the region decision is applied consistently. Nothing blocks a first human-reviewed `./scripts/plan.sh dev`.

**Before running `plan.sh dev`** (MANUAL ACTION):
- Export `AWS_REGION=us-east-2`.
- Confirm the state bucket and lock table exist in us-east-1 (N23).

**During plan review:** confirm there are zero replaces, all ARNs and AZs are us-east-2, and N15 holds.

**Before the first apply:**
- **Required:** a human decision on N16 (restrict ALB ingress, or explicitly accept a public unauthenticated HTTP API for bounded dev).
- **Recommended:** AWS-0052, the ALB and Neptune egress fixes from AWS-0104, and N20.
- **Before R7 is implemented:** correct the R7 proposal per N17 and N21.
- **Before prod:** N22.

FINDINGS: N16–N25 as tabulated in §7, with owners.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- Reads of the diff, review-report, scripts, Terraform, Scala sources and `reference.conf`.
- `grep`/`sed`/`cat -n`/`diff` across those files; `ls` of `infra/terraform/.terraform` (no backend state file).
- `jq` over `trivy-r4-{dev,prod}.json`.
- `/bin/bash -n` on all 8 scripts.
- One isolated `/bin/bash` 3.2 test that sources `tf-env.sh` with a pre-seeded `TF_ENV_VERIFIED_ENV`. This reads local files only and makes no AWS or terraform calls.
TEST RESULTS: not applicable (read-only reviewer); relied on the orchestrator's observed `sbt -batch check` exit 0 (614 tests) and terraform fmt/validate exit 0.
VALIDATION RESULTS:
- `bash -n` 8/8 OK under 3.2.57.
- The pre-seed test refused with exit 3.
- Trivy dev and prod failure sets are identical (47 each).
- All five DynamoDB keys match the client.
- No unguarded `terraform output` in the N2 scripts.
ASSUMPTIONS:
- AWS behaviour is taken from AWS documentation and not verified live: Bedrock geo-profile IAM shape and the `bedrock:InferenceProfileArn` condition key, Neptune `kms_key_arn` ForceNew, EMR egress needs, `dynamodb_table` deprecation.
- Model availability per the orchestrator's 2026-11-23 research notes.
- No Terraform state exists anywhere.
RISKS:
- N16 exposure if applied as-is.
- R7 implemented per the current proposal would deny the real caller.
- The smoke test can never pass (N18), so post-deploy evidence would show FAIL regardless of health.
MANUAL ACTION REQUIRED:
- Confirm the state bucket and lock-table region.
- Decide N16 before apply.
- Re-issue R7 with the corrected caller mapping.
- Complete Anthropic model enablement in the account when R7 proceeds.
- Make the prod CMK decision (N22).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human-reviewed `./scripts/plan.sh dev` (AWS_REGION=us-east-2); N16 decision before any `deploy.sh`

No files were modified by this review.

## Pass 4 — 2026-11-23 — VERDICT: APPROVE (repair-05 scope). Nothing here blocks a human-reviewed `plan.sh dev` once the tree is committed. Apply is gated: see §7.

The orchestrator transcribed this from code-reviewer's final message. Formatting was tightened for Markdown; no finding, status, path or claim was changed.

STATUS: COMPLETE · OWNER: code-reviewer · VERDICT: APPROVE

**Scope:**
- The repair-05 diff (`<session-scratchpad>/tf-phase12-r5.diff`, 23 files), checked against the live `infra/terraform/**` and `scripts/*.sh`.
- `git diff -- modules/storage modules/core`.
- `trivy-r5-{dev,prod}.json`.
- Cross-read: `Routes.scala`, `RuntimeComposer.scala`, `JsonCodecs.scala`, `PathFinderAgent.scala`, and `repair-04.md` §5.3.

**Summary:**
- Every claimed fix is in the code and behaves as described. In bash 3.2 the EXIT-trap and lock design is correct.
- No regression blocks planning.
- There are 4 MINOR and 6 INFO new findings (N26–N35).
- N16 restricts ALB access to an operator allowlist, as requested, but the allowlist check can be bypassed (N28). The human still has to choose between restricting and accepting a public API.

### 1. Claimed fixes

| ID | Result | Evidence | Note |
|---|---|---|---|
| N3 | FIXED | `scripts/tf-env.sh:62-79`, called from `tf_env_validate_name` and `tf_env_init_backend` | Tested under bash 3.2.57 with rc=3. `TF_VAR_*` is not refused (N30). |
| N4 | FIXED | `S3VectorsClient.scala:212-250,356`; tests `SignedStorageClientsSpec.scala:287-330` | No production caller. |
| N5 | FIXED | `modules/storage/main.tf:127-130` | |
| N6 | FIXED | `plan.sh:74-88`; `tree_clean=yes`; `deploy.sh:73` | `.claude/.last-test-run` is gitignored. See N32. |
| N7 | FIXED | `deploy.sh:96-139`; `PLAN_SCOPE=ecr-bootstrap`; `modules/ecs/main.tf:144-172` | Side effect: N29. |
| N8 | FIXED | `archive-evidence.sh:155-182`; `cleanup.sh:223-236` | The checksum grep matches the `shasum` format. |
| N9 | FIXED, with an ordering issue (N27) | `cleanup.sh:330-583` | |
| N10 | FIXED | `SignedStorageClientsSpec.scala:174-195` | |
| N11 | FIXED (syntax) | `NeptuneClient.scala:127-135,201-214`; tests `:457-495` | The query is expensive and the ORDER BY form is unverified (N35). |
| N12 | FIXED for EMR. MFA trust is documented; a human decision is needed only if Identity Center is used. | `modules/iam/main.tf:752-768`, `:632-641` | ResourceTag support comes from AWS documentation. |
| N13 | FIXED | `modules/neptune/main.tf:49-54,91`; `cleanup.sh:305-325` | The `terraform_data` stamp must be a create in plan review. |
| N16 | FIXED in code (restrictive option). Human decision still outstanding. | `variables.tf:60-83`; `network/main.tf:269-282`; `plan.sh:94-140` | Bypassable (N28). No TLS or auth yet; AWS-0054 and AWS-0053 remain, accepted for dev only. |
| N18 | FIXED (script) | `smoke-test.sh:84-98,140-205` | Test 4 FAILs against stubs (N31). |
| N19 (infra) | FIXED | `modules/ecs/main.tf:30,39-51,347-354,477-489,512-519` | The durable shared-state fix is still open (service-engineer). |
| N20 | FIXED | `modules/neptune/main.tf:21-28,97`; `archive-evidence.sh:196-197`; `cleanup.sh:223-236` | AWS-0075 is gone. |
| N24 | FIXED | `tf-env.sh:166-245` | See §2. |
| N25 | FIXED | `run-bounded-pipeline.sh:34-38`; `cleanup.sh:90-95,214`; `rollback.sh:71-77` | |
| R7 stage 1 | FIXED | `modules/iam/main.tf:303-310`. The only Bedrock grant left is the loader's Titan ARN (`:459`). | Stage 2 is WAITING-human. |
| AWS-0052 | FIXED | `modules/ecs/main.tf:309` | |
| AWS-0104 (ALB, Neptune, app) | FIXED / narrowed | `network/main.tf:284,333,346,361-373` | Neptune has no egress; side effect N26. EMR is unchanged by design. |
| Unrequested target-group and alarm changes | CORRECT | `ecs/main.tf:339-340,281`; `observability/main.tf:234` | No remaining references to the removed target groups (Step Functions uses task-definition families). |

### 2. Regression hunt (summary)

- **Lock and trap:**
  - Under `/bin/bash` 3.2.57, `$(...)` and `( ... )` subshells, including an `exit 3` inside one, do not run the parent's EXIT trap. The lock is removed only by its owner.
  - Exit codes 0, 3, 7 and a `set -e` failure (1) are preserved.
  - The `deploy.sh:202` → `smoke-test.sh` child re-enters with the inherited token for the same env only.
  - No script sets its own trap.
  - Residual: a SIGKILL leaves a stale lock that a human must remove; the narrow window between `mkdir` and the token write fails closed.
- **bash 3.2:**
  - `${arr[@]+…}` guards are used, and `compgen -e`, `=~ {m,n}` and `< <(…)` all work.
  - `valid_ipv4_cidr` accepts `0.0.0.0/1` and `128.0.0.0/1` (N28).
  - There is one unguarded arithmetic expansion at `cleanup.sh:520` (N33).
- **cleanup EMPTY phase:**
  - Nothing is deleted before the verified archive, the checksummed inventories and copies, and the typed EMPTY confirmation.
  - However, deletion happens before the destroy plan is built and checked, and before the destroy confirmation (N27).
  - Partial-failure accounting is sound.
- **deploy ECR check:** correct and read-only. It requires an `embedding-worker` image (N29).
- **plan.sh clean tree:**
  - `.claude/.last-test-run` does not block it.
  - 131 paths are currently dirty or untracked, including the constitutional `.claude/agents/test-engineer.md` (needs ratification).
  - Any commit between plan and deploy forces a re-plan, by design.
  - `init` runs without `-lockfile=readonly` (N32).
- **Security groups:**
  - Health checks use 8080. Fargate pulls and logs go out on 443 to the endpoints, the S3 gateway and NAT.
  - EMR master reaches Neptune on 8182. Core nodes have no 8182 rule (pre-existing; unused today).
  - The Neptune bulk loader would lose S3 egress (N26).
  - Adding SG descriptions forces replacement. No state exists, so this is a plan-review item only.
- **EMR tag-condition IAM:** the shape is correct. Tags come from provider `default_tags`.

### 3. Storage and core

- N4, N10 and N11 are as above, with meaningful, deterministic tests.
- Region defaults are us-east-2 in storage and core. The 4 core "Region config" tests are env-independent.
- No `us-east-1` remains in `modules/*/src/main`.
- The generation model ID is deliberately unchanged (N21).
- No ownership violations.

### 4. Trivy (dev and prod identical: 37 each — 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW)

| ID | Location | Now |
|---|---|---|
| AWS-0054 CRITICAL | `modules/ecs/main.tf:316` HTTP:80 | Accepted for bounded dev with the allowlist (subject to N28). Not for prod. |
| AWS-0104 CRITICAL | `modules/network/main.tf:339` app 443 | Narrowed as recommended. Accepted. |
| AWS-0104 CRITICAL | `modules/network/main.tf:400,420` EMR | Accepted by design. |
| AWS-0053 HIGH | `modules/ecs/main.tf:302` internet-facing | Mitigated by the allowlist. Accepted for dev pending the N16 decision. |
| AWS-0128 HIGH | `modules/neptune/main.tf:80` | Accepted for dev. Prod needs a CMK decision (N22). |
| AWS-0132 HIGH | `modules/storage/main.tf:37` | Accepted for dev. |

- **Resolved:** AWS-0052, AWS-0075, the 443 ingress rule, and ALB and Neptune egress.
- AWS-0017 went from 5 to 6 (the new audit log group).
- No new class of finding.

### 5. Status of all findings

| ID | Status |
|---|---|
| R1–R6, R8–R22 | FIXED (no regression; R9, R10 and R11 re-checked) |
| R7 | FIXED (stage 1); stage 2 WAITING-human |
| R23 | DEFERRED-by-human |
| N1, N2, N3, N4, N5, N6, N7, N8, N10, N11, N13, N18, N20, N24, N25 | FIXED (N18 expected to FAIL against stubs, N31) |
| N9 | FIXED, with ordering issue N27 |
| N12 | FIXED (EMR); MFA is a human decision only if SSO is used |
| N14 | OPEN, human to assign; now also affects deploy (N29) |
| N15 | OPEN: human plan review. Every SG, rule and `terraform_data` must be a create. |
| N16 | FIXED in code (restrictive). WAITING-human. HTTPS and auth required before prod. Bypass N28. |
| N17 | CLOSED |
| N19 | Infra FIXED; durable fix OPEN (service-engineer) |
| N21 | OPEN (info), before R7 stage 2 |
| N22 | OPEN, human, before prod |
| N23 | OPEN, human to confirm |

### 6. New findings

| ID | Severity | File:line | Impact | Owner |
|---|---|---|---|---|
| N26 | MINOR (latent) | `modules/network/main.tf:361-373`; `modules/neptune/main.tf:104`; `NeptuneClient.scala:165` | With zero egress, the Neptune bulk loader cannot reach S3 through the gateway endpoint. Fix: egress tcp/443 to `aws_vpc_endpoint.s3.prefix_list_id`. | infra-engineer |
| N27 | MINOR | `scripts/cleanup.sh:330-583` vs `:585-664,673` | Contents and ECR images are deleted before the destroy plan is checked and confirmed. If the run then aborts or the operator declines, the environment is left running with no images. Fix: build and check the destroy plan first, then EMPTY, then the destroy confirmation, then apply the reviewed plan. Whether raw data is reproducible is a human data-loss decision. | infra-engineer; human |
| N28 | MINOR | `scripts/plan.sh:94-140`; `variables.tf:60-83` | `0.0.0.0/1,128.0.0.0/1` covers the whole IPv4 internet and passes both checks. Fix: a minimum prefix length (e.g. /16) or a recorded wide-range acknowledgement. | infra-engineer |
| N29 | MINOR | `scripts/deploy.sh:96,120-139` | The first full deploy requires `embedding-worker:<sha>`, but no worker entry point exists (N14) and its desired count is 0. Fix: skip or flag desired-count-0 services, or have a human approve a documented placeholder. | infra-engineer; human (N14) |
| N30 | MINOR | `scripts/tf-env.sh:62-79` | `TF_VAR_*` is not refused, so an exported value could silently override a defaulted, human-approved variable (e.g. `titan_embedding_model_arn`). Fix: refuse `TF_VAR_*`. | infra-engineer |
| N31 | INFO | `RuntimeComposer.scala:37`; smoke Test 4; `deploy.sh:202-222` | Against stubs, every deploy ends with `DEPLOY APPLIED WITH FAILURES` and exit 1. That is honest. A human must accept it, or service-engineer must deliver the real composition. | human; service-engineer |
| N32 | INFO | `tf-env.sh:267`; `plan.sh:74-88` | `init` without `-lockfile=readonly` can rewrite the lock file and dirty the tree (fails closed). The clean check runs before init and plan. | infra-engineer |
| N33 | INFO | `scripts/cleanup.sh:520` | A jq failure makes the arithmetic a syntax error, so `set -e` exits without the abort summary. Key/path conflicts in `s3 sync` block cleanup, which is safe. | infra-engineer |
| N34 | INFO | `modules/ecs/main.tf:118-122` | Stale comment ("warns"). | infra-engineer |
| N35 | INFO (latent) | `NeptuneClient.scala:201-214`; `PathFinderAgent.scala:18-22` | The undirected `*1..d` match over all edge types grows exponentially and will hit the 30 s timeout. `ORDER BY length(p)` after `RETURN` refers to an unprojected variable; Neptune acceptance is unverified. Safer: `WITH p ORDER BY length(p) LIMIT 1 RETURN …`. | storage-engineer |

### 7. Verdict and gates

**APPROVE for repair-05.**

**Before `plan.sh dev`:**
- Commit the whole tree. This includes ratifying or reverting `test-engineer.md`.
- `AWS_REGION=us-east-2`.
- Confirm N23.
- Narrow `ALB_INGRESS_CIDRS`.
- `PLAN_SCOPE=ecr-bootstrap` first.

**During plan review:**
- Zero replaces; SGs, rules and `terraform_data` are all creates.
- us-east-2 everywhere.
- Ingress only for the supplied CIDRs.

**Before the first full apply:**
- Required: the N16 decision.
- Recommended: N28 and N30.
- Decide N29 and accept N31.
- N26 before Neptune bulk loading is used.

**Before any `cleanup.sh` run:** N27, plus the human data-loss decision.

**Before prod:** N22, HTTPS and authentication, and R7 stage 2 with N21.

**Process notes:**
- Commands: reads; grep, sed and jq; git status and diff; bash 3.2.57 local tests only (trap and lock, exit-status preservation, override refusal, 14 CIDR inputs). No AWS or terraform calls.
- Files created: none, apart from throwaway scripts in `<session-scratchpad>/locktest/`. Files modified: none.
- Assumptions from AWS documentation, not verified live: EMR ResourceTag support, Neptune loader egress, the audit log group name, and the QueryVectors limit.

No files were modified by this review.

## Pass 5 — 2026-11-23 — VERDICT: APPROVE (repair-06 scope)

The orchestrator transcribed this from code-reviewer's final message. Formatting was tightened for Markdown; no finding, status, path or claim was changed.

STATUS: COMPLETE · OWNER: code-reviewer · VERDICT: APPROVE

Every claimed fix is in the code and works as described. The new `tf_env_exit` and EXIT-trap rule behaves correctly under `/bin/bash` 3.2.57, which is the only bash on this machine.

No regression blocks a human-reviewed `plan.sh dev` once the tree is committed. One commit item is new: `.terraform.lock.hcl` has never been committed (N38).

New findings: 1 MINOR (latent) and 4 INFO (N36–N40).

### 1. Claimed fixes

| ID | Result | Evidence |
|---|---|---|
| N26 | FIXED | `infra/terraform/modules/network/main.tf:359-393`. There is exactly one egress rule: tcp/443 to the S3 prefix list. The S3 endpoint is on the private route table (`:151-158`), which the `private_data` subnets use (`:141-145`). Neptune uses those subnets and the data SG (`infra/terraform/main.tf:155-156`). Trivy does not flag the rule. The SG description change forces replacement; plan-review only (N15). |
| N27 | FIXED | `scripts/cleanup.sh`, in phase order: 1–4 at `:218-321`; destroy plan at `:411-499` (non-delete actions refused, retained addresses refused, empty-delete refused at `:481`, sha256 at `:483`); archive at `:504`; EMPTY at `:658` (warning, then typed confirmation at `:702`, recorded before deletes); destroy confirmation at `:845`; apply at `:854-869` (file, sha256 and `.sha256` re-checked, backend re-verified). Nothing is deleted before the plan check and EMPTY. The saved plan stays valid because emptying does not touch state; a stale plan fails closed. `DATA_EMPTIED` is set before the first delete (`:732`, `:795`) and is not lost in a subshell. Abort titles are accurate (`:182-197`). An unapplied plan is removed on abort or unexpected exit (`:174-180`, `:203-213`). Leftover risk: declining at phase 8 after EMPTY leaves contents gone but nothing destroyed. This is documented and accepted at EMPTY. |
| N28 | FIXED | `scripts/plan.sh:119,163-169`; `variables.tf:84-91`. 18 inputs tested under bash 3.2. The HCL rule is error-safe. Leftover: `/16` (65,536 addresses) per entry with no limit on the number of entries, which is part of the human N16 decision. |
| N30 | FIXED | `scripts/tf-env.sh:74`. All required variables come from tfvars or `-var`, so nothing breaks. See N39. |
| N32 | FIXED (INFO N37) | `scripts/tf-env.sh:304`: `-lockfile=readonly` with `-reconfigure`. `plan.sh:85-109,194,213-217`. Plan output goes to gitignored `local-runs/`. Depends on N38. |
| N33 | FIXED | `scripts/cleanup.sh:139` (`is_count`) at `:572,600,756,812`; `s3 sync` message `:619-629`. |
| N34 | FIXED | `modules/ecs/main.tf:121-126`. |
| N35 | FIXED (INFO N40) | `NeptuneClient.scala:190,199,223-231`; the default is `Vector("COAUTHOR")`, never an untyped match; depth clamp 1..10; `ttl` documented as epoch seconds; tests `SignedStorageClientsSpec.scala:457-510`. |
| Exit-code follow-up | FIXED | All 7 scripts exit 2 on a usage error (`archive-evidence.sh:47`, `cleanup.sh:103`, `deploy.sh:32`, `plan.sh:52`, `rollback.sh:28`, `run-bounded-pipeline.sh:29`, `smoke-test.sh:43`). `tf-env.sh:196-222`. |

### 2. Regression hunt (summary)

- **Trap contract** (bash 3.2.57, 12 cases):
  - Falling off the end gives 1; `tf_env_exit 0` gives 0; `tf_env_exit 5` gives 5.
  - Unbound variable, `${x:?}`, arithmetic error and a `set -e` failure each give 1.
  - Subshell exits do not run the parent's trap.
  - No `tf_env_exit`, `summary_and_exit` or `abort` sits inside a substitution or a piped loop.
  - A child script's rc reaches the parent unchanged, and a failing cleanup hook does not change rc.
  - No hook calls `exit`.
- **Success paths:** every one goes through `tf_env_exit`. No `exit 0` remains.
- **deploy → smoke-test:** `SMOKE_RC` is captured. The child inherits the lock token without owning the lock. N31 is unchanged.
- **cleanup unexpected exits:** guard exits, `set -e`, Ctrl-C and EOF all print an accurately titled summary and discard an unapplied plan. After `APPLY_STARTED=yes`, the title says resources may be partially destroyed.
- **COAUTHOR default:** it matches `getDirectCoauthors` and the test fixtures, but nothing in the repo produces Neptune data. `GraphExportWriter.scala` writes Parquet with no `~label`, and the `edgeType` in `core/Graph.scala` is free-form (N36).

### 3. Trivy (r6: dev and prod identical, 37 each — 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW)

The findings match r5 by ID and file; only line numbers moved:
- AWS-0054: `ecs/main.tf:319`
- AWS-0104: `network/main.tf:339,420,440`
- AWS-0053: `ecs:305`
- AWS-0128: `neptune:80`
- AWS-0132: `storage:37`
- AWS-0090 ×3 and AWS-0178
- LOW: 0017 ×6, 0025 ×5, 0033 ×6, 0089, 0099 ×3, 0124 ×5

All are in the accepted classes. The new data-SG prefix-list egress is not flagged.

### 4. Status of all findings

| ID | Status | Owner |
|---|---|---|
| R1–R6, R8–R22 | FIXED | — |
| R7 | Stage 1 FIXED; stage 2 WAITING-human (with N21) | human; infra-engineer |
| R23 | DEFERRED-by-human | human |
| N1–N8, N10, N11, N13, N18, N20, N24, N25 | FIXED | — |
| N9 | FIXED (ordering issue N27 fixed) | — |
| N12 | FIXED (EMR); MFA is a human decision only if SSO is used | human |
| N14 | OPEN: human to assign the embedding-worker entry point | human |
| N15 | OPEN: human plan review (zero replaces; SGs, rules and `terraform_data` all creates; the data-SG description changed) | human |
| N16 | FIXED in code; WAITING-human. HTTPS and auth required before prod. N28 closes the bypass; `/16` width needs acceptance. | human |
| N17 | CLOSED | — |
| N19 | Infra FIXED; durable shared-state fix OPEN | service-engineer |
| N21 | OPEN (info), before R7 stage 2 | human; rag-engineer |
| N22 | OPEN, before prod | human |
| N23 | OPEN, human to confirm | human |
| N26, N27, N28, N30, N32, N33, N34, N35 | FIXED. N27's data-loss decision is captured at EMPTY but must still be made. | — / human |
| N29 | OPEN (first full deploy requires an `embedding-worker:<sha>` image) | infra-engineer; human (N14) |
| N31 | OPEN (info): accept stub smoke FAIL, or deliver the real composition | human; service-engineer |

### 5. New findings

| ID | Severity | File:line | Issue | Owner |
|---|---|---|---|---|
| N36 | MINOR (latent) | `NeptuneClient.scala:108,199`; `core/Graph.scala:14-21`; `spark/.../GraphExportWriter.scala` | There is no shared Neptune label and property contract. No producer emits `COAUTHOR`, `Researcher` or `researcherId`, so a loader with different names would make the queries silently return nothing. Fix: label constants in core, used by spark and storage, plus a contract test. | domain-engineer (contract); spark-engineer / storage-engineer; human to assign the loader |
| N37 | INFO | `scripts/plan.sh:85-109` | `require_clean_tree … \|\| exit 2` suppresses `set -e` inside the function, so a `git status` failure counts as clean. Fix: `dirty="$(git …)" \|\| return 2`, and the same for `rev-parse`. | infra-engineer |
| N38 | INFO (commit gate) | `infra/terraform/.terraform.lock.hcl` | Untracked and never committed, although `.gitignore:38` says it is tracked. `-lockfile=readonly` relies on it. | infra-engineer; human commit |
| N39 | INFO (optional hardening) | `scripts/tf-env.sh:74` | `TF_CLI_CONFIG_FILE` (`dev_overrides`), `TF_REATTACH_PROVIDERS` and `TF_PLUGIN_CACHE_DIR` are not refused. | infra-engineer |
| N40 | INFO (latent) | `NeptuneClient.scala:229-231`; `PathFinderAgent.scala:18-22` | An undirected `*1..10` match over COAUTHOR enumerates every path before `LIMIT` and may hit the 30 s timeout. Measure on the bounded sample, then lower the default depth or use bidirectional BFS. | experiment-engineer; rag-engineer; storage-engineer |

**Note for the N27 data-loss decision:** recovery depends on the `datasets/` scripts. In the working tree, `download-dblp-sample.sh` is deleted and replaced by `download-dblp.sh` plus `dblp_*.py`. Confirm with dataset-engineer that raw data can be re-fetched before any cleanup.

### 6. Verdict and gates

**APPROVE for repair-06.**

**Before `plan.sh dev`:**
- Commit the whole tree, including `.terraform.lock.hcl` (N38).
- Ratify or revert `.claude/agents/test-engineer.md`.
- `AWS_REGION=us-east-2`.
- Confirm N23.
- Narrow `ALB_INGRESS_CIDRS` (/16–/32).
- No `TF_*` overrides exported.
- `PLAN_SCOPE=ecr-bootstrap` first.
- N37 is recommended.

**During plan review:**
- Zero replaces; every SG, rule and `terraform_data` is a create, including `data_to_s3_endpoint`.
- us-east-2 everywhere.
- Ingress only from the supplied CIDRs.

**Before the first full apply:**
- The N16 decision, including accepting the `/16` width.
- Decide N29 and N14, and accept N31.
- N36 before relying on Neptune loading or path queries.

**Before any `cleanup.sh` run:**
- The data-loss decision, with re-fetchability confirmed by dataset-engineer.
- Understanding that declining after EMPTY leaves contents deleted.

**Before prod:** N22, HTTPS and auth, R7 stage 2 with N21, and N40 measured.

**Process notes:**
- Commands: reads; grep, sed, awk and jq; git status and diff; bash 3.2.57 scratch tests (trap contract, 12 cases; CIDR validation, 18 cases). No AWS, terraform or sbt runs, and no `.env` files.
- Files modified: none. Throwaway files only, in `<session-scratchpad>/r7trap/`.
- Assumptions from documentation: readonly init on platforms without an h1 hash, Neptune loader S3 endpoint use, and stale-plan rejection.

No files were modified by this review.

## Pass 6 — 2026-11-23 — VERDICT: repair-07 APPROVE (conditional); real-data path REQUEST_CHANGES

**RECONSTRUCTED.** The orchestrator could not extract Pass 6's full text from the session log, so this section was rebuilt from the orchestrator's summary of the hand-back. Finding IDs, severities and owners are as reported; the wording is condensed.

**Scope:** repair-07 (N36, N37, N39) and the 500-record local real-data run `rs-20261123-dblp10k-first500`.

**Verdicts**
- repair-07: APPROVE on two conditions:
  - `sbt check` passes after formatting.
  - N41 and N43 are routed before any Neptune load or service exposure.
- Real-data path: REQUEST_CHANGES.
  - The reported numbers matched the artifacts.
  - The 12 uncommitted Spark files were formatting-only.

| ID | Severity | Finding | Owner(s) |
|---|---|---|---|
| N44 | Required | Provenance does not trace to DBLP or the ER run. The `policyVersion` default "v1" is wrong; the real policy is `er-mention-v1-uncalibrated`. Mixed policies are not refused. Hadoop pairs use `sourceDataset` "scholarchain". | spark, hadoop, domain |
| N45 | Required | `enrich_fetch.py` `--max-records` counts records, not requests. Retries go up to 6×. OpenAlex page 1 is unbounded. Redirects can go to any host while carrying the contact header. | dataset |
| N41 | HIGH | `NeptuneResponseParser` cannot parse Neptune's `~id`/`~labels`/`~properties` shape. The export lacks fields the parser requires. | storage, spark, domain, test |
| N42 | HIGH | `run-bounded-pipeline.sh` and `emr/main.tf` call `hadoop.Main` and `spark.Main`, which don't exist, with stale arguments. The hadoop jar has no `mainClass`. | infra, build, hadoop |
| N43 | MED | An undeclared `edgeTypes` value throws IllegalArgumentException and returns 500 instead of 400. | service |
| N46 | LOW | The `~/.terraformrc` `plugin_cache_dir` keys are not checked. | infra |
| N47 | LOW | In `cleanup.sh`, an s3vectors `list-indexes` error is swallowed. | infra |
| N48 | LOW | `deploy.sh` does not check it is at the repo toplevel and does not refuse `GIT_CONFIG_*`. | infra |
| N49 | LOW | Empty part files at the export root; gaps in the verifier. | hadoop |
| N50 | LOW | Work IDs carry a doubled `work:dblp:dblp:` prefix. | hadoop, domain (human decision) |
| N51 | LOW | The DBLP person-key rule uses the normalized name instead of the exact `baseName`. | entity-resolution |
| N52 | LOW | Betweenness is written as 0.0 when it was skipped. `RealDataRegressionSpec` is misnamed. | spark |
| N53 | LOW | `RUN-SUMMARY` records a dirty HEAD, and `repair-07.md` is missing. | orchestrator, producers |

## Pass 7 — 2026-11-24 — VERDICT: APPROVE (repair-08 scope), on two conditions

**Reviewer:** code-reviewer on Opus, read-only. It ran no sbt.

**Conditions**
1. The orchestrator's concurrent `sbt -batch check` exits 0. **Met:** exit 0, 828 tests per pass, marker `2026-11-24T11:06:11.093784Z`.
2. Before the Phase 06 gate, commit, then rerun the slice from the committed SHA using the assembly jars and a unique run ID per stage (N54, N55).

**Verified independently**
- **Formatting split:** of the 27 files, 17 are formatting-only and 9 also carry repair-08 changes. `RealDataRegressionSpec` is deleted (renamed). No entity-resolution code changed.
- **Label:** no live use of `COAUTHOR` remains outside `docs/agent-runs`. The only occurrences left are retired-label rejection tests and injection strings.
- **Neptune CSV recount:**
  - 1,901 Researcher, 500 Work, 3,531 `COAUTHORED_WITH`, 1,907 `AUTHORED`.
  - All 7,839 rows carry `sourceDataset=dblp`, `policyVersion=er-mention-v1-uncalibrated`, upstream run IDs and a per-job producer.
  - Descriptive properties are 100% populated.
- **Hadoop export:**
  - Every output has `sourceDataset=dblp`, the fixed timestamp and a J01, J06 or J07 producer.
  - 414 mentions carry an ORCID.
  - No files at the export root.
  - r08 and r08b are identical: 10 of 10 part files.
- **Spark manifest:**
  - Linkage verified; 0 decisions without an input run; the policy is derived from the decisions.
  - Betweenness exact; k-core converged in 14 of 5,000 iterations; LPA converged.
  - Input sha256 values match the Hadoop part files.
- **ORCID merges:**
  - All 6 Accepted decisions are true persons.
  - No ORCID maps to two names.
  - The 8 `orcid_mismatch` rejections are true negatives.
  - No false merge. The node count drops from 1,905 to 1,901, matching the 4 new merges.
- **Leakage scan:** no emails, keys, user name or institution domain in the diff or untracked files.
- **Not independently verified:** the Parquet metric and summary claims, because pyarrow is unavailable. These rest on the orchestrator's Spark-reader check.

**Prior findings**

| Finding | Status |
|---|---|
| N44 | CLOSED (code); evidence caveat N54 |
| N45 | CLOSED |
| N49 | CLOSED |
| N52 | CLOSED |
| N53 | UNVERIFIABLE by the reviewer at the time, because Pass 6 was not in this file. This file now carries Pass 6; `repair-07.md` is added as a reconstruction. |

| Spark-review item | Status |
|---|---|
| M_r work IDs | CLOSED |
| Bridge labels | CLOSED; wording note N62 |
| Skipped betweenness vs zero | CLOSED |
| k-core cap | CLOSED |
| Run manifest | CLOSED; caveats N54, N55 |
| `toLong`/`toStr` exposure | CLOSED |
| Betweenness seed | CLOSED |
| LPA convergence | CLOSED |
| ER→Hadoop run check | CLOSED; teams are not checked (N57) |

**New findings**

| ID | Severity | Finding | Owner |
|---|---|---|---|
| N54 | MED | `rerun-r08.sh` reuses one run ID for every Hadoop run, ER input, Spark `--run-id`, and the baseline. `erLinkage=verified` therefore cannot tell runs apart. Lineage is still traceable through sha256 values. Fix: unique run IDs per stage and execution. Optionally, Spark refuses a `--run-id` that appears among its upstream run IDs. | orchestrator/human; spark (guard) |
| N55 | LOW | The rerun used sbt class directories, not the assembly jars. The manifest has `jar.sha256=null` and `gitDirty=true`. | orchestrator/human |
| N56 | LOW | Under a single run ID, Hadoop `upstreamRunIds` just repeat the record's own run ID. J05 cross-source candidates still say "scholarchain"; a `dblp+crossref` style would be clearer. | hadoop |
| N57 | LOW | `checkErLinkage` does not compare team run IDs with pair run IDs. | spark |
| N58 | MED | Idempotency (pre-existing, not a regression): (a) no TTL or size cap; (b) the key is not bound to a request fingerprint or caller, so a different body replays the first answer (the IETF draft returns 422); (c) the leader has no deadline, so waiting followers can starve the 10-thread pool. Needs a separate work order. | service |
| N59 | LOW | Document that legacy metrics JSON reads back as `bridgeScoreMethod=exact`. | domain |
| N60 | LOW | DBLP ORCID validation checks the pattern only, with no ISO 7064 checksum, so a mistyped shared ORCID would force a merge. 0 conflicts in this run. | hadoop |
| N61 | LOW | `BudgetExhausted` exits 0, so it can't be told apart from a completed run without reading the run log. | dataset |
| N62 | LOW | Positive betweenness inside one community means brokerage, not a cross-community bridge. Report wording must not conflate the two. | spark (documentation-engineer for reports) |
| N63 | PROCESS | Pass 6 was missing from this file. Resolved by this append. | orchestrator |
| N64 | ER (separate) | 13 of 19 cross-paper persons are still unlinked, and Vitrià–Radeva is not consolidated. The policy is uncalibrated and LLM adjudication is disabled. Tetsuya Iwasaki has an ORCID on only one of his two mentions. | entity-resolution; calibration needs human review |

**Ownership:** every substantive edit is inside its owner's tree, and no constitutional files were touched.

**Commit-boundary recommendation, on top of `b52988e`**
- C1: formatting only (`fmt-only.diff`, 27 files).
- C2: core and storage together, as one atomic label change.
- C3: Hadoop.
- C4: Spark.
- C5: services idempotency.
- C6: dataset N45.
- C7: evidence (`docs/agent-runs/**`).

If staging hunks for C1 is impractical, fold C1–C4 into one commit and keep C5–C7 separate. Run `sbt check` at each intermediate commit.

No files were modified by this review.
