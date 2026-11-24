# Phase 12 repair 07: N36 (graph-schema contract), N37 and N39; Smoke Test 4 ownership plan

**RECONSTRUCTED 2026-11-24.** This record was written after the fact, to close N53. The orchestrator rebuilt it from its session summary and from `review-report.md` Pass 6. It is not a contemporaneous transcript. Counts and IDs are as recorded at the time. Anything that cannot be re-verified now is marked.

Date of the work: 2026-11-23. Branch and state: the work started on `remediation` (`753c499`). The human preserved it in WIP commit `b52988e` on `wip-backup-20261123`. Nothing was staged or committed by the agents.

## 1. Trigger

Human instruction (2026-11-23), condensed:
- Proceed with local Repair-07 for N36, N37 and N39.
- Keep dev ingress IP-restricted.
- Identify the owners and the exact changes needed for:
  - the embedding-worker entry point;
  - real runtime composition;
  - durable evidence lookup.
- Record the local rerun and an independent review.
- Keep Phase 12 and Spark approval pending.
- No staging, commits, live plan, apply, cleanup or model calls.

## 2. Routed to and changes

| Finding | Owner | Change |
|---|---|---|
| N36, graph-schema contract | domain-engineer | New `modules/core/.../GraphSchema.scala`: vertex labels, edge types, property keys and the identifier pattern, with `requireDeclaredEdgeType`. New `GraphSchemaSpec` (20 tests). |
| N36 | storage-engineer | `NeptuneClient` takes labels and keys from `GraphSchema`, with the query text unchanged byte for byte. Undeclared edge types are rejected. The path query is bounded. New `GraphSchemaContractSpec` (13 tests). |
| N36 | spark-engineer | New `NeptuneBulkLoadExport.scala`, which writes openCypher bulk-load CSVs using the `GraphSchema` names. New `NeptuneBulkLoadExportSpec`. |
| N37, N39 | infra-engineer | Hardening of `plan.sh`, `deploy.sh`, `cleanup.sh` and `tf-env.sh`. Dev ingress stays IP-restricted. |

Smoke Test 4 plan: a read-only Plan agent produced it, and it has **not been implemented**.
- **A. Embedding worker**
  - build: the sqs dependency and `mainClass`;
  - storage: `SqsQueueClient`;
  - rag: `BedrockTitanEmbedder`;
  - service: the worker main;
  - infra: the ECS command;
  - spark: the JSONL producer.
- **B. Runtime composition**
  - rag: port adapters;
  - domain: the synthesis-mode config;
  - service: `composeAws`;
  - infra: the Titan grant, which needs human approval.
- **C. Durable evidence**
  - domain: `IdGen.evidenceId`;
  - rag: `EvidenceBuilderAgent`;
  - storage: `putQueryResult` and `getQueryResult`;
  - service: `ServiceResultStore`;
  - infra: the PutItem grant, which needs human approval;
  - test: a contract test.

## 3. Rerun

At the time, `sbt -batch check` failed only at `scalafmtCheckAll`, on 15 files that other agents had written. With formatting excluded, every test passed. The marker stayed at `2026-11-23T17:08:10.437874Z`.

The human then ran `sbt -batch scalafmtAll`. On 2026-11-24 the orchestrator reran `sbt -batch check`: exit 0, 736 tests per pass, marker `2026-11-24T02:46:32.340770Z`. That run is recorded in `repair-08.md` §2.

## 4. Review

code-reviewer Pass 6 (Opus): APPROVE for repair-07, on two conditions:
- `sbt check` green after formatting. Met on 2026-11-24.
- N41 and N43 routed before any Neptune load or service exposure. Still OPEN.

See `review-report.md` Pass 6.

## 5. Human gate

Phase 12 remains PENDING. Spark implementation acceptance remains PENDING. The `model: sonnet` ratification is separate.
