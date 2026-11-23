# Phase 4 Work Order
Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline with 8 jobs: DBLP extraction, OpenAlex/ORCID/Crossref normalization, identifier extraction, blocking, feature construction, team extraction, coauthor pair expansion, and graph export preparation.


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order to hadoop-engineer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `56b42231-93db-4887-990c-eaa0c2dbb543`, sent 2026-11-18T21:58:00.122Z.

````text
Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline.

Required jobs:

1. DBLP record extraction
- Read complete DBLP publication records without splitting an XML record across mappers.
- Support article, inproceedings, proceedings, book, incollection, phdthesis, mastersthesis record types.
- Extract DBLP key, title, author sequence, year, venue fields, ee links, URLs, and record type.
- Emit valid normalized WorkRecord and AuthorMention records using the Phase 3 domain contracts.

2. External source normalization
- Implement one normalizer each for OpenAlex, ORCID, and Crossref JSON.
- Map source fields into shared WorkRecord and AuthorMention contracts without losing source-specific IDs.
- Preserve author identifiers (ORCID, OpenAlex ID), affiliations, topic identifiers, abstracts, and work identifiers when available.
- Write malformed and unsupported records to versioned reject outputs.

3. Exact identifier extraction and join preparation
- Normalize DOI (lowercase, trim), arXiv ID, ORCID, and OpenAlex ID.
- Emit exact-identifier keys and duplicate/conflict counters.

4. Researcher and work blocking
- Emit bounded candidate blocks using normalized names, initials, coauthor context, title, year, and venue.
- Prevent pathological common-name or title blocks from overwhelming reducers using a configurable maximum block size with counters for oversized groups.

5. Candidate feature construction
- Join records within blocks.
- Compute deterministic match features: title similarity (Jaccard on normalized tokens), author-name similarity, year distance, venue match, coauthor overlap count.
- Emit MatchCandidate records rather than final decisions.

6. Publication team extraction
- Emit one PublicationTeam record per retained work containing ordered author mentions.
- Emit author-paper records keyed by author mention and work ID.
- Count and report unusually large author teams (configurable threshold, default 50).

7. Coauthor pair expansion
- For each paper with n authors, generate n*(n-1)/2 candidate unordered coauthor pairs.
- Use the Phase 3 pair canonicalization (mentionA < mentionB lexicographically).
- Preserve work ID, year, author positions, and source provenance on every pair record.
- Hadoop counters for total pairs emitted and papers exceeding the large-team threshold.

8. Graph export preparation
- Emit partitioned work, author-mention, team, pair-candidate, topic, institution, and enrichment files ready for Spark consumption as Parquet or JSON.

Required operational behavior:
- Hadoop counters for: input records, accepted, rejected, duplicates, conflicts, oversize blocks, oversize author teams, emitted pairs, missing keys, and schema failures.
- Explicit partition strategy documented.
- Idempotent output paths containing run ID and dataset snapshot date.
- No mutable shared state across map or reduce tasks.
- Compression (gzip or snappy) for intermediate outputs.
- Deterministic outputs for deterministic inputs.
- Unit tests for every parser, pair generation logic, normalization function, and key function.
- Integration tests using the Phase 3 sample files under datasets/samples/.
- All code under modules/hadoop/src/main/scala/edu/scholarchain/hadoop/ and tests under modules/hadoop/src/test/scala/edu/scholarchain/hadoop/.

After implementation, run sbt check.
Report: job graph, mapper/reducer key-value contracts, partition counts, counters, rejected records, output paths, maximum observed team size, largest pair expansion, and the largest observed blocking group.
````

### Review order to code-reviewer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `053af8dd-7dcc-4479-a5d8-c0de0c4c6f8c`, sent 2026-11-19T01:07:12.400Z.

````text
Use the code-reviewer agent to perform a read-only review of the Phase 4 Hadoop MapReduce implementation.

Review scope: all files under modules/hadoop/ plus any changes to modules/core/ or build.sbt made in this phase.

Review in this order:
1. Correctness: XML record boundary handling, parser edge cases, pair generation math, normalization logic.
2. Contract compliance: do Hadoop outputs match the Phase 3 domain contracts (WorkRecord, AuthorMention, CoauthorPairCandidate, PublicationTeam)?
3. Artifact ownership: hadoop-engineer owns modules/hadoop/**. Verify no files were created outside that boundary except justified shared changes.
4. Distribution correctness: no mutable shared state across mappers/reducers, no driver-side collection of full datasets.
5. Counter and reject handling: are all required counters present? Are malformed records written to reject outputs?
6. Idempotency: are output paths deterministic given the same run ID and snapshot date?
7. Test quality: do tests cover edge cases (empty records, missing fields, Unicode, giant author lists, duplicate DOIs, single-author papers producing zero pairs)?
8. Security: no hardcoded paths, buckets, endpoints, or credentials.

For each finding provide severity (critical/high/medium/low), file and line, concrete failure input, expected impact, and owning agent.
Return APPROVE or REQUEST_CHANGES. Do not edit any file.
````

### Fix order to hadoop-engineer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `6c7aaa52-5a7f-4a9e-91cd-5e4886f78926`, sent 2026-11-19T01:13:14.779Z.

````text
Use the hadoop-engineer agent to fix the following code-review findings in the Phase 4 Hadoop MapReduce implementation. Do not modify any files outside modules/hadoop/.

## HIGH fixes

H1: Reject records are never written
- In each of the 4 extraction/normalization job mappers (DblpExtractionJob, OpenAlexNormalizationJob, OrcidNormalizationJob, CrossrefNormalizationJob), instantiate RejectWriter and call it for malformed or unparseable records.
- Each mapper's setup() should initialize the MultipleOutputs instance, map() should write rejects, and cleanup() should close it.
- Add a counter for rejected records per source.

H2: Author-paper records lack provenance
- In TeamExtractionJob, add provenance fields (runId, schemaVersion, source, timestamp) to the author-paper JSON output so it matches the project's provenance requirement.

## MEDIUM fixes

M2: Zero-author record handling inconsistency
- In CrossrefParser and OpenAlexParser, reject records with zero authors (emit to reject output with reason "no_authors") to match DBLP behavior.
- Add counter increment for rejected zero-author records.

M3: GraphExportJob fragile type detection
- Replace string-contains detection with proper JSON field-based detection (parse the JSON, check for distinguishing fields like "authorMentions" for teams, "mentionA" for pairs, etc.).

M4: FeatureConstructionReducer memory concern
- Add a configurable max-block-size safety valve (default 1000). If a block exceeds this, emit a counter increment for OVERSIZE_FEATURE_BLOCK and skip the block rather than OOM-ing.

## LOW fixes (address these too)

L1: Add DBLP record type counters for all 7 types (article, inproceedings, proceedings, book, incollection, phdthesis, mastersthesis).

L2: Add a test for a paper with 100+ authors verifying correct pair count (n*(n-1)/2).

L3: Add Unicode diacritics test cases to NormalizationUtilsSpec (e.g., "José García" → normalized form, "François Müller" → normalized).

L7: Enable compression (gzip) for intermediate job outputs, not just GraphExport.

After all fixes, run sbt check and report the full test count and any failures.
````

### Re-review order

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `82729cc1-4f9d-48e4-aeac-c5b772fd355b`, sent 2026-11-19T01:30:26.401Z.

````text
Use the code-reviewer agent to re-review the Phase 4 Hadoop MapReduce implementation after fixes.

Verify all 9 findings are fixed:
- H1: RejectWriter now wired in all 4 extraction mappers
- H2: Author-paper records include provenance
- M2: Zero-author records rejected in Crossref and OpenAlex parsers
- M3: GraphExportJob uses JSON parsing not string.contains
- M4: FeatureConstructionReducer has max-block-size safety valve
- L1: All 7 DBLP record type counters
- L2: 100+ author pair count test exists
- L3: Unicode diacritics normalization tests exist
- L7: Gzip compression on intermediate outputs

Check for any new issues introduced by the fixes.
Return APPROVE or REQUEST_CHANGES.
````

### Evidence instruction to the orchestrator

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `ca31d0c4-289a-48c6-aac6-fe3923f0717e`, sent 2026-11-19T01:46:17.926Z.

````text
Create the directory docs/agent-runs/phase-04/ and these 4 files:

1. docs/agent-runs/phase-04/prompt.md — content:
# Phase 4 Work Order
Use the hadoop-engineer agent to implement the ScholarChain Hadoop MapReduce pipeline with 8 jobs: DBLP extraction, OpenAlex/ORCID/Crossref normalization, identifier extraction, blocking, feature construction, team extraction, coauthor pair expansion, and graph export preparation.

2. docs/agent-runs/phase-04/agent-report.md — content:
# Phase 4 Agent Report
STATUS: COMPLETE
OWNER: hadoop-engineer
FILES CREATED: 35 (10 jobs, 4 parsers, 4 I/O classes, 3 utilities, 2 models, 11 test suites, 1 test resource)
TESTS: 219 passing (97 core + 122 hadoop)
COMMANDS RUN: sbt check — PASS
KEY DECISIONS: Custom XmlRecordReader for DBLP record boundaries, hadoop-local MatchCandidate type, pair canonicalization via core CoauthorPairCandidate, gzip intermediate compression, configurable max-block-size safety valve (default 1000)

3. docs/agent-runs/phase-04/review-report.md — content:
# Phase 4 Code Review
INITIAL VERDICT: REQUEST_CHANGES (2 high, 4 medium, 8 low)
RE-REVIEW VERDICT: APPROVE — all 9 findings verified fixed
Key fixes: RejectWriter wired into all mappers, author-paper provenance added, zero-author rejection, field-based JSON parsing in GraphExportJob, block-size safety valve, all 7 DBLP type counters, 100-author pair expansion test, Unicode diacritics normalization

4. docs/agent-runs/phase-04/human-gate.md — content:
# Phase 4 Human Gate
DECISION: ACCEPTED
REVIEWED: All 35 Hadoop files, 219 tests passing, code review APPROVE
COMMIT: ab38367
MANUAL CHANGES: None. Authorized hadoop-engineer to reformat 13 core files (ownership exception documented).
RISK: normalizeVenue now strips diacritics — changes venue-based blocking keys from prior runs if venues contained diacritics.
````
