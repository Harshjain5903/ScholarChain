# Phase 12 — Test report (local Terraform checks)

Run date: 2026-11-22
Code version: branch `remediation`, parent `753c499`, plus uncommitted working tree. `infra/terraform/**` is untracked.
Tool versions: Terraform v1.17.2 (darwin_arm64), hashicorp/aws provider 6.26.0 (from `.terraform.lock.hcl`).
Scope: local static checks only. No AWS credentials and no network calls to AWS APIs. Provider download from registry.terraform.io only.
Recorded by: the orchestrator, from the infra-engineer report (`repair-01.md` §4) and its own rerun. This is not a test-engineer report: Phase 12 has no Scala test changes, and the test-engineer has not been invoked for this phase.

## Results — local

| # | Command (from `infra/terraform` unless noted) | Run by | Before repair | After repair | Exit |
|---|---|---|---|---|---|
| 1 | `terraform fmt -check -recursive` | human (before); orchestrator (after) | passed (human-reported) | passed, no output | 0 |
| 2 | `terraform init -backend=false -upgrade` | human (before, without `-upgrade`); infra-engineer (after) | passed with aws 5.31.0 | passed, installed aws v6.26.0 | 0 |
| 3 | `terraform validate` | human (before); infra-engineer and orchestrator (after) | **FAILED**: `modules/storage/main.tf:191`, `aws_s3tables_table_bucket` not supported by provider 5.31.0 | `Success! The configuration is valid.` | 0 |
| 4 | `bash -n scripts/*.sh` (7 scripts; repo root) | infra-engineer | not run | all 7 passed | 0 each |
| 5 | `grep -rn s3tables . --exclude-dir=.terraform` | orchestrator | 1 reference | no matches | 1 (expected) |

Human rerun (confirmed by the human architect on 2026-11-22): after the repair, the human personally ran `terraform fmt`, `terraform init -backend=false` and `terraform validate` on their Mac. Formatting and validation passed with provider 6.26.0. The human has not run `plan` or `apply`.

Human verification (confirmed by the human architect, recorded 2026-11-22): "I personally ran Terraform formatting, initialization with the backend disabled, and validation. The first validation failed on the S3 Tables resource with AWS provider 5.31.0. I reran the checks after the repair using provider 6.26.0, and validation returned: 'Success! The configuration is valid.'" The human gate remains PENDING because code-reviewer Pass 1 returned REQUEST_CHANGES.

Totals for the local checks above: 5 run, 5 pass, 0 fail, 0 skip (after repair).
History: check 3 failed before the repair. That failure is kept in this report and in `repair-01.md` §1.

## Results — local, after repair-02 (orchestrator rerun, 2026-11-22)

| # | Command | Result | Exit |
|---|---|---|---|
| 6 | `sbt -batch check` (repo root) | Before repair-02: **FAILED** at `scalafmtCheckAll` on 3 Phase 11 adversarial specs. After: passed. Each pass had 7 module results: 114, 116, 133, 24, 63, 63 and 65 = 578 tests; 0 failed, 0 canceled, 0 ignored. There were 2 passes because `markTestRun` reruns the tests. The marker changed from `2026-11-23T00:35:36.530167Z` to `2026-11-23T00:37:15.338711Z`. | 0 |
| 7 | `terraform init -backend=false` | passed | 0 |
| 8 | `terraform fmt -check -recursive` | passed, no output | 0 |
| 9 | `terraform validate` | `Success! The configuration is valid.` | 0 |
| 10 | `bash -n scripts/*.sh` | 7 of 7 passed | 0 |
| 11 | `terraform providers lock -platform=darwin_arm64 -platform=linux_amd64` (infra-engineer) | `Success! Terraform has updated the lock file.` | 0 |

New tests added in repair-02:
- core `AppConfigSpec`: 4 → 21 tests.
- rag: `SemanticContextAgentSpec` plus 2 workflow tests; rag total 65.
- storage: `EmbeddingWriteValidationSpec`, +11 tests; storage total 63.

One existing assertion was updated: core `dimensions shouldBe 1536` became 1024, following the human's decision. Details are in `repair-02.md` §4.1. Not run: the Python dataset tests (`datasets/scripts/tests`), which are not part of Phase 12, and shellcheck, which is not installed.

## Results — local, after repair-03 (orchestrator rerun, 2026-11-22)

| # | Command | Result | Exit |
|---|---|---|---|
| 12 | `sbt -batch check` | passed. Per module: 114, 116, 133, 32, 63, 65, 91 = 614 tests per pass; 0 failed, 0 canceled, 0 ignored. 0 `[error]` lines. The token sentinel `FAKE_TOKEN` appears 0 times in the log. Marker updated from `2026-11-23T01:01:59.052336Z` to `2026-11-23T01:03:39.319591Z`. | 0 |
| 13 | `terraform init -backend=false` | passed | 0 |
| 14 | `terraform fmt -check -recursive` | passed | 0 |
| 15 | `terraform validate` | `Success! The configuration is valid.` | 0 |
| 16 | `bash -n scripts/*.sh` | 8 of 8 passed. code-reviewer also checked them with `/bin/bash` 3.2.57. | 0 |

Tests added: storage `SignedStorageClientsSpec` (+28, which brings storage to 91) and services `LoggingConfigSpec` (+8, which brings services to 32). No existing assertion changed. Independent review: code-reviewer Pass 2 returned REQUEST_CHANGES (N1 and N2 MAJOR); see `review-report.md`.

## Results — local, after repair-04 (orchestrator rerun, 2026-11-23)

| # | Command | Result | Exit |
|---|---|---|---|
| 17 | `terraform init -backend=false -input=false` | passed | 0 |
| 18 | `terraform fmt -check -recursive` | passed, no output | 0 |
| 19 | `terraform validate` | `Success! The configuration is valid.` | 0 |
| 20 | `/bin/bash -n scripts/*.sh` (bash 3.2.57) | 8 of 8 passed | 0 |
| 21 | `sbt -batch check` | passed. Per module: 114, 116, 133, 32, 63, 65, 91 = 614 tests per pass; 0 failed, 0 `[error]` lines. Marker went from `2026-11-23T01:03:39.319591Z` to `2026-11-23T11:50:15.890334Z`. No Scala changed in repair-04. | 0 |
| 22 | `trivy config` (Trivy 0.76.1, misconfiguration scan of `infra/terraform`, with dev tfvars and again with prod tfvars) | 47 failed checks for each environment: 6 CRITICAL, 4 HIGH, 5 MEDIUM, 32 LOW. The failure sets are identical. code-reviewer's triage (Pass 3 §4) rates the findings TRUE POSITIVE (fix before first apply): AWS-0053 and AWS-0054, the public HTTP ALB, recorded as N16; AWS-0104 egress on the ALB and Neptune security groups; and AWS-0075, the Neptune audit-log export, recorded as N20. It rates the rest ACCEPTABLE for bounded dev. | 0 (the scan completed; findings are reported, not gated) |

Infrastructure local checks are N1 and N2 regression evidence: infra-engineer's guard refusal tests (`repair-04.md` §3.2) exited 2 or 3, as expected, before any AWS call. code-reviewer's pre-seeded `TF_ENV_VERIFIED_ENV` test also refused, with exit 3.

## Results — local, after repair-05 (orchestrator rerun, 2026-11-23)

| # | Command | Result | Exit |
|---|---|---|---|
| 23 | `terraform fmt -check -recursive` / `init -backend=false` / `validate` | passed; "Success! The configuration is valid." without `alb_ingress_cidrs` set | 0 |
| 24 | `/bin/bash -n scripts/*.sh` (3.2.57) | 8 of 8 passed | 0 |
| 25 | `sbt -batch check` | passed. Per module 118, 116, 133, 97, 65, 32, 63 = 624 tests per pass (was 614: storage +6, core +4). 0 failed, 0 `[error]`. Marker went from `2026-11-23T12:05:34.955294Z` to `2026-11-23T12:18:01.910164Z`. | 0 |
| 26 | `trivy config`, dev and prod | 37 failed checks each (was 47): 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW. Every remaining CRITICAL and HIGH finding is in Pass 3/4's "accepted for bounded dev" class. | 0 |

Infra-engineer's local refusal tests for N3, N6, N16, N24 and N25 (`repair-05.md` §3.2) and code-reviewer's bash 3.2 lock and trap tests (Pass 4 §2) all behaved as expected, before any AWS call.

## Results — local, after repair-06 (orchestrator rerun, 2026-11-23)

| # | Command | Result | Exit |
|---|---|---|---|
| 27 | `terraform fmt -check` / `init -backend=false -lockfile=readonly` / `validate` | passed; "Success! The configuration is valid." | 0 |
| 28 | `/bin/bash -n scripts/*.sh` | 8 of 8 passed | 0 |
| 29 | Each entry script with no arguments; `deploy.sh dev`; `TF_VAR_x=1 plan.sh dev` | rc=2 for every usage error (it was 0 under bash 3.2 before repair-06). `TF_VAR_*` is refused with rc=3. | 2 / 3, as expected |
| 30 | `sbt -batch check` | passed. Per module 118, 116, 133, 98, 65, 32, 63 = 625 per pass (storage +1). 0 failed, 0 `[error]`. Marker went from `2026-11-23T12:18:01.910164Z` to `2026-11-23T14:50:02.946911Z`. | 0 |
| 31 | `trivy config`, dev and prod | 37 each: 4 CRITICAL, 3 HIGH, 4 MEDIUM, 26 LOW. Unchanged; all in accepted classes. | 0 |

## Results — cloud (not run)

| Check | Status | Reason |
|---|---|---|
| `terraform plan` against AWS | NOT RUN | The human has not authorized it. Before any plan, the human must approve the account, Region (us-east-2), profile and environment, and confirm the state-bucket region (N23). |
| Security scan of the plan JSON or of live resources | NOT RUN | No plan exists. Only the static Trivy scan of the configuration (row 22) has run. |
| `terraform apply`, live deploy, smoke test | NOT RUN | Phase 14 only, after explicit human approval. |

## Not covered / residual risks

- `terraform validate` does not check IAM semantics or resource availability in the Region. The IAM changes still need code-reviewer and human review.
- The two notes below describe the position after repair-01 and have since been superseded:
  - The lock file only had the darwin_arm64 hash and was ignored by `.gitignore:38`. repair-02 tracked it and added a linux_amd64 hash.
  - The open blockers were listed in `repair-01.md` §7. The current open items are in `repair-04.md` §7.
- The guard's success path (STS, backend init, real state outputs) has not been exercised. Backend init has never run, because every validate used `-backend=false`.

## Confirmation

This confirmation was written for repair-01 and is kept as written then: "No production Scala file was changed by this repair. Changes are limited to `infra/terraform/**`."

Later repairs:
- repair-02 and repair-03 changed Scala; see those records.
- repair-04 changed only `infra/terraform/**` and `scripts/*.sh`.

After repair-04, nothing was staged or committed: `git diff --cached --name-only` printed 0.
