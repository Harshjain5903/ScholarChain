# Phase 4 Agent Report
STATUS: COMPLETE
OWNER: hadoop-engineer
FILES CREATED: 35 (10 jobs, 4 parsers, 4 I/O classes, 3 utilities, 2 models, 11 test suites, 1 test resource)
TESTS: 219 passing (97 core + 122 hadoop)
COMMANDS RUN: sbt check — PASS
KEY DECISIONS: Custom XmlRecordReader for DBLP record boundaries, hadoop-local MatchCandidate type, pair canonicalization via core CoauthorPairCandidate, gzip intermediate compression, configurable max-block-size safety valve (default 1000)


## Original subagent final reports (RECONSTRUCTED 2026-11-23)
Each report below is the subagent's final message, copied word for word from its transcript (`<session>/subagents/agent-<id>.jsonl`). Text above this section is the original file and is unchanged.

### hadoop-engineer

hadoop-engineer subagent `a1e1ad5899ab3970f`, session `42f04f0e-5685-4456-8160-45e1f9fb0fff`.

````text
All 193 tests pass (96 hadoop + 97 core). The `sbt check` alias fails on a pre-existing scalafmt issue in the core module (owned by domain-engineer), not on any hadoop code.

---

```
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Implement ScholarChain Hadoop MapReduce pipeline (jobs J01-J08, parsers, utilities, counters, tests)
FILES CREATED:
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/util/NormalizationUtils.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/counters/HadoopCounters.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/model/RejectRecord.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/model/MatchCandidate.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/config/JobConfig.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/io/XmlRecordReader.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/io/DblpInputFormat.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/io/JsonLineInputFormat.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/io/RejectWriter.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/DblpParser.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/OpenAlexParser.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/OrcidParser.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/CrossrefParser.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/DblpExtractionJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/OpenAlexNormalizationJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/OrcidNormalizationJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/CrossrefNormalizationJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/IdentifierExtractionJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/BlockingJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/FeatureConstructionJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/TeamExtractionJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/PairExpansionJob.scala
  modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/GraphExportJob.scala
  modules/hadoop/src/test/resources/dblp-test.xml
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/util/NormalizationUtilsSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/DblpParserSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/OpenAlexParserSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/OrcidParserSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/CrossrefParserSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/IdentifierExtractionSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/BlockingKeySpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/FeatureComputationSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/TeamExtractionSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/PairExpansionSpec.scala
  modules/hadoop/src/test/scala/edu/scholarchain/hadoop/integration/NormalizationIntegrationSpec.scala
FILES MODIFIED:
  modules/hadoop/src/main/scala/.gitkeep (deleted)
  modules/hadoop/src/test/scala/.gitkeep (deleted)
COMMANDS RUN:
  sbt hadoop/compile (success)
  sbt hadoop/test (96 tests, all passed)
  sbt hadoop/scalafmtCheck, hadoop/Test/scalafmtCheck (success)
  sbt test (193 tests total, all passed)
  sbt check (failed on pre-existing core module scalafmt issues, not hadoop)
TEST RESULTS: 96 hadoop tests passed (11 suites, 0 failures). 97 core tests also pass. Full sbt check blocked by pre-existing scalafmt violations in modules/core (domain-engineer ownership).
VALIDATION RESULTS:
  - hadoop/compile: success
  - hadoop/test: 96/96 passed
  - hadoop/scalafmtCheck: success
  - All file changes within modules/hadoop/** boundary
ASSUMPTIONS:
  - Used Java DOM parser (javax.xml.parsers) for DBLP XML since scala-xml is not a declared dependency
  - JSON serialization between MapReduce stages uses upickle/ujson via Text writables
  - MatchCandidate is a hadoop-module-local intermediate type (not in core) since it is pre-decision
  - Sample JSON files are used for integration tests via inline JSON matching the sample schemas
  - The J09 "graph candidate export" is covered by J08 GraphExportJob (the work order lists 8 jobs)
RISKS:
  - sbt check cannot pass due to pre-existing scalafmt violations in core module. This is not caused by hadoop changes.
  - XmlRecordReader is not tested end-to-end with actual HDFS (would need hadoop-minicluster dependency for that)
  - Hadoop Mapper/Reducer classes tested indirectly through pure-function parsing logic, not through simulated MR execution
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: domain-engineer (pre-existing scalafmt violations in modules/core block sbt check)
BLOCKED-ON EVIDENCE: sbt check output: "scalafmt: 11 files must be formatted (/Users/harshjain/Documents/scholarchain/modules/core)" -- errors in EntityResolution.scala, Graph.scala, IdGen.scala, Author.scala, AppConfig.scala, Provenance.scala, Enums.scala, Query.scala, Publication.scala, Collaboration.scala, Evidence.scala, GoldenFileSpec.scala, TestFixtures.scala
NEXT GATE: Fix core scalafmt issues (domain-engineer), then sbt check passes; then local or EMR sample run with deterministic output checks
```

## Design Report

### Job Graph (data flow)

```
Raw DBLP XML -----> J01 DblpExtractionJob --------\
OpenAlex JSON ----> J02a OpenAlexNormalizationJob --+--> Normalized WorkRecords
ORCID JSON -------> J02b OrcidNormalizationJob -----+    + AuthorMentions
Crossref JSON ----> J02c CrossrefNormalizationJob --/

Normalized Records --> J03 IdentifierExtractionJob --> Identifier Index
Normalized Records --> J04 BlockingJob --> Blocked Groups
Blocked Groups ------> J05 FeatureConstructionJob --> MatchCandidates
Normalized Records --> J06 TeamExtractionJob --> PublicationTeams + AuthorPaperRecords
PublicationTeams ----> J07 PairExpansionJob --> CoauthorPairCandidates

All outputs ---------> J08 GraphExportJob --> Partitioned gzip JSON for Spark
```

### Mapper/Reducer Key-Value Contracts

| Job | Mapper K/V In | Mapper K/V Out | Reducer K/V Out |
|-----|--------------|----------------|-----------------|
| J01 DblpExtraction | LongWritable/Text(xml) | Text(srcId)/Text(json) | Text(json)/NullWritable |
| J02a-c Normalization | LongWritable/Text(json) | Text(json)/NullWritable | (map-only) |
| J03 IdentifierExtraction | LongWritable/Text(json) | Text(id_key)/Text(srcId) | Text(id_key)/Text(srcIds) |
| J04 Blocking | LongWritable/Text(json) | Text(blockKey)/Text(json) | Text(blockKey)/Text(tab-joined) |
| J05 FeatureConstruction | LongWritable/Text(blocked) | Text(blockKey)/Text(json) | Text(candidate_json)/NullWritable |
| J06 TeamExtraction | LongWritable/Text(json) | Text(json)/NullWritable | (map-only) |
| J07 PairExpansion | LongWritable/Text(json) | Text(json)/NullWritable | (map-only) |
| J08 GraphExport | LongWritable/Text(json) | Text(json)/NullWritable via MultipleOutputs | (map-only) |

### Partition Strategy (GraphExportJob)
- work_records: by source type (DBLP, OpenAlex, Crossref, ORCID)
- author_mentions: by source type
- teams: by year decade
- pair_candidates: by year decade
- Output format: gzip-compressed JSON lines

### Counter Categories
- **ScholarChain** (shared): input_records, accepted, rejected, missing_required_fields
- **DblpExtraction**: article_count, inproceedings_count, proceedings_count, book_count, etc.
- **IdentifierExtraction**: total_identifiers, duplicates, conflicts, missing_keys
- **Blocking**: total_blocks, oversize_blocks, records_in_blocks
- **FeatureConstruction**: pairs_compared, features_computed
- **TeamExtraction**: total_teams, total_author_paper_records, large_teams
- **PairExpansion**: total_pairs_emitted, papers_processed, large_team_expansions, skipped_singleton_teams
- **GraphExport**: work_records_out, author_mentions_out, teams_out, pair_candidates_out

### Reject Record Format
JSON with fields: sourceRecordId, reasonCode, reasonMessage, originalContent (truncated to 4096 chars), jobName, provenance. Written via MultipleOutputs to "rejects" named output.

### Output Path Convention
`{output_base}/run_id={run_id}/snapshot_date={date}/{job_name}/`

### Test Count: 96 tests across 11 suites
- NormalizationUtilsSpec: 16 tests
- DblpParserSpec: 7 tests
- OpenAlexParserSpec: 6 tests
- OrcidParserSpec: 5 tests
- CrossrefParserSpec: 5 tests
- IdentifierExtractionSpec: 6 tests
- BlockingKeySpec: 7 tests
- FeatureComputationSpec: 11 tests
- TeamExtractionSpec: 4 tests
- PairExpansionSpec: 8 tests
- NormalizationIntegrationSpec: 8 tests (end-to-end + reject handling)

### No Missing Dependencies
All code compiles against existing dependencies (hadoop-client provided, upickle, typesafe-config, scalatest). No build changes needed.
````
