# ScholarChain Data Lineage

Owner: `documentation-engineer`. This document follows one record from a source file to the Neptune bulk-load CSV and the Parquet outputs. Directory names, job names and provenance values come from the code cited in each section.

The record shown at each stage comes from a local replay on the committed sample [datasets/samples/dblp/dblp-sample-50.xml](../datasets/samples/dblp/dblp-sample-50.xml), using the commands in the [README](../README.md#bounded-local-real-data-run-hadoop--entity-resolution--spark). The replay used run IDs `doc-replay-hadoop`, `doc-replay-er` and `doc-replay-spark` and timestamp `2026-11-23T00:00:00Z`. Its output stayed in a session scratchpad and is not committed.

## 1. The provenance contract

[Provenance.scala](../modules/core/src/main/scala/edu/scholarchain/core/Provenance.scala) (`edu.scholarchain.core.Provenance`) defines these fields:

| Field | Type | Meaning |
|---|---|---|
| `sourceDataset` | String (non-empty) | Upstream dataset, e.g. `dblp` |
| `sourceRecordId` | String | ID of the source record, or of the output record it describes |
| `pipelineRunId` | String (non-empty) | Run that produced this record |
| `schemaVersion` | String (non-empty) | e.g. `1.0.0` |
| `timestamp` | String | Wall-clock time, or the fixed run timestamp |
| `transformationStep` | Option[String] | Step that produced the record |
| `producer` | Option[String] | Component that produced the record |
| `upstreamRunIds` | Vector[String] | Sorted, distinct run IDs of the inputs |
| `policyVersion` | Option[String] | Entity-resolution policy that the record depends on |

How each stage fills the last four fields:

| Stage | `transformationStep` | `producer` | `upstreamRunIds` | `policyVersion` |
|---|---|---|---|---|
| Hadoop ([HadoopLineage.scala](../modules/hadoop/src/main/scala/edu/scholarchain/hadoop/provenance/HadoopLineage.scala)) | job name, e.g. `pair-expansion` | `hadoop/<jobId>-<jobName>`, e.g. `hadoop/J07-pair-expansion` | input records' `pipelineRunId`s | not set |
| Entity resolution ([EntityResolutionRunner.scala](../modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/EntityResolutionRunner.scala)) | – (MatchDecision has no Provenance object) | `entity-resolution-engineer/EntityResolutionRunner` in `RUN-SUMMARY.json` | `input_run:<hadoop run id>` reason code on every decision | `policyVersion` field on every decision: `er-mention-v1-uncalibrated` |
| Spark ([Lineage.scala](../modules/spark/src/main/scala/edu/scholarchain/spark/Lineage.scala)) | step within the job, e.g. `coauthor-edge-aggregation` | `spark-analytics/<Job>`, e.g. `spark-analytics/CanonicalGraphBuilder` | Hadoop and ER run IDs read from the inputs | read from the match decisions |

## 2. End-to-end lineage

```text
SOURCE
  DBLP XML  (datasets/raw/dblp/snapshot=2026-11-16/dblp.xml.gz -> dblp_select.py
             -> datasets/derived/dblp/selection-{10k,50k}.xml; or the committed 50-record sample)
  Crossref / OpenAlex / ORCID JSON  (fetched by enrich_fetch.py; NOT used by the bounded run)
     |
     v
HADOOP  (HadoopJobDriver, -D scholarchain.pipeline.run-id=<H>, -D scholarchain.run.timestamp=<T>)
  J01  dblp-extraction        producer hadoop/J01-dblp-extraction      -> stages/j01-work_records/
                                                                           part-*, _rejects/
  J06  team-extraction        producer hadoop/J06-team-extraction      -> stages/j06-teams/
  J07  pair-expansion         producer hadoop/J07-pair-expansion       -> stages/j07-pair_candidates/
  J08  graph-export (J01,J06,J07 inputs; routes lines by JSON shape)   -> export/work_records/
                                                                           export/teams/
                                                                           export/pair_candidates/
                                                                           export/author_paper/
                                       (also defined: author_mentions/, topics/, institutions/)
  J03  identifier-extraction                                           -> stages/j03-identifiers/
  J04  blocking                                                        -> stages/j04-blocks/
  J05  feature-construction   producer hadoop/J05-feature-construction -> stages/j05-features/
  ExportVerifier export/   (work_records, teams, pair_candidates must parse as core types)
     |
     | export/work_records
     v
ENTITY RESOLUTION  (EntityResolutionRunner --run-id <E> --input-run-id <H>)
  er/match-decisions/part-00000   MatchDecision JSON lines
                                  (policyVersion, runId=<E>, reasonCodes incl. input_run:<H>)
  er/review-queue.json            Unresolved / Conflict / low-confidence decisions
  er/RUN-SUMMARY.json             counts, policy, producer, sha256 of every file
     |
     | export/{pair_candidates,teams,work_records} + er/match-decisions
     v
SPARK  (ScholarChainPipeline --run-id <S>)
  Input lineage check before any write: one ER policy across all decisions; every decision's
  input_run:<H> must match the pair candidates' pipelineRunId (else fail, unless
  --allow-unlinked-er-input true)
  Parquet (S3 target)                            Neptune openCypher CSV (Neptune target)
    edges/        partitioned by decade            neptune/nodes/researcher/
    metrics/      (manifest target: DynamoDB)      neptune/nodes/work/
    summaries/    partitioned by entityType        neptune/relationships/coauthor/  (COAUTHORED_WITH)
                  (manifest target: S3 Vectors)    neptune/relationships/authored/  (AUTHORED)
    paths/  components/  communities/  temporal/
  manifest/   run manifest: code SHA and dirty flag, input sha256s, argv, config,
              upstream run IDs, ER policy, methods, counts
  run-stats/  run statistics JSON
```

The Hadoop outputs J03–J05 are written but are **not read** by `EntityResolutionRunner` or `ScholarChainPipeline`. The runner blocks author mentions itself, on surname and first initial, from `work_records`. This is documented in the scaladoc of `EntityResolutionRunner` and `ScholarChainPipeline`.

## 3. Spark transformation steps

These are taken from the `lineage.prov(...)` call sites in `modules/spark/src/main`.

| Producer (`spark-analytics/<Job>`) | `transformationStep` |
|---|---|
| `CanonicalGraphBuilder` | edge step. The replay shows `coauthor-edge-aggregation`. |
| `CollaborationMetricsJob` | `researcher-metrics` |
| `TemporalAnalysisJob` | `temporal-window-snapshot` |
| `SemanticContextJob` | `researcher-profile-summary`, `community-summary`, `collaboration-path-summary` |
| `ShortestPathJob` (stamped in `GraphExportWriter`) | `shortest-path-evidence` |
| `NeptuneBulkLoadExport` | `authorship-export`, `work-vertex-export` |

## 4. One record, traced (observed in the sample replay)

The Hadoop J07 pair candidate in `export/pair_candidates/` carries this provenance:

```json
{"sourceDataset":"dblp","sourceRecordId":"dblp:conf/acit2/KawaiON15","pipelineRunId":"doc-replay-hadoop",
 "schemaVersion":"1.0.0","timestamp":"2026-11-23T00:00:00Z","transformationStep":"pair-expansion",
 "producer":"hadoop/J07-pair-expansion","upstreamRunIds":["doc-replay-hadoop"]}
```

An entity-resolution decision in `er/match-decisions/part-00000` has these fields (others omitted):

```json
{"decision":"Unresolved","reasonCodes":["block:name:malik:j","input_run:doc-replay-hadoop","score:0.4023",
 "path:low_probabilistic","llm_disabled"],"policyVersion":"er-mention-v1-uncalibrated","runId":"doc-replay-er"}
```

The Neptune `COAUTHORED_WITH` row in `neptune/relationships/coauthor/*.csv` has this `provenance:String` column:

```json
{"sourceDataset":"dblp","sourceRecordId":"coauthor-edge:004b6f9b…","pipelineRunId":"doc-replay-spark",
 "schemaVersion":"1.0.0","timestamp":"2026-11-23T00:00:00Z","transformationStep":"coauthor-edge-aggregation",
 "producer":"spark-analytics/CanonicalGraphBuilder","upstreamRunIds":["doc-replay-er","doc-replay-hadoop"],
 "policyVersion":"er-mention-v1-uncalibrated"}
```

The same CSV row stores the representative work ID as `work:dblp:dblp:journals/corr/abs-2603-21466`. That doubled prefix is open finding N50. See the README section "Known limitations".

## 5. Gaps

- The enrichment sources (Crossref, OpenAlex, ORCID) have normalization jobs J02a–c, but they are not part of the bounded chain. No enrichment record reaches entity resolution or Spark yet.
- Loading the Neptune CSV, the DynamoDB metrics and the S3 Vectors summaries into AWS is **NOT YET RUN**.
- Archived real-data evidence covers a 500-record development slice: [phase-12/repair-08.md](agent-runs/phase-12/repair-08.md) §4.2. That run reused a single run ID across stages (finding N54), which is why the README commands give each stage its own run ID.
