# Phase 12 repair 01: replace the S3 Tables placeholder with S3 Vectors

Date: 2026-11-22
Branch and state: `remediation`. Parent commit `753c499`. `infra/terraform/**` is untracked and nothing is committed.
Evidence assembled by: the orchestrator (Claude Code main session), from the human's report, the infra-engineer final report and the orchestrator's own rerun.

## 1. Trigger

- Source: a human check. The human architect reported running these in `infra/terraform`: `terraform fmt`, `terraform init -backend=false` and `terraform validate`.
- Reported result: `fmt` passed and `init` passed. `validate` **failed** at `infra/terraform/modules/storage/main.tf:191`, because AWS provider 5.31.0 does not support resource `aws_s3tables_table_bucket`.
- Severity: HIGH. The configuration could not be validated. S3 Tables is also the wrong service, because the specification requires Amazon S3 Vectors (`aws_s3vectors_vector_bucket` and `aws_s3vectors_index`) as the durable semantic tier.
- Observed vs expected: observed was an unsupported resource type and a failed validate. Expected was a valid configuration that defines an S3 Vectors bucket and the 5 required indexes.
- Human confirmation (recorded 2026-11-22 at the human's request): "I personally ran Terraform formatting, initialization with the backend disabled, and validation on my Mac. The first validation failed on aws_s3tables_table_bucket with provider 5.31.0. After the repair, formatting and validation passed with provider 6.26.0. I have not run plan or apply."
- Note: the exact terminal output of the human's run was not saved. This section records the failure as the human reported it. No original Phase 12 agent report exists in the repository, because the Phase 12 Terraform was created untracked before this remediation.

## 2. Routed to

`infra-engineer`. Its `docs/agents.md` row is "`infra/terraform/**`, `scripts/*.sh`". The orchestrator did not edit any Terraform file.

The agent reported these other owners as BLOCKED-ON (evidence in section 4):
- `rag-engineer`: the embedding dimension and the 256-element query vector.
- `domain-engineer`: the core `reference.conf` dimension, index-name and endpoint keys.
- `storage-engineer`: the distance metric and the metadata schema.
- `build-engineer`: `.gitignore:38` ignores `.terraform.lock.hcl`.

Approval for the major provider upgrade (hashicorp/aws 5.x to 6.x): the human's request for this repair explicitly asked the agent to "choose and pin a compatible AWS provider version".

## 3. Work order given (verbatim; local home and scratchpad paths shortened to `~` and `<session-scratchpad>`)

````text
Work order (Phase 12 repair, infra-engineer). Repository: ~/Documents/scholarchain. Branch `remediation`. Everything under infra/terraform/** is untracked, and the working tree has many other uncommitted changes from other owners. Preserve all of them. Do NOT stage, commit, push, run `terraform plan`, `apply`, `destroy` or `import`, or touch any AWS account. No AWS credentials are configured, and none are needed. Do not read .env or credential files.

## Trigger (human-observed failure, record it exactly)
The human ran `terraform fmt`, `terraform init -backend=false` and `terraform validate` in infra/terraform. fmt and init passed. Validate failed at `infra/terraform/modules/storage/main.tf:191`: AWS provider 5.31.0 does not support resource `aws_s3tables_table_bucket`. S3 Tables is the wrong service anyway. The course specification requires Amazon S3 Vectors as the durable semantic tier, using the Terraform resources `aws_s3vectors_vector_bucket` and `aws_s3vectors_index`.

## Tasks
1. Read infra/terraform/** (versions.tf or wherever required_providers is set, main.tf, modules/storage, modules/iam, modules/ecs, outputs and variables, environments/*.tfvars) and the current version constraint. Record the exact original constraint.
2. Find the lowest stable hashicorp/aws provider release that ships both `aws_s3vectors_vector_bucket` and `aws_s3vectors_index`. Verify this from the provider changelog or registry. If the network is available, use `terraform providers schema -json` after init, or `curl https://registry.terraform.io/v1/providers/hashicorp/aws/versions` and the GitHub CHANGELOG. Do not guess. Pin an exact version (`= X.Y.Z`) and choose a release at least 2 weeks old. This is a major provider upgrade (5.x to 6.x). The human explicitly authorised choosing and pinning a compatible version in this work order, so note that as the approval source. Fix any other code the major upgrade breaks (for example deprecated or renamed arguments such as `aws_region` `name`, or the s3 bucket sub-resources) and list every such change.
3. Replace the `aws_s3tables_table_bucket` placeholder with one `aws_s3vectors_vector_bucket` and one `aws_s3vectors_index` per index the application uses. Determine the application config from source. Do not edit it; it is read-only to you:
   - Index names. The spec requires researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1 and topic-institution-summary-v1. grep modules/storage, modules/rag, modules/core, modules/services and any *.conf or *.yaml config for index names, embedding dimension, and distance metric (cosine/euclidean) and data type (float32).
   - Use variables (with defaults that match the application) for bucket name, dimension, distance metric and index names. Do not hardcode an account ID. Use `for_each` over the index set.
   - Add non_filterable metadata keys where the provider supports them, for long pointer fields such as textS3Uri and textHash, consistent with the app's metadata schema. Filterable keys stay filterable.
   - Encryption: SSE-S3 (AES256) by default, with an optional KMS key variable if the module already has a KMS key. Tags consistent with the rest of the code.
   - Outputs: vector bucket name and ARN, plus a map of index name to index ARN. Wire them through the root module if other modules or the ECS env vars consume them. Make sure the ECS task env var names match what the application reads (for example VECTOR_BUCKET). Report the names you matched and their source file:line.
4. IAM: make sure the loader or embedding-writer role has only `s3vectors:PutVectors`, `GetVectors`, `ListVectors`, `DeleteVectors` (only if the app deletes), `GetIndex` and `ListIndexes` as needed, and the runtime reader role has only `s3vectors:QueryVectors`, `GetVectors` and `GetIndex`. Scope both to the bucket and index ARNs, not `*`. Remove any leftover `s3tables:*` permissions. Verify the action names from AWS docs where the network allows. If you can't verify, say so.
5. Remove every remaining S3 Tables reference (grep -rn s3tables infra/).
6. Run, from infra/terraform, and capture the exact output and exit codes:
   - `terraform fmt -recursive` then `terraform fmt -check -recursive`
   - `terraform init -backend=false -upgrade`
   - `terraform validate`
   - `bash -n scripts/*.sh` (syntax only)
   If validate fails, fix the issue within infra/terraform and rerun. Report each iteration. Keep `.terraform/` out of git (check `git check-ignore`). Report whether `.terraform.lock.hcl` was created or changed. It should be committed later, so don't ignore it.
7. Show `git diff --stat` is not applicable to untracked files. Instead list each file you changed, with a short before/after summary, and print a unified diff of your changes. Keep a pre-edit copy in the scratchpad <session-scratchpad>/tf-before/ (cp -R infra/terraform there BEFORE editing, excluding .terraform) and produce `diff -ruN --exclude=.terraform --exclude=.terraform.lock.hcl` against it.

## Final report
Use exactly these 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN (with exit codes and key output lines), TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Also include:
- the original constraint and the pinned version, with the source that proves the resources exist in that version
- a table matching app config to Terraform: index name, dimension, metric, env var, with file:line for each
- the full unified diff path in the scratchpad, and the diff text if it's under ~400 lines.

Never claim a command succeeded unless you saw its output. If the application config is missing or inconsistent (for example no dimension defined anywhere), do not invent one. Use a variable with no silent default where that's safe, or report it as BLOCKED-ON storage-engineer or rag-engineer with evidence.
````

## 4. Agent final report (verbatim, 14 fields)

STATUS: BLOCKED-ON

The Terraform repair is done and `terraform validate` passes. But two inputs have no correct value I can take from the application: the embedding dimension and the distance metric. I made them required variables with no default rather than invent them, so `terraform plan` will fail until they're set.

OWNER: infra-engineer

WORK ORDER: Phase 12 repair. Replace `aws_s3tables_table_bucket` with S3 Vectors (`aws_s3vectors_vector_bucket` and `aws_s3vectors_index`), pin a compatible hashicorp/aws 6.x release, fix the IAM, and wire outputs and ECS env vars.

FILES CREATED: none (all edits are to existing untracked files).

FILES MODIFIED (all under `~/Documents/scholarchain/infra/terraform/`):
- `versions.tf`: `~> 5.31.0` became `= 6.26.0`, with a comment explaining why.
- `modules/provider/main.tf`: this file had a second constraint, `~> 5.31.0`. It is now `= 6.26.0`.
- `modules/storage/main.tf`: removed the `aws_s3tables_table_bucket` and the comment-only index list. Added one `aws_s3vectors_vector_bucket` (SSE-S3 AES256, tags) and `aws_s3vectors_index` with `for_each` over the index names, including `metadata_configuration`.
- `modules/storage/variables.tf`: was 2 variables. Added `vector_bucket_name` (null means `<project>-<env>-vectors`), `vector_index_names` (the 5 names), `vector_dimension` (required, 1 to 4096), `vector_distance_metric` (required, cosine or euclidean), `vector_data_type` (float32) and `vector_non_filterable_metadata_keys` (`["textS3Uri","textHash"]`, at most 10).
- `modules/storage/outputs.tf`: added `vector_bucket_name`, `vector_bucket_arn`, `vector_index_arns` (index name to ARN) and `vector_dimension`.
- `modules/iam/main.tf`: `data.aws_region.current.name` became `.region` (deprecated in 6.0). The runtime statement is now `S3VectorsQuery`, and the loader statement is now `S3VectorsWrite`. Both are scoped to the index ARNs (details under VALIDATION RESULTS).
- `modules/iam/variables.tf`: added `s3vectors_index_arns`.
- `modules/ecs/main.tf`: added the env vars `SCHOLARCHAIN_S3_VECTORS_BUCKET`, `SCHOLARCHAIN_S3_VECTORS_REGION` and `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS`. Removed `health_check_custom_config.failure_threshold`, which is deprecated in 6.0 (AWS always uses 1).
- `modules/ecs/variables.tf`: added `vector_bucket_name` and `vector_dimension`.
- `main.tf`: storage now receives the S3 Vectors variables. IAM receives `values(module.storage.vector_index_arns)`. ECS receives the bucket name and dimension.
- `variables.tf`: added `s3_vectors_bucket_name`, `s3_vectors_index_names`, `s3_vectors_dimension` (no default), `s3_vectors_distance_metric` (no default) and `s3_vectors_non_filterable_metadata_keys`.
- `outputs.tf`: added `s3_vectors_bucket_name`, `s3_vectors_bucket_arn` and `s3_vectors_index_arns`.
- `environments/dev.tfvars` and `environments/prod.tfvars`: added a commented note that the two inputs are required and why. No values were set.
- `.terraform.lock.hcl`: regenerated by init. It now records version 6.26.0 (was 5.31.0) with constraint 6.26.0.

COMMANDS RUN (from `infra/terraform` unless noted):
- Snapshot: `rsync -a --exclude=.terraform infra/terraform <scratchpad>/tf-before/`. Exit 0.
- `terraform version`: v1.17.2 darwin_arm64.
- `terraform init -backend=false -upgrade` (first run, after changing the pin): exit 0. Output: "Installing hashicorp/aws v6.26.0... Installed hashicorp/aws v6.26.0 (signed by HashiCorp)".
- `terraform providers schema -json` in `infra/terraform`: exit 1, "Backend initialization required". I did not initialise the S3 backend. Instead I got the schema from scratch-only configs (provider pin only, no backend): `scratchpad/schema-probe` (6.26.0) and `scratchpad/schema-probe-6240` (6.24.0). Init and schema both exited 0 in each.
- `curl` to the registry `/v1/providers/hashicorp/aws/versions`, `/6.26.0` and `/6.24.0`; the GitHub `CHANGELOG.md` (raw, main); and the AWS docs pages `list_amazons3vectors.html` and `s3-vectors-limitations.html`. All exit 0.
- `grep -rn s3tables infra/ --exclude-dir=.terraform`: exit 1 (no matches). A stale-reference grep for `aws_region.current.name`, `vector-bucket/`, `GetVector"`, `PutVector"` and `ListVectorIndexes`: exit 1 (no matches).
- `terraform fmt -recursive`: exit 0, reformatted `versions.tf` (alignment after my comment).
- `terraform fmt -check -recursive`: exit 0, no output.
- `terraform init -backend=false -upgrade`: exit 0, "Finding hashicorp/aws versions matching "6.26.0"... Terraform has been successfully initialized!"
- `terraform validate`: exit 0, "Success! The configuration is valid." It passed on the first iteration, with no warnings.
- `bash -n` on each `scripts/*.sh` file (archive-evidence, cleanup, deploy, plan, rollback, run-bounded-pipeline, smoke-test): all exit 0.
- `git check-ignore -v infra/terraform/.terraform infra/terraform/.terraform.lock.hcl`: exit 0. `.gitignore:37` matches `.terraform/` and `.gitignore:38` matches `.terraform.lock.hcl`.
- `diff -ruN --exclude=.terraform --exclude=.terraform.lock.hcl tf-before/terraform terraform`: exit 1 (differences found, as expected). 14 files, 255 insertions, 31 deletions.
- `git diff --cached --name-only | wc -l`: 0. Nothing was staged.

TEST RESULTS: fmt check passed, validate passed, and every script passed `bash -n`. `terraform plan` was not run, as the work order instructs.

VALIDATION RESULTS:
- **Original constraint and pin:** the original was `version = "~> 5.31.0"`, in both `versions.tf:7` and `modules/provider/main.tf:9`. It is now pinned to `= 6.26.0`. Approval source: the human's Phase 12 work order explicitly authorised choosing and pinning a compatible version, including the 5.x to 6.x major upgrade.
- **Proof the resources exist in that version:**
  - CHANGELOG `## 6.24.0 (December 2, 2025)` lists "New Resource: `aws_s3vectors_index`" and "New Resource: `aws_s3vectors_vector_bucket`" (#43393).
  - CHANGELOG `## 6.26.0 (December 10, 2025)` adds the `encryption_configuration` and `metadata_configuration` blocks to `aws_s3vectors_index` (#45470).
  - The registry shows 6.24.0 published 2025-12-02T23:47:43Z and 6.26.0 published 2025-12-10T20:55:33Z, so both are more than 2 weeks old.
  - Schema probes: 6.24.0 has both resources, but the index has no blocks. 6.26.0 has both resources plus the `metadata_configuration.non_filterable_metadata_keys` block. Schema dump: `scratchpad/schema-6.26.0.json`.
- **Why 6.26.0 and not 6.24.0:** 6.24.0 is the strict minimum that ships both resources. But the work order also requires non-filterable keys, and S3 Vectors only accepts those when an index is created. On 6.24.0, adding them later would mean replacing all 5 indexes. 6.26.0 is the lowest release that meets every requirement.
- **Other 6.x breakage fixed:** `aws_region.name` became `.region`, and the deprecated `failure_threshold` was removed. I checked the 6.0.0 breaking-change list against every resource this code uses. `aws_eip` already used `domain`, and nothing else applied. Validate shows no deprecation warnings.
- **IAM actions verified against the AWS Service Authorization Reference (prefix `s3vectors`):**
  - PutVectors, GetVectors, ListVectors, DeleteVectors, GetIndex and QueryVectors apply to the Index resource. ListIndexes applies to the VectorBucket resource.
  - The ARN formats are `arn:${Partition}:s3vectors:${Region}:${Account}:bucket/${BucketName}` and `.../bucket/${BucketName}/index/${IndexName}`.
  - The old policy used `GetVector`, `PutVector`, `DeleteVector` and `ListVectorIndexes`, which do not exist, and the wrong ARN segment `vector-bucket/`. Those grants would never have matched.
  - Runtime role (`S3VectorsQuery`): QueryVectors, GetVectors and GetIndex. Loader role (`S3VectorsWrite`): PutVectors, GetVectors and ListVectors. Both are scoped to the 5 index ARNs, not `*`.
  - The loader does not get DeleteVectors: `S3VectorsStorageClient.deleteVectors` is defined but has no caller in `modules/*/src/main`. The only caller found is `putVectors`, at `EmbeddingBatchWriter.scala:41`. It also does not get GetIndex or ListIndexes, which the work order made optional and no code path calls.
  - There were no `s3tables:*` IAM permissions to remove. No `s3tables` references remain under `infra/`.
- **Limits verified (AWS docs, `s3-vectors-limitations`):** "Non-filterable metadata keys per vector index: Up to 10" and "Dimension value per vector: 1 to 4,096". Both are enforced in variable validation.

App config to Terraform mapping:

| Item | Application source (file:line) | Terraform (file:line) | Env var |
|---|---|---|---|
| Index names (5) | `modules/storage/.../S3VectorsClient.scala:55-59` (`all` at 61-67); used in `modules/rag/.../SemanticContextAgent.scala:21,29,37,39,41` | `variables.tf:146`, `modules/storage/variables.tf:26`, used with `for_each` at `modules/storage/main.tf:214` | none (hardcoded in the app) |
| Bucket | `modules/core/src/main/resources/reference.conf:7-8`, `modules/storage/src/main/resources/reference.conf:33-34` (default `scholarchain-vectors`) | `variables.tf:140`, `modules/storage/variables.tf:15`, `main.tf:195` | `SCHOLARCHAIN_S3_VECTORS_BUCKET` at `modules/ecs/main.tf:177` |
| Region | `modules/storage/src/main/resources/reference.conf:35-36` | `var.aws_region` | `SCHOLARCHAIN_S3_VECTORS_REGION` at `modules/ecs/main.tf:178` |
| Dimension | **Conflict:** core `reference.conf:25` = 1536; `modules/rag/.../EmbeddingModelConfig.scala:20` = 1024 for the default model `amazon.titan-embed-text-v2:0` (core `reference.conf:50`); `SemanticContextAgent.scala:97` builds a 256-element query vector | `variables.tf:158` and `modules/storage/variables.tf:43`, **no default** | `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS` (core `reference.conf:26`) at `modules/ecs/main.tf:179` |
| Distance metric | **Not defined anywhere** in `modules/**`, `prompts/**` or `docs/**` (searched for cosine and euclidean) | `variables.tf:163` and `modules/storage/variables.tf:53`, **no default** | none read by the app |
| Data type | `S3VectorsClient.scala:30` `data: Vector[Float]` | `modules/storage/variables.tf:63`, default `float32` | none |
| Non-filterable keys | `VectorMetadata.textS3Uri` and `textHash` at `S3VectorsClient.scala:21-22`. The other 8 keys stay filterable. | `variables.tf:168`, `modules/storage/variables.tf:74`, `main.tf:224` | none |

The work order's example env var `VECTOR_BUCKET` is not read anywhere in the app. The app reads `SCHOLARCHAIN_S3_VECTORS_*`, so that is what I wired.

ASSUMPTIONS:
- The work order's spec index list matches the code exactly (both have 5 names).
- The bucket name `<project>-<env>-vectors` keeps the previous Terraform naming. It is injected through env vars, so the app's unprefixed default is overridden.
- `ecs_task_loader` is the embedding-writer role.
- The index inherits SSE-S3 from the bucket, since its `encryption_configuration` is omitted.
- No KMS variable was added, because the configuration has no `aws_kms_key`.

RISKS:
1. **`plan` will fail until the dimension and metric are supplied.** This is deliberate.
2. **The app's 256-element placeholder query vector** (`SemanticContextAgent.scala:97`) will be rejected by any index whose dimension is not 256.
3. **`.terraform.lock.hcl` is gitignored** at `.gitignore:38`, so it cannot be committed as the work order wants. `.gitignore` is owned by build-engineer. The lock file also only has the `h1:` hash for darwin_arm64; CI on Linux would need `terraform providers lock -platform=linux_amd64`.
4. **The provider block sits in a child module** (`modules/provider`). This predates my changes. The root has no `provider "aws"` block, so `region` and `default_tags` do not apply to the other modules. Plan would depend on `AWS_REGION` in the environment, and only the resources' own `tags` get applied.
5. **The loader role is not attached to any task definition.** Every ECS task uses the runtime role. As things stand, nothing has write permission at runtime, which is safe, but the embedding writer has no deployable identity.
6. **`scripts/cleanup.sh` has two problems.**
   - Phase 6 runs `-target=module.storage` but prints "Logs bucket ... NOT destroyed". That is not true.
   - IAM now depends on storage (it needs the index ARNs), so a storage-targeted destroy also takes down the IAM policies that depend on it.

   I did not change the scripts because this work order is Terraform-only.
7. **These index settings cannot be changed after creation:** dimension, metric and non-filterable keys. Changing any of them replaces all 5 indexes and loses their vectors. AWS docs also mention ENHANCED and CLASSIC index modes, and 6.26.0 has no index-mode attribute, so the service default applies.
8. **Core config has two keys Terraform does not set:**
   - `s3-vectors.index-name` defaults to `scholarchain-vectors`, which matches none of the 5 indexes.
   - `s3-vectors.endpoint` defaults to `localhost` (core `reference.conf:21-24`).

   The storage client builds its endpoint from the bucket and region, so these may be unused. That should be confirmed.

MANUAL ACTION REQUIRED:
- The human architect should get the dimension and distance metric resolved, then set `s3_vectors_dimension` and `s3_vectors_distance_metric` in both tfvars files (through infra-engineer).
- Review the IAM changes, which are security-sensitive.
- Decide whether `.terraform.lock.hcl` should be un-ignored.
- Review plan, cost, account and region before any apply. I have not run plan, apply, destroy or import, and I touched no AWS account.

BLOCKED-ON OWNER:
- rag-engineer: `EmbeddingModelConfig` and the `SemanticContextAgent` query vector.
- domain-engineer: core `reference.conf` dimension, index-name and endpoint.
- storage-engineer: confirm the metric and the metadata schema.
- build-engineer: `.gitignore:38`.
- The human architect makes the final call on the metric.

BLOCKED-ON EVIDENCE:
- `modules/core/src/main/resources/reference.conf:25` `dimensions = 1536`
- `modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingModelConfig.scala:20` gives 1024 for titan-embed-text-v2, the default model per core `reference.conf:50`.
- `modules/rag/src/main/scala/edu/scholarchain/rag/SemanticContextAgent.scala:97` `(0 until 256)`
- No cosine or euclidean setting anywhere in application source or config.
- `.gitignore:38` `.terraform.lock.hcl`

NEXT GATE: resolve the dimension and metric, then set them in tfvars, then run `terraform plan` (infra-engineer, after a backend or credentials decision), then security review of the IAM changes, then human approval of plan, cost, account and region, then deploy-engineer.

**Diff:** 14 files, +255/-31, 455 lines, which is over the ~400-line limit, so the full text is only in the file. Full unified diff: `<session-scratchpad>/tf-phase12.diff`. The pre-edit snapshot is at `<session-scratchpad>/tf-before/terraform/`. The key replacement in `modules/storage/main.tf`:

```hcl
-resource "aws_s3tables_table_bucket" "vectors" {
-  name = "${local.prefix}-vectors"
-}
+resource "aws_s3vectors_vector_bucket" "vectors" {
+  vector_bucket_name = local.vector_bucket_name
+  encryption_configuration = [{ sse_type = "AES256", kms_key_arn = null }]
+  tags = { Name = local.vector_bucket_name, Purpose = "vectors" }
+}
+resource "aws_s3vectors_index" "indexes" {
+  for_each           = var.vector_index_names
+  vector_bucket_name = aws_s3vectors_vector_bucket.vectors.vector_bucket_name
+  index_name         = each.key
+  data_type          = var.vector_data_type
+  dimension          = var.vector_dimension
+  distance_metric    = var.vector_distance_metric
+  metadata_configuration {
+    non_filterable_metadata_keys = var.vector_non_filterable_metadata_keys
+  }
+  tags = { Name = "${local.vector_bucket_name}-${each.key}", Index = each.key }
+}
```

(The excerpt is condensed. The file itself is `terraform fmt` clean.)


## 5. Rerun evidence

The agent's results are in section 4. Afterwards the orchestrator reran the checks itself, from `infra/terraform`, on 2026-11-22:

| Command | Before (human-reported) | After (orchestrator rerun) |
|---|---|---|
| `terraform fmt -check -recursive` | passed | exit 0, no output |
| `terraform init -backend=false -upgrade` | init passed on 5.31.0 | agent run: exit 0, installed hashicorp/aws v6.26.0 |
| `terraform validate` | FAILED: `modules/storage/main.tf:191`, `aws_s3tables_table_bucket` unsupported in 5.31.0 | exit 0, `Success! The configuration is valid.` |
| `grep -rn s3tables . --exclude-dir=.terraform` | 1 resource reference | exit 1 (no matches) |
| provider pin (`versions.tf:11`, `modules/provider/main.tf:9`) | `~> 5.31.0` | `= 6.26.0` |
| `git diff --cached --name-only` | n/a | 0 files staged |

Diff: 14 files changed, 255 insertions and 31 deletions (`diff -ruN` against the pre-edit snapshot). The diff file is kept outside the repository, in the session scratchpad as `tf-phase12.diff`.

`terraform plan` was **NOT RUN**. No AWS credentials were used and no resources were created.

## 6. Re-review

`code-reviewer`: **NOT YET RUN**. The IAM changes are security-sensitive and need both an independent read-only review and the human's review.

## 7. Outcome

**Partially fixed.**
- Fixed: the unsupported resource, the provider pin, the S3 Vectors bucket and the 5 indexes, the outputs, the ECS env vars, and the IAM action names and ARNs. The old policy used non-existent `s3vectors` actions and a wrong ARN segment.
- Remaining:
  - The embedding dimension (1536, 1024 or 256 in the app) and the distance metric are not defined consistently in the application. They are required Terraform variables with no default, so `terraform plan` cannot run until the owners above resolve them and the human approves the values.
  - `.terraform.lock.hcl` is gitignored.
  - There is no root provider block.
  - The loader role is not attached to any task.
  - `scripts/cleanup.sh` has a misleading message and a targeted-destroy risk.
- Owners: as listed in section 2, plus infra-engineer for the provider block, the loader role and `cleanup.sh`. Target: before the Phase 12 human gate.
- The human architect has not yet recorded a decision.
