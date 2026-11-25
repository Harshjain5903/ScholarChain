# Phase 13 — Enrichment integration and runtime completion (local)

Assembled by the orchestrator from the owning agents' reports and its own reruns.
Date: 2026-11-24. Base commit: `77ffbd0` plus an uncommitted working tree.
Nothing was staged, committed or pushed by any agent. No AWS resource exists yet.

## 1. Scope

Two goals: make the three additional datasets flow through the real pipeline
(course specification functionality items 4 and 5), and replace the stub runtime
with the real Graph-RAG runtime (items 13 to 15).

## 2. Owners and work orders

| Work order | Owner | Change |
|---|---|---|
| DS-P03-02 | dataset-engineer | ORCID bounded fetch readiness and selection of 2,000 iDs; manifest audit against all 17 specification fields; DOI-verified JSONL extracts for Hadoop |
| HDP-P13-02 | hadoop-engineer | Crossref, OpenAlex and ORCID parsers rewritten for the real API shapes; `WholeFileInputFormat`; identifier-extraction cross-source counters; enriched chain run |
| HDP-P13-01 | hadoop-engineer | N50 work-ID prefix fix; N56 upstream run IDs; N60 ORCID ISO 7064 checksum |
| DOM-P13-01/02/03 | domain-engineer | Model and provider config; `IdGen.evidenceId`; researcher metric graph keys and codec; `componentSize`; metric-window config |
| STO-P13-01/02/03 | storage-engineer | Neptune response parser (N41); researcher metric reads and allowlisted ranking; durable query results; `SqsQueueClient`; poison-message path; S3 Vectors write path |
| SPK-P13-01 | spark-engineer | `componentSize` by distributed join; typed metric columns on the Neptune CSV through the core codec; component statistics |
| RAG-P09-R01/R02 | rag-engineer | Bedrock Converse and Titan adapters; Anthropic and Voyage fallback; schema-validated synthesis; deterministic claim verification; Neptune metrics store; allowlisted rankings; `RuntimeComposition` |
| SVC-P13-01 | service-engineer | Runtime mode stub or aws; durable jobs, explain and evidence routes; `EmbeddingWorker`; N43; N58 |
| BLD-P13-01b | build-engineer | Entity-resolution and Hadoop assembly main classes; main-class guard; SQS dependency; standalone jars; batch-runner Dockerfile; services image entrypoint |
| ER-P05-GOLDb | entity-resolution-engineer | Gold-set sampler and evaluator (170 pairs, 102/68, calibration-only tuning); labeling guide |
| ER-P05-LABELUI | entity-resolution-engineer | Offline single-file HTML labeling tool |
| INF-P12-R09/R10/R11/R12 | infra-engineer | EMR launch path; us-east-2 state backend; ECS Fargate batch path; cost guardrails; Pass 8 and Pass 9 findings |

## 3. Enriched local run

Run base `rs-20261124-dblp10k-enriched`, in
`local-runs/real-10k-enriched/` (gitignored). Separate run ID per stage
(`-hadoop`, `-er`, `-spark`), which closes review finding N54 for this run.
Input: `datasets/derived/dblp/selection-10k.xml`, sha256 `ac743405…e567a`,
10,000 works, selection seed `scholarchain-dblp-2026-11-16`.

### 3.1 Hadoop (LocalJobRunner, `mapreduce.framework.name=local`)

| Dataset | In | Accepted | Rejected | Reject reasons |
|---|---|---|---|---|
| DBLP | 10,000 | 10,000 | 0 | — |
| Crossref | 42,910 files | 41,180 | 1,730 | MISSING_YEAR 1,648; MISSING_AUTHORS 78; MISSING_TITLE 4 |
| OpenAlex | 1,000 files, 49,510 works | 49,501 | 9 | MISSING_AUTHORS 7; MISSING_TITLE 2 |
| ORCID | 0 (not fetched) | — | — | Exits 3 with a clear message; writes nothing |

All three parsers had to be rewritten: they previously read only the flat test
fixtures, not the real API shapes (Crossref wraps the work in `message`;
OpenAlex returns a `results[]` array per chunk file). Real records are now in
the test fixtures.

**DOI linkage, from the J03 counters, DBLP denominator 10,000:**

| Match | DBLP works |
|---|---|
| Crossref | 8,257 |
| OpenAlex | 9,124 |
| Both | 8,203 |
| **Either** | **9,178** |

- 50,552 distinct DOI keys; 40,910 DOI groups hold both Crossref and OpenAlex.
- 116,815 distinct ORCIDs; 31,369 appear in records from more than one source.
- The `conflicts` counter of 73,254 counts identifier keys carried by more than
  one source dataset (41,885 DOI plus 31,369 ORCID). These are links, not
  data-quality conflicts. J03 sees only identifiers, so it cannot detect a
  semantically contradictory DOI; that needs the J05 title and year features.
- Largest blocking group at full scale: 2,119 members. 17 blocks exceeded the
  1,000 cap and 6,309 members were dropped, so full-scale blocking is lossy.

**Bound applied from J04 onward.** The full 100,681 records projected to about
48M pairs, 29 GB and 40 minutes, so J04 to J08 ran on all 10,000 DBLP works plus
every Crossref or OpenAlex work whose DOI sits in a J03 group that also holds a
DBLP id: 27,384 records. The normalizers and J03 ran on the FULL data, so the
numbers above are full-data numbers. Rule and manifest:
`hadoop/run-scripts/select_joined.py`, `hadoop/bounded/bound-manifest.json`.

Determinism: rerunning the Crossref (22 parts) and OpenAlex (13 parts)
normalizers produced byte-identical part files.

Timings: DBLP 3 s, Crossref 147 s, OpenAlex 11 s, J03 4 s, J04 16 s, J05 127 s,
J06 2 s, J07 3 s, J08 6 s. `ExportVerifier` exit 0.

### 3.2 Entity resolution

Policy `er-mention-v1-uncalibrated`, 22 s.

| Measure | DBLP only (10K) | Enriched |
|---|---|---|
| Work records | 10,000 | 27,384 |
| Author mentions | 38,995 | 106,204 |
| Candidate pairs | 178,655 | 811,109 |
| MATCH | 876 | 84,611 |
| NON_MATCH | — | 695,304 |
| REVIEW | — | 31,194 |

By path: exact_id 155,824; name_gate 534,425; deterministic_bibliographic
64,012; rejected 22,492; low_probabilistic 14,363; unresolved_band 11,381;
dblp_person_key 6,930; high_probabilistic 1,682.

The 155,824 exact-identifier decisions are DOI and ORCID evidence linking the
same work or person across three independent sources. **These are mostly
cross-source links, not newly merged DBLP homonyms.** Precision and recall are
NOT YET MEASURED; they require the human-labeled gold set.

### 3.3 Spark

131 s, 8 partitions, window 1980–2026, betweenness seed 42.

| Output | Count |
|---|---|
| Researcher nodes | 47,361 |
| Work nodes | 27,290 |
| COAUTHORED_WITH edges | 107,632 |
| AUTHORED edges | 105,946 |
| Metrics / components / communities rows | 47,361 |
| Summaries | 58,164 |
| Temporal rows | 49,536 |

- Provenance `sourceDataset = "crossref+dblp+openalex"` on researcher and edge
  records; ER linkage `verified`; policy version carried from the decisions.
- Betweenness: `sampled`, 2,000 sources, seed 42 (an explicitly documented
  scalable approximation, as the specification permits).
- k-core: converged in 58 iterations, maximum coreness 33.
- Label propagation: 10,798 communities, largest 52. **Did not converge** at
  either the default 20 or at 80 iterations; 9 of 47,361 vertices (0.019%)
  oscillate between tied labels. This is expected for label propagation on
  symmetric ties and is reported rather than suppressed. Both runs are kept:
  `spark/` (20 iterations) and `spark-lpa/` (80 iterations).

## 4. Verification

| Command | Result |
|---|---|
| `sbt -batch check` (scalafmt caches deleted first) | exit 0, 1,202 tests per pass, both passes, 0 failed |
| `/usr/bin/python3 -I -B -m unittest discover -s datasets/scripts/tests` | 64 tests OK |
| `/usr/bin/python3 -I -B datasets/scripts/validate_manifest.py` | `status=PASS pass=122 fail=0 skip=0`; all four datasets carry all 17 specification fields |
| `terraform fmt -check` / `validate` (root and bootstrap) | pass |
| `/bin/bash scripts/test-pipeline-lib.sh` (bash 3.2) | all passed, 113 checks |
| `trivy config` with the reviewed ignore file | 0 findings |

The scalafmt cache must be cleared before `check`: it silently skips newly added
files and produced three false greens during this phase.

## 5. Independent review

- Pass 8 (infrastructure): REQUEST_CHANGES, findings N65–N79.
- Pass 9 (infrastructure re-review): **APPROVE**, with follow-ups N80–N89 fixed
  in INF-P12-R12.
- Pass 10 (full integration): **NOT RUN.** The Opus weekly usage limit was
  reached on 2026-11-24 and resets 2026-11-27, after the submission deadline.
  This is recorded as a gap, not as an approval.

## 6. External API licenses (confirmed from the providers' documentation)

| Source | License | Cost | Documented limit |
|---|---|---|---|
| Crossref | Metadata almost entirely free of copyright, any purpose, no sign-up | $0 | None published; back off on 429 |
| OpenAlex | CC0 | $0 | 10 requests/second without a key |
| ORCID | Public Data File CC0; public record data licensed for commercial and non-commercial use | $0 | Anonymous tier 12/second, 25,000 reads/day per IP |
| DBLP | As recorded in the manifest | $0 | — |

ORCID's own pages disagree on whether an anonymous read of
`/v3.0/{iD}/record` needs a token. A single-record test settles it; a free
`/read-public` credential is the documented alternative.

## 7. Open items

- **ORCID records: not fetched.** The 2,000-iD list and the resumable fetch
  command are ready; the human runs it.
- **Gold set: not labeled.** 170 pairs sampled and split 102/68; the offline
  HTML labeling tool is at `modules/entity-resolution/tools/gold-label.html`.
  Entity-resolution precision, recall, F1 and the confusion matrix depend on it.
- **AWS: nothing deployed.** The Terraform state bucket does not exist, so no
  plan has run. Neptune, DynamoDB, S3 Vectors, the ECS services, the elasticity
  experiment and the cleanup evidence are all NOT YET RUN.
- **EMR denied.** The course organization's SCP denies `elasticmapreduce:*` and
  `emr-serverless:*`. Dev runs the same assembly jars as ECS Fargate tasks with
  the MapReduce local runner and Spark `local[*]`. EMR stays configurable. This
  is a documented response to an account restriction.
- Crossref records whose `issued` date is `[[null]]` (1,648) are rejected rather
  than given an invented year from the deposit date. This costs some links and
  is an open architect decision.
- Full-scale blocking drops 6,309 members from 17 oversize blocks.
- Label propagation leaves 9 vertices unstable (§3.3).
- Pass 10 review not performed (§5).
