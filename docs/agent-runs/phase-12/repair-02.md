# Phase 12 repair 02: 1024/cosine alignment, provider, loader role, cleanup message

Date: 2026-11-22
Branch and state: `remediation`. Parent commit `753c499`. Uncommitted working tree; `infra/terraform/**` is untracked.
Evidence assembled by: the orchestrator. In the verbatim sections below, local home and scratchpad paths are shortened to `~` and `<session-scratchpad>`.

## 1. Trigger

- Source: the human architect's configuration decision and repair request (2026-11-22), which followed `repair-01.md` §7. The human's decision, in their words: "I approve 1024 dimensions and cosine for ScholarChain's S3 Vectors indexes. This is my configuration decision. Phase 12 acceptance and deployment approval remain pending."
- Findings carried over from repair-01:
  - The application disagreed on the embedding dimension: 1536 in core `reference.conf`, 1024 in rag `EmbeddingModelConfig`, and a 256-element placeholder query vector in `SemanticContextAgent`.
  - No distance metric was configured.
  - `.terraform.lock.hcl` was gitignored.
  - There was no root provider block, so the Region and default tags were not applied to the modules.
  - The loader role was not attached to any task.
  - `scripts/cleanup.sh` said the logs bucket was not destroyed, which was false.
- The human also required:
  - The query vector must come from the same model that populates the index.
  - A Voyage fallback must use a separate index set, or re-embedding must happen before any model switch.
  - An independent review of the IAM changes.

## 2. Routed to

Each owner comes from its `docs/agents.md` row.

| Order | Agent | Owned path | Purpose |
|---|---|---|---|
| 1 | domain-engineer | `modules/core/**` | dimension 1024, metric cosine, index-model id, validated config |
| 2 | build-engineer | `.gitignore` | track `.terraform.lock.hcl` |
| 3 | infra-engineer | `infra/terraform/**`, `scripts/*.sh` | tfvars, ECS env vars, root provider and default_tags, embedding-worker with loader role, truthful cleanup message, lock for darwin_arm64 and linux_amd64 |
| 4 | test-engineer | the adversarial specs it created in Phase 11 | formatting only, to unblock `sbt check`. The domain-engineer reported this as BLOCKED-ON. The orchestrator routed it to test-engineer as the creator of those files; see section 3.4. |
| 5 | rag-engineer | `modules/rag/**` | `QueryEmbedder` port replaces the placeholder; query-side model and dimension guard |
| 6 | storage-engineer | `modules/storage/**` | write-side model and dimension guard |
| 7 | code-reviewer (read-only) | — | independent IAM/Terraform review. Results are in `review-report.md` (Pass 1). |

Agents 1–3 ran in parallel because none of them ran sbt at the same time as another. Agents 4–6 ran one after another, so that sbt runs did not overlap.

## 3–4. Work orders and agent final reports

### 3.1 / 4.1 — domain-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, domain-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). There are many uncommitted changes from other owners: preserve them. Edit only `modules/core/**`. Do NOT stage or commit. Do not read .env.

Human decision (2026-11-22, the human architect's own configuration decision): every ScholarChain S3 Vectors index uses **dimension 1024** and **distance metric cosine**. The index-populating embedding model is Amazon Titan Text Embeddings v2 (`amazon.titan-embed-text-v2:0`, 1024-d), which matches the existing default in core `reference.conf` (around line 50; verify). A Voyage fallback must never share an index with Titan vectors.

Current inconsistencies, verified by the orchestrator:
- `modules/core/src/main/resources/reference.conf`, `s3-vectors` block: `dimensions = 1536`. It also has `index-name = "scholarchain-vectors"`, which matches none of the 5 real indexes (researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1), and `endpoint = "localhost"`.
- There is no distance-metric setting anywhere.

Tasks:
1. Set `s3-vectors.dimensions = 1024`, keeping the env override `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS`.
2. Add `s3-vectors.distance-metric = "cosine"` with env override `${?SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC}`. Use this exact env var name, because Terraform/ECS is being wired to it in parallel. Add the field to the typed config, as an enum or validated value (cosine | euclidean). Reject other values, and reject dimensions outside 1..4096, at load time.
3. Add an explicit embedding-model identity to the S3 Vectors config, for example `s3-vectors.embedding-model-id`, defaulting to the existing Titan v2 id. Reuse the existing model key if one exists rather than duplicating it; say which you chose. This is so the index model can be compared with the query model. Keep the existing env var names if they exist and report all env var names exactly.
4. Decide what to do with `index-name` and `endpoint`. Check whether any code in modules/*/src/main reads them (grep). If unused, remove them or mark them deprecated, with justification. If used, fix the default so it doesn't point at a non-existent index. Report the callers with file:line.
5. Add or update config tests (load defaults: 1024 and cosine; env override; invalid metric rejected; dimension 0 or 5000 rejected). Do not weaken existing assertions. If an existing test asserts 1536, update it and explain why.
6. Run `sbt core/test`, then `sbt check` (the definition of done). Report exact counts and exit codes. If `sbt check` fails because of another module's code (rag, storage and so on reference the old value), do not edit those modules. Report BLOCKED-ON with file:line and the owner.

Final report: the 14 fields STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Include the exact config keys, env var names and the typed-config API that rag/storage should call. Never claim a command passed unless you saw the output.

````

#### Final report (verbatim)

`sbt check` failed at its first step, the format check, on three test files outside core. Those files are committed and unchanged locally; they've been unformatted since commit 1437112. When I ran the other `check` steps (`Test/compile` and `test`) separately, they passed with 551 tests and 0 failures. `markTestRun` never ran because `check` stopped first.

STATUS: BLOCKED-ON

OWNER: domain-engineer

WORK ORDER: Phase 12 repair-02: make every S3 Vectors index use dimension 1024, cosine distance and Titan v2 in the core config, with load-time validation and tests.

FILES CREATED: none

FILES MODIFIED:
- ~/Documents/scholarchain/modules/core/src/main/resources/reference.conf
- ~/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/AppConfig.scala
- ~/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/AppConfigSpec.scala

Only files in `modules/core/**` changed. Nothing was staged or committed, and other owners' uncommitted changes are untouched.

COMMANDS RUN:
- grep and find over `modules/`, `tests/` and the repo to find config callers and env var users (`.env` excluded)
- `sbt "core/scalafmt; core/Test/scalafmt; core/test"`: exit 0
- `sbt "core/testOnly edu.scholarchain.core.AppConfigSpec"`: exit 0
- `sbt check`: **exit 1**
- `sbt "Test/compile; test"`: exit 0 (run only to see whether anything besides formatting fails)

TEST RESULTS:
- `core/test`: 114 tests in 7 suites, 114 passed, 0 failed. `AppConfigSpec` went from 4 tests to 21, all passing.
- `sbt check`: **FAILED** at `scalafmtCheckAll`, which ran 1 s and stopped before compiling or testing. The errors were "1 files must be formatted" for:
  - `modules/hadoop/src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala`
  - `modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala`
  - `modules/services/src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala`
- `Test/compile; test` across all modules: passed. Per-suite counts were 52, 49, 114, 116, 24, 133 and 63, for 551 passed and 0 failed. No module fails to compile or test against the new core config.

VALIDATION RESULTS:
- **Config keys and env vars** (all under `scholarchain.s3-vectors` in core `reference.conf`):
  - `dimensions = 1024`, override `${?SCHOLARCHAIN_S3_VECTORS_DIMENSIONS}` (name kept; ECS already sets it at `infra/terraform/modules/ecs/main.tf:179`).
  - `distance-metric = "cosine"`, override `${?SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC}` (the exact name you specified).
  - `embedding-model-id = "amazon.titan-embed-text-v2:0"`, override `${?SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID}` (new).
  - The query-time model is still `models.embedding-model-id`, override `${?SCHOLARCHAIN_EMBEDDING_MODEL_ID}`. Its default is the Titan v2 id at what was `reference.conf:50-51`, so your line reference was correct.
- **Task 3 (which model key):** I did not reuse `models.embedding-model-id` and added a separate index-model key instead. That key is the query model, and its env var can be changed at runtime. If the index identity pointed at it (for example through a `${scholarchain.models.embedding-model-id}` substitution), switching the query model to Voyage would also change the index identity, and the comparison would always come out equal. That would defeat the "Voyage never shares a Titan index" rule. A test covers this case.
- **Task 4 (`index-name` and `endpoint`): removed.** No code outside core calls `AppConfig` or `S3VectorsConfig` at all. Grepping `modules/*/src` and `tests/` found no readers of `scholarchain.s3-vectors.endpoint` or `index-name`, so there are no file:line callers to report. No Terraform sets `SCHOLARCHAIN_S3_VECTORS_ENDPOINT` or `SCHOLARCHAIN_S3_VECTORS_INDEX_NAME`; those env var names are now retired.
  - The `indexName` matches in rag and storage (`rag/.../Ports.scala:46`, `SemanticContextAgent.scala:62`, `storage/.../S3VectorsClient.scala:87,142,183`, `EmbeddingBatchWriter.scala:35`) are method parameters, not config reads.
  - Storage's own block is a different path, `scholarchain.storage.s3-vectors` (`storage/reference.conf:32`), so it doesn't overlap.
- **Typed-config API for rag and storage** (in `edu.scholarchain.core`):
  - `AppConfig.load(): AppConfig` and `AppConfig.load(config: Config): AppConfig`. Either one throws `ConfigException.BadValue` if a metric is anything other than exactly `cosine` or `euclidean`, if dimensions fall outside 1..4096, or if the model id is blank. It throws `ConfigException.Missing` if a key is absent.
  - `appConfig.s3Vectors: S3VectorsConfig(dimensions: Int, distanceMetric: DistanceMetric, embeddingModelId: String)`
  - `s3Vectors.isCompatibleModel(queryModelId: String): Boolean`, which is true only on an exact match with the index model.
  - `s3Vectors.isCompatibleVector(vectorLength: Int): Boolean`, which checks `vectorLength == dimensions`.
  - `enum DistanceMetric { Cosine, Euclidean }` with `.configValue` (`"cosine"` or `"euclidean"`) and `DistanceMetric.parse(String): Either[String, DistanceMetric]`.
  - `S3VectorsConfig.validated(dimensions, distanceMetric: String, embeddingModelId): Either[String, S3VectorsConfig]`, plus `S3VectorsConfig.MinDimensions = 1` and `MaxDimensions = 4096`.
- **Existing test changed:** the defaults test asserted `dimensions shouldBe 1536`, `endpoint shouldBe "localhost"` and `indexName shouldBe "scholarchain-vectors"`. It now asserts 1024, `Cosine` and Titan v2 instead. The reason is your 2026-11-22 decision and the removal of the two fields. All other assertions are unchanged. The type-mismatch test's HOCON now includes the new required keys, so it still fails only because of the bad `port` value.
- **New tests:**
  - Defaults: 1024, cosine, Titan v2.
  - Each of the three env vars overrides its value. These tests resolve core's `reference.conf` with the real process environment switched off.
  - The index model stays the same when only the query model is overridden.
  - Invalid metrics are rejected: `dotproduct`, `Cosine`, `COSINE`, `" cosine"`, `""` and `manhattan`.
  - Dimensions 0, 5000, -1 and 4097 are rejected; the boundary values 1 and 4096 are accepted.
  - A blank model id is rejected.
  - Missing keys are rejected.
  - The removed keys no longer exist.
  - Compatibility helpers: 1024 is compatible; 1536 and 256 are not.

ASSUMPTIONS:
- Metric matching is exact and case-sensitive, so values like `Cosine` are rejected.
- Load does not fail when the query model differs from the index model. A fallback may legitimately run against a separate index set, so callers have to enforce the check with `isCompatibleModel`.
- I did not add a list of the five index names, since you didn't ask for it. Index names stay call-site parameters.

RISKS:
- `modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingModelConfig.scala:15` reads a different env var, `EMBEDDING_MODEL_ID`, not `SCHOLARCHAIN_EMBEDDING_MODEL_ID`, and works out dimensions on its own. Its fallback branch (`:26`) also maps an unknown model, such as Voyage, to 1024 dimensions. Because of that, a Voyage model would pass a dimension-only check. rag should switch to `AppConfig.load().s3Vectors` together with `isCompatibleModel`.
- The repair-01 evidence records a 256-element query vector at `SemanticContextAgent.scala:97`. I didn't re-check that line. It would fail `isCompatibleVector`, so it belongs to `rag-engineer`.
- Removing `endpoint` and `indexName` from `S3VectorsConfig` changes its public API. No callers exist today.
- Terraform should also export `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID`, or rely on the Titan default.

MANUAL ACTION REQUIRED: Decide who formats the three unformatted test files. Running `sbt scalafmtAll` or the per-module `Test/scalafmt` should fix them with formatting changes only. Then re-run `sbt check`.

BLOCKED-ON OWNER: `test-engineer`, who created all three files in commit 1437112 ("phase-11 test-engineer: add 55 adversarial tests"). They are not under `tests/**`, though, so `docs/agents.md` also points to `hadoop-engineer`, `entity-resolution-engineer` and `service-engineer` as the module owners. That makes ownership unclear, and per the escalation rules the human architect should decide who fixes them.

BLOCKED-ON EVIDENCE: `sbt check` exit 1. `[error] (hadoop / Test / scalafmtCheck) scalafmt: 1 files must be formatted`, and the same for `entityResolution` and `services`. The files are `modules/hadoop/src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala`, `modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala` and `modules/services/src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala`. `git status` shows all three clean; the last commit to touch them is 1437112.

NEXT GATE: Format the three files, then re-run `sbt check` and confirm exit 0 with `.claude/.last-test-run` updated. After that: contract tests for rag and storage adoption of `S3VectorsConfig`, and code review.

### 3.2 / 4.2 — build-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, build-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). Preserve all other uncommitted changes. Do NOT stage or commit. Do not run sbt; another agent is running sbt at the same time.

The human architect decided that the Terraform provider lock file must be tracked in Git, so that every machine uses the same pinned provider (hashicorp/aws 6.26.0). Today `.gitignore` line ~38 contains `.terraform.lock.hcl`, which ignores it.

Tasks:
1. Remove the `.terraform.lock.hcl` ignore rule from `.gitignore`. Keep `.terraform/` (the provider cache), `*.tfstate*`, `*.tfplan` and the dataset ignores exactly as they are. Add a one-line comment explaining that the lock file is intentionally tracked.
2. Verify:
   - `git check-ignore -v infra/terraform/.terraform.lock.hcl` should print nothing (exit 1).
   - `git check-ignore -v infra/terraform/.terraform/x` should still match.
   - `git check-ignore -q datasets/raw/x` should still match.
   - `git status --short infra/terraform/.terraform.lock.hcl` should show it as untracked `??`.
3. Show `git diff -- .gitignore`.

Final report with the 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Under TEST RESULTS, say that sbt was not run per the work order, because `.gitignore` does not affect compilation, and that the orchestrator runs `sbt check` later.

````

#### Final report (verbatim)

I removed the `.terraform.lock.hcl` ignore rule from `.gitignore` and all the checks came out as expected. Nothing was staged or committed, and I did not run sbt.

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Phase 12 repair-02. Stop ignoring `.terraform.lock.hcl` so the pinned provider lock file (hashicorp/aws 6.26.0) can be tracked in Git, and keep the other Terraform and dataset ignore rules.
FILES CREATED: none
FILES MODIFIED: ~/Documents/scholarchain/.gitignore
COMMANDS RUN:
- `git check-ignore -v infra/terraform/.terraform.lock.hcl`
- `git check-ignore -v infra/terraform/.terraform/x`
- `git check-ignore -q datasets/raw/x`
- `git status --short infra/terraform/.terraform.lock.hcl`
- `git diff -- .gitignore`

TEST RESULTS: sbt was not run, as the work order instructed (another agent is running sbt right now). `.gitignore` does not affect compilation, formatting or tests. The orchestrator will run `sbt check` later.

VALIDATION RESULTS:
- `git check-ignore -v infra/terraform/.terraform.lock.hcl` printed nothing and exited 1, so the lock file is no longer ignored.
- `git check-ignore -v infra/terraform/.terraform/x` printed `.gitignore:37:.terraform/  infra/terraform/.terraform/x` and exited 0, so the provider cache is still ignored.
- `git check-ignore -q datasets/raw/x` exited 0, so dataset ignores still work.
- `git status --short infra/terraform/.terraform.lock.hcl` printed `?? infra/terraform/.terraform.lock.hcl` (untracked).
- `git diff -- .gitignore` shows two parts. Only the first is from this work order:
  ```
  @@ -35,7 +35,7 @@
   .terraform/
  -.terraform.lock.hcl
  +# .terraform.lock.hcl is intentionally tracked to pin provider versions/hashes (human architect decision).
  ```
  The second part adds the dataset and local-run ignores, `*.tfplan`, and the `.DS_Store` note at the end of the file. Those lines were already uncommitted before I started, and I left them exactly as they were.

ASSUMPTIONS: The comment goes where the old rule was, in the Terraform section. Leaving the `*.tfstate`, `*.tfstate.*`, `.terraform/` and `*.tfplan` rules unchanged was enough to keep them all working.

RISKS: Low. `.terraform/` is still ignored, so no provider binaries or cache can be committed by accident. The lock file holds only provider versions and checksums, no secrets.

MANUAL ACTION REQUIRED: Stage and commit `.gitignore` and `infra/terraform/.terraform.lock.hcl` when you're ready; I didn't do either, per the work order. The lock file's contents belong to `infra-engineer`, so changes to it should go through them.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: `sbt check` (run by the orchestrator) and code review

### 3.3 / 4.3 — infra-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, infra-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). Edit only `infra/terraform/**` and `scripts/*.sh`. Preserve all other uncommitted changes. Do NOT stage, commit, or run `terraform plan`, `apply`, `destroy` or `import`. Use no AWS credentials. Do not read .env. You made repair-01 earlier: hashicorp/aws pinned to `= 6.26.0`, S3 Vectors bucket plus 5 indexes, `s3_vectors_dimension` and `s3_vectors_distance_metric` left with no default.

Before editing, snapshot `cp -R infra/terraform scripts` (excluding .terraform) into `<scratchpad>/r2-before/`, where scratchpad is <session-scratchpad>.

Human decision (2026-11-22): S3 Vectors indexes use **dimension 1024, distance metric cosine**. The embedding model that populates them is `amazon.titan-embed-text-v2:0`.

Tasks:
1. Set `s3_vectors_dimension = 1024` and `s3_vectors_distance_metric = "cosine"` in `environments/dev.tfvars` and `environments/prod.tfvars`, replacing the "required" notes. Keep the root variables without defaults, so a missing tfvars still fails loudly. Keep validation.
2. ECS env: keep `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS`. Add `SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC`; domain-engineer is adding exactly this config key in parallel. Find the existing env var the app uses for the embedding model id (grep `modules/core/src/main/resources/reference.conf` for `titan` and its `${?...}` override) and pass the same model id from a Terraform variable (default `amazon.titan-embed-text-v2:0`), so the query-side model equals the index-populating model. Report env var names with file:line.
3. Region and default tags: the AWS provider block currently lives in child `modules/provider`, and the root has no `provider "aws"` block, so `region` and `default_tags` do not apply to other modules. Fix it with a single root `provider "aws"` block with `region = var.aws_region` and `default_tags` (project, environment, owner/course, managed-by=terraform, and an expiration tag if variables exist; do not invent personal data). Remove or neutralise the child-module provider (child modules keep only `required_providers`). Verify that resources that previously set tags manually still validate, and that tags are not duplicated in conflicting ways.
4. Loader role: `ecs_task_loader` (the S3 Vectors writer role, with PutVectors, GetVectors and ListVectors on the 5 index ARNs) is not attached to any task. Find the intended task, meaning the embedding or ingestion worker that runs storage `EmbeddingBatchWriter` / `EmbeddingQueueManager`. If a worker task definition or service exists, set its `task_role_arn` to the loader role, and make sure runtime query services keep the read-only runtime role. If no worker task exists, add only an ECS task definition `embedding-worker` that uses the loader role, plus a service with `desired_count = 0` and a bounded max if the module pattern needs one. Report exactly what you added and its cost implication (a task definition is free; desired 0 means no running tasks). Do not widen any IAM permissions.
5. `scripts/cleanup.sh`: the phase that runs `-target=module.storage` prints "Logs bucket ... NOT destroyed", which is false. Make the message truthful. List exactly what the targeted destroy removes, including the IAM policies that now depend on storage index ARNs, and what is retained and why. Keep strict mode and the explicit human confirmation. Do not run the script. Check with `bash -n` and, if installed, `shellcheck`.
6. Run from `infra/terraform`: `terraform fmt -recursive`, `terraform fmt -check -recursive`, `terraform init -backend=false -upgrade`, `terraform validate`. Also run `terraform providers lock -platform=darwin_arm64 -platform=linux_amd64` so the lock file holds hashes for both Mac and Linux CI. This downloads from the registry only, with no AWS calls; report whether it succeeded. Run `bash -n scripts/*.sh`. Capture exact outputs and exit codes.
7. Produce `diff -ruN --exclude=.terraform --exclude=.terraform.lock.hcl` from the r2-before snapshot into `<scratchpad>/tf-phase12-r2.diff`, and give the stat.

Final report with the 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Include an IAM summary table (role, attached to which task, actions, resources) for the independent reviewer. Never claim a command passed without seeing its output.

````

#### Final report (verbatim)

STATUS: COMPLETE

All seven tasks are done. fmt, init, validate and the provider lock all succeeded. Before anything goes live, the human needs to look at five open items in RISKS (R1–R5). The most important: the new embedding worker's role cannot call Bedrock, and I did not add that permission.

OWNER: infra-engineer

WORK ORDER: Phase 12 repair-02. Set S3 Vectors to 1024 / cosine, add the distance-metric and embedding-model env vars to ECS, move to one root AWS provider with default_tags, attach the loader role to an embedding-worker task, make the cleanup.sh storage message truthful, and run the checks.

FILES CREATED:
- ~/Documents/scholarchain/infra/terraform/providers.tf

FILES MODIFIED:
- ~/Documents/scholarchain/infra/terraform/main.tf
- ~/Documents/scholarchain/infra/terraform/variables.tf
- ~/Documents/scholarchain/infra/terraform/outputs.tf
- ~/Documents/scholarchain/infra/terraform/environments/dev.tfvars
- ~/Documents/scholarchain/infra/terraform/environments/prod.tfvars
- ~/Documents/scholarchain/infra/terraform/modules/ecs/main.tf
- ~/Documents/scholarchain/infra/terraform/modules/ecs/variables.tf
- ~/Documents/scholarchain/infra/terraform/modules/ecs/outputs.tf
- ~/Documents/scholarchain/infra/terraform/modules/storage/variables.tf (descriptions only)
- ~/Documents/scholarchain/infra/terraform/modules/storage/outputs.tf (added `vector_distance_metric`)
- ~/Documents/scholarchain/infra/terraform/.terraform.lock.hcl (one linux_amd64 `h1:` hash added)
- ~/Documents/scholarchain/scripts/cleanup.sh
- Deleted: ~/Documents/scholarchain/infra/terraform/modules/provider/ (main.tf, variables.tf, outputs.tf). These files were untracked in git, so the only copy is in the r2-before snapshot.

COMMANDS RUN:
1. `rsync -a --exclude=.terraform infra/terraform scripts <scratchpad>/r2-before/` (snapshot)
2. `terraform fmt -recursive`, exit 0, no files changed
3. `terraform fmt -check -recursive`, exit 0, no output
4. `terraform init -backend=false -upgrade -input=false`, exit 0, "Terraform has been successfully initialized!", hashicorp/aws v6.26.0
5. `terraform validate`, exit 0, "Success! The configuration is valid."
6. `terraform providers lock -platform=darwin_arm64 -platform=linux_amd64`, exit 0, "Success! Terraform has updated the lock file." Both platforms were "signed by HashiCorp". darwin_arm64 hashes were already tracked; one linux_amd64 hash was added (`h1:B7X8EU6aZ9KzIvP0VBDhhgadgjXIrUgMXt/pJ6EUXvo=`). Registry download only, no AWS calls.
7. `terraform console`, run on a scratch copy with `backend.tf` removed and local state only. All AWS credential variables were blanked (`AWS_CONFIG_FILE=/dev/null`, `AWS_SHARED_CREDENTIALS_FILE=/dev/null`, empty keys and profile, IMDS disabled). I used it to read tag and variable values (TEST RESULTS). The scratch copy has been deleted. An earlier console attempt in the real directory failed with "Backend initialization required" and changed nothing.
8. `bash -n scripts/*.sh`: all 7 scripts exit 0.
9. `which shellcheck`: not installed, so shellcheck was not run.
10. `diff -ruN --exclude=.terraform --exclude=.terraform.lock.hcl r2-before r2-after > <scratchpad>/tf-phase12-r2.diff`, exit 1 (differences found, as expected), then `diffstat`.

Not run: `terraform plan`, `apply`, `destroy`, `import`; any AWS CLI command; staging or committing.

TEST RESULTS:
- fmt, fmt -check, init, validate and providers lock all exit 0 (outputs quoted above).
- Console results, dev and prod both:
  - `local.default_tags` = `{Environment="<env>", ManagedBy="terraform", Project="ScholarChain"}`
  - `var.s3_vectors_dimension` = 1024
  - `var.s3_vectors_distance_metric` = "cosine"
  - `var.embedding_model_id` = "amazon.titan-embed-text-v2:0"
- Optional tags: with `-var tag_course=TEST -var tag_expiration_date=2027-01-31`, the tag map gains Course and Expiration.
- Bad inputs are rejected: `tag_expiration_date=soon` fails its validation rule, and `s3_vectors_distance_metric=dot` fails with "must be cosine or euclidean".
- Missing tfvars: the console returned `(known after apply)` rather than an error, because console tolerates unset variables. The loud failure under `plan` comes from Terraform treating a variable with no default as required. I did not observe it, because plan is not allowed.
- `bash -n`: 7 of 7 scripts exit 0.

VALIDATION RESULTS:

**Task 1, tfvars.** `dev.tfvars:39-41` and `prod.tfvars:39-41` now set `s3_vectors_dimension = 1024`, `s3_vectors_distance_metric = "cosine"` and `embedding_model_id = "amazon.titan-embed-text-v2:0"`. The root variables still have no default. I added validation on the root (1–4096 integer; cosine or euclidean) and kept the storage module's existing validation.

**Task 2, ECS environment.** Env vars are defined once in `modules/ecs/main.tf` (`local.shared_environment`) and used by all 5 services and the worker:

| Env var | Line in `modules/ecs/main.tf` | Read by |
|---|---|---|
| `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS` (kept) | 50 | `reference.conf:28` |
| `SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC` (new) | 51 | `reference.conf:30` |
| `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID` (new) | 52 | `reference.conf:35` |
| `SCHOLARCHAIN_EMBEDDING_MODEL_ID` (new) | 53 | `reference.conf:60` |

- `reference.conf` here means `modules/core/src/main/resources/reference.conf`, as it currently stands with domain-engineer's in-flight edits. Line numbers may move.
- The query-time model key (`models.embedding-model-id`, `SCHOLARCHAIN_EMBEDDING_MODEL_ID`) existed before this work.
- domain-engineer's in-flight file also adds `s3-vectors.embedding-model-id` (`SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID`) for the model that populated the indexes. I set both from the one Terraform variable `embedding_model_id` (default `amazon.titan-embed-text-v2:0`), so the query model always equals the index model.
- Root wiring is at `main.tf:139` (loader role ARN) and `main.tf:151-152` (distance metric, model id).

**Task 3, provider.** `providers.tf:31` is now the only `provider "aws"` block (checked with grep). It sets `region = var.aws_region` and default_tags:
- Always: Project, Environment, ManagedBy=terraform.
- Only when set: Owner, Course, Expiration, from new variables `tag_owner`, `tag_course`, `tag_expiration_date`. All default to null, so no values are invented.
- `extra_tags` is merged last.

I removed `module "provider"` and `modules/provider/`. It held no resources, so there is nothing in state to move. I also removed the unused `local.common_tags` from main.tf. No child module has a provider block, and validate passes. The tags resources set by hand use only Name, Service, Purpose, Index and Tier, which do not overlap the default-tag keys, so nothing conflicts.

**Task 4, loader role.** No worker task or service existed before: the 5 services and all Step Functions tasks use the runtime role. I added these resources in `modules/ecs/main.tf`:
- `aws_ecs_task_definition.embedding_worker` (line 534), with `task_role_arn = var.ecs_task_loader_role_arn` (line 541). No ports, no HTTP health check.
- `aws_ecs_service.embedding_worker` (line 569) with `desired_count = 0` (line 573). It has no load balancer and no autoscaling, so Terraform holds it at 0.
- Two supporting resources that I added beyond the "task definition plus service" in the work order, because the task cannot start without them:
  - `aws_ecr_repository.embedding_worker` plus a lifecycle policy (line 487). Without it, the only image choice would be another service's image running with write permissions.
  - `aws_cloudwatch_log_group.embedding_worker` (line 524). The awslogs driver does not create log groups.
- New outputs: `embedding_worker_service_name`, `embedding_worker_ecr_repository_url`, and (ECS module only) `embedding_worker_task_definition_arn`.

The 5 query-path services still use the runtime role (line 178). No IAM policy was changed.

Cost: the task definition and the zero-task service are free, and with desired 0 no Fargate task runs. The ECR repo costs nothing while empty (storage is billed only once an image is pushed). The log group costs nothing while no logs are written.

**Task 5, cleanup.sh.** The false lines "Logs bucket ... NOT destroyed" and "Logs bucket preserved", and the header line "Preserves logs bucket and evidence", are replaced with an accurate notice.
- Destroyed in Phase 6:
  - All 6 S3 buckets, including logs, plus their versioning, encryption, public-access-block and lifecycle settings.
  - All 5 DynamoDB tables, including EvidenceBundle and RunLedger.
  - The 5 S3 Vectors indexes and the vector bucket.
  - The dependent IAM inline policies `<prefix>-ecs-task-runtime-policy` and `<prefix>-ecs-task-loader-policy`.
- Retained:
  - Non-empty S3 buckets, only because deletion fails without force_destroy. Their settings may already have been removed by then.
  - The state bucket and lock table, which this config does not manage.
  - Local `docs/agent-runs/`.

I also updated the Phase 2 message to mention the embedding worker and ECR, and added read-only `aws s3vectors list-vector-buckets` lines to the before and after inventories. `set -euo pipefail` and the `CONFIRM_CLEANUP=yes` gate are unchanged. The script was not run.

**Task 6, checks.** All passed; see TEST RESULTS.

**Task 7, diff.** `<session-scratchpad>/tf-phase12-r2.diff` covers 15 files, 337 insertions, 102 deletions:

| File | Change |
|---|---|
| scripts/cleanup.sh | +46 / -? (46 lines changed) |
| environments/dev.tfvars | 13 |
| environments/prod.tfvars | 13 |
| main.tf | 32 |
| modules/ecs/main.tf | 151 |
| modules/ecs/outputs.tf | 15 |
| modules/ecs/variables.tf | 17 |
| modules/provider/main.tf | 27 (deleted) |
| modules/provider/outputs.tf | 4 (deleted) |
| modules/provider/variables.tf | 15 (deleted) |
| modules/storage/outputs.tf | 5 |
| modules/storage/variables.tf | 4 |
| outputs.tf | 10 |
| providers.tf | 37 (new) |
| variables.tf | 50 |

**IAM summary for the reviewer.** No IAM policy was changed in this repair.

| Role | Attached to | Actions | Resources |
|---|---|---|---|
| `<prefix>-ecs-task-runtime-role` | Task role of query-api, graph-retriever, vector-retriever, synthesis-worker, verification-worker (also what Step Functions runs) | s3:GetObject, ListBucket | `arn:aws:s3:::<prefix>-*` and `/*` |
| | | dynamodb:BatchGetItem, GetItem, Query | `table/<prefix>-*` |
| | | sqs:SendMessage, ReceiveMessage, DeleteMessage, GetQueueAttributes | `<prefix>-*` |
| | | secretsmanager:GetSecretValue | `secret:scholarchain/*` |
| | | bedrock:InvokeModel | `foundation-model/*` |
| | | logs:CreateLogStream, PutLogEvents | `/scholarchain/<env>/*` |
| | | s3vectors:QueryVectors, GetVectors, GetIndex | the 5 index ARNs |
| `<prefix>-ecs-task-loader-role` | Task role of **embedding-worker only** (new, desired 0) | s3:GetObject, ListBucket, PutObject, DeleteObject | `<prefix>-*` and `/*` |
| | | dynamodb:BatchGetItem, BatchWriteItem, GetItem, PutItem, Query, UpdateItem, DeleteItem | `table/<prefix>-*` |
| | | sqs:SendMessage, ReceiveMessage, DeleteMessage, GetQueueAttributes | `<prefix>-*` |
| | | secretsmanager:GetSecretValue | `secret:scholarchain/*` |
| | | logs:CreateLogStream, PutLogEvents | `/scholarchain/<env>/*` |
| | | s3vectors:PutVectors, GetVectors, ListVectors | the 5 index ARNs |
| | | **no** bedrock:InvokeModel | (see R1) |
| `<prefix>-ecs-task-execution-role` | Execution role of all 6 task definitions | AmazonECSTaskExecutionRolePolicy (managed); secretsmanager:GetSecretValue | `*` (managed policy); `secret:scholarchain/*` |
| `<prefix>-step-functions-role` | Query state machine | ecs:RunTask/StopTask/DescribeTasks; iam:PassRole for execution and runtime roles only, not loader; sqs:SendMessage; events:PutEvents; logs delivery | as before |
| `<prefix>-deploy-role` | None (assumed by humans or CI) | iam:PassRole already included the loader role; ECR push is scoped to `repository/<prefix>-*`, which covers the new repo | unchanged |

ASSUMPTIONS:
- The worker runs the same Scala build as the other services, from its own image `<prefix>-embedding-worker:latest`. No worker entrypoint (`main` method) exists yet in `modules/storage`; I checked and found only `ScholarChainPipeline.main` in spark.
- Worker CPU and memory reuse `var.cpu` / `var.memory`.
- The ECS service is placed in the app subnets with the app security group.
- The distance metric is passed lowercase (`cosine`), matching the `cosine|euclidean` comment in reference.conf.
- The Owner / Course / Expiration tag names are my choice. Their values are left to the human.

RISKS:
- **R1 (blocks a working worker).** The loader role has no `bedrock:InvokeModel`. A worker that creates Titan embeddings, rather than only writing vectors made elsewhere, will get AccessDenied. I did not add the permission, because the work order said not to widen IAM. A security-sensitive change needs human review.
- **R2.** The app has no config key for the embedding queue URL, so the worker gets no queue URL env var.
- **R3.** Pre-existing bug, unchanged: `module.workflow` is never given `embedding_queue_url` (its default is `""`), so the Audit and AuditFailure states send to an empty QueueUrl.
- **R4.** The first plan after this change will show:
  - all 5 task definitions replaced (the env var list changed), and the services rolling to new revisions;
  - provider default tags applied across every module. Resources created under the old setup only got default tags when the region and tags came from the environment, so a large tag-only diff is likely;
  - new: 1 ECR repo with its lifecycle policy, 1 log group, 1 task definition, 1 service.
  - Any existing indexes that were built with a different dimension or metric would be replaced, and their vectors lost.
- **R5.** cleanup.sh Phase 6 still destroys data with only the single `CONFIRM_CLEANUP` gate. The message is now accurate, but the behavior has not changed.
- **R6.** `modules/core` reference.conf is being edited in parallel. If domain-engineer renames `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID` or `SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC`, `modules/ecs/main.tf:51-52` must follow.
- **R7.** shellcheck was not run because it is not installed.

MANUAL ACTION REQUIRED:
- Decide R1: whether to grant the loader role `bedrock:InvokeModel` scoped to `foundation-model/amazon.titan-embed-text-v2:0` (route to infra-engineer).
- Review the first `terraform plan` (tag-only diffs, task definition replacements, new worker resources), plus cost, account and region, before any apply.
- Optionally set `tag_owner` / `tag_course` / `tag_expiration_date` in the tfvars.
- Push an embedding-worker image and its entrypoint before raising `desired_count` above 0.

BLOCKED-ON OWNER: none for this order. Follow-ups:
- storage-engineer / service-engineer: worker entrypoint and queue URL config key (R2).
- human architect: Bedrock permission for the loader role (R1).

BLOCKED-ON EVIDENCE: none

NEXT GATE: Independent review of `tf-phase12-r2.diff` and the IAM table, a security review of R1, then a human-run `terraform plan` (not run here) and human approval.

Files are in <session-scratchpad>:
- tf-phase12-r2.diff
- r2-before/
- r2-after/
- r2-logs/ (fmt.txt, fmtcheck.txt, init.txt, validate.txt, lock.txt, lock-before.hcl)

### 3.4 / 4.4 — test-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, test-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). Preserve all other uncommitted changes. Do NOT stage or commit.

`sbt check` fails at `scalafmtCheckAll` on three test files you created in commit 1437112 (phase-11 adversarial tests):
- modules/hadoop/src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala
- modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala
- modules/services/src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala

Task: apply formatting only to these three files. Use `sbt "hadoop/Test/scalafmtOnly <path>"` or per-module `Test/scalafmt`. Do not run `scalafmtAll`, which would reformat other owners' files. Then show that the change is whitespace and layout only:
- Run `git diff --stat` on the three files.
- Run `git diff -w --ignore-blank-lines` on them and confirm the result is empty, or explain each non-whitespace hunk (for example, scalafmt rewriting braces or imports).
- Confirm no test name or assertion changed. Compare `grep -c 'should\|assert\|in {'` before and after for each file.

Then run `sbt check` and report the exit code, the total tests passed/failed per module, and whether `.claude/.last-test-run` was updated (`ls -l` before and after). If `check` fails for any other reason, do not fix production code. Report it with the owner.

Final report with the 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Never claim a command passed without seeing the output.

````

#### Final report (verbatim)

STATUS: COMPLETE

OWNER: test-engineer

WORK ORDER: Phase 12 repair-02. Apply scalafmt to the three phase-11 adversarial spec files only, show the change is layout-only, then run `sbt check`.

FILES CREATED: none

FILES MODIFIED:
- `~/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala`
- `~/Documents/scholarchain/modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala`
- `~/Documents/scholarchain/modules/services/src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala`

Nothing was staged or committed. The other uncommitted changes in the tree are untouched.

COMMANDS RUN:
- A baseline grep count and file copies, taken before formatting.
- `sbt "hadoop/Test/scalafmtOnly <relative path>"` and the entityResolution and services equivalents. All three reported success but changed nothing, because the relative paths did not match.
- `sbt scalafmtCheckAll Test/scalafmtCheck`. It flagged exactly these three files and no others.
- The same three `scalafmtOnly` commands with absolute paths. Each reported "Reformatted 1 Scala sources".
- `git diff --stat`, `git diff -w --ignore-blank-lines`, and the grep counts again, all on the three files.
- `sbt check`, with the log written to `<session-scratchpad>/check.log`.

TEST RESULTS:
- `sbt check` exited 0, with 0 failures in every test summary.
- The log shows two passes of per-suite summaries, and the module names are not in the summary lines, so I can't attribute counts to modules reliably.
- Both passes report the same set of totals: 24, 49, 52, 63, 114, 116 and 133 tests, all succeeded with 0 failed, 0 canceled and 0 ignored.
- "All tests passed" appeared after each summary.
- All five adversarial specs ran in both passes: Storage, Rag, Service, EntityResolution and Hadoop. A sixth, AdversarialSparkSpec, also ran in both.

VALIDATION RESULTS:
- **`git diff --stat`:** 3 files changed, 11 insertions and 14 deletions (hadoop 2 lines, entity-resolution 4, services 19).
- **`git diff -w --ignore-blank-lines`:** one hunk is left, in `AdversarialServiceSpec`. Scalafmt joined the wrapped class header `class AdversarialServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:` onto one line. That is line-wrapping only, with no token changes. The other two files show no diff once whitespace is ignored.
- **`grep -c 'should\|assert\|in {'`:** the counts are identical before and after: hadoop 38 to 38, entity-resolution 31 to 31, services 19 to 19. No test name or assertion changed.
- **`.claude/.last-test-run`:** before, `Nov 22 07:34`, 28 bytes. After, `Nov 22 18:09`, 28 bytes, with content `2026-11-23T00:09:37.719716Z`. It was updated.

ASSUMPTIONS:
- `Test/scalafmtOnly` with absolute paths was treated as the approved way to touch only these files.
- The per-module `Test/scalafmtCheck` output showing exactly one unformatted file per module confirmed no other files needed formatting.

RISKS:
- The first `scalafmtOnly` attempt reported success without formatting anything, so success output alone is not proof of a change. The `git diff` output above is what shows the files were reformatted.
- Per-module test counts are not separately attributed, as noted under TEST RESULTS.

MANUAL ACTION REQUIRED: Review and commit the three formatted files when ready.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: `sbt check` exit 0, with 0 failures in every test summary. Production code was not edited.

### 3.5 / 4.5 — rag-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, rag-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). Edit only `modules/rag/**` (and `prompts/**` if needed). Preserve all other uncommitted changes. Do NOT stage or commit. Make no paid model calls and no network calls to Bedrock or Voyage; tests use fakes only. Do not read .env.

Human decision (2026-11-22): every S3 Vectors index uses dimension 1024 and cosine distance, populated by `amazon.titan-embed-text-v2:0`. A Voyage fallback must use a separate index set, or require re-embedding before a model switch. It must never query or write a Titan index with Voyage vectors.

domain-engineer has added this to core (`edu.scholarchain.core`):
- `AppConfig.load()` / `AppConfig.load(config)`.
- `appConfig.s3Vectors: S3VectorsConfig(dimensions = 1024, distanceMetric: DistanceMetric = Cosine, embeddingModelId = "amazon.titan-embed-text-v2:0")`. Env vars: `SCHOLARCHAIN_S3_VECTORS_DIMENSIONS`, `SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC`, `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID`. This is the index model.
- `s3Vectors.isCompatibleModel(queryModelId)` (exact match) and `s3Vectors.isCompatibleVector(len)`.
- The query model is `models.embedding-model-id`, env `SCHOLARCHAIN_EMBEDDING_MODEL_ID`.

Problems to fix:
1. `modules/rag/src/main/scala/edu/scholarchain/rag/SemanticContextAgent.scala` around lines 94-97 has `simpleQueryVector`, a 256-element hash placeholder. Replace it with a real query embedding obtained through an embedding port (an `EmbeddingClient` / `QueryEmbedder` trait in rag `Ports.scala`) that returns `Vector[Float]` plus the model id that produced it. The agent must:
   - embed the query text with the configured query model;
   - before querying, check that the model id equals the index model (`isCompatibleModel`) and that the vector length is 1024 (`isCompatibleVector`);
   - on a mismatch, not query, and instead return a typed failure or abstention (an empty semantic context with a clear reason code such as `EMBEDDING_MODEL_MISMATCH` / `EMBEDDING_DIMENSION_MISMATCH`, consistent with how the agent reports errors today) so the answer path abstains rather than returning nonsense neighbours;
   - never fall back silently to a placeholder vector.
   If a production Bedrock embedding adapter already exists in rag, wire it. If not, define the port plus a deterministic test fake only, and report clearly that the live Bedrock adapter is not implemented (owner rag-engineer, a later phase). Do not invent a working AWS client.
2. `modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingModelConfig.scala`:
   - It reads `EMBEDDING_MODEL_ID` (line ~15), not the `SCHOLARCHAIN_` name.
   - It derives its own dimensions.
   - Its fallback maps unknown models (for example Voyage) to 1024 (line ~26).
   Make rag use the core config (`AppConfig.load().s3Vectors` and the query model from core), or remove the duplication. An unknown model must not be treated as compatible. Keep the Voyage fallback policy explicit: it requires a distinct index-model id (a separate index set) and re-embedding, and the compatibility check enforces this.
3. Check `RuntimeWorkflow.scala` (~line 114) and `SemanticContextAgent.scala` (~line 80) for hardcoded model IDs. Replace them with config.

Tests (rag test tree; keep existing assertions unless one is demonstrably wrong, and explain any change):
- Query vector length is 1024 and comes from the fake embedder, not a hash.
- Model mismatch (query model voyage-x vs index Titan) means no vector query happens, and a typed mismatch result is returned.
- Dimension mismatch (fake returns 256) means no query, and a typed result is returned.
- The happy path passes the embedded vector to the vector port unchanged.
- An unknown model id is not treated as compatible.
- The config env override is respected, if testable without the real process env.

Run `sbt rag/test`, then `sbt check`. Report exact counts and exit codes, and whether `.claude/.last-test-run` was updated. If storage or services must change to compile, do not edit them: report BLOCKED-ON with file:line.

Final report with the 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Never claim a command passed without seeing the output.

````

#### Final report (verbatim)

STATUS: COMPLETE

OWNER: rag-engineer

WORK ORDER: Phase 12 repair-02. Replace the hash placeholder query vector with an embedding port, check the query model and dimension against the core S3 Vectors settings before any vector query, remove the duplicated rag embedding config, and take model ids from config instead of hardcoding them.

FILES CREATED:
- ~/Documents/scholarchain/modules/rag/src/test/scala/edu/scholarchain/rag/SemanticContextAgentSpec.scala
- ~/Documents/scholarchain/modules/rag/src/test/scala/edu/scholarchain/rag/TestEmbedders.scala (test-only fakes: `FakeQueryEmbedder`, `RecordingVectorStore`, `ConstTextStore`, and a helper that loads reference.conf with the real process env ignored)

FILES MODIFIED:
- ~/Documents/scholarchain/modules/rag/src/main/scala/edu/scholarchain/rag/Ports.scala: added `case class QueryEmbedding(vector: Vector[Float], modelId: String)` and `trait QueryEmbedder { def embed(text: String, modelId: String): QueryEmbedding }`.
- ~/Documents/scholarchain/modules/rag/src/main/scala/edu/scholarchain/rag/SemanticContextAgent.scala:
  - Removed `simpleQueryVector` entirely; there is no placeholder path left.
  - The constructor is now `(vectors, text, embedder: QueryEmbedder, embeddingConfig: EmbeddingModelConfig)`.
  - The checks run in this order:
    1. If the configured query model fails `isCompatibleModel`, it returns `EMBEDDING_MODEL_MISMATCH`. The embedder is not called, so no paid call is spent.
    2. It calls `embed`. If that throws, it returns `EMBEDDING_UNAVAILABLE`.
    3. If the model id the embedder reports fails `isCompatibleModel`, it returns `EMBEDDING_MODEL_MISMATCH`.
    4. If `isCompatibleVector(len)` fails, it returns `EMBEDDING_DIMENSION_MISMATCH`.
  - On any failure the agent queries no index and returns an empty bundle with `failure = Some(...)`.
  - `SemanticContext.embeddingModel` now holds the model id the embedder reported, instead of the hardcoded `"titan-embed-text-v2"`.
- ~/Documents/scholarchain/modules/rag/src/main/scala/edu/scholarchain/rag/SemanticContextFormat.scala: added `enum SemanticFailureReason(code)` with the codes `EMBEDDING_MODEL_MISMATCH`, `EMBEDDING_DIMENSION_MISMATCH` and `EMBEDDING_UNAVAILABLE`, which serialize as the code string. Also added `case class SemanticRetrievalFailure(reason, detail)` and a new bundle field `failure: Option[SemanticRetrievalFailure] = None` plus `abstained`. The field defaults to `None`, so existing JSON still reads.
- ~/Documents/scholarchain/modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingModelConfig.scala: rewritten as a thin view with fields `(queryModelId, index: S3VectorsConfig)`, built only from `AppConfig` via `fromAppConfig`, `load()`, `load(config)` and a lazy `Default`.
  - The `EMBEDDING_MODEL_ID` env read is gone.
  - The model-name table and the "unknown model gets 1024" fallback are gone.
  - Compatibility comes only from core's exact-match `isCompatibleModel` and `isCompatibleVector`.
  - The Voyage policy is written in the scaladoc: a fallback model needs a separate, re-embedded index set whose index model id is set to that model.
- ~/Documents/scholarchain/modules/rag/src/main/scala/edu/scholarchain/rag/RuntimeWorkflow.scala: new required constructor parameter `generationModelId: String`, which replaces the hardcoded `"bedrock-v1"`. When semantic retrieval abstains, the audit `verificationSummary` gets `; semanticAbstained=<CODE>` appended.
- ~/Documents/scholarchain/modules/rag/src/test/scala/edu/scholarchain/rag/RuntimeWorkflowSpec.scala: the two existing workflow constructions now pass the fake embedder, the reference-default config and `generationModelId`. Their assertions are unchanged. Added two workflow tests.
- ~/Documents/scholarchain/modules/rag/src/test/scala/edu/scholarchain/rag/RetrievalSmokeTest.scala: `EmbeddingModelConfig.fromEnv()` was renamed to `load()` because the method now loads through `AppConfig`, not raw env. The three assertions are unchanged.
- The other rag files already showed as modified before this session (formatting-only diffs). `rag/scalafmt` was run over them; this work order made no other edits to them. No files outside `modules/rag/**` were touched, and nothing was staged or committed.

COMMANDS RUN:
- Read-only inspection with `git diff --stat`, `grep`, `sed`, `cat` and `ls`
- `sbt "rag/scalafmt; rag/Test/scalafmt; rag/test"`: first run had 7 failures. Typesafe Config's `resolveWith` threw `ConfigException$BugOrBroken` in my test helper; I fixed it by resolving with `withFallback` and system env disabled.
- `sbt "rag/scalafmt; rag/Test/scalafmt; rag/test"`: exit 0, 65/65
- `sbt rag/test`: exit 0
- `sbt check`: exit 0
- `cat .claude/.last-test-run`, before and after

TEST RESULTS:
- `sbt rag/test`: exit 0. Total 65, succeeded 65, failed 0, 4 suites.
- `sbt check`: exit 0, ending with `[success] Total time: 26 s, completed 22-Nov-2026, 6:30:24 pm`. Per-module runs, all with 0 failures: 116, 114, 24, 52, 65 (rag), 133 and 63, for 567 tests in total.
- `.claude/.last-test-run` was updated from `2026-11-23T00:09:37.719716Z` to `2026-11-23T00:30:24.060851Z`.

VALIDATION RESULTS: The new tests cover each case in the work order, all passing.
- **Embedder vector, not a hash:** the vector is 1024 long, equals the fake's deterministic vector, and is queried on all 5 indexes. Two different query texts give the same vector, so it does not come from the text.
- **Model mismatch:** query `voyage-x` against the Titan index sends 0 vector queries, makes 0 embedder calls, and returns `EMBEDDING_MODEL_MISMATCH`. The same happens when the embedder reports a different model than the index.
- **Dimension mismatch:** a fake that returns 256 values gives 0 queries and `EMBEDDING_DIMENSION_MISMATCH`, with both 256 and 1024 in the detail.
- **Embedder failure:** a throwing embedder gives 0 queries and `EMBEDDING_UNAVAILABLE`.
- **Happy path:** a custom 1024-length vector reaches the vector port unchanged, and the contexts record the Titan model id.
- **Unknown model ids:** `some-unknown-model`, `amazon.titan-embed-text-v2:0-custom`, `titan-embed-text-v2` and the empty string are all treated as incompatible.
- **Voyage policy:** Voyage against a Titan index is refused; Voyage against a Voyage index set is allowed.
- **Config:** the defaults are Titan v2, 1024 dimensions and cosine. `SCHOLARCHAIN_EMBEDDING_MODEL_ID=voyage-3` is respected and makes the config incompatible. The legacy `EMBEDDING_MODEL_ID` is ignored. The `SCHOLARCHAIN_S3_VECTORS_*` overrides are respected. These run against reference.conf with the process env disabled, so the real environment is not touched.
- **Workflow:** the audit `modelVersion` comes from config. On a model mismatch the evidence has no TextChunk items and the summary contains `semanticAbstained=EMBEDDING_MODEL_MISMATCH`.
- **Serialization:** a failed bundle round-trips through JSON.

ASSUMPTIONS:
- No production Bedrock embedding adapter exists anywhere in the repo; a grep of the main sources found none. I defined only the `QueryEmbedder` port plus test fakes and did not invent an AWS client. The live Titan adapter is still not implemented (owner: rag-engineer, later phase).
- "Abstain" means the semantic part contributes no evidence. Metric and path evidence still flow, and `AnswerSynthesisAgent` abstains when the evidence is empty overall, as it does today.
- The audit `modelVersion` is the configured generation model id, because `LlmClient` does not report one.

RISKS:
- Nothing constructs `SemanticContextAgent` or `RuntimeWorkflow` in production yet; only rag tests do. Whoever wires them up must pass a real `QueryEmbedder` and `EmbeddingModelConfig.load()`. Until a Bedrock adapter exists there is no live semantic retrieval.
- The rag check can only trust config. If someone sets both `SCHOLARCHAIN_EMBEDDING_MODEL_ID` and `SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID` to Voyage but still points at the Titan bucket, the five index names are the same fixed `*-v1` names. Those names are shared with storage (`S3VectorsClient.scala:55-57`) and Terraform (`infra/terraform/variables.tf:150`), and Voyage vectors at 1024 dimensions would pass the dimension check. Keeping a separate index set physically separate (its own vectors bucket or index names) is a storage-engineer and infra-engineer matter.
- `SemanticContext.windowStartYear`/`windowEndYear` are still hardcoded to 2000/2026. That was out of scope and is unchanged.

MANUAL ACTION REQUIRED: None for this change. Later decisions are needed on the phase that adds the live Bedrock Titan `QueryEmbedder`, and on how a fallback index set is kept physically separate (storage-engineer and infra-engineer).

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: retrieval and groundedness evaluation, after a live Titan `QueryEmbedder` adapter exists and is wired by service-engineer.

### 3.6 / 4.6 — storage-engineer

#### Work order (verbatim)

````text
Work order (Phase 12 repair-02, storage-engineer). Repo: the current working directory (ScholarChain, branch `remediation`). Edit only `modules/storage/**`. Preserve all other uncommitted changes. Do NOT stage or commit. No AWS or network calls; use fakes in tests. Do not read .env.

Human decision (2026-11-22): every S3 Vectors index has dimension 1024 and cosine distance, and is populated by `amazon.titan-embed-text-v2:0`. Vectors from another model (for example a Voyage fallback) must never be written into a Titan index. A model switch requires a separate index set or full re-embedding.

Core config (domain-engineer, `edu.scholarchain.core`):
- `AppConfig.load().s3Vectors: S3VectorsConfig(dimensions, distanceMetric, embeddingModelId)`
- `isCompatibleModel(id)` (exact match)
- `isCompatibleVector(len)`

The query side (rag `SemanticContextAgent`) already enforces this.

Tasks:
1. In the write path (`EmbeddingBatchWriter.scala`, and `S3VectorsClient.putVectors` callers / `EmbeddingQueueManager` as relevant), validate every vector before `putVectors`:
   - length == configured dimensions;
   - metadata `embeddingModel` (or the equivalent field in `VectorMetadata`; check `S3VectorsClient.scala` ~lines 15-30) equals the configured index model.
   Reject mismatches into the existing rejected-vector / failure record path with reason codes `EMBEDDING_DIMENSION_MISMATCH` / `EMBEDDING_MODEL_MISMATCH`. Never write them, and never silently drop them; count them. If metadata lacks a model field, report how the model is recorded and add the minimum needed, consistent with the course spec's vector metadata (`embeddingModel`, `textHash`, `runId`, ...).
2. Make the writer take the S3 Vectors config through its constructor or an existing config path. Do not read raw env vars, and do not hardcode 1024 or the model id in main code.
3. Content-hash skip rule: an unchanged text should be skipped only when the textHash and the embedding model both match. If the model differs, re-embed and write to the configured index set, which must be the correct one per policy. Implement this only if a skip rule already exists; otherwise note it.
4. Tests (storage test tree):
   - a 1024 Titan vector is written;
   - a 256 vector is rejected with the dimension code and not written;
   - a Voyage-model vector is rejected with the model code and not written;
   - a mixed batch writes only the valid ones and reports the counts;
   - an unknown or empty model id is rejected.
   Keep existing assertions; explain any change.
5. Run `sbt storage/test`, then `sbt check`. Report the exact counts and exit codes, and whether `.claude/.last-test-run` was updated. If rag or services fail to compile because of your change, do not edit them; report BLOCKED-ON with file:line.

Final report with the 14 fields: STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Never claim a command passed without seeing the output.

````

#### Final report (verbatim)

STATUS: COMPLETE

OWNER: storage-engineer

WORK ORDER: Phase 12 repair-02. The S3 Vectors write path now checks every vector's dimension and model against the configured index contract before it is written, rejects mismatches with reason codes and counts them, applies the same rule to the content-hash skip check, and adds tests.

FILES CREATED:
- ~/Documents/scholarchain/modules/storage/src/test/scala/edu/scholarchain/storage/EmbeddingWriteValidationSpec.scala

FILES MODIFIED:
- ~/Documents/scholarchain/modules/storage/src/main/scala/edu/scholarchain/storage/EmbeddingBatchWriter.scala
- ~/Documents/scholarchain/modules/storage/src/main/scala/edu/scholarchain/storage/EmbeddingQueueManager.scala
- ~/Documents/scholarchain/modules/storage/src/test/scala/edu/scholarchain/storage/StorageSpec.scala
- ~/Documents/scholarchain/modules/storage/src/test/scala/edu/scholarchain/storage/AdversarialStorageSpec.scala

The formatting-only changes that were already uncommitted in `EmbeddingBatchWriter.scala` and `EmbeddingQueueManager.scala` are kept. I staged and committed nothing, and changed no files outside `modules/storage/**`.

COMMANDS RUN:
1. `sbt -batch storage/scalafmtAll storage/test` (exit code not captured because zsh does not set `PIPESTATUS`; the output showed 63/63 passing)
2. `sbt -batch storage/test`, exit 0
3. `sbt -batch check`, exit 0
4. Read-only checks: `git status`, `git diff`, `grep`, and reading `.claude/.last-test-run`

TEST RESULTS:
- `sbt storage/test`: exit 0. 63 tests run, 63 passed, 0 failed, 0 canceled, 0 ignored. That is 52 existing tests plus 11 new ones.
- `sbt check`: exit 0, with 0 `[error]` lines. The tests ran twice: once from `;test`, and again because `markTestRun` triggers the tests itself.
  - Each run had 7 module results (114, 133, 63, 65, 24, 116, 63), so 578 tests per run and 1156 in total. All passed; none failed.
  - Log line: `Wrote test-run marker: ~/Documents/scholarchain/.claude/.last-test-run`
- `.claude/.last-test-run` was updated, from `2026-11-23T00:30:24.060851Z` to `2026-11-23T00:35:36.530167Z`.
- rag and services compiled with no errors. Nothing outside storage calls `EmbeddingBatchWriter`, `putVectors`, `VectorRecord` or `isDuplicate`, so no other module was affected.

VALIDATION RESULTS:
- **Where the model is recorded:** `VectorMetadata` already has `embeddingModel: String`, next to `textHash` and `runId` (`S3VectorsClient.scala` lines 14-25), so no new metadata field was needed.
- **Settings come from config:** `EmbeddingBatchWriter` now requires an `indexConfig: edu.scholarchain.core.S3VectorsConfig` constructor argument, normally `AppConfig.load().s3Vectors`. Main code does not hardcode 1024 or the model id and reads no environment variables.
- **Validation:** `EmbeddingBatchWriter.validate` checks each record in this order, and the first failure sets the reason:
  1. empty vector: `EMPTY_EMBEDDING`
  2. wrong length (`isCompatibleVector`): `EMBEDDING_DIMENSION_MISMATCH`
  3. wrong model (`isCompatibleModel`, exact match): `EMBEDDING_MODEL_MISMATCH`
- **Rejections are recorded and counted, never written:**
  - Rejected records never reach `putVectors`; if every record fails, `putVectors` is not called at all.
  - `BatchWriteResult` keeps its existing `written`, `rejected`, `retried` and `errors` fields and gains two new ones, which default to empty:
    - `rejections: Vector[RejectedVector]`, holding the key, reason code, detail and the original metadata for provenance.
    - `rejectedByReason: Map[String, Int]`
  - Each error line includes the reason code and the expected and actual values.
- **Feature tests** (all pass, using a fake S3 Vectors client that records what it is asked to write):
  - A 1024-length Titan v2:0 vector is written.
  - A 256-length vector is rejected with `EMBEDDING_DIMENSION_MISMATCH` and the fake records 0 writes.
  - A Voyage vector is rejected with `EMBEDDING_MODEL_MISMATCH` and the fake records 0 writes.
  - A mixed batch of 7 writes only `ok-1` and `ok-2`: written=2, rejected=5, `{DIMENSION_MISMATCH: 2, MODEL_MISMATCH: 2, EMPTY_EMBEDDING: 1}`.
  - Empty, whitespace-only, unknown and space-padded model ids are all rejected as `MODEL_MISMATCH`, 4 of 4.
  - Extra tests cover:
    - When both the length and the model are wrong, the length is reported.
    - A writer built from `AppConfig.load().s3Vectors` behaves the same way.
    - A writer given a different index contract follows that contract exactly.
- **Skip rule:** a skip check already existed (`isDuplicate(entityId, textHash, modelVersion)`, keyed on all three), so I extended it rather than writing a new one.
  - `shouldSkipEmbedding(entityId, textHash, indexConfig)` skips only when the stored key matches the configured index model.
  - `recordEmbedded(entityId, textHash, model)` records the stored key.
  - Tests show that text previously embedded with Voyage is not skipped under the Titan config, so it gets re-embedded.
  - The writer only ever writes to the configured index set (the `-v1` indexes in `S3VectorsIndexes`).
- **Changes to existing tests** (no assertion was weakened):
  - Every writer constructor call now passes `indexConfig`.
  - In `StorageSpec` "count written and rejected correctly", the fixtures changed from `Vector(0.1f, 0.2f)` with model `amazon.titan-embed-text-v2` to 1024-length vectors with `amazon.titan-embed-text-v2:0`. Without this the new validation would reject them. The assertions (written=1, rejected=2, 2 errors) are unchanged.
  - The "Empty embedding" message text is kept, so existing `include("Empty embedding")` checks still pass.

ASSUMPTIONS:
- "Unknown model" means any id that is not exactly the configured one, including `amazon.titan-embed-text-v2` without the `:0` version suffix.
- Each rejected record gets one reason code, chosen by the check order empty, then dimension, then model.
- Records that `putVectors` itself reports as failed still count in `rejected`, as before, but are not listed in `rejectedByReason`, which covers only validation rejections.

RISKS:
- `markComplete(jobId)` adds the jobId to the dedup store, but `isDuplicate` looks up a hash of entityId, textHash and model. So before this change the skip rule could never trigger through the public API. I added `recordEmbedded` and left `markComplete` as it was. Callers must call `recordEmbedded` only after a successful write.
- `S3VectorsStorageClient.putVectors` does no validation itself. A direct call that skips `EmbeddingBatchWriter` would bypass the checks; no such call exists today.
- The writer does not check that `indexName` belongs to the configured index set; `writeBatch` accepts any name.
- Older test fixtures and serialization tests still use the model string `amazon.titan-embed-text-v2` (no `:0`). Vectors with that metadata would now be rejected. Any existing upstream data with that string needs checking.
- NaN or infinite vector values are not checked.

MANUAL ACTION REQUIRED: Review the changes and stage and commit them yourself. If you want callers moved from `markComplete` to `recordEmbedded`, or index-name checking added, those are new work orders.

BLOCKED-ON OWNER: none

BLOCKED-ON EVIDENCE: none

NEXT GATE: A test-engineer contract test (`tests/contracts`) checking that the rag query-side check (`SemanticContextAgent` / `EmbeddingModelConfig`) and the storage write-side `EmbeddingBatchWriter.validate` agree for the same `S3VectorsConfig`. Then a bounded load test of `writeBatch` with mixed batches, checking that `written + rejected == input size`.


## 5. Rerun evidence (orchestrator rerun, 2026-11-22, after all six agents)

| Command | Before repair-02 | After (orchestrator) |
|---|---|---|
| `sbt -batch check` | exit 1 at `scalafmtCheckAll`: 3 Phase 11 adversarial specs unformatted (the domain-engineer run) | **exit 0**. Each test pass had 7 module results: 114, 116, 133, 24, 63, 63 and 65 (578 tests), 0 failed. `markTestRun` reran the tests, so there were 2 passes. `.claude/.last-test-run` changed from `2026-11-23T00:35:36.530167Z` to `2026-11-23T00:37:15.338711Z`. |
| `terraform init -backend=false` | — | exit 0 |
| `terraform fmt -check -recursive` | exit 0 | exit 0 |
| `terraform validate` | `Success!` (repair-01) | `Success! The configuration is valid.` |
| `bash -n scripts/*.sh` (7 scripts) | pass | pass |
| `git diff --cached --name-only` | 0 | 0 (nothing staged) |

Diffs:
- Terraform and scripts: `tf-phase12-r2.diff`, 15 files, +337/−102, kept outside the repository in the session scratchpad.
- Scala: modules/core, rag, storage, plus 3 test specs; see `git diff --stat`.

**NOT RUN:** `terraform plan`, `apply` and `destroy`; any AWS call; any paid model call; shellcheck (not installed).

## 6. Re-review

code-reviewer Pass 1 (2026-11-22): **REQUEST_CHANGES**. Full text is in `review-report.md`.
- Confirmed correct:
  - S3 Vectors resources: 5 indexes, float32, 1024 dimensions, cosine, non-filterable keys.
  - s3vectors action names and the index-ARN scoping.
  - Runtime and loader separation: the loader role is attached only to embedding-worker, whose desired count is 0.
  - PassRole scoping.
  - A single root provider.
- Remaining:
  - 1 CRITICAL (R1: dev and prod share one state key).
  - 11 MAJOR (R2–R12).
  - 7 MINOR (R13–R19).
  - 4 INFO (R20–R23).
  Most are pre-existing. R3 (no Bedrock permission on the loader role) needs a human security decision.

## 7. Outcome

**Partially fixed.**
- Fixed: every item in the work order.
  - 1024 dimensions and cosine are consistent across core, rag, storage, tfvars and ECS.
  - The placeholder query vector is removed.
  - Query and write guards for model and dimension.
  - The lock file is tracked and has hashes for darwin_arm64 and linux_amd64.
  - Root provider with default tags.
  - The loader role is wired to embedding-worker.
  - The cleanup message is truthful.
  - `sbt check` is green.
- Open:
  - code-reviewer findings R1–R23. Owners: infra-engineer, plus storage-engineer for R4 (queue URL key), R5 (SigV4) and R12 (S3 Vectors API).
  - There is no live Bedrock `QueryEmbedder` adapter (rag-engineer, a later phase).
  - Next repair: repair-03.
- Human gate: **PENDING**. Phase 12 acceptance and deployment approval remain with the human architect.
