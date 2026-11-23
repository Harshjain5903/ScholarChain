# Phase 12 repair 05: remaining code-reviewer findings after Pass 3

Date: 2026-11-23
Branch and state: `remediation`, parent `753c499`. The working tree is uncommitted, and `infra/terraform/**` and `scripts/*.sh` are untracked.
Evidence assembled by: the orchestrator. Local scratchpad paths are shortened to `<session-scratchpad>`.

## 1. Trigger

- Source: code-reviewer Pass 3 (`review-report.md`) returned APPROVE for repair-04 but left open findings:
  - N3–N15 from Pass 2.
  - New findings N16–N25.
  - R7 stage 1.
- Human instruction (2026-11-23), verbatim: "fix the issues".
- Standing prohibitions, from the human's earlier instructions in this phase, still apply: no live plan, apply, cleanup, model calls, AWS changes, staging or commits.

### Choices the orchestrator made where a finding called for a human decision

These are recorded here and reported back for the human to accept or change. None is a human approval.

- **N16** (public, unauthenticated HTTP ALB). The more restrictive option was implemented:
  - A required operator-CIDR allowlist with no default and no invented IP address.
  - Port 443 removed.
  - `drop_invalid_header_fields` set on the ALB.

  The human may still choose a public API instead.
- **R7.** Only stage 1 was done. It is a tightening: the runtime role's `foundation-model/*` Bedrock grant was removed. No new Bedrock grant was added.
- **Not attempted**, because each needs a human decision or an owner assignment:
  - R7 stage 2 and N21: choosing the generation model and enabling Anthropic model access.
  - N22: the prod Neptune CMK.
  - N23: confirming where the state bucket lives (backend unchanged).
  - N14: the embedding-worker entry point (owner to be assigned by the human).
  - N19 application side: durable shared state (service-engineer).
  - API authentication (service-engineer; required before prod).
  - R23 (deferred by the human).
  - `.claude/agents/test-engineer.md` (out of scope by human decision).

## 2. Routed to

| Order | Agent | Owned path | Findings |
|---|---|---|---|
| 1 (parallel) | infra-engineer | `infra/terraform/**`, `scripts/*.sh` | N16, AWS-0104/0052, N20, R7 stage 1, N3, N24, N25, N5, N6, N7, N8, N9, N12, N13, N18, N19 (infra part); N15 and N23 report-only |
| 1 (parallel) | storage-engineer | `modules/storage/**` | N4, N10, N11, storage region defaults |
| 2 | domain-engineer | `modules/core/**` | core region defaults (after storage, so that sbt runs did not overlap) |
| 3 | code-reviewer (read-only) | — | Pass 4 |

## 3. Work orders and agent final reports

The work orders are as sent. The agents' final reports were transcribed from their hand-back messages. Formatting was tightened for Markdown; no finding, number, path or claim was changed.

### 3.1 Work order to infra-engineer (summary of the 17 tasks; the full text is in the session record)

- **Prohibitions:**
  - No `terraform plan` (project or scratch), apply, destroy or cleanup.
  - No AWS CLI, API or Bedrock calls, and no AWS changes.
  - No `git add` or commit, and no `.env` or credential files.
  - Edit only `infra/terraform/**` and `scripts/*.sh`.
  - Do not change `titan_embedding_model_arn`, `aws_region` or the AZs, or the backend `region`.
- **Tasks:**
  1. N16: restrictive option. Add `alb_ingress_cidrs` (required, validated, rejects /0) and do not invent an IP in tfvars. Remove port 443. Set `drop_invalid_header_fields`.
  2. AWS-0104: ALB egress only tcp/8080 to the app security group. No egress from the Neptune security group. App egress tcp/443 plus tcp/8182 to the data security group. EMR egress kept, with a comment.
  3. N20: Neptune audit log export, a managed log group with retention, and an archive export.
  4. R7 stage 1: remove the runtime role's `foundation-model/*` statement. Add no new grant.
  5. N3: refuse `TF_WORKSPACE`, `TF_DATA_DIR` and `TF_CLI_ARGS*`.
  6. N24: run lock.
  7. N25: base-10 numeric parsing.
  8. N5: TTL on entity-link-cache, after confirming the attribute.
  9. N6: `plan.sh` requires a clean tree and records the clean HEAD.
  10. N7: check that the image exists before apply, and use a safe ECR lifecycle policy.
  11. N8: export all five tables.
  12. N9: a separately confirmed empty phase with inventories, archiving the logs and artifacts bucket contents. No `force_destroy`.
  13. N12: scope the EMR step actions. Document the MFA limitation.
  14. N13: a unique final snapshot ID.
  15. N18: smoke-test payload field `question`, and the evidence ID taken from `claims[].evidenceIds`.
  16. N19 (infra part): route evidence and explain requests to `query-api`, with stickiness.
  17. N15 and N23: report only.
- **Checks:** `fmt`, `init -backend=false`, `validate`, `/bin/bash -n`, and refusal tests that exit before any AWS call.

### 3.2 infra-engineer final report

STATUS: COMPLETE. This was local authoring only. Nothing was planned, applied or deployed. `sbt check` was not run, because no application source changed.

All 17 tasks are done. `terraform validate` passes without `alb_ingress_cidrs` being set. While working on N19, infra-engineer found two pre-existing bugs that would have failed the first plan or apply, and fixed both (task 16).

**Per-task outcomes:**

1. **N16:** new required variable `alb_ingress_cidrs` (`infra/terraform/variables.tf:60-83`):
   - It has no default.
   - Validation: the list must be non-empty, and every entry must be a canonical IPv4 CIDR with the host bits zero. 0.0.0.0/0, ::/0 and any other /0 are rejected.
   - It is wired through `main.tf:21` to `modules/network/variables.tf:26` and `modules/network/main.tf:269-282`, which creates one tcp/80 rule per CIDR.
   - The port 443 ingress rule is removed.
   - `drop_invalid_header_fields = true` is set (`modules/ecs/main.tf:298`).
   - The tfvars leave the variable unset and carry an operator comment (`environments/{dev,prod}.tfvars:14-22`).
   - `plan.sh` reads `ALB_INGRESS_CIDRS="203.0.113.10/32[,…]"` and validates it in bash (`plan.sh:93-142`). It passes the value with `-var` (`:170`) and records it in the plan metadata (`:186`).
   - `cleanup.sh:634` uses the placeholder `["192.0.2.0/24"]` (TEST-NET-1) for the destroy plan. Any non-delete action in that plan aborts the script.
2. **AWS-0104:** the ALB, app and data security groups now use standalone `aws_vpc_security_group_{ingress,egress}_rule` resources, which avoids a dependency cycle (`modules/network/main.tf:236-466`).
   - ALB egress: tcp/8080 to the app security group only (`:284`).
   - App egress: tcp/443 to 0.0.0.0/0, and tcp/8182 to the data security group (`:333`, `:346`).
   - Data (Neptune) security group: no egress.
   - EMR egress: unchanged, with a comment (`:388-391`).
   - Security groups now have descriptions, which partly addresses AWS-0099.
3. **N20:**
   - `enable_cloudwatch_logs_exports = ["audit"]` (`modules/neptune/main.tf:97`).
   - Managed log group `/aws/neptune/<prefix>-neptune/audit` with retention (`:21-28`), and `depends_on` (`:110`).
   - New outputs.
   - `archive-evidence.sh:196-198` exports the log, and `cleanup.sh:222-235` requires it.
4. **R7 stage 1:** the `BedrockInvoke` statement on `foundation-model/*` is removed from `ecs_task_runtime`, with a comment pointing to `repair-04.md` §5.3 (`modules/iam/main.tf:303-310`). No grant was added. The loader role's Titan grant (`:459`) is unchanged.
5. **N3:** `tf_env_refuse_tf_overrides` (`scripts/tf-env.sh:62-80`, which uses `compgen -e`) exits 3. It is called from `tf_env_validate_name` (`:87`) and `tf_env_init_backend` (`:246`).
6. **N24:** an mkdir-based lock at `infra/terraform/.terraform/scholarchain-run.lock` (gitignored) (`tf-env.sh:160-237`).
   - It records a token and info (env, pid, script, start time) and is released by an EXIT trap that is installed when `tf-env.sh` is sourced. The trap removes the lock only if the owner and token match.
   - A second concurrent run gets exit 3. A stale lock needs a human to remove it.
   - A child process may reuse the lock with the inherited token and the same env; this lets `deploy.sh` call `smoke-test.sh`.
   - `tf_env_require_verified` also checks the lock (`:341-345`).
   - Scripts define `tf_env_script_cleanup` instead of setting their own EXIT trap.
7. **N25:** in `run-bounded-pipeline.sh:31-38`, the timeout must match `^[1-9][0-9]{0,3}$`, be at most 1440, and is read with `10#`. The same fix was applied to `cleanup.sh:89-94` (`ARCHIVE_MAX_AGE_HOURS` 1-168), `cleanup.sh:214` and `rollback.sh:77`.
8. **N5:** `ttl { attribute_name = "ttl" enabled = true }` (`modules/storage/main.tf:121-130`).
   - `DynamoDbClient.scala:113` writes `"ttl"` as a Number, from `ttl: Long` (`:32`).
   - The unit is not confirmed. The only fixture value is epoch seconds. If milliseconds were ever written, items would never expire, which is a safe failure. storage-engineer should document the unit.
9. **N6:** `plan.sh:73-90` refuses (exit 2) if `git status --porcelain --untracked-files=all` shows anything for the whole repo. It also refuses gitignored files under `infra/terraform` outside `.terraform/`. The metadata records `commit`, `tree_clean=yes`, `plan_scope` and `alb_ingress_cidrs` (`:181-190`). `deploy.sh:73` requires `tree_clean=yes`.
10. **N7:**
    - `deploy.sh:91-139` uses `terraform show -json` on the saved plan. If the plan changes anything other than ECR, `aws ecr describe-images` must return a `sha256:` digest for `<sha>` in all 6 repos, or the script exits 1 before apply.
    - `PLAN_SCOPE=ecr-bootstrap` (`plan.sh:51-70`) targets ECR only. Deploy exits after applying it, with a "push images, then plan in full" banner (`:155-170`).
    - Lifecycle policy (`modules/ecs/main.tf:133-166`, `:588-591`): expire untagged images 14 days after push. Tagged images never expire, because any `<sha>` tag may be running or be a rollback target, and tags are immutable.
11. **N8:** `archive-evidence.sh:150-187` scans all 5 tables with `--consistent-read` and full pagination, checks that `NextToken` is absent and that `Items` length equals `Count`, and adds checksums to `SHA256SUMS`. `cleanup.sh:222-235` requires all 5 table exports and the Neptune audit export to be listed in `SHA256SUMS`.
12. **N9:** new phase 5 in `cleanup.sh` (`:329-583`); later phases are renumbered 6-9.
    - It inventories all 6 buckets (full version listings as JSON and TSV, plus counts of versions and delete markers) and all 6 ECR repos.
    - It copies the current objects of the logs and artifacts buckets into `<archive>/cleanup-empty-<UTC>/s3-contents/` and checks their sizes. It prints that raw, curated, graph and rag contents are not copied (they are reproducible).
    - It writes and verifies its own `SHA256SUMS`.
    - It deletes only after a second typed confirmation, `EMPTY <account>/<region>/<env>`:
      - S3 objects with `delete-objects` in batches of 1000, inventoried versions only.
      - ECR images with `batch-delete-image` in batches of 100, inventoried digests only, up to 3 passes.
    - It recounts afterwards. Anything left over, or any error, aborts before the destroy, and the abort titles say exactly what happened (`:100`, `:128-136`).
    - The logs bucket is retained and not emptied. S3 Vectors stays excluded. No `force_destroy` is used.
13. **N12:** the `EMRSteps` statement is conditioned on `elasticmapreduce:ResourceTag/Environment = <env>` and `ResourceTag/Project = "ScholarChain"` (`modules/iam/main.tf:745-768`). Tags are used because referencing the cluster ARN would create an IAM↔EMR cycle. The MFA and Identity Center limitation is documented (`:632-642`), and MFA is kept.
14. **N13:**
    - `terraform_data.final_snapshot_stamp` takes `input = timestamp()` with `ignore_changes = [input]`.
    - The prod identifier is `<prefix>-neptune-final-<YYYYMMDDhhmmss>` (`modules/neptune/main.tf:30-55`, `:91`). It is stable for the life of the cluster and new after every destroy and redeploy.
    - A variable supplied only to cleanup would not work, because the provider uses the identifier from the prior state on delete.
    - `cleanup.sh:301-323` blocks if a manual snapshot with that ID already exists.
15. **N18:**
    - The payload is `{"question": …}`.
    - Tests 3-5 share a cookie jar and an Idempotency-Key. JSON is parsed with jq or `python3 -I`; if neither is available, the script exits 2.
    - Test 3 requires a 2xx response and a `queryId`.
    - Test 4 takes `claims[].evidenceIds[0]`, validates it as a path segment, and requires a 2xx response with a matching `evidenceId`. A missing ID is a FAIL.
    - Test 5 requires a byte-identical body.
    - Lines: `scripts/smoke-test.sh:21-34`, `:41-100`, `:138-206`.
16. **N19 (infra part):**
    - `/v1/evidence*` is routed to the query-api target group (priority 105, `modules/ecs/main.tf:467-475`). `/v1/explain*` and `/v1/jobs*` were already routed there; `Routes.scala` confirms that `queryResults`, `jobStore`, `idempotencyCache` and `auditStore` all live in query-api.
    - lb_cookie stickiness of 3600 s is set on query-api (`:339-343`).
    - The durable shared-state fix (service-engineer) is still open.
    - Two extra pre-existing fixes:
      1. Only routed services (`alb_services = keys(routing_rules)` = query-api and graph-retriever, `:51`) now get a target group (`:340`) and a dynamic `load_balancer` block (`:512-519`). ECS rejects a target group that has no listener, and vector-retriever and verification-worker already had such target groups.
      2. The `ecs_unhealthy_tasks` alarm indexed a map keyed by short service names using full names. It now uses `for_each = var.ecs_target_group_arn_suffixes` (`modules/observability/main.tf:228-245`).
17. **N15 and N23:** report only. The backend is unchanged.
    - From documentation knowledge, not verified: S3 backend `dynamodb_table` has been deprecated since Terraform 1.11 in favour of `use_lockfile = true`, and 1.17 is expected to accept it with a warning.
    - Migration: add `use_lockfile = true` alongside `dynamodb_table`, run `init -reconfigure` through `tf-env.sh`, grant the runner `s3:Get/Put/DeleteObject` on `<key>.tflock`, and later remove `dynamodb_table`.

**Checks (actual):** Terraform v1.17.2, darwin_arm64.

| Check | Result | Exit |
|---|---|---|
| `fmt -recursive` | — | 0 |
| `fmt -check -recursive` | — | 0 |
| `init -backend=false -input=false` | — | 0 |
| `validate` | "Success! The configuration is valid." without `alb_ingress_cidrs`. Rerun after the task-16 fixes with the same result. | 0 |
| `/bin/bash -n` (bash 3.2.57) | 8 of 8 passed | 0 |

**Refusal and behaviour tests:**
- **N3:** `TF_WORKSPACE`, `TF_DATA_DIR`, `TF_CLI_ARGS` and `TF_CLI_ARGS_init` with `plan.sh dev`, and `TF_CLI_ARGS_plan` with `smoke-test.sh dev`: each exits 3 and names the variable.
- **N6:** in the real, dirty repo, `plan.sh dev` lists the files and exits 2.
- **N6, N16 and the `plan.sh` accept path,** tested in a scratch git repo where `aws` and `terraform` were shims that fail loudly:
  - Exit 2: an untracked `.tf` file, a modified tracked file, an ignored `x.tfstate`. An ignored file under `.terraform/` is allowed.
  - Exit 2 before any AWS step: `ALB_INGRESS_CIDRS` empty, `0.0.0.0/0`, `::/0`, `10.0.0.0/0`, a bare IP, a value with host bits set, `256.1.1.1/32`, a leading zero, `/33`, `,`, a list mixing a valid entry with `/0`, IPv6, and `PLAN_SCOPE=foo`.
  - A valid list passed validation and stopped at the first shim (`aws sts`) with exit 4.
- **N16, Terraform side:** the variable block was copied into a provider-less scratch module and checked with `validate` only (no `console`, no `plan`). The valid list passes. `[]`, /0, ::/0, a value with host bits set, `300.1.1.1`, IPv6 and a bare IP each fail with exit 1.
- **N25:**
  - `run-bounded-pipeline.sh dev` with 08, 09, 0, 007, 1441, abc, -5 or 12345: exit 2.
  - `ARCHIVE_MAX_AGE_HOURS=08 cleanup.sh dev`: exit 2.
  - `plan.sh staging`: exit 2.
- **N24:**
  - A concurrent second run is refused with exit 3, and the lock is released after the holder exits.
  - A child with the inherited token and the same env is allowed; with a different env it gets exit 3.
  - Forged tokens are handled safely.
  - A guard exit releases the lock.
  - No lock was left in the real repo.
- **jq filters** (deploy ECR scope, cleanup batches, TSV, copy verification, ECR chunking) and the smoke-test JSON helpers (jq and python3 agree): tested on synthetic JSON.

**Not done or unverifiable:**
- AWS behaviours taken from documentation and not checked live:
  - EMR support for `ResourceTag` conditions on the step actions.
  - The Neptune audit log group name.
  - ECS rejecting a target group with no listener.
  - The provider using the prior-state snapshot ID on destroy.
  - `--consistent-read` and `Count` on paginated scans.
- No plan was run, so the `terraform_data` stamp, the security-group rule resources and the alarm change are unobserved.
- Smoke Test 4 will FAIL while `composeStubs()` returns no `evidenceIds`.
- `deploy.sh` now requires jq.
- Copying the logs bucket can be large.
- The ECR untagged-expiry rule assumes single-platform images.

**Confirmations:**
- No plan (project or scratch), apply, destroy or console.
- No AWS CLI, API or Bedrock calls; the scratch tests used failing shims.
- No `git add` or commit, and no `.env` or credential files read.
- Edits only under `infra/terraform/**` and `scripts/*.sh`.
- `titan_embedding_model_arn`, `aws_region`, the AZs and the backend `region` are unchanged.

**Files modified:**
- `infra/terraform/{variables,main,outputs}.tf`
- `environments/{dev,prod}.tfvars`
- `modules/network/{main,variables}.tf`
- `modules/ecs/{main,outputs}.tf`
- `modules/neptune/{main,variables,outputs}.tf`
- `modules/storage/main.tf`
- `modules/iam/main.tf`
- `modules/observability/main.tf`
- `scripts/{tf-env,plan,deploy,smoke-test,run-bounded-pipeline,rollback,archive-evidence,cleanup}.sh`

**MANUAL ACTION REQUIRED:**
- Decide N16.
- Supply `ALB_INGRESS_CIDRS`.
- For the first deployment: run `PLAN_SCOPE=ecr-bootstrap`, push images, then run a full plan.
- Review the first plan.
- Confirm the state-bucket region (N23).
- Approve R7 stage 2.
- Decide whether to move to `use_lockfile`.

### 3.3 Work order to storage-engineer (summary)

- **Prohibitions:** no AWS calls, no staging or commit, no `.env` files. Edit only `modules/storage/**`. Feature tests are required.
- **Tasks:**
  1. N4: QueryVectors `topK` must be 1..100, with no `nextToken` paging. Replace the test that assumed paging.
  2. N10: use an advancing clock in the re-signing test.
  3. N11: replace `shortestPath()` with a bounded variable-length path, using parameters and the edge-type allowlist, and add tests.
  4. Change the storage `reference.conf` region defaults to us-east-2, keeping the env overrides.
- **Run:** `storage/test` and the scalafmt checks.

### 3.4 storage-engineer final report

STATUS: COMPLETE (scoped). `sbt -batch "storage/test"` passed. Full `sbt check` was left for the domain-engineer's run.

**Files modified** (all under `modules/storage/**`):

- `S3VectorsClient.scala` (N4):
  - `MaxTopK` is 100 (was 10000). `queryVectors` rejects `topK` outside 1..100 with `IllegalArgumentException` before any request, with the message "topK must be between 1 and 100 (S3 Vectors QueryVectors limit; no paging), got N".
  - The `nextToken` loop is removed, so each query is a single request whose results are capped at `topK`. ListVectors is not implemented, so no pagination remains.
  - The docs say the limit (100, or 30 in preview) comes from AWS documentation and was not verified live.
  - The example endpoint in a comment is now us-east-2.
- `NeptuneClient.scala` (N11):
  - `shortestPath` now builds its query through `NeptuneStorageClient.shortestPathQuery`: `MATCH p = (a:Researcher {researcherId: $fromId})-[<filter>*1..<d>]-(b:Researcher {researcherId: $toId}) RETURN [n IN nodes(p) | n.researcherId] AS path ORDER BY length(p) ASC LIMIT 1`.
  - IDs are passed as parameters only. Depth is clamped to 1..10.
  - There is a new optional `edgeTypes: Vector[String] = Vector.empty`, validated against the allowlist by a shared `edgeTypeFilter` helper, which `getNeighbors` now also uses. The existing signature still works.
- `AwsSigV4.scala`: comment example changed to us-east-2.
- `reference.conf`: the neptune, dynamodb and s3-vectors `region` defaults are now "us-east-2". The `${?SCHOLARCHAIN_*_REGION}` overrides are kept.
- `SignedStorageClientsSpec.scala`: the test helper `vectorsClient` takes a `clock`. New `SteppingClock` and `signatureOf` helpers.

**Tests:** the storage module went from 91 to 97. `SignedStorageClientsSpec` went from 28 to 34; `StorageSpec` (42), `AdversarialStorageSpec` (10) and `EmbeddingWriteValidationSpec` (11) are unchanged.
- The test that assumed paging to 10,000 is replaced by: exactly one request, no `nextToken` sent, at most `topK` results (the response includes a `nextToken` and the test asserts no second call).
- The out-of-range test now uses 1..100: 101, 500, 10000 and 0 are rejected without calling the service, and the test asserts `MaxTopK == 100`. A new test accepts `topK=100`.
- The re-signing test (N10) uses a clock that advances 7 s per send. It asserts X-Amz-Date values of 20261122T120000Z and then 20261122T120007Z, that the Authorization signatures differ, and that the bodies are identical.
- Three Neptune tests:
  - The query has no `shortestPath`, uses the bounded pattern with `ORDER BY length(p)` and `LIMIT 1`, and a hostile ID appears only in the parameters.
  - Depth is clamped, and the any-type and multi-type filters are built correctly.
  - An invalid edge type is rejected before sending.
- Two config tests: the defaults are us-east-2, and each can be overridden from env.

**Commands:**
- `sbt -batch "storage/scalafmt" "storage/Test/scalafmt" "storage/test"`: exit 0, "Tests: succeeded 97, failed 0 … All tests passed." (compiled with `-Xfatal-warnings`).
- `sbt -batch "storage/scalafmtCheckAll" "storage/scalafmtSbtCheck"`: exit 0.

**Unverified:**
- The live QueryVectors limit and the absence of `nextToken`.
- Neptune openCypher support for the list comprehension, `length(p)` and ORDER BY on a path.

**Risk:** without `shortestPath`, Neptune enumerates paths up to the depth bound. At depth 10 with no type filter this could be expensive. Lowering the cap or passing `Vector("COAUTHOR")` touches rag-engineer and is a separate decision.

**Confirmations:** no AWS or network calls (fakes only), no staging, no `.env` files, and edits only under `modules/storage/**`.

### 3.5 Work order to domain-engineer (summary)

- **Task:** change the core `reference.conf` region defaults (about :16 and :63) to us-east-2, keeping the env overrides.
- **Do not change:** `models.generation-model-id` (N21; the model is not chosen yet), or the embedding model, 1024 dimensions and cosine settings.
- **Tests:** add `AppConfigSpec` feature tests.
- **Run:** full `sbt -batch check`. Edit only `modules/core/**`.

### 3.6 domain-engineer final report

STATUS: COMPLETE.

**Files modified:**
- `modules/core/src/main/resources/reference.conf`:
  - :16 `scholarchain.neptune.region` and :63 `scholarchain.models.region` now default to "us-east-2".
  - The overrides at :17 and :64 are unchanged.
  - Not touched: the generation model ID, the embedding model IDs, 1024 dimensions and cosine.
- `AppConfigSpec.scala`:
  - The default-load test (:20, :43) now expects us-east-2.
  - A new "Region config" block with 4 tests: both regions default to us-east-2; no default is us-east-1; the `SCHOLARCHAIN_NEPTUNE_REGION=eu-west-1` override works and the models region stays us-east-2; the `SCHOLARCHAIN_MODELS_REGION=us-west-2` override works.
  - The "us-east-1" values in the type-mismatch test (:63, :76) are inline fixtures and were kept.

**`sbt -batch check`:** exit 0.
- `scalafmtCheckAll` and `Test/compile` succeeded.
- Tests per module: core 118, hadoop 133, entity-resolution 116, storage 97, rag 65, services 32, spark 63. Total 624; 0 failed, canceled, ignored or pending.
- `markTestRun` reran the suite with the same counts.
- Core went from 114 to 118 tests (+4), and the total from 620 to 624.
- `.claude/.last-test-run` went from `2026-11-23T11:50:15.890334Z` to `2026-11-23T12:05:34.955294Z`.

**Unverified:** Bedrock model availability in us-east-2.

**Confirmations:** no AWS or model calls, nothing staged, no `.env` files, and edits only under `modules/core`.

## 4. Rerun evidence (orchestrator, 2026-11-23, after all three agents)

| Command | Result | Exit |
|---|---|---|
| `terraform fmt -check -recursive` | no output | 0 |
| `terraform init -backend=false -input=false` | passed | 0 |
| `terraform validate` | `Success! The configuration is valid.` (without `alb_ingress_cidrs`) | 0 |
| `/bin/bash -n scripts/*.sh` (bash 3.2.57) | 8 of 8 passed | 0 |
| `sbt -batch check` | passed. Per module: 118, 116, 133, 97, 65, 32, 63 = 624 per pass, 2 passes, 0 `[error]` lines. Marker went from `2026-11-23T12:05:34.955294Z` to `2026-11-23T12:18:01.910164Z`. | 0 |
| `trivy config` (0.76.1), dev and prod tfvars | **37** failed checks each (was 47): 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW. See the breakdown below. | 0 |
| `git diff --cached --name-only` | 0 (nothing staged) | 0 |

The Trivy findings still open are all in Pass 3's "acceptable for bounded dev" class:
- CRITICAL:
  - AWS-0054: the HTTP listener, `modules/ecs/main.tf:316`. It is now behind the CIDR allowlist.
  - AWS-0104: app egress tcp/443, `modules/network/main.tf:339`.
  - AWS-0104: EMR egress, `modules/network/main.tf:400,420`.
- HIGH:
  - AWS-0053: internet-facing ALB, `modules/ecs/main.tf:302`. It is now restricted by the CIDR allowlist.
  - AWS-0128: Neptune with an AWS-managed key, `modules/neptune/main.tf:80`.
  - AWS-0132: S3 with an AWS-managed key, `modules/storage/main.tf:37`.
- MEDIUM:
  - AWS-0090: versioning on curated, artifacts and logs.
  - AWS-0178: VPC flow logs.

These are no longer reported: AWS-0052, the ALB and Neptune egress findings, AWS-0075 (Neptune audit export), and part of AWS-0099.

Diffs, kept outside the repository:
- Terraform and scripts: `<session-scratchpad>/tf-phase12-r5.diff`, against the pre-repair-05 snapshot (23 files, 2153 lines).
- Scala: `git diff -- modules/storage modules/core`.

**NOT RUN:** `terraform plan` (live or scratch), apply, cleanup, deploy, any AWS call, any Bedrock call, and shellcheck (not installed).


## 5. Re-review

code-reviewer Pass 4 (2026-11-23) returned **APPROVE** for the repair-05 scope. The full text is in `review-report.md`.

Fixed:
- N3–N8, N10, N11, N13, N18, N20, N24, N25, R7 stage 1, AWS-0052, and the AWS-0104 findings on the ALB, Neptune and app security groups.
- N9, with an ordering issue (N27).
- N12 for EMR.
- N16 in code (restrictive option).
- N17 is closed.
- The two unrequested infra fixes were checked and found correct.

New findings:
- MINOR:
  - N26: the Neptune bulk loader would have no egress to S3.
  - N27: the cleanup EMPTY phase deletes before the destroy plan and its confirmation.
  - N28: the CIDR allowlist accepts `0.0.0.0/1` plus `128.0.0.0/1`.
  - N29: deploy requires an `embedding-worker` image that cannot exist yet.
  - N30: `TF_VAR_*` is not refused.
- INFO: N31–N35.

## 6. Outcome

**Fixed:** everything listed in section 5.

**Next repair (repair-06):** N26, N27, N28, N30, N32, N33 and N34 go to infra-engineer, and N35 to storage-engineer.

**Waiting on the human:**
- N16: ratify the restriction, or accept a public API.
- N29: the embedding-worker image.
- N31: accept that smoke Test 4 FAILs against stubs.
- N23: confirm the state bucket.
- N22: the prod CMK.
- N14: assign an owner.
- R7 stage 2 and N21: the generation model and model access.
- The data-loss decision for uncopied raw, curated, graph and rag contents before any cleanup.
- Commit, or ratify, the working tree, including `.claude/agents/test-engineer.md`, before `plan.sh` can run.

**Deferred by the human:** R23.

**Human gate: PENDING.**
