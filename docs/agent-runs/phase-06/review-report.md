# Phase 5 Code Review Report

## Initial Review

```
VERDICT: REQUEST_CHANGES
REVIEWER: code-reviewer (read-only)
```

### Findings

| # | Severity | File | Finding | Owning Agent |
|---|----------|------|---------|--------------|
| 1 | HIGH | EntityResolutionEngine.scala | `author_similarity` and `coauthor_overlap` both call `FeatureFunctions.coauthorOverlap` — double-counting coauthor overlap, giving 0 weight to actual name similarity | entity-resolution-engineer |
| 2 | MEDIUM | ScoringPolicy.scala | `unresolvedBandMax` defaults to 0.93 (same as `acceptThreshold`), making `high_probabilistic` match path unreachable | entity-resolution-engineer |
| 3 | MEDIUM | gold-set.json | Gold set lacks realistic field data (empty titles, empty author lists) — synthetic limitation | entity-resolution-engineer |
| 4 | MEDIUM | ReviewQueueGenerator.scala | Method named `generateQueue` returns unresolved + low-confidence pairs but naming suggests only unresolved | entity-resolution-engineer |
| 5 | MEDIUM | LlmAdjudicator.scala | Cache hash uses only sourceId/targetId/policyVersion/promptVersion — ignores features map, so different feature inputs with same IDs get same cache key | entity-resolution-engineer |
| 6 | LOW | IdentifierNormalizer.scala | `normalizeDoi` doesn't strip `doi:` prefix that Hadoop's NormalizationUtils emits | entity-resolution-engineer |
| 7 | LOW | gold-set.json | Gold set notes field is empty for most entries | entity-resolution-engineer |

### Finding Details

**Finding 1 (HIGH):** In `EntityResolutionEngine.computeFeatures`, the `author_similarity` key calls `FeatureFunctions.coauthorOverlap(a, b)` — same function as `coauthor_overlap`. This means the engine completely ignores researcher name similarity and double-weights coauthor overlap. The `bestMatchNameSimilarity` or equivalent function should be used for `author_similarity`.

**Finding 2 (MEDIUM):** `ScoringPolicy` defaults: `acceptThreshold = 0.93`, `unresolvedBandMax = 0.93`. Since the match path logic assigns `high_probabilistic` when `score >= unresolvedBandMax && score < acceptThreshold`, this range is empty (no score can be >= 0.93 AND < 0.93). The `unresolvedBandMax` should be lower (e.g. 0.85) to create a reachable high-confidence probabilistic band.

**Finding 5 (MEDIUM):** `StubLlmAdjudicator.computeInputHash` builds the hash from `s"$sourceId|$targetId|$policyVersion|$promptVersion"`. If the same pair is re-evaluated with different features (e.g., after adding new enrichment data), the cache returns stale results. The features map should be included in the hash.

**Finding 6 (LOW):** Hadoop's `NormalizationUtils.normalizeDoi` outputs DOIs without the `doi:` prefix, but `IdentifierNormalizer.normalizeDoi` regex is `"^(?:https?://(?:dx\\.)?doi\\.org/)?(.+)$"` — doesn't handle `doi:10.1234/...` format. Should add `doi:` as an alternative prefix.

## Fixes Applied

| Finding | Fix Description |
|---------|-----------------|
| 1 (HIGH) | Added `bestMatchNameSimilarity` function to `FeatureFunctions.scala`; updated `EntityResolutionEngine.computeFeatures` to use it for `author_similarity` instead of `coauthorOverlap` |
| 2 (MEDIUM) | Changed `unresolvedBandMax` default from 0.93 to 0.85 in `ScoringPolicy.scala` and `reference.conf` |
| 5 (MEDIUM) | Updated `computeInputHash` in `LlmAdjudicator.scala` to include sorted features map in hash input |
| 6 (LOW) | Updated DOI regex to `"^(?:doi:|https?://(?:dx\\.)?doi\\.org/)?(.+)$"` to strip `doi:` prefix |

Findings 3, 4, and 7 were acknowledged but not fixed (synthetic gold set limitation, cosmetic naming, cosmetic notes — accepted as-is).

## Re-Review After Fixes

```
VERDICT: APPROVE
REVIEWER: code-reviewer (read-only)
```

All 4 fixes verified correct. 6 new tests added for `bestMatchNameSimilarity` (total: 104 tests, all passing). `sbt check` passes.
