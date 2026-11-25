# Phase 12 Human Gate

Status: **PENDING**

This file is reserved for the human architect's decision. The orchestrator has prepared the
questions and the evidence pointers below but must not record an acceptance, a rejection or a
commit hash on the human's behalf. Nothing in this file is an approval.

## What is ready for review

- `prompt.md` — the work orders for all eight repair rounds.
- `agent-report.md` — the phase-level 14-field summary.
- `repair-01.md` … `repair-08.md` — per-round findings, owners, changes, reruns and outcomes.
- `review-report.md` — independent review passes 1 to 9 (`code-reviewer`, read-only).
- `test-report.md` — every command run, with exit codes.

Latest verification, performed by the orchestrator on 2026-11-24 with the scalafmt caches
cleared: `sbt -batch check` exit 0, 1,187 tests per pass, 0 failures. `terraform fmt` and
`validate` pass in both roots. `scripts/test-pipeline-lib.sh` passes 113 of 113 under bash 3.2.

Review pass 9 returned **APPROVE** for the infrastructure, conditional on finding N80, which was
then fixed in work order INF-P12-R12.

## Decisions the human has already recorded in-session (quoted, not interpreted)

These were stated by the human architect during the sessions and are reproduced here as context.
They are not a Phase 12 gate decision.

- 2026-11-23: "I approve using COAUTHORED_WITH as the shared label to match the professor's
  README."
- 2026-11-24: "Use ECS Fargate for the dev batch pipeline and continue now. Instructor approval
  isn't required. Document that the course AWS policy blocks EMR and that Fargate runs the same
  JARs using the MapReduce local runner and Spark local[*]. Keep EMR configurable in case it
  becomes available."
- 2026-11-24: cost posture "reductions plus a demo window".
- 2026-11-24: fix the doubled work-ID prefix (N50) before any Neptune load.
- 2026-11-24: runtime LLM Bedrock Claude Haiku 5.5 through the US inference profile; embeddings
  on Titan v2.

## Questions for the human architect (each PENDING until answered)

1. PENDING — Do you accept the Phase 12 infrastructure and scripts as reviewed in pass 9?
2. PENDING — Do you ratify `infra/terraform/.trivyignore.yaml`? It currently states that it is
   not ratified.
3. PENDING — Do you accept the bootstrap bucket using SSE-S3 rather than a customer-managed KMS
   key (trivy AWS-0132), and no access-log bucket (AWS-0089)?
4. PENDING — Do you accept the 60-day reproducibility window for noncurrent jar and dataset
   versions (finding N88)?
5. PENDING — Do you accept that the environment lock is held for the whole pipeline run, which
   blocks plan and deploy for that environment meanwhile (finding N89)?
6. PENDING — Do you accept the cost estimate and the deploy-demo-cleanup cadence it implies,
   given the $25 budget?
7. PENDING — Which principal will run `scripts/run-bounded-pipeline.sh`? The code and comments
   now assume the human operator's admin SSO profile.

## Remaining risk at the time of writing

- No AWS resource exists yet. The Phase 14 deployment, the Phase 15 experiments and the Phase 16
  AWS-dependent evaluations are NOT RUN.
- The ECS batch path has never been executed end to end; it requires the batch-runner image and
  the job jars to be present first.
- A standing dev environment would exhaust the $25 budget in roughly four days.

## Manual changes

Record here any edit you made by hand, as the specification requires. The orchestrator assembled
every file in this directory from agent reports and its own verification runs; no implementation
agent wrote a human decision.

## Accepted commit

PENDING. Record the commit hash here once you accept the phase. See `commit.txt`.
