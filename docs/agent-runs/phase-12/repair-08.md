# Phase 12: repair-08 (real-data path: lineage, COAUTHORED_WITH, Spark review items; N45 dataset repair)

Date: 2026-11-23 to 2026-11-24. This record was assembled by the orchestrator from the owning agents' reports and from the orchestrator's own reruns.

Base: branch `wip-backup-20261123`, HEAD `b52988e`, plus an uncommitted working tree. Nothing was staged, committed or pushed by any agent. Local work only: no AWS calls, no Terraform plan or apply, no model calls, no network fetches.

Phase 06 human gate: **PENDING**. This record does not decide it.

## 1. Inputs to this repair

- Review Pass 6 (code-reviewer, Opus):
  - repair-07: APPROVE, on conditions.
  - real-data path: REQUEST_CHANGES, findings N41–N53.
- Independent Spark review (Phase 06 audit session handoff `HANDOFF-spark-review-and-baseline.md`, kept in the orchestrator scratchpad): REQUEST_CHANGES. It raised:
  - the work IDs behind M_r (maximum team size) are never produced;
  - misleading bridge labels;
  - lineage-free provenance;
  - an incomplete run manifest;
  - a `v1` policy default;
  - skipped betweenness written as 0;
  - an unbounded k-core.
- Human decision, recorded in the session on 2026-11-23: "I approve using COAUTHORED_WITH as the shared label to match the professor's README." Keep the weak entity-resolution result as a separate finding.

## 2. Formatting checkpoint (before any fix)

- The human ran `sbt -batch scalafmtAll` themselves.
- The orchestrator then ran `sbt -batch check`: **exit 0**, 736 tests per pass, 0 failed. Marker `2026-11-24T02:46:32.340770Z`.
- The formatting diff touches 27 files, +492/−241.
  - 26 files are unchanged once whitespace and scaladoc `*` are stripped.
  - `SignedStorageClientsSpec.scala` also has a scalafmt modifier reorder: `private final` → `final private`.
- The formatting cannot be committed on its own on `remediation` (`753c499`):
  - 20 of the 27 files do not exist there.
  - The hunks for the other 7 Spark files do not apply to it.

## 3. Work orders, owners, changes

| Work order | Owner | Finding(s) | Change summary | Owner-reported gate |
|---|---|---|---|---|
| DOM-P12-R08 | domain-engineer | label, N44, Spark-review M_r / N52 | `EdgeType.Coauthor` = `COAUTHORED_WITH`; `PropertyKey` displayName/title/year/venue; `ResearcherMetrics.maximumTeamWorkIds`, `bridgeScoreMethod` (+ `BridgeScoreMethod`); `Provenance` optional `transformationStep`, `producer`, `upstreamRunIds`, `policyVersion`. All backward-compatible; new `LineageContractSpec`. | core 162/162; check failed only on 4 storage tests pinning the old label |
| STO-P12-R08 | storage-engineer | label | Storage tests and scaladoc moved to `COAUTHORED_WITH` via the constant; new test: the retired `COAUTHOR` is rejected before any HTTP call. | storage 112/112; check blocked by services flake |
| SVC-P12-R08 | service-engineer | flaky `AdversarialServiceSpec` idempotency race (found during this repair) | New `IdempotencyStore`: single-flight per key using `ConcurrentHashMap.putIfAbsent` and `CompletableFuture`; failures not cached; waiter timeout. New deterministic `IdempotencyConcurrencySpec` (4 of 5 tests fail on the old logic). | 10/10 loop pass; check exit 0, 766 |
| SPK-P12-R08 | spark-engineer | N44, policy, M_r IDs, bridge labels, N52, k-core cap, manifest, label export, test gaps | New `Lineage.scala`, `RunManifest.scala`, `InputLineageSpec`. `RealDataRegressionSpec` renamed to `DblpSliceFindingsFixtureSpec`. The ER policy is derived from the decisions; mixed or contradicting policies are refused; ER and Hadoop run linkage is checked. | spark 148/148; check exit 0, 810 |
| HDP-P12-R08 | hadoop-engineer | N44 (pairs), timestamp, ORCID, N49; N50 report only | New `provenance/HadoopLineage.scala`; `-D scholarchain.run.timestamp`; DBLP `orcid` carried into `AuthorMention.orcid`; `LazyOutputFormat`; stricter `ExportVerifier`. | hadoop 166/166; check exit 0, 828; marker `2026-11-24T11:01:02Z` |
| DS-P12-N45 | dataset-engineer | N45 (separate dataset repair) | `--max-requests` budget counts every attempt, including retries, redirect hops and OpenAlex page 1. Redirects are handled manually and allowed only to an https allowlist (api.crossref.org, api.openalex.org, pub.orcid.org), default port, no userinfo. Run log `fetch-runs-<date>.tsv`. | unittest 55/55; validate_manifest PASS 106 |

Machine sleep interrupted the hadoop-engineer run twice. It was resumed, and its `sbt check` completed (about 2h40m of wall time because of dark-wake sleep).

## 4. Orchestrator reruns

### 4.1 Dataset (N45)

| Command | Exit | Result |
|---|---|---|
| `/usr/bin/python3 -I -B -m unittest discover -s datasets/scripts/tests -p 'test_*.py'` | 0 | 55 tests OK |
| `/usr/bin/python3 -I -B datasets/scripts/validate_manifest.py` | 0 | `status=PASS pass=106 fail=0 skip=0` |
| `git diff -- datasets \| grep -ciE '@uic\.edu\|harshjain'` | 1 (no match) | 0 matches: the contact email is not in the diff |

### 4.2 500-record real slice, same input (LOCAL REAL-DATA, development slice; not an approved scale)

- Script: orchestrator scratchpad `rerun-r08.sh`.
- Input: `input/dblp-slice-first500.xml`, sha256 `6b5c9be1…cd44`. Unchanged from the baseline.
- Code state: HEAD `b52988e` with a dirty tree. `git diff HEAD` sha256 `a6792760…3dfd`; untracked-file digest `20eac16a…02da`.

| Step | Output dir | Exit |
|---|---|---|
| Hadoop J01, J06, J07, J08, J03, J04, J05 with `scholarchain.run.timestamp=2026-11-23T00:00:00Z` | `hadoop-r08/` | all 0; ExportVerifier 0 |
| Same chain again | `hadoop-r08b/` | all 0; ExportVerifier 0 |
| Reproducibility: sha256 of every `part-*` in r08 compared with r08b | | **10 of 10 identical** |
| `EntityResolutionRunner --input-run-id rs-20261123-dblp10k-first500` | `entity-resolution-r08/` | 0 |
| `ScholarChainPipeline` (local[*], 4 partitions, betweenness samples 2000, seed 42, no `--policy-version`) | `spark-r08/output` | 0 |
| `sbt -batch packageJobs` | | 0 |

Jars built by `packageJobs`:
- hadoop assembly: 10,632,347 B, sha256 `4ae66e75…302b`. No `Main-Class` (N42, open).
- spark assembly: 10,712,961 B, sha256 `c2ca7022…954d`. `Main-Class: edu.scholarchain.spark.ScholarChainPipeline`.

### 4.3 Comparison with the baseline and the input ground truth

Ground truth from DBLP person names in the slice:
- 1,907 mentions belong to 1,888 distinct persons.
- 19 persons appear on more than one paper.
- 1 coauthor pair repeats across papers: Jordi Vitrià and Petia Radeva.

| Measure | Baseline (pre-fix) | repair-08 rerun | Fully resolved |
|---|---|---|---|
| Coauthor relationship type | `COAUTHOR` | `COAUTHORED_WITH` (3,531) | — |
| Researcher nodes | 1,905 | 1,901 | 1,888 |
| Cross-paper persons linked | 2 of 19 | **6 of 19** | 19 |
| ER outcomes (Accepted / Rejected / Unresolved) | 2 / 275 / 21 | 6 / 275 / 17 | — |
| Vitrià–Radeva consolidated (jointPublicationCount 2) | no | **no** | yes |
| Coauthor edges, all with jointPublicationCount = 1 | 3,531 | 3,531 | 3,530 |
| Edge provenance `sourceDataset` | `spark-analytics` | `dblp` | — |
| Spark ER policy | `v1` (wrong) | `er-mention-v1-uncalibrated`, read from the decisions; linkage `verified` | — |
| Betweenness method | unrecorded | `exact` (1,901 sources) | — |
| Community summaries naming bridges | 500, including all-zero ones | 6, each with betweenness > 0; 492 say "No bridge researchers identified" | — |
| Researchers with M_r work IDs | 0 | 1,901 of 1,901 (M_r > 0 for all) | — |
| k-core | no cap | converged in 14 iterations (cap 5000), max coreness 12 | — |
| Label propagation | not reported | converged, 0 unstable vertices, 498 communities | — |
| Neptune descriptive properties | none | displayName 1,901/1,901; title, year and venue 500/500 | — |
| Run manifest | no code SHA, inputs, argv or upstream runs | `run` section with gitSha + dirty flag, input sha256, argv, config, lineage, methods, counts | — |

What improved, and why:
- The 4 newly linked persons are Emilio Corchado, Juan Francisco de Paz, Marlon Dumas and Tsong Yueh Chen.
- All 4 come from the `exact_id_match:orcid` rule. Hadoop now carries DBLP `orcid` attributes through.
- No entity-resolution code or threshold changed.
- All 6 Accepted decisions are persons in the ground-truth list. Pass 7 is asked to confirm this independently.

What still needs entity-resolution work. This is a **separate open finding**, not a repair-08 defect:
- 13 of 19 cross-paper persons are still split. Examples: Jordi Vitrià, Changjie Fan, Khaled M. Rabie.
- The Vitrià–Radeva repeated pair is still two separate edges.
- The policy is UNCALIBRATED, with an accept threshold of 0.7 and no gold set. LLM adjudication is disabled.
- Owner: entity-resolution-engineer. Calibration needs human review.

Also seen in the output, open by human decision:
- The work IDs still carry the doubled `work:dblp:dblp:` prefix (N50).
- hadoop-engineer traced the cause: `DblpParser` builds `sourceRecordId = "dblp:<key>"`, and core `IdGen.workId` then adds `work:dblp:`.

## 5. `sbt check` (orchestrator)

`sbt -batch check`, run from the repo root after the rerun and `packageJobs`:
- **Exit 0.** 0 `[error]` lines.
- 828 tests per pass, in both passes: services 37, entity-resolution 138, rag 65, core 162, storage 112, hadoop 166, spark 148. 0 failed.
- Marker moved to `2026-11-24T11:06:11.093784Z`.
- The `git diff HEAD` sha256 outside `docs/` was the same before and after the check (`a6792760…3dfd`), and identical to the code state used for the rerun.

## 6. Independent review

code-reviewer Pass 7 (Opus, read-only): **APPROVE** for the repair-08 scope, on two conditions.
- Condition 1 was that the orchestrator's `sbt check` exits 0. It did (§5).
- Condition 2: commit, then rerun the slice from the committed SHA with the assembly jars and a unique run ID per stage, before the Phase 06 gate (N54, N55).

Status of the earlier findings:
- N44, N45, N49 and N52 are CLOSED.
- All Spark-review items are CLOSED.

New findings:
- N54–N63 are recorded in `review-report.md` Pass 7.
- N64 is the separate entity-resolution weakness.

The full text is in `review-report.md` (Pass 7).

## 7. Open items after repair-08

- N41: Neptune parser and export shape.
- N42: EMR launch path; the Hadoop jar has no `Main-Class`.
- N43: an undeclared edge type returns 500 instead of 400.
- N46–N48: script hardening.
- N50: work-ID prefix; needs a human decision.
- N51: DBLP person-key rule.
- Weak ER result: see §4.3.
- Metrics Parquet gained 2 columns. Storage-side readers, if any, must be checked (storage-engineer).
- The idempotency store is in-memory and per instance. Cross-instance deduplication would be a separate work order.
- Human gates: Phase 06 rerun gate PENDING; Phase 12 gate PENDING; `test-engineer.md` ratification is separate.
