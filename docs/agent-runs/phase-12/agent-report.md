# Phase 12 — Agent report summary (AWS infrastructure in Terraform)

Phase 12 produced eight repair rounds and nine review passes. Each owning agent returned a
report in the required 14-field contract; those reports are transcribed in the `repair-NN.md`
records in this directory, which are the authoritative per-round evidence. This file is the
phase-level summary the specification requires, assembled by the orchestrator.

Date range: 2026-11-22 to 2026-11-24. Branch: `remediation`, then `wip-backup-20261123`
(`b52988e`), then `remediation-reviewed` (`32ea38e`, `ae1f4d7`, `77ffbd0`).

## STATUS

COMPLETE for authoring and local validation. BLOCKED-ON the human architect for the first AWS
action: the one-time state-bucket bootstrap, then a reviewed `plan.sh dev`. No AWS resource has
been created by any agent.

## OWNER

`infra-engineer` owns `infra/terraform/**` and `scripts/*.sh`. Findings that fell outside that
tree were routed to their owners and are recorded in the repair records: `domain-engineer`,
`storage-engineer`, `hadoop-engineer`, `spark-engineer`, `entity-resolution-engineer`,
`rag-engineer`, `service-engineer`, `dataset-engineer` and `build-engineer`. `code-reviewer`
produced passes 1 to 9 and wrote no implementation file.

## WORK ORDER

See `prompt.md` in this directory.

## FILES CREATED

- `infra/terraform/` — root module (`main.tf`, `variables.tf`, `outputs.tf`, `providers.tf`,
  `versions.tf`, `backend.tf`), `environments/{dev,prod}.tfvars` and `.backend.hcl`, and modules
  for network, iam, storage, emr, dynamodb, neptune, s3vectors, ecs, workflow, observability and
  `batch_fargate`.
- `infra/bootstrap/` — standalone local-state root that creates only the Terraform state bucket.
- `infra/terraform/.trivyignore.yaml` — one entry per accepted finding, with justification and
  expiry. NOT ratified by the human.
- `scripts/` — `plan.sh`, `deploy.sh`, `rollback.sh`, `smoke-test.sh`, `run-bounded-pipeline.sh`,
  `cleanup.sh`, `archive-evidence.sh`, `tf-env.sh`, `pipeline-lib.sh`, `test-pipeline-lib.sh`.

## FILES MODIFIED

All of the above across the eight repair rounds. Per-round file lists are in `repair-01.md`
through `repair-08.md`.

## COMMANDS RUN

Local and read-only only:

- `terraform fmt -check -recursive`, `terraform init -backend=false -lockfile=readonly`,
  `terraform validate` in both roots.
- `/bin/bash -n scripts/*.sh` (bash 3.2.57) and `/bin/bash scripts/test-pipeline-lib.sh`.
- `trivy config` with the dev and prod tfvars, with and without the ignore file.
- `sbt -batch check` after every round that touched Scala.
- Read-only AWS calls on 2026-11-24 under profile `scholarchain`: `sts get-caller-identity`,
  `s3api get-bucket-location`/`head-object`, `dynamodb list-tables`,
  `bedrock get-foundation-model`/`get-inference-profile`, `emr list-clusters`,
  `emr-serverless list-applications`, `budgets describe-budgets`, plus service-availability
  probes.

NOT run: `terraform plan` against AWS, `apply`, `destroy`, any deployment, any pipeline
submission, and any paid model call.

## TEST RESULTS

- `scripts/test-pipeline-lib.sh`: 113 of 113 pass under bash 3.2.57 (grew from 17 to 113 across
  the rounds).
- `bash -n`: all scripts pass.
- `terraform validate`: Success in the root and the bootstrap root.
- `sbt -batch check`: exit 0. The final cache-cleared run on 2026-11-24 reported 1,187 tests per
  pass with 0 failures. Earlier per-round counts are in `test-report.md`.
- Guard refusal tests: every entry script exits 2 on a usage error and 3 on a refused override
  (`TF_*`, `TF_VAR_*`, `GIT_*`, `GIT_CONFIG_*`, a CLI config file, a `~/.terraformrc` keyword).

## VALIDATION RESULTS

- `trivy config` on `infra/terraform` with the dev tfvars: 30 findings, down from 37, all in the
  accepted classes; 0 with the reviewed ignore file. The bootstrap root reports 2 findings
  (AWS-0132 SSE-S3 instead of a customer-managed key, AWS-0089 no access-log bucket), both
  triaged as accepted for the course environment.
- Review pass 9 verdict: APPROVE, conditional on finding N80 being fixed before the first ECS
  pipeline run if that run uses the deploy role. N80 was then fixed in INF-P12-R12.
- Independent cost estimate, rated credible by the reviewer: about $6.70 to $7.30 per day in
  steady state for dev, about $9.00 per day in a demo window with all services up, and $0.10 to
  $0.50 for one bounded pipeline run on Fargate Spot. The $25 budget is therefore a
  deploy-demo-cleanup window, not a standing environment.

## ASSUMPTIONS

- Dev runs `batch_compute = "ecs"` because the course organization's SCP denies EMR. Prod keeps
  `"emr"` so the configuration remains valid for an unrestricted account.
- The operator who runs `scripts/run-bounded-pipeline.sh` is the human using an admin SSO
  profile, not the deploy role.
- Prices in the cost estimate are us-east-2 list prices from the agents' knowledge, not from the
  Pricing API.

## RISKS

- A standing dev environment exhausts the $25 budget in roughly four days. Budgets alert but do
  not stop spend.
- The ECS batch path has never been executed: it needs the batch-runner image and the job jars in
  ECR and S3 first. Every preflight fails closed until they exist.
- The s3a `s3:prefix` conditions and `run-*` write limits are untested against real s3a
  behaviour.
- Terraform `moved` blocks for the EMR module and the parked-service alarms take effect on the
  next plan and must be reviewed there.

## MANUAL ACTION REQUIRED

1. Run `infra/bootstrap` once to create the state bucket (see its README), then set
   `SCHOLARCHAIN_STATE_BUCKET` or edit the backend files.
2. Supply `ALB_INGRESS_CIDRS` and `BUDGET_ALERT_EMAIL` at plan time.
3. Review the dev plan: account, Region, cost, plan checksum, and the `moved` blocks.
4. Ratify or reject `infra/terraform/.trivyignore.yaml`.
5. Accept or reject the 60-day reproducibility window and the environment-lock duration
   (findings N88 and N89).
6. Record the Phase 12 human gate in `human-gate.md`.

## BLOCKED-ON OWNER

Human architect (every item above). `build-engineer` must also deliver the batch-runner image
build, and the image must be pushed after the ECR bootstrap apply.

## BLOCKED-ON EVIDENCE

Read-only verification on 2026-11-24: `aws s3api get-bucket-location --bucket
scholarchain-terraform-state` returned `NoSuchBucket`; `aws emr list-clusters` and
`aws emr-serverless list-applications` returned `AccessDeniedException ... with an explicit deny
in a service control policy`. Both are quoted in `repair-08.md`.

## NEXT GATE

Human bootstrap apply, then `ALB_INGRESS_CIDRS=... BUDGET_ALERT_EMAIL=... ./scripts/plan.sh dev`,
then human plan approval, then `deploy-engineer`. Phase 14 begins only after that approval.
