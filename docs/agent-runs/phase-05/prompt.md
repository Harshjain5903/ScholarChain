# Phase 5 Work Order: Entity Resolution

Use the entity-resolution-engineer agent to implement the ScholarChain entity-resolution subsystem against the Phase 3 contracts and Phase 4 MatchCandidate records.

Required behavior:
- Exact identifier rules for DOI, arXiv ID, ORCID, OpenAlex ID, Semantic Scholar ID, and repository URL.
- Normalization for Unicode, punctuation, whitespace, title markup, initials, name order, and DOI prefixes.
- Feature functions for researcher-name similarity, initials, author order, coauthor overlap, affiliation compatibility, ORCID or external IDs, title similarity, year distance, venue compatibility, citation-neighborhood evidence when available, and source reliability.
- A versioned scoring policy.
- Separate thresholds for accepted, rejected, and unresolved results.
- Conflict detection when authoritative identifiers disagree.
- LLM adjudication only for a bounded unresolved band.
- Strict JSON schema for LLM adjudication with accept, reject, and abstain outcomes.
- No LLM call may alter source records or create an identifier not present in the candidates.
- Cache LLM adjudications by input hash, prompt version, model version, and policy version.
- Produce accepted, rejected, unresolved, and conflict outputs.
- Generate a review queue for human-labeled evaluation examples.

Evaluation requirements:
- At least 200 manually reviewed candidate pairs.
- Precision, recall, F1, confusion matrix, and coverage, reported separately for researcher-identity resolution and work resolution when both are implemented.
- Metrics by match path: exact ID, deterministic bibliographic, probabilistic, and LLM-adjudicated.
- Error analysis with at least ten false positives and ten false negatives when available.
- Threshold sensitivity analysis.

Run sbt check and the gold-set evaluation.
Report the policy version, feature definitions, thresholds, model and prompt versions, confusion matrix, unresolved rate, and representative errors.


## Further human work orders for this phase (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Review order to code-reviewer (first attempt; subagent stopped at the usage limit)

Session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`, message `c4dc8c54-b815-49f3-8103-036c0408572e`, sent 2026-11-19T02:14:42.638Z.

````text
Use the code-reviewer agent to perform a read-only review of all Phase 5 entity-resolution changes.

Review scope: all files under modules/entity-resolution/

Check for:
1. Correctness — feature functions, scoring math, threshold logic, conflict detection
2. Contract compliance — uses core MatchDecision, MatchOutcome, Provenance correctly
3. No ownership violations — only writes to modules/entity-resolution/**
4. Gold set quality — 200 pairs, labeled correctly, covers all match paths
5. LLM adjudication — bounded, cached, can abstain, no fabricated IDs
6. Evaluation — precision/recall/F1 separate for researcher vs work resolution, per-path metrics
7. Security — no secrets, no hardcoded endpoints
8. Test coverage — edge cases, adversarial inputs, threshold boundaries

For each finding: severity (critical/high/medium/low), file, line, concrete failure, and owning agent.
Return APPROVE or REQUEST_CHANGES.
````

### Review order to code-reviewer (second attempt)

Session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`, message `2e8ebee2-9cd0-43b3-be39-ff81f4d04214`, sent 2026-11-19T11:30:37.920Z.

````text
Use the code-reviewer agent to perform a read-only review of all Phase 5 entity-resolution changes.

Review scope: all files under modules/entity-resolution/

Check for:
1. Correctness — feature functions, scoring math, threshold logic, conflict detection
2. Contract compliance — uses core MatchDecision, MatchOutcome, Provenance correctly
3. No ownership violations — only writes to modules/entity-resolution/**
4. Gold set quality — 200 pairs, labeled correctly, covers all match paths
5. LLM adjudication — bounded, cached, can abstain, no fabricated IDs
6. Evaluation — precision/recall/F1 separate for researcher vs work resolution, per-path metrics
7. Security — no secrets, no hardcoded endpoints
8. Test coverage — edge cases, adversarial inputs, threshold boundaries

For each finding: severity (critical/high/medium/low), file, line, concrete failure, and owning agent.
Return APPROVE or REQUEST_CHANGES.
````

### Fix order to entity-resolution-engineer

Session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`, message `e9cef154-8bd6-47b0-9f9b-4f972d9b37c8`, sent 2026-11-19T11:35:29.383Z.

````text
Use the entity-resolution-engineer agent to fix the following code review findings in modules/entity-resolution/.

Fix 1 (HIGH): In EntityResolutionEngine.scala, the `author_similarity` feature incorrectly calls `FeatureFunctions.coauthorOverlap`. Change it to use `FeatureFunctions.nameSimilarity` (or an average-best-match across author names) so it measures name similarity, not coauthor overlap. The two features must compute different things.

Fix 2 (MEDIUM): In ScoringPolicy.scala, the `matchPath` method has an unreachable `high_probabilistic` range when `unresolvedBandMax == acceptThreshold` (both 0.93). Fix by either removing the dead branch or adjusting the boundary so the range is reachable (e.g., make `high_probabilistic` cover scores >= 0.85 and < acceptThreshold).

Fix 3 (MEDIUM): In LlmAdjudicator.scala, `computeInputHash` only hashes sourceId, targetId, policyVersion, and promptVersion. Add a hash of the features map so that re-evaluation with different features produces a different cache key.

Fix 4 (LOW): In IdentifierNormalizer.scala, `normalizeDoi` does not strip a `doi:` prefix. Add prefix stripping to match the Hadoop NormalizationUtils behavior, so `"doi:10.1234/abc"` normalizes to `"10.1234/abc"`.

Do NOT fix findings 3 (gold set lacks field data — acknowledged limitation of synthetic gold set), 4 (ReviewQueueGenerator naming — cosmetic), or 7 (gold set notes — cosmetic). These are accepted as-is.

After fixes, run `sbt check` and report results.
````

### Re-review order

Session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`, message `96f7b3e4-42f2-4ddd-b461-010660af6e3f`, sent 2026-11-19T11:39:23.446Z.

````text
Use the code-reviewer agent to re-review the entity-resolution fixes in modules/entity-resolution/.

Verify:
1. `author_similarity` now uses `bestMatchNameSimilarity`, distinct from `coauthorOverlap`
2. `high_probabilistic` range is reachable with the new unresolvedBandMax=0.85
3. LLM cache hash includes features map
4. `normalizeDoi` strips `doi:` prefix

Return APPROVE or REQUEST_CHANGES.
````

### Formatting fix order (repair-02)

Session `7aafb09f-5927-4b74-9c74-70dface1b0d2`, message `2bba398a-337c-463c-ab07-259e72a38c87`, sent 2026-11-19T23:06:52.865Z.

````text
Fix the entity-resolution formatting issue (entity-resolution-engineer)
````
