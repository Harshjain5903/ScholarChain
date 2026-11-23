# Phase 12 repair 04: N1 evidence-bundle key, N2 script guard, us-east-2, R7 trace, security scan

Date: 2026-11-23
Branch and state: `remediation`, parent `753c499`. The working tree is uncommitted; `infra/terraform/**` and `scripts/*.sh` are untracked.
Evidence assembled by: the orchestrator. In the verbatim sections, local scratchpad paths are shortened to `<session-scratchpad>`.

## 1. Trigger

- Source: code-reviewer Pass 2 in `review-report.md`. The verdict was REQUEST_CHANGES, with 2 MAJOR findings:
  - N1: the evidence-bundle table's hash key is `evidenceId`, but the application uses `bundleId`.
  - N2: `smoke-test.sh` and `run-bounded-pipeline.sh` bypass the `tf-env.sh` environment and state guard.
- Human direction (2026-11-23), as recorded by the orchestrator:
  - Proceed with local repair-04 for N1 and N2.
  - Align the evidence-bundle table key with the application's `bundleId` contract.
  - Reuse the existing environment and state guard in both scripts.
  - "Use us-east-2 consistently for the initial deployment and retain the existing Ohio Titan grant for the embedding worker."
  - R7: trace which roles actually call Bedrock, and propose the exact model IDs, ARNs and estimated cost for the human's approval.
  - Defer the dedicated audit queue (R23).
  - Leave `.claude/agents/test-engineer.md` out of this repair.
  - Run the local checks and a security scan, request a third independent review, and record the results.
  - Leave the human gate pending.
- Prohibited by the human for this round: a live plan, apply, cleanup, model calls, AWS changes, staging and commits.

## 2. Routed to

| Order | Agent | Owned path | Scope |
|---|---|---|---|
| 1 | infra-engineer | `infra/terraform/**`, `scripts/*.sh` | N1, N2, the us-east-2 change, and a read-only R7 trace with a proposal (no IAM edits) |
| 2 | code-reviewer (read-only) | — | Pass 3, including Trivy triage and review of the R7 proposal |

The orchestrator did the following itself:
- Researched public Bedrock model IDs, Regions and pricing for R7 (section 5). No AWS or model call was made.
- Reran the checks, including the Trivy scan (section 4).

No other specialist was needed: no Scala source changed in this repair.

## 3. Work order and agent final report (verbatim)

### 3.1 Work order: orchestrator → infra-engineer

```text
Phase 12 repair-04 work order (orchestrator → infra-engineer). Local authoring only.

HARD PROHIBITIONS (human architect, verbatim intent): no live `terraform plan` (not even on a scratch config this time — repair-03 disclosed a scratch-config plan; do not repeat it), no apply, no destroy, no cleanup, no AWS CLI/API calls, no Bedrock/model calls, no AWS changes, no `git add`/commit. Do not read `.env` or credential files. Do not edit `.claude/**`, CLAUDE.md, docs/agents.md, or any file outside `infra/terraform/**` and `scripts/*.sh`. Read-only inspection of `modules/**` is allowed for the trace in task 4.

Context: code-reviewer Pass 2 (docs/agent-runs/phase-12/review-report.md, "Pass 2") returned REQUEST_CHANGES with N1 and N2 MAJOR. Read that section first.

TASK 1 — N1 (MAJOR). `infra/terraform/modules/storage/main.tf` ~line 153–161: the evidence-bundle table uses `hash_key = "evidenceId"` and attribute `evidenceId`. The application contract is `bundleId` (modules/storage/src/main/scala/edu/scholarchain/storage/DynamoDbClient.scala:47-49, :172, :179 `attribute_not_exists(bundleId)`, :184 GetItem key `bundleId`). Change the table's hash key and attribute definition to `bundleId` (type S). Check every other reference (outputs, IAM conditions, archive-evidence.sh export/scan keys, cleanup.sh, docs comments in infra) and align them. While there, verify the other four tables' key names against DynamoDbClient.scala and report each as MATCH/MISMATCH (fix any mismatch the same way and report it). Nothing is deployed, so no state migration is required; say so in a comment only if useful.

TASK 2 — N2 (MAJOR). `scripts/smoke-test.sh` and `scripts/run-bounded-pipeline.sh` do not use the environment/state guard. Reuse the existing `scripts/tf-env.sh` exactly as plan.sh/deploy.sh/rollback.sh do (source it, call the same guard functions, same env allowlist, same state-key check, same refusal on legacy shared state). Do not duplicate guard logic; if tf-env.sh needs a small additional function to support these two scripts, add it there and keep the other callers working. Both scripts must refuse to act if the guard fails and must read Terraform outputs only from the guarded environment's state.

TASK 3 — Region (human decision, 2026-11-23): "Use us-east-2 consistently for the initial deployment and retain the existing Ohio Titan grant for the embedding worker." So:
- `environments/dev.tfvars` and `environments/prod.tfvars`: `aws_region = "us-east-2"`, AZs `["us-east-2a", "us-east-2b"]`.
- `environments/{dev,prod}.backend.hcl`: if they carry a region for the state bucket, decide whether it must change; report what you did and why (state bucket region is separate from deploy region — do not silently change it without explaining).
- Do NOT change `titan_embedding_model_arn` (variables.tf ~225-228); it stays exactly `arn:aws:bedrock:us-east-2::foundation-model/amazon.titan-embed-text-v2:0`. Keep the `check "titan_grant_region"` block — it should now pass.
- Grep the whole of `infra/terraform/**` and `scripts/*.sh` for any remaining `us-east-1` literal, region-specific AMI/endpoint/AZ, or example text, and align or report each.
- Note any service availability concern in us-east-2 you are aware of (Neptune, S3 Vectors, EMR, ECS Fargate, Bedrock Titan v2) as UNVERIFIED unless you can confirm from repo docs; do not call AWS.

TASK 4 — R7 trace and proposal ONLY (do not change IAM for R7). Trace which roles/tasks would actually call Bedrock:
- Read-only grep `modules/**/src/main` for Bedrock/InvokeModel/Converse/model-id usage, ports (`modules/rag/.../Ports.scala` QueryEmbedder etc.), `modules/entity-resolution/.../LlmAdjudicator.scala`, `modules/core/src/main/resources/reference.conf` `models { embedding-model-id, generation-model-id }`, and services runtime composition.
- Map each caller → which ECS service/task definition / EMR step / Step Functions state runs it → which IAM role it uses today (infra/terraform/modules/iam, ecs, workflow, emr).
- Produce a table: caller (file:line) | model kind (embedding/generation/adjudication) | runtime (ECS service name, EMR, none) | IAM role today | current Bedrock permission on that role | proposed permission (actions, exact resource ARN pattern) | concrete client exists? (yes/no/port only).
- For generation in us-east-2, state whether a cross-region inference profile would be needed (inference-profile ARN in us-east-2 plus foundation-model ARNs in the profile's destination Regions) — mark as needing orchestrator confirmation; the orchestrator will supply exact model IDs and pricing.
- State whether the current runtime role grant `bedrock:InvokeModel` on `foundation-model/*` (R7) is still present and exactly where (file:line).

TASK 5 — Local checks, from `infra/terraform`: `terraform fmt -recursive` then `terraform fmt -check -recursive`, `terraform init -backend=false`, `terraform validate`; from repo root `bash -n scripts/*.sh` and `/bin/bash -n` (bash 3.2) on each script. Report actual output and exit codes. Do not run plan.

FINAL REPORT format (verbatim-quotable; it will be copied into docs/agent-runs/phase-12/repair-04.md): 1) Files changed with a one-line reason each; 2) N1 result incl. per-table key MATCH table; 3) N2 result; 4) Region changes and every us-east-1 occurrence found and its disposition; 5) R7 trace table and proposal (no IAM edits); 6) Check commands with actual output and exit codes; 7) Anything you did not do or could not verify; 8) Confirmation that no plan/apply/AWS call/staging occurred. Use repo-relative paths; do not include absolute home paths.
```

### 3.2 infra-engineer final report

The orchestrator transcribed this from the agent's final message. Tables and wording were tightened for Markdown, but no finding, number, path or claim was changed or dropped.

```text
STATUS: COMPLETE
OWNER: infra-engineer
WORK ORDER: Phase 12 repair-04 -- N1, N2, us-east-2, R7 trace (no IAM edit), local checks

### 1) Files changed
| File | Reason |
|---|---|
| `infra/terraform/modules/storage/main.tf` | N1: evidence-bundle table `hash_key`/attribute `evidenceId` -> `bundleId` (S), with a comment citing `DynamoDbClient.scala`. |
| `scripts/tf-env.sh` | N2: added `TF_ENV_VERIFIED_ENV`, `tf_env_require_verified`, `tf_env_output`, `tf_env_output_json`, `tf_env_require_state_identity`; `tf_env_verify_backend` now records the verified env (one added line; no other existing behaviour changed). |
| `scripts/smoke-test.sh` | N2: sources `tf-env.sh`, runs the full guard sequence, reads the ALB DNS only via `tf_env_output` (removed the `SCHOLARCHAIN_ALB_DNS`/`localhost` fallback); replaced macOS-incompatible `head -n -1` with `sed '$d'`. |
| `scripts/run-bounded-pipeline.sh` | N2: sources `tf-env.sh`, runs the full guard sequence, reads the EMR cluster id only via `tf_env_output`, requires state bucket outputs to match `scholarchain-<env>-*` before any step submission; validates `timeout-minutes`. |
| `infra/terraform/environments/dev.tfvars`, `prod.tfvars` | Region: `aws_region = "us-east-2"`, AZs `["us-east-2a", "us-east-2b"]`, comment citing the 2026-11-23 human decision. |
| `infra/terraform/environments/dev.backend.hcl`, `prod.backend.hcl` | Comment only: why the state-bucket region is unchanged. Value unchanged. |
| `infra/terraform/variables.tf` | Example text in two validation error messages now says us-east-2. `titan_embedding_model_arn` untouched. |

### 2) N1 result
Evidence-bundle table: `hash_key = "bundleId"`, attribute `bundleId` type S (`modules/storage/main.tf`). Nothing has been applied, so no state migration is needed (stated in the comment).
Other references: outputs and IAM reference table names/ARNs only (no key names, no `dynamodb:LeadingKeys` condition); `archive-evidence.sh` does a full `scan` by table name (no key names); `cleanup.sh` has none. `smoke-test.sh` still checks `"evidenceId"` -- that is the REST response field (`modules/services/.../JsonCodecs.scala:105`, `Routes.scala:212-226`), not the DynamoDB key, so it is correct and was left as is.

| Table | Terraform key(s) | `DynamoDbClient.scala` key(s) | Result |
|---|---|---|---|
| run-ledger | `runId` (S) | `runId` (:83, :90, :95) | MATCH |
| entity-link-cache | `sourceId` / `targetId` (S/S) | `sourceId`/`targetId` (:110-111, :119, :127-129) | MATCH |
| query-session | `queryId` (S) | `queryId` (:145, :152, :157) | MATCH |
| evidence-bundle | `evidenceId` -> `bundleId` (S) | `bundleId` (:172, :179, :184) | MISMATCH -> FIXED |
| artifact-registry | `artifactId` (S) | `artifactId` (:199, :206, :211) | MATCH |

### 3) N2 result
New in `scripts/tf-env.sh`:
- `TF_ENV_VERIFIED_ENV=""` is reset every time the file is sourced, so a caller-exported value is ignored; `tf_env_verify_backend` sets it after a successful check (the only change to existing code).
- `tf_env_require_verified <env>`: refuses (exit 3) unless the backend was verified for `<env>` in this shell and `.terraform/terraform.tfstate` still names `scholarchain/<env>/terraform.tfstate`; re-read on every call.
- `tf_env_output <env> <name>` / `tf_env_output_json <env> <name>`: guard, then `terraform output -raw` / `-json`.
- `tf_env_require_state_identity <env>`: state output `environment` must equal `<env>` and `aws_region` must equal the target region; an empty or never-applied state is refused.

Both scripts now run the same sequence as `rollback.sh`: `tf_env_validate_name` -> `tf_env_require_aws_target` -> `tf_env_init_backend` (same dev/prod allowlist, `-reconfigure`, state-key check, legacy shared-state refusal), then `tf_env_require_state_identity`, and read every Terraform output via `tf_env_output*`. Neither script calls `terraform output` directly any more (grep-checked). `plan.sh`, `deploy.sh`, `rollback.sh`, `cleanup.sh`, `archive-evidence.sh` are unchanged.

Local refusal tests (all exit before any AWS call): `smoke-test.sh staging` -> 2; `run-bounded-pipeline.sh qa` -> 2; `run-bounded-pipeline.sh dev abc` -> 2; `tf_env_output prod ...` with `TF_ENV_VERIFIED_ENV=prod` pre-exported -> 3; `tf_env_require_state_identity dev` unverified -> 3; `tf_env_output_json dev ...` unverified -> 3. The success path needs STS, S3 and state access and was not exercised.

### 4) Region
- tfvars: dev and prod `aws_region = "us-east-2"`, AZs `["us-east-2a","us-east-2b"]`.
- `titan_embedding_model_arn` unchanged: `arn:aws:bedrock:us-east-2::foundation-model/amazon.titan-embed-text-v2:0` (`variables.tf:228`). `check "titan_grant_region"` kept; its condition evaluated with these literals in an empty scratch dir via `terraform console` (no providers, no AWS): `true`.
- Backend files: `region = "us-east-1"` kept in both. It must name the Region where the existing state bucket `scholarchain-terraform-state` and lock table actually live; changing it does not move the bucket, and a wrong value would point `init` and the legacy-state check at the wrong place. Deploy Region and state Region are independent (`backend.tf:23-24`). Moving state to us-east-2 requires creating/moving the bucket and lock table first, then updating both files together.

| us-east-1 occurrence | Disposition |
|---|---|
| `dev.tfvars:6-7`, `prod.tfvars:6-7` (`aws_region`, AZs) | changed to us-east-2 |
| `variables.tf:11`, `variables.tf:49` (example text) | changed to us-east-2 |
| `dev.backend.hcl`, `prod.backend.hcl` `region` (state bucket) | kept, comment explains why |
| anything else | none found (no AMI ids, hard-coded endpoints or zone ids; all endpoints/ARNs use `var.aws_region` / `data.aws_region`) |

Outside my paths (not edited, for owners): `modules/core/src/main/resources/reference.conf:16,63` and `modules/storage/src/main/resources/reference.conf:19,26,35` default to `us-east-1`. ECS overrides all five via `SCHOLARCHAIN_*_REGION` (`ecs/main.tf:76-82`), so deployed tasks are unaffected; local runs are not. Comments at `S3VectorsClient.scala:172` and `AwsSigV4.scala:161` use us-east-1 only as an example.

Service availability in us-east-2: all UNVERIFIED (nothing in repo docs; no AWS call). From general knowledge Neptune, EMR, ECS Fargate and Titan Text Embeddings v2 are offered in us-east-2, and S3 Vectors listed us-east-2 among its preview Regions. `emr-7.0.0` and `m5.xlarge` in us-east-2 also unverified.

### 5) R7 trace and proposal (no IAM edits)
R7 is still present: `infra/terraform/modules/iam/main.tf:306` (Sid `BedrockInvoke`, role `ecs_task_runtime`) grants `bedrock:InvokeModel` on `arn:aws:bedrock:${local.region}::foundation-model/*` (line 311); with the new tfvars that is us-east-2 `foundation-model/*`. All five query services use `ecs_task_runtime` (`ecs/main.tf:8-14`, `:227`); the embedding worker uses `ecs_task_loader` (`ecs/main.tf:591`). `ScholarChainServer.scala:6` wires only `RuntimeComposer.composeStubs()`; no concrete Bedrock client exists anywhere in `modules/**/src/main`.

| Caller (file:line) | Model kind | Runtime | IAM role today | Bedrock permission today | Proposed permission | Concrete client? |
|---|---|---|---|---|---|---|
| `rag/.../SemanticContextAgent.scala:64` `embedder.embed` (port `Ports.scala:82` `QueryEmbedder`) | embedding, query time (`models.embedding-model-id` = `amazon.titan-embed-text-v2:0`, `reference.conf:59-60`) | ECS `scholarchain-<env>-vector-retriever` (Step Functions `SemanticContext`, `workflow/main.tf:233-239`) | `ecs_task_runtime` | `InvokeModel` on `foundation-model/*` (`iam/main.tf:306-311`) | `bedrock:InvokeModel` on exactly `var.titan_embedding_model_arn` | port only |
| `rag/.../AnswerSynthesisAgent.scala:34` `llm.generate` (port `Ports.scala:85` `LlmClient`) | generation (`models.generation-model-id` default `anthropic.claude-3-sonnet-20240229-v1:0`, `reference.conf:61-62`; ECS does not set `SCHOLARCHAIN_GENERATION_MODEL_ID`) | ECS `scholarchain-<env>-synthesis-worker` (`BuildEvidence`/`Synthesize`, `workflow/main.tf:276`, `:308-314`) | `ecs_task_runtime` | same `foundation-model/*` | `bedrock:InvokeModel` on the exact generation ARN(s) the orchestrator confirms; `InvokeModelWithResponseStream` only if streaming is used | port only |
| `entity-resolution/.../LlmAdjudicator.scala:45` (trait; only impl `StubLlmAdjudicator` :57; `EntityResolutionEngine.scala:12` has no production caller) | adjudication | none (no EMR step or service constructs it) | n/a (EMR roles have no Bedrock grant) | none | none until a real adjudicator and runtime exist | no (stub) |
| Embedding worker (no entry point; N14; job carries `textS3Uri`, not a vector) | embedding, ingest | ECS `scholarchain-<env>-embedding-worker` (desired count 0) | `ecs_task_loader` | `InvokeModel` on the exact Titan ARN (`iam/main.tf:459-462`, R3 approved) | no change | no |
| `ClaimVerificationAgent`, query-api, graph-retriever, verification-worker | none | ECS | `ecs_task_runtime` | same `foundation-model/*` | none | n/a |

Proposal: replace the R7 statement with the exact Titan ARN plus the confirmed generation ARN(s); better, split `ecs_task_runtime` per service so only vector-retriever gets Titan, only synthesis-worker gets generation, and the other three get no Bedrock. Today all five share one role, so a scoped statement would still reach all of them.

Generation in us-east-2 (UNVERIFIED, needs orchestrator confirmation): recent Anthropic models on Bedrock in us-east-2 are, to my knowledge, reached through a cross-Region inference profile; the configured default (Claude 3 Sonnet 20240229) may not be on-demand there and may be retired. With a profile: the app model ID must be the profile ID (e.g. `us.anthropic.<model>`); IAM needs `bedrock:InvokeModel` on `arn:aws:bedrock:us-east-2:<account>:inference-profile/us.anthropic.<model>` and on `arn:aws:bedrock:<dest-region>::foundation-model/anthropic.<model>` for every destination Region (for a US profile, typically us-east-1, us-east-2, us-west-2).

### 6) Checks
| Command | Output | Exit |
|---|---|---|
| `terraform fmt -recursive` (infra/terraform, v1.17.2) | first run reformatted `dev.tfvars`/`prod.tfvars` alignment; comment moved above `environment`; re-run: no output | 0 |
| `terraform fmt -check -recursive` | no output | 0 |
| `terraform init -backend=false -input=false` | "...should now work. ...rerun this command to reinitialize..." | 0 |
| `terraform validate` | "Success! The configuration is valid." | 0 |
| `echo 'split(":", "<titan ARN>")[3] == "us-east-2"' \| terraform console` (empty scratch dir) | `true` | 0 |
| `bash -n scripts/*.sh` per file (PATH bash 3.2.57) | 8/8 clean | 0 each |
| `/bin/bash -n` per file (3.2.57) | 8/8 clean | 0 each |
| N2 refusal tests (section 3) | as listed | 2/3 as expected |

### 7) Not done / not verified
- Not run: `terraform plan`; the check block was evaluated only as an expression in `terraform console`.
- Not tested: the guard's success path (STS, legacy-state `head-object`, real state outputs).
- Not verified: the state bucket's real Region; service and instance availability in us-east-2; generation model availability and inference-profile requirements.
- Left open (not in this work order): N3, N5, N6-N9, N12, N13; R7 IAM changes; the `us-east-1` defaults in `modules/*/reference.conf` (domain-engineer, storage-engineer).
- Everything under `infra/terraform/` and `scripts/` is untracked, including `.terraform.lock.hcl`, which `init -backend=false` may have rewritten.

### 8) Confirmation
No `terraform plan` (real or scratch), apply, destroy or cleanup; no AWS CLI/API/Bedrock calls; no AWS changes; no `git add`/commit; no `.env` or credential files read; nothing edited outside `infra/terraform/**` and `scripts/*.sh` (`modules/**` read only). Beyond `fmt`, `init -backend=false` and `validate`, the only Terraform runs were the provider-free `terraform console` evaluation and `terraform version`.

MANUAL ACTION REQUIRED: confirm the state bucket and lock-table Region (kept us-east-1); supply generation model ID(s), inference profile, destination Regions and pricing before R7 is scoped; confirm us-east-2 availability for S3 Vectors, Neptune, EMR 7.0.0 and m5.xlarge.
NEXT GATE: code-reviewer Pass 3, then human review of the first `plan.sh` output.
```

**Process note:** in a separate empty scratch directory, infra-engineer evaluated the `titan_grant_region` condition expression with `terraform console`. No providers were loaded and no AWS call was made. No `terraform plan` was run, on the project or on any scratch configuration.

## 4. Rerun evidence (orchestrator, 2026-11-23, after infra-engineer)

| Command | Result | Exit |
|---|---|---|
| `terraform fmt -check -recursive` (`infra/terraform`) | no output | 0 |
| `terraform init -backend=false -input=false` | passed | 0 |
| `terraform validate` | `Success! The configuration is valid.` | 0 |
| `/bin/bash -n` on each `scripts/*.sh` (bash 3.2.57) | 8 of 8 passed | 0 |
| `grep -rn us-east-1 infra/terraform scripts --exclude-dir=.terraform` | Only `environments/{dev,prod}.backend.hcl`: the state-bucket region and its explanatory comment | — |
| `.terraform.lock.hcl` compared with the repair-03 snapshot | unchanged | — |
| `sbt -batch check` | passed. Per module: 114, 116, 133, 32, 63, 65 and 91, which is 614 tests per pass. 0 failed, 0 `[error]` lines. Marker went from `2026-11-23T01:03:39.319591Z` to `2026-11-23T11:50:15.890334Z`. No Scala changed in this repair. | 0 |
| `trivy config` (Trivy 0.76.1), with `--tf-vars environments/dev.tfvars` and with `prod.tfvars`, skipping `.terraform` | 47 failed checks in each: 6 CRITICAL, 4 HIGH, 5 MEDIUM, 32 LOW. The failure sets for dev and prod are identical. Triage is in `review-report.md`, Pass 3 §4. | 0 |
| `git diff --cached --name-only` | 0 files (nothing staged) | 0 |

Diff: `<session-scratchpad>/tf-phase12-r4.diff`, 9 files, 383 lines. It is against the repair-03 snapshot and kept outside the repository.

Trivy output is kept in the session scratchpad: `trivy-r4-dev.json`, `trivy-r4-prod.json` and `trivy-r4-dev.txt`.

**NOT RUN:** `terraform plan` (live or scratch), apply, cleanup, deploy, any AWS API call, any Bedrock or model call, and shellcheck (not installed). tfsec and checkov are not installed either; Trivy was the only scanner used.

## 5. R7: Bedrock callers, proposed model IDs, ARNs and estimated cost (PROPOSAL, awaiting human approval)

No IAM change has been made. The current grant is still `bedrock:InvokeModel` on `foundation-model/*` on the shared `ecs_task_runtime` role (`infra/terraform/modules/iam/main.tf:306-311`).

### 5.1 Who actually calls Bedrock

This combines infra-engineer's trace with code-reviewer's correction N17:

- No concrete Bedrock client exists in `modules/**/src/main`. The only composition is `RuntimeComposer.composeStubs()`.
- The two ports that would call Bedrock are `QueryEmbedder` (`SemanticContextAgent`) and `LlmClient` (`AnswerSynthesisAgent`).
  - At runtime, both run inside the **`query-api`** ECS service, because `/v1/query` executes the whole `RuntimeWorkflow` in-process (`Routes.scala:117-127`).
  - They do not run in `vector-retriever` or `synthesis-worker`: all five services run the same entry point.
- The ingest-time embedding worker uses `ecs_task_loader`, which already has the R3-approved Titan grant. It has no entry point yet (N14).
- `LlmAdjudicator` (entity-resolution) is a stub only, with no runtime, so it needs no grant.

### 5.2 Model facts (public AWS model cards, read 2026-11-23; no AWS account call)

| Use | Model ID to configure | In-Region in us-east-2? | Resource ARNs IAM must allow |
|---|---|---|---|
| Embedding: query time and ingest | `amazon.titan-embed-text-v2:0` | Yes (in-Region only; no geo or global profile) | `arn:aws:bedrock:us-east-2::foundation-model/amazon.titan-embed-text-v2:0` |
| Generation, recommended | `us.anthropic.claude-haiku-5-5` (US geo inference profile) | No. Only via the US geo profile (from us-east-2 it routes to us-east-1, us-east-2 and us-west-2) or the global profile | `arn:aws:bedrock:us-east-2:<account>:inference-profile/us.anthropic.claude-haiku-5-5`, plus `arn:aws:bedrock:{us-east-1,us-east-2,us-west-2}::foundation-model/anthropic.claude-haiku-5-5` |
| Generation, higher-quality alternative | `us.anthropic.claude-sonnet-5-5` | Same as Haiku | The same pattern with `claude-sonnet-5-5` |

The current config default, `anthropic.claude-3-sonnet-20240229-v1:0` (`modules/core/src/main/resources/reference.conf:61`), is a legacy model and is not proposed (N21).

Sources:
- AWS model cards for Titan Text Embeddings V2, Claude Haiku 5.5 and Claude Sonnet 5.5 (docs.aws.amazon.com/bedrock/latest/userguide/model-card-*.md).

### 5.3 Proposed IAM, corrected per N17

Two stages:

1. **Now, while no Bedrock client exists:** remove the R7 `BedrockInvoke` statement from `ecs_task_runtime` entirely. No service task role then has Bedrock access. Code-reviewer called this least privilege.
2. **When service-engineer and rag-engineer add a real Bedrock adapter:** give `query-api` its own task role (also add it to the deploy and Step Functions `PassRole` lists) with exactly these permissions:
   - `bedrock:InvokeModel` on the Titan ARN above.
   - `bedrock:InvokeModel` on the approved generation inference-profile ARN.
   - `bedrock:InvokeModel` on the three destination `foundation-model` ARNs, with the condition `StringEquals bedrock:InferenceProfileArn = <that profile ARN>`.
   - Add `bedrock:InvokeModelWithResponseStream` only if streaming is used.
   - Do not add `aws:RequestedRegion` to the Claude statements.

   The other four services get no Bedrock access. Terraform must also export `SCHOLARCHAIN_GENERATION_MODEL_ID` (N21).

Prerequisites that need a human:
- One-time Anthropic model access enablement in the AWS account.
- A decision on N16 first, because a public, unauthenticated `/v1/query` would allow unbounded model spend.

### 5.4 Estimated cost

The orchestrator calculated these as estimates, not measurements.

**Pricing used.** This comes from third-party summaries of Bedrock pricing (cloudforecast.io, caylent.com, promptunit.ai, economize.cloud, read 2026-11-23). The official AWS pricing page did not render the Anthropic or Titan rows during research. **UNVERIFIED: confirm on https://aws.amazon.com/bedrock/pricing/ before approving.**
- Titan Text Embeddings V2: $0.02 per 1M input tokens.
- Claude Haiku 5.5, US geo profile: $0.11 input and $0.55 output per 1M tokens (global profile $0.10 and $0.50).
- Claude Sonnet 5.5, US geo profile: $2.20 input and $11.00 output per 1M tokens (global profile $2 and $10).

**Assumptions.** These are the orchestrator's assumptions and need the human's review:
- The 50K-record development run embeds about 27M tokens in total: paper abstracts plus researcher, community, path and topic summaries.
- Each query sends about 8K input tokens (question plus evidence bundle) and returns about 1.5K output tokens, including thinking.
- The demo and grading window uses about 300 queries.

| Item | Haiku 5.5 (recommended) | Sonnet 5.5 |
|---|---|---|
| Titan ingest embedding, ~27M tokens | ~$0.54 | ~$0.54 |
| Titan query embedding, 300 × ~50 tokens | < $0.01 | < $0.01 |
| Generation per query | ~$0.0017 | ~$0.034 |
| Generation, 300 queries | ~$0.51 | ~$10.20 |
| **Bedrock total** | **~$1.05** | **~$10.75** |

**Recommendation.** Use `us.anthropic.claude-haiku-5-5` for development, with a hard query cap. Sonnet 5.5 would use about 40% of the $25 budget on model calls alone. Neptune, EMR, NAT and ALB charges are separate and will probably dominate the $25.

## 6. Re-review

code-reviewer Pass 3 (2026-11-23) returned **APPROVE** for the repair-04 scope. The full text is in `review-report.md`. In that copy, the reviewer's closing `FINDINGS:` bullet list was shortened to a pointer to its §7 table, which has the same content.

Results:
- N1 and N2: FIXED.
- R3: FIXED (the region check now passes).
- R7: WAITING on the human, after the N17 correction (section 5.3).
- R23: DEFERRED by the human.
- N3–N15: still open (MINOR or INFO).
- New findings: N16–N25.
  - **N16 (MAJOR, pre-existing, surfaced by Trivy):** the internet-facing HTTP ALB has no authentication. A human decision is required before any apply.
  - N17: the R7 caller mapping. Corrected in section 5.3.
  - N18: `smoke-test.sh` can never pass. It sends `query` instead of `question`, and its evidenceId extraction never matches.
  - N19: `/v1/evidence` is routed to a different task than the one holding the in-memory results.
  - N20: the Neptune audit log is not exported.
  - N21–N25: INFO.
- The reviewer says nothing blocks a human-reviewed `./scripts/plan.sh dev`.

## 7. Outcome

**Fixed (repair-04 scope):**
- N1: the evidence-bundle key is now `bundleId`.
- N2: both scripts use the `tf-env.sh` guard.
- us-east-2 is used consistently, and the Ohio Titan grant for the embedding worker is retained (R3 closed).
- The security scan has been run and triaged.

**Waiting on the human:**
- R7 approval. Generation model and cost are in section 5; the IAM shape is in section 5.3.
- The N16 decision before any apply.
- Confirmation of the state bucket and lock-table region (N23).
- Anthropic model enablement, when R7 proceeds.
- The prod Neptune CMK decision (N22), before prod.

**Deferred by the human:**
- R23, the audit queue.
- `.claude/agents/test-engineer.md`.

**Open for later repairs:**
- N3–N13, N18–N20, N24 and N25: infra-engineer, plus storage-engineer and service-engineer as listed.
- The us-east-1 defaults in `modules/{core,storage}` `reference.conf` (domain-engineer and storage-engineer). ECS overrides them when deployed.

**Human gate: PENDING.**
