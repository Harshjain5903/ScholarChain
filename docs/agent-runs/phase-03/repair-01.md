# Phase 03 – Repair Record 01: code-review fix round (RECONSTRUCTED 2026-11-23)

**Original finding.** Code-reviewer `a2b82abdabb0487a6` returned REQUEST_CHANGES (4 high, 6 medium, 4 low per `prompt.md`'s original summary).

**Reproduction evidence.** Session `42f04f0e-5685-4456-8160-45e1f9fb0fff`, review at 2026-11-18T17:15–17:18Z; verbatim in `review-report.md`.

**Owner.** dataset-engineer and domain-engineer, each for its own findings.

**Changes.** See the verbatim fix reports below.

**Rerun result.** Both fix reports state `sbt check` passed (domain-engineer: 97 tests).

**Independent review outcome.** Code-reviewer `a404bee3cda443104` re-review: APPROVE (verbatim in `review-report.md`). Note: the retro run of the committed tree fails the format check (test-report.md).


## Original subagent final reports (RECONSTRUCTED 2026-11-23)
Each report below is the subagent's final message, copied word for word from its transcript (`<session>/subagents/agent-<id>.jsonl`). Text above this section is the original file and is unchanged.

### dataset-engineer fixes

dataset-engineer subagent `abdffaee08adfbe6c`, session `42f04f0e-5685-4456-8160-45e1f9fb0fff`.

````text
All validations pass. Here is the report.

```
STATUS: COMPLETE
OWNER: dataset-engineer
WORK ORDER: Fix code review findings M5, L4, L6 in datasets/
FILES CREATED: none
FILES MODIFIED:
  /Users/harshjain/Documents/scholarchain/datasets/scripts/download-openalex-sample.sh
  /Users/harshjain/Documents/scholarchain/datasets/scripts/download-crossref-sample.sh
  /Users/harshjain/Documents/scholarchain/datasets/scripts/download-orcid-sample.sh
  /Users/harshjain/Documents/scholarchain/datasets/samples/dblp-sample.json
  /Users/harshjain/Documents/scholarchain/datasets/samples/crossref-sample.json
  /Users/harshjain/Documents/scholarchain/datasets/dataset-manifest.yaml
COMMANDS RUN:
  bash -n datasets/scripts/download-openalex-sample.sh (and three other scripts)
  python3 -I -c "import json; json.load(open(...))" for all four sample JSON files
TEST RESULTS: all bash -n syntax checks passed; all JSON validations passed
VALIDATION RESULTS:
  - 4/4 scripts pass bash -n
  - 4/4 sample JSON files parse successfully
ASSUMPTIONS:
  - The fictional DOI "10.1109/ICDE55515.2023.00142" follows realistic IEEE ICDE convention
  - download-dblp-sample.sh has no python3 invocations, so no -I fix was needed there
RISKS:
  - The manifest validation_commands for dblp-sample.json still reference python3 without -I; those are documentation strings rather than executable script lines, but could be updated for consistency in a follow-up
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: dataset validation tests and manifest review
```

**Fixes applied:**

**M5 -- Add `-I` flag to python3 invocations in download scripts:**
- `download-openalex-sample.sh` line 93: `python3 -c` changed to `python3 -I -c`
- `download-crossref-sample.sh` line 106: `python3 -c` changed to `python3 -I -c`
- `download-orcid-sample.sh` line 159: `python3 -c` changed to `python3 -I -c` (line 103 already had `-I`)
- `download-dblp-sample.sh`: no python3 invocations found; no change needed

**L4 -- Add cross-dataset linkage to DBLP sample:**
- In `dblp-sample.json`, the third record (Martinez/Fernandez ICDE paper) now has `"doi": "10.1109/ICDE55515.2023.00142"` instead of `null`
- Added a matching record in `crossref-sample.json` with the same DOI, title, authors, and venue, completing the cross-dataset linkage surface

**L6 -- Fix Crossref license characterization:**
- In `dataset-manifest.yaml`, the Crossref `license` field changed from `"Public domain (metadata facts)"` to `"Public domain (factual metadata); subject to Crossref Metadata Terms of Use"`
- The `reuse_terms` field now includes explicit mention of rate limits, polite-pool requirements, and a link to the Crossref metadata terms documentation
````

### domain-engineer fixes

domain-engineer subagent `a9355ae22d41b19b3`, session `42f04f0e-5685-4456-8160-45e1f9fb0fff`.

````text
`sbt check` passed. All 97 tests succeeded and the test-run marker was written.

Here is the report:

```
STATUS: COMPLETE
OWNER: domain-engineer
WORK ORDER: Fix code review findings (H1-H4, M1-M4, M6, L1, L5) in modules/core
FILES CREATED: none
FILES MODIFIED:
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Collaboration.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Query.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Evidence.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Provenance.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Publication.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/Author.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/scala/edu/scholarchain/core/IdGen.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/main/resources/reference.conf
  /Users/harshjain/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/TestFixtures.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/ValidationSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/PairOrderingSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/IdGenSpec.scala
  /Users/harshjain/Documents/scholarchain/modules/core/src/test/scala/edu/scholarchain/core/AppConfigSpec.scala
COMMANDS RUN: sbt core/compile, sbt core/test, sbt check
TEST RESULTS: 97 tests passed, 0 failed, 7 suites completed
VALIDATION RESULTS: sbt check passed; test-run marker written
ASSUMPTIONS:
  - VerificationStatus enum has no "Unverified" variant; used "Verified" in AnswerClaim validation tests
  - Typesafe Config coerces int->string silently, so M6 type-mismatch test uses string-where-int-expected (neptune.port = "not-a-number")
  - Golden files do not contain ResearcherMetrics, so no golden file updates were needed
RISKS:
  - Downstream modules constructing ResearcherMetrics without provenance will fail to compile (H1 adds a required field)
  - CoauthorPairCandidate and CoauthorEdge now reject equal IDs; any code relying on self-pairs will break (H4+M2)
  - WorkRecord with negative year and AuthorMention with negative position will now throw (M3)
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: contract tests, sbt check, and code review
```

Summary of all fixes implemented:

- **H1**: Added `provenance: Provenance` as final field of `ResearcherMetrics`; updated `TestFixtures.sampleResearcherMetrics`
- **H2**: Added `require(confidence >= 0.0 && confidence <= 1.0)` to `AnswerClaim`; added 3 tests
- **H3**: Added `require(score >= 0.0 && score <= 1.0)` to `EvidenceItem`; added 3 tests
- **H4+M2**: Added `require(mentionA < mentionB)` to `CoauthorPairCandidate` body, `require(researcherA < researcherB)` to `CoauthorEdge` body; updated smart constructors to use strict `<` and reject equal IDs; added copy()-bypass tests and self-pair rejection tests
- **M1**: Added `require(schemaVersion.nonEmpty)` to `Provenance`; added test
- **M3**: Added `require(year >= 0)` to `WorkRecord`, `require(position >= 0)` to `AuthorMention`; added 4 tests
- **M4**: Added `blocking-key-fields = ${?SCHOLARCHAIN_ER_BLOCKING_KEY_FIELDS}` env override in `reference.conf`
- **M6**: Added 2 config error tests (empty config, type mismatch) in `AppConfigSpec`
- **L1**: Added `require(fallbackName.nonEmpty)` guard in `IdGen.researcherId` fallback branch; added 3 tests
- **L5**: Added `require(teamSize == authorMentions.size)` to `PublicationTeam`; added 2 tests
````
