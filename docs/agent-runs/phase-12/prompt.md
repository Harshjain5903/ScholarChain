# Phase 12 — Work orders (AWS infrastructure in Terraform)

Phase 12 covers the course specification's Phase 12: "create the AWS infrastructure in Terraform",
plus the Phase 13 automation scripts that the same owner (`infra-engineer`) authors under
`scripts/`. The phase ran across several orchestrator sessions and produced eight repair
rounds (`repair-01.md` … `repair-08.md`) and nine independent review passes
(`review-report.md`).

This file records the human architect's work orders as they were given. The orchestrator
assembled this record; it is not an agent artifact. Earlier rounds are reconstructed from the
session transcript and are labelled where that is the case.

---

## Round 1 — initial infrastructure (reconstructed)

Human intent: create the complete ScholarChain AWS environment as reviewed Terraform under
`infra/terraform/`, plus the operational scripts under `scripts/`, following the specification's
Phase 12 work order: foundation, network, identity and encryption, data lake, EMR, DynamoDB,
Neptune, S3 Vectors, runtime compute, workflow and messaging, observability, and cost and
safety. Required scripts: `plan.sh`, `deploy.sh`, `rollback.sh`, `smoke-test.sh`,
`run-bounded-pipeline.sh`, `cleanup.sh`. Run `terraform fmt -check`, `terraform validate`,
shell syntax checks and a security scan. Do not apply.

Outcome: `repair-01.md`, `repair-02.md`, `repair-03.md`; review passes 1 and 2.

---

## Round 4 — repair-04 (verbatim)

> Proceed with local Repair‑04 for N1 and N2. Have infra-engineer align the evidence-bundle
> table key with the application's bundleId contract and reuse the existing environment/state
> guard in both smoke-test.sh and run-bounded-pipeline.sh. Use us-east-2 consistently for the
> initial deployment and retain the existing Ohio Titan grant for the embedding worker. For R7,
> trace which roles actually call Bedrock and propose the exact model IDs, ARNs, and estimated
> cost for my approval. Defer the dedicated audit queue and leave
> .claude/agents/test-engineer.md outside this repair. Run the local checks and security scan,
> request a third independent review, and record the results. Leave the human gate pending. No
> live plan, apply, cleanup, model calls, AWS changes, staging, or commits in this round.

Outcome: `repair-04.md`; review pass 3 (APPROVE, repair-04 scope).

---

## Rounds 5 and 6 — repair-05 and repair-06

> fix the issues

followed by

> continue

Outcome: `repair-05.md` (17 infra tasks plus storage and core items; review pass 4 APPROVE) and
`repair-06.md` (findings N26–N35 plus the bash 3.2 usage-exit defect; review pass 5 APPROVE).

---

## Round 7 — repair-07 (verbatim)

> Proceed with local Repair‑07 for N36, N37, and N39. Keep IP-restricted dev ingress. Then
> identify the owning agents and exact changes needed for the embedding-worker entry point, real
> runtime composition, and durable evidence lookup so Smoke Test 4 can pass for real. Record the
> local rerun and independent review. Keep Phase 12 and Spark implementation approval pending,
> and leave the model: sonnet decision separate. No staging, commits, live Terraform plan, apply,
> cleanup, or model calls.

Outcome: `repair-07.md`; review pass 6 (repair-07 APPROVE conditional; real-data path
REQUEST_CHANGES with findings N41–N53).

---

## Round 8 — repair-08

Human intent, given over several messages: use the review findings to finish the Spark step;
check whether `sbt -batch check` passes; fix N44 so outputs trace back to DBLP, the Hadoop run
and the actual entity-resolution policy; handle N45 as a separate dataset repair; use
`COAUTHORED_WITH` consistently across the schema, export, loader, queries and tests; carry
forward the missing maximum-team-size work IDs, misleading bridge labels, skipped betweenness
reported as zero, and the k-core iteration limit; rerun the same 500-record slice and compare it
with the baseline; rebuild the jars; obtain a fresh independent review; and leave the Phase 06
gate pending.

Human approval recorded in-session (2026-11-23): "I approve using COAUTHORED_WITH as the shared
label to match the professor's README."

Outcome: `repair-08.md`; review pass 7 (APPROVE, conditional on a green `sbt check`).

---

## Rounds 9 to 12 — AWS readiness (us-east-2 backend, non-EMR batch path, cost)

Human intent: complete the project against the specification end to end; before any live
Terraform plan, show the target account, profile, Region and environment, and verify the state
backend read-only; keep every apply, deploy, paid model call and destructive cleanup behind
explicit approval.

Read-only AWS verification performed by the orchestrator on 2026-11-24 under profile
`scholarchain` is recorded in `repair-08.md` §4 and in the review report. It established that
only us-east-2 is usable, that the configured state bucket did not exist, and that an
organization service control policy denies all `elasticmapreduce:*` and `emr-serverless:*`
actions.

Human decisions recorded in-session (2026-11-24):

- "Use ECS Fargate for the dev batch pipeline and continue now. Instructor approval isn't
  required. Document that the course AWS policy blocks EMR and that Fargate runs the same JARs
  using the MapReduce local runner and Spark local[*]. Keep EMR configurable in case it becomes
  available."
- Cost posture: reductions plus a demo window.
- Fix the doubled work-ID prefix (N50) before any Neptune load.
- Runtime LLM: Bedrock Claude Haiku 5.5 via the US inference profile; embeddings on Titan v2.

Outcome: review passes 8 (REQUEST_CHANGES, findings N65–N79) and 9 (APPROVE, findings N80–N89),
and the infrastructure work orders INF-P12-R09 through INF-P12-R12. See `review-report.md`.

---

## Standing constraints for every round

- No `terraform plan` against AWS, no apply, no deploy, no destructive cleanup and no paid model
  calls without explicit human approval.
- No staging, commits or pushes by any agent. The human commits.
- Constitutional files (`.claude/**`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`) require human
  ratification; they were not modified in this phase.
- `.env` and credential files are never read.
- The contact email used by the dataset scripts lives only in an environment variable and in
  request headers, never in a repository file.
