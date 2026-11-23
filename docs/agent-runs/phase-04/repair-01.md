# Phase 04 – Repair Record 01: code-review fix round (RECONSTRUCTED 2026-11-23)

**Original finding.** Code-reviewer `aeb364a7c2e0f5db5` returned REQUEST_CHANGES (2 high, 4 medium, 8 low per the original review summary).

**Reproduction evidence.** Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, 2026-11-19T01:07–01:11Z; verbatim in `review-report.md`.

**Owner.** hadoop-engineer.

**Changes.** See the verbatim fix report below (findings H1–H2, M2–M4, L1–L3, L7).

**Rerun result.** The fix report states `sbt check` passed.

**Independent review outcome.** Code-reviewer `a886ef3d22c10054d` re-review: APPROVE. Note: the retro run of the committed tree fails the format check (test-report.md).


## Original subagent final reports (RECONSTRUCTED 2026-11-23)
Each report below is the subagent's final message, copied word for word from its transcript (`<session>/subagents/agent-<id>.jsonl`). Text above this section is the original file and is unchanged.

### hadoop-engineer fixes

hadoop-engineer subagent `a91965328c0209758`, session `42f04f0e-5685-4456-8160-45e1f9fb0fff`.

````text
`sbt check` passes. The `[Fatal Error]` on line 8 is from the DBLP XML test file parsing (not an actual error -- it's logged by the XML parser inside a test that tests malformed XML handling, and the test passes).

All checks pass. Let me summarize the changes.

```
STATUS: COMPLETE
OWNER: hadoop-engineer
WORK ORDER: Fix code-review findings H1, H2, M2, M3, M4, L1, L2, L3, L7 in modules/hadoop/
FILES CREATED:
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/GraphExportSpec.scala
FILES MODIFIED:
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/io/RejectWriter.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/DblpParser.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/CrossrefParser.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/parsers/OpenAlexParser.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/counters/HadoopCounters.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/util/NormalizationUtils.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/DblpExtractionJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/OpenAlexNormalizationJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/OrcidNormalizationJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/CrossrefNormalizationJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/TeamExtractionJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/GraphExportJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/FeatureConstructionJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/BlockingJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/IdentifierExtractionJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/main/scala/edu/scholarchain/hadoop/jobs/PairExpansionJob.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/DblpParserSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/CrossrefParserSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/parsers/OpenAlexParserSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/PairExpansionSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/jobs/TeamExtractionSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/util/NormalizationUtilsSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/hadoop/src/test/scala/edu/scholarchain/hadoop/integration/NormalizationIntegrationSpec.scala
COMMANDS RUN:
  sbt hadoop/compile
  sbt hadoop/test
  sbt check (x2)
TEST RESULTS: 122 hadoop tests passed, 97 core tests passed. sbt check succeeded.
VALIDATION RESULTS:
  H1: All 4 extraction/normalization mappers now instantiate MultipleOutputs in setup(), call RejectWriter.writeReject() on parse failures, and close in cleanup(). RejectWriter signature widened to MultipleOutputs[?, ?] for compatibility with both (Text,Text) and (Text,NullWritable) mapper outputs.
  H2: TeamExtractionJob.authorPaperJson() now emits provenance object with sourceDataset, sourceRecordId, pipelineRunId, schemaVersion, timestamp. Test confirms all fields present.
  M2: CrossrefParser and OpenAlexParser now return Left(("MISSING_AUTHORS", ...)) when authors list is empty or absent. Tests added for both parsers.
  M3: GraphExportJob.detectRecordType now uses ujson parsing instead of string.contains(). Detects all 8 types (work_records, author_mentions, teams, pair_candidates, author_paper, topics, institutions, other) plus "malformed" for unparseable JSON. 10 tests added in GraphExportSpec.
  M4: FeatureConstructionReducer checks block size against configurable max (default 1000) before O(n^2) pair generation. Oversize blocks are skipped with OVERSIZE_FEATURE_BLOCKS counter increment and warning log.
  L1: DblpParser now returns record type as third tuple element. DblpExtractionJob.counterForRecordType maps all 7 DBLP types (article, inproceedings, proceedings, book, incollection, phdthesis, mastersthesis) to their counter names. 6 new tests cover the record types.
  L2: PairExpansionSpec now includes a 100-author test verifying 4950 pairs with canonical ordering.
  L3: NormalizationUtilsSpec now tests Unicode diacritics: Spanish (Jose Garcia), French/German (Francois Muller), Polish (Lukasz Wojcik). NormalizationUtils improved with NFKD combining-mark stripping and transliteration table for non-decomposing characters (L-stroke, D-stroke, O-stroke, Eth).
  L7: Gzip compression configured in all 9 Tool classes (DblpExtraction, OpenAlexNormalization, OrcidNormalization, CrossrefNormalization, TeamExtraction, Blocking, IdentifierExtraction, FeatureConstruction, PairExpansion). GraphExportTool already had it.
ASSUMPTIONS:
  - RejectWriter MultipleOutputs[?, ?] wildcard type works with Hadoop's independently-parameterized write(namedOutput, K, V) method in Scala 3.
  - Best-effort source ID extraction (regex for XML, ujson parse for JSON) in mapper reject paths is acceptable for cases where the full parser failed.
  - The NFKD + combining mark removal + transliteration table approach handles the specified Unicode test cases correctly.
RISKS:
  - The normalizeVenue function now also strips diacritics, which changes existing venue normalization behavior. Existing venue-based blocking keys will differ from prior runs if venues contained diacritics.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: local or EMR sample run and deterministic output checks
```
````
