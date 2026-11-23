# Phase 05 – Repair Record 01: review fix round (RECONSTRUCTED 2026-11-23)

**Original finding.** Orchestrator-written review: REQUEST_CHANGES (1 high, 4 medium, 2 low); see `review-report.md`.

**Reproduction evidence.** Session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`, 2026-11-19T11:32Z.

**Owner.** entity-resolution-engineer.

**Changes.** Four fixes, verbatim report below.

**Rerun result.** The fix report states 122 tests passed and `sbt check` passed.

**Independent review outcome.** Re-review written inline by the orchestrator at 11:39Z: APPROVE. **No independent code-reviewer review is recorded for Phase 5.**


## Original subagent final reports (RECONSTRUCTED 2026-11-23)
Each report below is the subagent's final message, copied word for word from its transcript (`<session>/subagents/agent-<id>.jsonl`). Text above this section is the original file and is unchanged.

### entity-resolution-engineer fixes

entity-resolution-engineer subagent `a0e1dc71af416b1ba`, session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`.

````text
All 122 tests pass (104 entity-resolution + 18 hadoop). `sbt check` completed successfully.

```
STATUS: COMPLETE
OWNER: entity-resolution-engineer
WORK ORDER: Fix four code review findings in entity-resolution module
FILES MODIFIED:
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/FeatureFunctions.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/EntityResolutionEngine.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/ScoringPolicy.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/resources/reference.conf
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/LlmAdjudicator.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/main/scala/edu/scholarchain/entityresolution/IdentifierNormalizer.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/FeatureFunctionsSpec.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/ScoringPolicySpec.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/LlmAdjudicatorSpec.scala
  - /Users/harshjain/Documents/scholarchain/modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/IdentifierNormalizerSpec.scala
FILES CREATED: none
FIXES APPLIED:
  1. (HIGH) FeatureFunctions.scala: Added bestMatchNameSimilarity function that averages best per-author nameSimilarity scores between two author lists. EntityResolutionEngine.computeFeatures now calls bestMatchNameSimilarity for author_similarity instead of duplicating coauthorOverlap.
  2. (MEDIUM) ScoringPolicy.scala line 72 and reference.conf line 6: Changed unresolvedBandMax default from 0.93 to 0.85, creating a reachable high_probabilistic range (0.85, 0.93). Updated matchPath test expectations accordingly.
  3. (MEDIUM) LlmAdjudicator.scala lines 86-91: computeInputHash now includes a deterministic representation of the features map (sorted by key, formatted as key=value pairs) in the hash input string.
  4. (LOW) IdentifierNormalizer.scala line 6: Updated DoiPrefixPattern regex from "^(?:https?://(?:dx\\.)?doi\\.org/)?(.+)$" to "^(?:doi:|https?://(?:dx\\.)?doi\\.org/)?(.+)$" so the doi: prefix is stripped. Added test case.
COMMANDS RUN: sbt entityResolution/test, sbt check
TEST RESULTS: 122 tests succeeded, 0 failed, 0 canceled, 0 ignored (104 entity-resolution + 18 hadoop)
VALIDATION: sbt check passed. Test-run marker written to .claude/.last-test-run.
ASSUMPTIONS: The bestMatchNameSimilarity function uses the shorter list to iterate over and finds the max nameSimilarity against the longer list, which is the standard best-match approach for asymmetric name similarity.
RISKS: Changing unresolvedBandMax from 0.93 to 0.85 means pairs scoring in (0.85, 0.93) that were previously classified as unresolved_band will now be classified as high_probabilistic. This is the intended behavior but changes match path labels for existing scored pairs.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: gold-set evaluation and match-policy review
```
````
