# Phase 12 repair 06: code-reviewer Pass 4 findings N26–N35, and the usage-error exit status

Date: 2026-11-23
Branch and state: `remediation`, parent `753c499`. The working tree is uncommitted, and `infra/terraform/**` and `scripts/*.sh` are untracked.
Evidence assembled by: the orchestrator. Local scratchpad paths are shortened to `<session-scratchpad>`.

## 1. Trigger

- Source: code-reviewer Pass 4 (`review-report.md`) returned APPROVE for repair-05 and raised N26–N35.
- Human instruction (2026-11-23): "fix the issues". After an interrupted response, the human said "continue".
- The standing prohibitions still apply: no live plan, apply, cleanup, model calls, AWS changes, staging or commits.

Not attempted, because each is a human decision:
- N29: the embedding-worker image needed for deploy.
- N31: accepting that smoke Test 4 FAILs against stubs.
- N16: ratifying the restriction, or choosing a public API.
- N22, N23, N14, R7 stage 2 and N21, and the data-loss decision before cleanup.

## 2. Routed to

| Order | Agent | Owned path | Findings |
|---|---|---|---|
| 1 (parallel) | infra-engineer | `infra/terraform/**`, `scripts/*.sh` | N26, N27, N28, N30, N32, N33, N34 |
| 1 (parallel) | storage-engineer | `modules/storage/**` | N35; document the `ttl` unit (infra-engineer's N5 note) |
| 2 | infra-engineer, follow-up message | `scripts/*.sh` | A new pre-existing defect infra-engineer reported: under bash 3.2 with the EXIT trap, a `${N:?}` usage error exits with status 0 |
| 3 | code-reviewer (read-only) | — | Pass 5 |

## 3. Work orders and agent final reports

The work orders are summarized below. The agents' reports were transcribed from their hand-back messages. Formatting was tightened for Markdown; no finding, number, path or claim was changed.

### 3.1 Work order to infra-engineer (summary)

- **Limits:** the same prohibitions as repair-05. Do not decide N29 or N31.
- **Tasks:**
  - N26: exactly one Neptune egress rule, tcp/443, to the S3 gateway prefix list.
  - N27: reorder `cleanup.sh`. First build and check the destroy plan (saved and checksummed), then the inventories and copies, then the EMPTY confirmation, then the deletes, then the recount, then the destroy confirmation, then apply the already-reviewed plan. Use accurate abort titles, and print an explicit data-loss warning before EMPTY.
  - N28: a minimum prefix length of /16 in both `plan.sh` and the Terraform validation, with no bypass.
  - N30: refuse exported `TF_VAR_*`.
  - N32: `init -lockfile=readonly`, and re-check that the tree is clean after init and after plan.
  - N33: numeric guards before arithmetic, and a clear message when `s3 sync` fails.
  - N34: fix the stale comment.
- **Checks:** `fmt`, `init -backend=false`, `validate`, `bash -n`, plus local shim walk-throughs for N27, N28, N30, N32 and N33.

### 3.2 infra-engineer final report

STATUS: COMPLETE. All 7 fixes are in, and N29 and N31 are untouched.

- **N26:** `modules/network/main.tf:358-366` (description) and `:377-393` (new `aws_vpc_security_group_egress_rule.data_to_s3_endpoint`, tcp/443 to `aws_vpc_endpoint.s3.prefix_list_id`). This is the data security group's only egress rule. The `private_data` route table (`:141-145`) is associated with the S3 gateway endpoint (`:151-158`). Changing the description forces replacement, so plan review must show it as a create (N15).
- **N27:** the new `cleanup.sh` phase order is described in the header (`:12-70`). Line numbers here are as of the repair-06 report; the follow-up shifted lines after `:102` by +4.

  | Phase | What happens |
  |---|---|
  | 1 | preflight |
  | 2 | target; exits 0 if state is empty |
  | 3 | verify the archive |
  | 4 | read-only inventory and blockers |
  | 5 | build the destroy plan and check it: deletes only, no retained address, not empty. Save it, checksum it, write `.sha256`. "Only retained resources remain" exits 0 here, before any deletion. |
  | 6 | inventories and verified copies; nothing is deleted |
  | 7 | data-loss warning, typed EMPTY, written to `empty-confirmation.txt` (no username); then deletes, recount, re-checksum, and abort if anything is left |
  | 8 | typed destroy confirmation |
  | 9 | the plan's checksum is re-checked against the in-memory value and the `.sha256` file; the backend is re-verified; the saved plan is applied |
  | 10 | post-cleanup inventory |

  New helpers: `print_summary`, `summary_and_exit`, `abort_title` (reports "BUCKET/ECR CONTENTS WERE DELETED (phase 7) BUT NOTHING WAS DESTROYED" when deletes happened, otherwise "NOTHING WAS DESTROYED OR DELETED"), `discard_unapplied_plan`, and a `tf_env_script_cleanup` EXIT-hook summary.
- **N28:** `variables.tf:59-62,85-91` and `plan.sh` `ALB_MIN_PREFIX=16`. Both give a clear error and offer no bypass.
- **N30:** `tf-env.sh:28-31,74,83-86`. Only exported variables are refused. `plan.sh` passes its values only through `-var-file` and `-var`.
- **N32:** `tf-env.sh:279` runs `init … -lockfile=readonly`; this is the only init in any script. `plan.sh` has a new `require_clean_tree` check (dirty or untracked files, ignored files under `infra/terraform`, HEAD unchanged). It runs before planning, after init and after plan. If the tree changed after the plan, the plan file is deleted, no metadata is written, and the script exits 2.
- **N33:** a new `is_count` guard is applied to every count and re-count, including the formerly unguarded line `:520`, and to the digest read. A failure sets `EMPTY_FAIL` and leads to the abort summary. When `s3 sync` fails, the script prints the exit code, likely causes, and "NOTHING will be deleted".
- **N34:** `modules/ecs/main.tf:118-125` comment updated.

**Checks:**
- `terraform fmt -recursive`, `fmt -check`, `init -backend=false -input=false -lockfile=readonly` and `validate` (no `TF_VAR_*`, no `alb_ingress_cidrs`) all returned rc=0; `validate` printed "Success! The configuration is valid."
- The lock file's sha256 was unchanged.
- `/bin/bash -n` returned rc=0 on all 8 scripts.

**Tests:**
- **N28, Terraform side:** a provider-less scratch module, checked with `validate` only.
  - Accepted (rc=0): 203.0.113.0/24, 10.20.0.0/16, 203.0.113.10/32.
  - Rejected (rc=1) with the "/16 or longer" message: 10.0.0.0/8, 0.0.0.0/1, 0.0.0.0/1 + 128.0.0.0/1, 10.0.0.0/15.
  - 0.0.0.0/0 was rejected with the /0 message.
- **N28, `plan.sh`:** a scratch repo with shims that refuse unknown calls.
  - 0.0.0.0/1, the two-entry list, /8 and /15 each gave rc=2 with the "wider than /16 (N28)" message.
  - /0 gave rc=2 with the N16 message.
  - A non-canonical entry gave rc=2.
  - 203.0.113.0/24 gave rc=0, with `init … -lockfile=readonly` and `tree_clean=yes`.
- **N30:**
  - `plan.sh` with exported `TF_VAR_titan_embedding_model_arn`: rc=3.
  - An unexported variable: not refused.
  - `TF_VAR_image_tag` with cleanup, rollback, smoke-test, run-bounded-pipeline and archive-evidence: rc=3 for each.
- **N32:**
  - Lock file rewritten during init: rc=2, and no plan directory was created.
  - `main.tf` changed during the plan: rc=2, the plan was deleted, and no metadata was written.
- **N27 and N33 walk-throughs,** run with a fake `aws` and `terraform` under a pty:

  | Case | rc | What happened |
  |---|---|---|
  | A: EMPTY typed, destroy declined | 1 | The plan was built before the deletes. Apply was never called. The title said contents were deleted but nothing was destroyed. The unapplied plan was removed. |
  | B: EMPTY declined | 1 | "NOTHING WAS DESTROYED OR DELETED". |
  | C: re-count returned non-JSON | 1 | "could not parse the re-count"; abort summary; 0 applies. |
  | D: artifacts copy failed | 1 | Explicit message; nothing was deleted. |
  | E: full path | 0 | Plan sha256 re-verified, then applied; "CLEANUP COMPLETE". |
  | F: plan file changed after EMPTY | 1 | "destroy plan file is missing or changed after review"; 0 applies. |
  | End of input at the EMPTY prompt | — | "CLEANUP STOPPED UNEXPECTEDLY - NOTHING WAS DESTROYED OR DELETED". |

**Not verified:**
- `-lockfile=readonly` together with `-reconfigure` against the real S3 backend.
- That a saved destroy plan stays valid after out-of-band deletes (this relies on Terraform documentation: objects are not in state, and a changed state serial fails closed).
- That the Neptune loader egress is sufficient.
- Real AWS CLI error strings.

**Reported risk:** under bash 3.2 with the tf-env EXIT trap, a `${1:?Usage…}` usage error exits with status 0 (seen with `deploy.sh dev`). The orchestrator routed this back as the follow-up in 3.3.

### 3.3 Follow-up to infra-engineer: usage errors exit 2 (orchestrator message, verbatim)

> Repair-06 follow-up from the orchestrator: fix the risk you reported, where usage errors exit 0. Scope and limits are the same as your work order: only scripts/*.sh; no plan, apply, AWS calls, or git add/commit.
> Task: in every scripts/*.sh, replace `${N:?Usage...}` (and any similar `:?` expansions on arguments or required env vars) with explicit checks that print the usage to stderr and `exit 2`. Make sure the tf-env EXIT trap preserves that status. Look for any other way the trap could turn a failure into rc=0, and fix it if you find one.
> Verify by running each script with missing arguments under /bin/bash 3.2: expect rc=2 for every one. Run `deploy.sh dev` with one argument: expect rc=2. Re-run `/bin/bash -n` on all scripts. Spot-check that a valid-argument refusal path, for example `TF_VAR_x` exported, still returns rc=3.
> Report back with: files and lines changed, the exact rc for each script, and confirmation that nothing else changed.

### 3.4 infra-engineer follow-up report

STATUS: COMPLETE.

**Root cause.** In bash 3.2.57 with an EXIT trap set, three kinds of fatal expansion error stop the script but report status 0, both to the trap and to the caller:
- `${N:?msg}`;
- an unbound variable under `set -u`;
- an arithmetic syntax error.

Plain command failures, `exit N` and signals were already passed through correctly.

**Changes:**
- `tf-env.sh:190-198`: new `TF_ENV_CLEAN_EXIT`, reset on every source and not exported, and a new `tf_env_exit <rc>`.
- `tf-env.sh:200-222`: `tf_env_on_exit` now:
  - saves `rc=$?` first;
  - converts an rc of 0 that did not go through `tf_env_exit` into 1, with an error message;
  - runs the cleanup and releases the lock;
  - ends with `exit "${rc}"`.
- Usage checks now use an explicit `if [[ -z "${1:-}" ]] … exit 2`, in:
  - `archive-evidence.sh:46-50`
  - `cleanup.sh:102-106`
  - `plan.sh:51-55`
  - `rollback.sh:27-31`
  - `run-bounded-pipeline.sh:28-32`
  - `smoke-test.sh:42-46`
  - `deploy.sh:31-36` (checks both arguments)
- Intended success exits now go through `tf_env_exit`:
  - `archive-evidence.sh:328`
  - `plan.sh:258`
  - `smoke-test.sh:234`
  - `run-bounded-pipeline.sh:176`
  - `deploy.sh:176,231`
  - `rollback.sh:146` (`tf_env_exit "${FAILED}"`)
  - `cleanup.sh:168` (`summary_and_exit` calls `tf_env_exit "${code}"`)
- No non-zero exit was changed.

**Observed rc,** under `/bin/bash` 3.2.57 with shims:
- **Usage errors:** every script with no arguments returned 2, and so did `deploy.sh dev` and `deploy.sh "" x`.
- **Refusals:** with `TF_VAR_x=1` exported, `plan.sh dev`, `cleanup.sh dev`, `rollback.sh dev` and `deploy.sh dev /nonexistent` each returned 3.
- **Other validation:** `plan.sh qa`, a `/8` value in `ALB_INGRESS_CIDRS`, and `run-bounded-pipeline.sh dev 08` each returned 2.
- **Success paths:** the `plan.sh` success path returned 0; the full cleanup walk returned 0.
- **Cleanup aborts:** declining the destroy after EMPTY returned 1 with the accurate title; end of input at EMPTY returned 1.
- **Trap probes:**
  - An unbound variable, an arithmetic syntax error and `${1:?}` each returned 1, with the new message.
  - `false` returned 1, and `exit 7` returned 7.
  - `tf_env_exit 0` returned 0.
  - Falling off the end of a script returned 1, by design.

**Confirmations:**
- Only `scripts/*.sh` was touched.
- No plan, apply or destroy; no AWS calls; no project git add or commit (commits were made only in the scratch repo); no `.env` files read.

**Risk:** a future script that sources `tf-env.sh` must end every success path with `tf_env_exit 0`. A plain `exit 0` now fails closed with exit 1. This is documented at `tf-env.sh:193-195`.

### 3.5 Work order to storage-engineer (summary)

- **N35:** use `WITH p ORDER BY length(p) ASC LIMIT 1 RETURN …`.
- **Default edge type:** when the caller passes no edge types, default to the coauthorship type.
- **Depth clamp:** lower it only if no caller depends on depths above 6; do not edit `modules/rag`.
- **ttl:** document that it is epoch seconds.
- **Tests:** add them, and run `storage/test` and `scalafmtCheckAll`.

### 3.6 storage-engineer final report

STATUS: COMPLETE.

**Files changed:**
- `NeptuneClient.scala`
- `DynamoDbClient.scala` (comment only)
- `SignedStorageClientsSpec.scala`

**Query.** The shortest-path query is now:

```
MATCH p = (a:Researcher {researcherId: $fromId})-[:<types>*1..<d>]-(b:Researcher {researcherId: $toId})
WITH p ORDER BY length(p) ASC LIMIT 1
RETURN [n IN nodes(p) | n.researcherId] AS path
```

The result column is unchanged.

**Default edge type.** `DefaultPathEdgeTypes = Vector("COAUTHOR")` is used when no edge types are passed.
- Why COAUTHOR: it is the only relationship type the storage module queries (`getDirectCoauthors`), and it is the label in the test fixtures. `modules/spark` and `modules/core` contain no edge-label string.
- Explicitly passed types are still validated against the allowlist. IDs are passed as parameters only.

**Depth clamp.** Kept at 1..10. `PathFinderAgent.findPaths` is public and clamps to `min(10, maxDepth)`, with a default of 6. `RuntimeWorkflow.scala:66` uses the default and passes no edge types, so the runtime path traverses COAUTHOR only.

**ttl.** Documented as epoch seconds. This matches the code, which writes `fromN` with no conversion, and the fixture value `1700000000L`.

**Tests.** The storage module went from 98 to 98 tests (the baseline run reported 97 executed).
- Updated: the bounded-match test now asserts the `WITH … ORDER BY … LIMIT 1` clause comes before `RETURN`; the clamp and multi-type test is adjusted.
- New: a test that the default traversal is COAUTHOR only and never an untyped match.

**Commands:**
- `sbt -batch "storage/test"` (baseline): rc=0.
- `sbt -batch "storage/scalafmt" "storage/Test/scalafmt" "storage/test"`: rc=0, 98 succeeded.
- `sbt -batch scalafmtCheckAll`: rc=0.

**Unverified:**
- Neptune acceptance of the `WITH` form with the list comprehension.
- Query cost at depths 6–10.
- That the label `COAUTHOR` matches a real bulk-load export: no Spark exporter emits a label yet, so if the label differs, the path queries return nothing.

**Confirmations:** edits only under `modules/storage/**`; no AWS calls, staging or `.env` files.

## 4. Rerun evidence (orchestrator, 2026-11-23, after all agents)

| Command | Result | Exit |
|---|---|---|
| `terraform fmt -check -recursive` | no output | 0 |
| `terraform init -backend=false -input=false -lockfile=readonly` | passed | 0 |
| `terraform validate` | `Success! The configuration is valid.` | 0 |
| `/bin/bash -n scripts/*.sh` | 8 of 8 passed | 0 |
| Each of the 7 entry scripts with no arguments (clean environment) | rc=2 for every one | 2 |
| `deploy.sh dev` (one argument) | rc=2 | 2 |
| `TF_VAR_x=1 plan.sh dev` | rc=3 (refused) | 3 |
| `sbt -batch check` | passed. Per module: 118, 116, 133, 98, 65, 32, 63 = 625 per pass, 2 passes, 0 `[error]` lines. Marker went from `2026-11-23T12:18:01.910164Z` to `2026-11-23T14:50:02.946911Z`. | 0 |
| `trivy config`, dev and prod | 37 each: 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW. Unchanged from repair-05; same accepted classes. | 0 |
| `git diff --cached --name-only` | 0 (nothing staged) | 0 |

Diff: `<session-scratchpad>/tf-phase12-r6.diff`, 11 files, 1180 lines, against the pre-repair-06 snapshot. Storage changes: `git diff -- modules/storage`.

**NOT RUN:** `terraform plan`, apply, cleanup, deploy, any AWS call, any Bedrock call, and shellcheck (not installed).

## 5. Re-review

code-reviewer Pass 5 (2026-11-23) returned **APPROVE** for the repair-06 scope. The full text is in `review-report.md`.

Fixed: N26, N27, N28, N30, N32, N33, N34, N35, and the exit-code follow-up. Trivy is unchanged: 37 findings per environment, all in accepted classes.

New findings:
- N36 (MINOR, latent): there is no shared Neptune label and property contract (domain-engineer, with spark-engineer and storage-engineer).
- N37 (INFO): a `git status` failure inside `require_clean_tree` is treated as a clean tree (infra-engineer).
- N38 (INFO, commit gate): `.terraform.lock.hcl` has never been committed.
- N39 (INFO): Terraform CLI-config and provider override variables are not refused.
- N40 (INFO): the cost of the depth-10 path query is unmeasured.

## 6. Outcome

**Fixed:** everything in section 5.

**Open, can be fixed locally:**
- N36 (domain-engineer, spark-engineer, storage-engineer).
- N37 and N39 (infra-engineer).
- N40, which needs a measurement on real data (experiment-engineer).

**Waiting on the human:**
- Commit the tree, including the lock file (N38).
- Ratify or revert `.claude/agents/test-engineer.md`.
- N16: accept the restriction, including the `/16` width, or choose a public API.
- N14 and N29: the embedding-worker entry point and image.
- N31: accept that smoke Test 4 FAILs against stubs.
- N23: confirm the state bucket.
- The cleanup data-loss decision, with re-fetchability confirmed by dataset-engineer.
- N22: the prod CMK.
- R7 stage 2 and N21: the generation model.

**Deferred by the human:** R23.

**Human gate: PENDING.**
