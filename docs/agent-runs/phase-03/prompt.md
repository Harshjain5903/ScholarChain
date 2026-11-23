# Phase 3 Work Orders

## Part 1: Dataset-Engineer
Create datasets/dataset-manifest.yaml for DBLP, OpenAlex, ORCID, and Crossref with all 20 required fields. Create bounded download scripts, schema docs, and sample JSON files.

## Part 2: Domain-Engineer
Create immutable Scala domain records (27 types), 6 enums, stable ID functions, config loader with HOCON, uPickle serialization, and contract tests with golden files.

## Fix Round
Fixed 4 high, 6 medium, 4 low code-review findings: added provenance to ResearcherMetrics, require guards on case class bodies for validation, config error tests, python3 -I flags, cross-dataset DOI link, Crossref license fix.


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order to dataset-engineer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `ecab52e4-7972-496b-92d6-e74dfad8fc86`, sent 2026-11-18T01:11:22.515Z.

````text
Use the dataset-engineer agent to create the ScholarChain dataset manifest and supporting files.

Create:

1. datasets/dataset-manifest.yaml for these datasets:
   - DBLP (required seed) — source: https://dblp.org/xml/dblp.xml.gz
   - OpenAlex — source: https://help.openalex.org/tutorials/download-the-snapshot/
   - ORCID — source: https://info.orcid.org/documentation/integration-guide/working-with-bulk-data/
   - Crossref — source: https://www.crossref.org/services/metadata-retrieval/public-data-file/

   Each entry must include: name, purpose, source_url, documentation links, license/reuse terms, snapshot_date placeholder, retrieval command or script reference, format, expected size estimate, S3 raw prefix (s3://scholarchain-raw/<dataset>/snapshot_date=YYYY-MM-DD/), SHA-256 checksum placeholder, schema version, selected subset rule for bounded testing, incremental update strategy, linkage fields (DOI, title, author, year, venue, ORCID, OpenAlex ID as applicable), personal data and privacy notes, known limitations, validation commands, and owning agent (dataset-engineer).

2. datasets/scripts/download-dblp-sample.sh — bounded download script that fetches only a small sample (first N records or head of the XML). Must be idempotent, use bash strict mode, and NOT download the full corpus by default.

3. datasets/scripts/download-openalex-sample.sh — similar bounded sample script for OpenAlex.

4. datasets/scripts/download-orcid-sample.sh — similar for ORCID.

5. datasets/scripts/download-crossref-sample.sh — similar for Crossref.

6. datasets/schemas/ — create schema notes for each dataset describing the key fields, record types, and how they map to ScholarChain's domain model.

7. datasets/samples/ — create small representative sample files (hand-written valid examples, 3-5 records each) showing what normalized output looks like for each dataset. Use realistic but fictional data.

All scripts must be executable (chmod +x). No script should silently fetch large files. Each must print what it's doing and validate downloads.

Run validation: bash -n on all scripts, check YAML syntax on the manifest.

Report: manifest structure, linkage field matrix across datasets, known gaps, and sample file locations.
````

### Work order to domain-engineer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `dbe8e188-86df-4213-8b7c-082b32f3f276`, sent 2026-11-18T01:21:59.202Z.

````text
Use the domain-engineer agent to create the ScholarChain shared domain contracts, stable ID functions, configuration, and contract tests in modules/core/.

Create under modules/core/src/main/scala/edu/scholarchain/core/:

1. Domain records (immutable case classes):
   - WorkRecord (workId, source, sourceRecordId, title, normalizedTitle, year, venueId, doi, arxivId, authors: Vector[AuthorMention], abstractTextRef, provenance)
   - AuthorMention (mentionId, rawName, normalizedName, position, orcid, openAlexId, affiliationRaw, sourceRecordId, provenance)
   - CanonicalResearcher (researcherId, displayName, orcid, openAlexId, semanticScholarId, alternateNames, provenance)
   - AuthorshipRecord (researcherId, workId, authorPosition, confidence, provenance)
   - PublicationTeam (workId, year, authorMentions: Vector[AuthorMention], teamSize, provenance)
   - CoauthorPairCandidate (mentionA, mentionB, workId, year, provenance)
   - CoauthorEdge (researcherA, researcherB, jointPublicationCount, firstCollaborationYear, lastCollaborationYear, representativeWorkIds, provenance)
   - ResearcherMetrics (researcherId, publicationCount, distinctCoauthorCount, totalCoauthorOccurrences, averageCoauthorsPerPaper, maximumCoauthorsOnOnePaper, weightedDegree, componentId, communityId, bridgeScore, coreness, windowStartYear, windowEndYear)
   - Venue, Institution, Topic (with IDs, names, source, provenance)
   - Provenance (sourceDataset, sourceRecordId, pipelineRunId, schemaVersion, timestamp)
   - MatchDecision (sourceId, targetId, features: Map[String,Double], decision: MatchOutcome, confidence, reasonCodes, policyVersion, runId)
   - GraphNode, GraphEdge (with typed IDs, properties maps, provenance)
   - EvidenceItem, EvidenceBundle
   - QueryPlan, AnswerClaim, AnswerResponse

2. Enums:
   - SourceType (DBLP, OpenAlex, ORCID, Crossref, etc.)
   - MatchOutcome (Accepted, Rejected, Unresolved, Conflict)
   - EvidenceType (TextChunk, GraphPath, MetricRecord, MatchRecord)
   - CollaborationMetric (DistinctCoauthorCount, WeightedDegree, PublicationCount, etc.)
   - QueryOperation (ResearcherRanking, ShortestPath, ComponentLookup, CommunityComparison, BridgeRanking, TemporalComparison, TopicFilter, InstitutionFilter)
   - VerificationStatus (Verified, PartiallySupportd, Unsupported, Conflicting)

3. Stable ID functions in an IdGen object:
   - workId: namespace + source ID
   - researcherId: canonical external ID when validated, else versioned ScholarChain ID
   - coauthorEdgeId: hash of ordered researcher IDs + window + policy version
   - chunkId: hash of entity ID + source version + doc type + text hash
   - runId: unique per pipeline run

4. Pair canonicalization: CoauthorPairCandidate must enforce researcherA < researcherB lexicographically so each undirected pair has one identity.

5. Configuration loader in AppConfig:
   - Load from environment variables + HOCON (application.conf)
   - No hardcoded bucket names, endpoints, thresholds, model IDs, account IDs
   - Sections for storage, neptune, s3Vectors, entityResolution, retrieval, models, operations

6. JSON serialization using uPickle:
   - ReadWriter instances for all domain records
   - Schema version field on serialized records

Create under modules/core/src/test/scala/edu/scholarchain/core/:

7. Contract tests:
   - Serialization round-trip tests for every domain record
   - Stable ID determinism tests
   - Pair ordering tests (A,B same as B,A)
   - Time window validation (startYear <= endYear)
   - Provenance required (non-empty) tests
   - Invalid confidence range rejection (must be 0.0 to 1.0)
   - Schema version presence tests
   - Freeze at least 3 representative JSON wire examples as golden files under src/test/resources/

Run sbt check.

Report: list of all records created, stable ID formulas, pair canonicalization rule, configuration sections, schema versions, test count and results, and any design decisions made.
````

### Review order to code-reviewer

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `769f8b5c-5f8f-4898-975a-9f4e3f8318d9`, sent 2026-11-18T17:14:55.869Z.

````text
Use the code-reviewer agent to review the complete Phase 3 diff (dataset-engineer + domain-engineer work). Run git diff HEAD~1 to see all changes. Review for: correctness, contract completeness, schema drift risks, serialization safety, stable ID determinism, pair canonicalization edge cases, config completeness, dataset manifest accuracy, sample data validity, and test coverage gaps. Return APPROVE or REQUEST_CHANGES with findings.
````

### Fix order (split by owning agent)

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `ae5bdde4-b1dc-40ce-8afb-cecc44d214dc`, sent 2026-11-18T17:26:40.547Z.

````text
Fix the Phase 3 code review findings. These are split by owning agent.

DOMAIN-ENGINEER FIXES (use the domain-engineer agent):

H1: Add provenance: Provenance field to ResearcherMetrics in Collaboration.scala. Update its uPickle ReadWriter, serialization test, and any golden file if needed.

H2: Add require(confidence >= 0.0 && confidence <= 1.0) to AnswerClaim in Query.scala. Add a test for invalid confidence rejection.

H3: Add require(score >= 0.0 && score <= 1.0) to EvidenceItem in Evidence.scala. Add a test for invalid score rejection.

H4: Add require(researcherA < researcherB) inside CoauthorPairCandidate and CoauthorEdge case class bodies so copy() can't bypass ordering. Add a test that copy() with swapped IDs throws.

M1: Add require(schemaVersion.nonEmpty) to Provenance. Add a test.

M2: Add require(mentionA != mentionB) to CoauthorPairCandidate. Same for CoauthorEdge (researcherA != researcherB). Add tests.

M3: Add require(year.forall(_ >= 0)) to WorkRecord. Add require(position >= 0) to AuthorMention. Add tests.

M5: In any Scala code that invokes python3, add -I flag. (If this is only in shell scripts, skip — that's dataset-engineer's.)

M6: Add at least 2 config loading tests — one with missing section, one with malformed value.

L1: Add require guard in IdGen.researcherId for empty fallback name.

L5: Add require(teamSize == authorMentions.size) to PublicationTeam.

Run sbt check after all fixes.

DATASET-ENGINEER FIXES (use the dataset-engineer agent):

M4: Add ${?BLOCKING_KEY_FIELDS} env override to reference.conf blocking-key-fields. (Actually this is in core — route to domain-engineer instead.)

M5: Add -I flag to any python3 -c invocations in download scripts under datasets/scripts/.

L4: Add a cross-dataset DOI link to the DBLP sample record in datasets/samples/dblp-sample.json.

L6: Review and fix Crossref license characterization in dataset-manifest.yaml if imprecise.

Run bash -n on all scripts and validate JSON samples after fixes.

Report all fixes made and test results.
````

### Re-review order

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `c7207fad-c403-4ff0-bbba-ddd4d8962408`, sent 2026-11-18T21:49:52.587Z.

````text
Use the code-reviewer agent to re-review the Phase 3 fixes.

Review the complete current diff (git diff HEAD) covering all domain-engineer and dataset-engineer changes.

Verify:
1. Every H1-H4, M1-M6, L1/L4/L5/L6 finding is actually fixed.
2. No new ownership violations, contract drift, or regressions introduced.
3. require guards are in case class bodies (not just companion apply).
4. New tests actually test the intended rejection (not just happy path).
5. sbt check passes (97 tests).
6. Breaking changes (ResearcherMetrics provenance, pair rejection, negative year/position) are documented.

Return APPROVE or REQUEST_CHANGES with findings.
````

### Evidence instruction to the orchestrator

Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, message `cee46204-72f6-4af5-89f5-8907ebdc19cd`, sent 2026-11-18T21:54:30.793Z.

````text
Create these 4 files exactly:

mkdir -p docs/agent-runs/phase-03

Write docs/agent-runs/phase-03/prompt.md:
# Phase 3 Work Orders

## Part 1: Dataset-Engineer
Create datasets/dataset-manifest.yaml for DBLP, OpenAlex, ORCID, and Crossref with all 20 required fields. Create bounded download scripts, schema docs, and sample JSON files.

## Part 2: Domain-Engineer
Create immutable Scala domain records (27 types), 6 enums, stable ID functions, config loader with HOCON, uPickle serialization, and contract tests with golden files.

## Fix Round
Fixed 4 high, 6 medium, 4 low code-review findings: added provenance to ResearcherMetrics, require guards on case class bodies for validation, config error tests, python3 -I flags, cross-dataset DOI link, Crossref license fix.

Write docs/agent-runs/phase-03/agent-report.md:
# Phase 3 Agent Report

## Dataset-Engineer
STATUS: COMPLETE
FILES CREATED: 16
DATASETS: DBLP (seed), OpenAlex, ORCID, Crossref
VALIDATION: bash -n on all scripts, JSON validation on all samples

## Domain-Engineer
STATUS: COMPLETE
FILES CREATED: 23
TYPES: 27 domain types, 6 enums
TESTS: 97 passing (73 original + 24 validation tests from fix round)
KEY DESIGN: pair canonicalization (mentionA < mentionB), stable IDs (ORCID > OpenAlex > SHA-256), 7 HOCON config sections

Write docs/agent-runs/phase-03/review-report.md:
# Phase 3 Code Review

## Initial Review: REQUEST_CHANGES
- 0 critical, 4 high, 6 medium, 6 low findings

## Re-Review After Fixes: APPROVE
- All 16 findings verified fixed
- 24 new tests added covering rejection paths
- No new issues, no ownership violations

Write docs/agent-runs/phase-03/human-gate.md:
# Phase 3 Human Gate

DECISION: ACCEPTED
REVIEWED: All domain types, enums, config, serialization, dataset manifest, samples, scripts, golden files, and 97 tests
MANUAL CHANGES: None
RISKS: ResearcherMetrics now requires provenance (compile-time catch), pair constructors reject equal IDs (runtime)

Do not create or modify any other files.
````
