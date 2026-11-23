# Phase 06 – Repair Record 02: Spark driver-side collection

**Status:** OPEN. The independent review returned REQUEST_CHANGES, and `sbt check`
fails (see below). The human decision and the accepted commit are PENDING in
`human-gate.md`. Nothing in this record is staged or committed.

**Date:** 2026-11-22. **Branch:** `remediation`. **Base commit:** `753c4996e50890d7aacda38c44a800ff0ecb35cc`.

The historical Phase 06 report in `agent-report.md` (STATUS: BLOCKED-ON) is
unchanged. `repair-01.md` is also unchanged.

## Issue

The instructor's Phase 6 work order and gate require "Avoid collecting the full graph
on the driver" and "No complete graph is collected to the driver". The orchestrator
inspected three files at the base commit and found driver-side collection that grows
with graph size:

| File | Driver-side collection at base commit |
|---|---|
| `GraphUtils.scala` | `buildVertexMapping` / `buildVertexMappingFromIds`: `.zipWithUniqueId().collect()` into driver `Map`s (O(V)); `buildGraph` broadcasts that map and runs `parallelize(mapping.toStr.toSeq)` |
| `BridgeAnalysisJob.scala` | `communityAssignments.collectAsMap()` + broadcast (O(V)); adjacency `collectAsMap()` (O(E)) with Brandes run on the driver; `rng.shuffle` over all vertex IDs on the driver |
| `KCoreJob.scala` | `.collect().toSet` of low-degree vertices on every iteration; a coreness `Map` with one entry per vertex on the driver, then `parallelize` |

## Owner and work order

The orchestrator (main Claude Code session) routed the fix to **spark-engineer**
(subagent `a244c7389ed727ba0`, final report 2026-11-22T22:31:08Z). It was told to:
- make the fix only under `modules/spark/**`;
- keep graph semantics and output schemas;
- leave existing tests unedited (including the test-engineer's `AdversarialSparkSpec.scala`);
- add feature tests;
- not start AWS.

## Commands run by the orchestrator, with actual results

Environment: OpenJDK 17.0.21. All commands were run from the repository root.

| # | When | Command | Exit | Actual result |
|---|---|---|---|---|
| 1 | Before the fix | `sbt -batch "sparkAnalytics/test"` | 0 | 40 tests run, 40 succeeded, 0 failed |
| 2 | After the fix | `sbt -batch "sparkAnalytics/test"` | 0 | 63 tests run, 63 succeeded, 0 failed ("All tests passed.") |
| 3 | After the fix | `sbt -batch check` | 1 | **FAILED** at the first step, `scalafmtCheckAll`, before any compile or test step. See the output below. |

Output of command 3:

```text
[warn] scalafmt: /Users/harshjain/Documents/scholarchain/modules/hadoop: 1 files aren't formatted properly:
[warn] scalafmt: /Users/harshjain/Documents/scholarchain/modules/entity-resolution: 1 files aren't formatted properly:
[warn] scalafmt: /Users/harshjain/Documents/scholarchain/modules/services: 1 files aren't formatted properly:
[error] (entityResolution / Test / scalafmtCheck) scalafmt: 1 files must be formatted (/Users/harshjain/Documents/scholarchain/modules/entity-resolution)
[error] (services / Test / scalafmtCheck) scalafmt: 1 files must be formatted (/Users/harshjain/Documents/scholarchain/modules/services)
[error] (hadoop / Test / scalafmtCheck) scalafmt: 1 files must be formatted (/Users/harshjain/Documents/scholarchain/modules/hadoop)
```

The failing files are `AdversarialEntityResolutionSpec.scala`, `AdversarialHadoopSpec.scala`
and `AdversarialServiceSpec.scala`. All three were last changed in commit `1437112`
(Phase 11, test-engineer), and none has local changes. `.scalafmt.conf` has no local
changes either. So this failure is already present at the base commit and does not
come from the Spark change. `.claude/.last-test-run` was not updated, and its
timestamp is still 2026-11-22 07:34:19.

The spark-engineer also reported running `sbt -batch "Test/compile" "test"` (exit 0,
534 tests across 7 modules, 0 failed). The orchestrator did not re-run that command.

**Definition of done (CLAUDE.md):** NOT MET. `sbt check` has not been observed passing.

## Spark-engineer final report (verbatim)

text
STATUS: BLOCKED-ON

OWNER: spark-engineer

WORK ORDER: Remediate Phase 06 driver-side collection: an RDD-backed vertex mapping, distributed sampled Brandes betweenness and cross-community degree, distributed iterative k-core peeling, and hand-checkable feature tests.

`sbt -batch check` fails at its first step, `scalafmtCheckAll`, because of three committed Phase 11 test files outside `modules/spark`. I did not touch those files, and they have no local changes. All Spark work is done, and every test in every module passes.

FILES CREATED:
- /Users/harshjain/Documents/scholarchain/modules/spark/src/test/scala/edu/scholarchain/spark/DistributedGraphAlgorithmsSpec.scala (23 tests)

FILES MODIFIED:
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/GraphUtils.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/BridgeAnalysisJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/KCoreJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/ConnectedComponentsJob.scala (minimal: researcher names via `cc.vertices.innerJoin(graph.vertices)`)
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/CommunityDetectionJob.scala (minimal: `innerJoin` with `graph.vertices`; cross-community edge counts joined to `graph.vertices` for names)
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/ShortestPathJob.scala (minimal: `mapping.resolve(requestedIds)` plus a bounded filter on vertex attributes for path-vertex names)

No existing file under `modules/spark/src/test/` was changed (confirmed with `git status`).

COMMANDS RUN:
1. `sbt -batch "sparkAnalytics/scalafmt" "sparkAnalytics/Test/compile"` (three times while fixing compile errors; the last run succeeded)
2. `sbt -batch "sparkAnalytics/scalafmt" "sparkAnalytics/Test/scalafmt" "sparkAnalytics/test"`
3. `sbt -batch "sparkAnalytics/test"`
4. `sbt -batch "sparkAnalytics/scalafmtCheckAll" "sparkAnalytics/Test/scalafmtCheck"`
5. `sbt -batch check`
6. `sbt -batch "Test/compile" "test"`: the remaining `check` steps without `markTestRun`, for diagnosis only.

TEST RESULTS:
- `sparkAnalytics/test`: 63 run, 63 succeeded, 0 failed. That is the 40-test baseline plus 23 new tests.
- Format check for the Spark module, main and test: both passed.
- `sbt -batch check`: failed (exit 1) at `scalafmtCheckAll`, before any compile or test step ran. The unformatted files are:
  - `modules/entity-resolution/src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala`
  - `modules/hadoop/src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala`
  - `modules/services/src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala`
  - All three were last changed in commit 1437112 ("phase-11 test-engineer: add 55 adversarial tests").
- `sbt -batch "Test/compile" "test"`: exit 0. All 7 module runs passed: 97, 52, 116, 24, 49, 133 and 63 tests, 534 in total, 0 failed.
- `markTestRun` did not run, so `.claude/.last-test-run` was not updated.

VALIDATION RESULTS:
- Hand-computed exact betweenness matches to within 1e-9:
  - P4: b = c = 4.
  - P5: 0, 6, 8, 6, 0.
  - C4: 1.0 each, which checks shortest-path counts above 1.
  - Two triangles joined by bridge vertex X (C-X-D): C = 16, X = 18, D = 16, all others 0.
  - Bowtie: center 8.
- Exact betweenness agrees with a local reference Brandes on a generated 24-vertex graph, and is the same with 1 and 5 partitions.
- Sampled betweenness (k=3) is deterministic for a fixed seed, and every score is a multiple of n/k.
- The `maxGraphSize` fallback gives 0.0 for every vertex, including an isolate, and cross-community degree is still computed.
- Cross-community degree is correct with unassigned researchers (they use their own ID as community) and shared leaf communities; isolates are absent from the output, as before.
- Coreness:
  - Exact on hand-checkable graphs: K4 → 3, star → 1, triangle with pendant → 2,2,2,1, isolate → 0, empty graph → empty.
  - Cascading cases: K4 plus a pendant path, and P5.
  - Matches a reference peeling on a generated 30-vertex graph.
- The mapping round-trip holds: unique IDs for every endpoint and isolate, vertex attribute equals the researcher ID, every edge appears in both directions, weights are kept, and the assignment is stable when recomputed.
- The last test sets `sc.setCheckpointDir` and gets the same results using reliable checkpointing.

Remaining `collect` / `take` / `parallelize` / driver actions in the three target files:

| Location | Call | Driver cost |
|---|---|---|
| `GraphUtils.scala:42` `resolve` | `filter(wanted).collect()` | Bounded by the number of IDs requested, not by V |
| `GraphUtils.scala:51` `resolveVertexIds` | `filter(wanted).collect()` | Bounded by the number of IDs requested |
| `GraphUtils.scala:77` `RddLookupView.get` | `take(1)` | At most 1 row |
| `GraphUtils.scala:79` `RddLookupView.iterator` | `toLocalIterator` | Not bounded in total (see note) |
| `GraphUtils.scala:168` `checkpointAndCount` | `count()` | One scalar |
| `BridgeAnalysisJob.scala:89` | `numVertices` | One scalar |
| `BridgeAnalysisJob.scala:146` | `count()` | One scalar |
| `BridgeAnalysisJob.scala:166` | `takeSample(false, k, seed)` | k ≤ `sampleCount` Long IDs (default 50), documented in Scaladoc |
| `BridgeAnalysisJob.scala:171` | `parallelize` of the k sampled sources | O(k) |
| `BridgeAnalysisJob.scala:185` | `previous.fold(...)` | A Scala `Option.fold`, not a Spark action |
| `KCoreJob.scala:97` | RDD `fold` | Returns one `(Long, Int)` pair per iteration |
| Both jobs | `checkpointAndCount` in each loop | One `Long` per iteration |

Note on `RddLookupView.iterator`: it streams one partition at a time, but iterating it in full passes O(V) rows through the driver. It only runs if a caller iterates the compatibility views `toLong`/`toStr`, and no production code does. `toLong.get` (used by `AdversarialSparkSpec` line 39) costs one filter job returning at most one row.

ASSUMPTIONS:
- `toLong` and `toStr` are kept as read-only lookup views backed by the RDD, because `AdversarialSparkSpec` line 39 needs `mapping.toLong should contain key ...`. Their deprecated `-` overloads throw `UnsupportedOperationException`.
- To keep ID assignment deterministic, each partition is sorted after `distinct`, then `zipWithUniqueId` runs, and the mapping is persisted and checkpointed.
- If a researcher has more than one community assignment, the lexicographically smallest is used. The old behavior picked one arbitrarily via `collectAsMap`.
- `buildGraph` now drops edges whose endpoints are not in the mapping; before, the broadcast map lookup threw. This cannot happen with mappings built by `buildVertexMapping`.
- KCore keeps the old degree definition (out-degree, so duplicate coauthor edges count more than once). It jumps k straight to max(k, minDegree+1), which gives the same coreness.
- Sampled source sets differ from the old `Random.shuffle`; they are deterministic for a given seed and graph partitioning. Exact results (k ≥ n) match.
- The `mapping` parameter is no longer read by `BridgeAnalysisJob` or `KCoreJob`; it stays for source compatibility. The `sc` parameter of `buildGraph` is likewise unused.

RISKS:
- `maxGraphSize` (default 100000) turns betweenness off entirely above that vertex count; every score becomes 0.0. The default is unchanged, and this is documented in Scaladoc.
- Without a checkpoint directory, iterations use `localCheckpoint`, which does not survive executor loss. That matters with dynamic allocation, which EMR enables by default. `ScholarChainPipeline` does not call `sc.setCheckpointDir`. Next step: add a configurable checkpoint directory (HDFS on EMR).
- Distributed Brandes holds O(k·V) (source, vertex) state, and iterations scale with the graph diameter. KCore can need up to O(V) iterations on long chains (path peeling), each a distributed pass.
- Shortest-path counts use `Long`, as before, so they can overflow on graphs with an extreme number of equal-length shortest paths.
- The joins on researcher ID in `buildGraph` and cross-community degree can be skewed for researchers with very many coauthors.
- Driver collections still remaining outside this work order's scope, deliberately not refactored:
  - `ConnectedComponentsJob.scala:48` `sizeMap` `collectAsMap` plus broadcast at 54: O(number of components), up to O(V) when there are many singletons.
  - `CommunityDetectionJob.scala:50` `communitySizes` `collectAsMap` plus broadcast at 53: O(number of communities).
  - `CommunityDetectionJob.scala:60` `vertexCommMap` `collectAsMap` plus broadcast at 62: O(V).
  - `ShortestPathJob.scala:96` `bfsGraph.vertices.collectAsMap()`: O(V) per source. The new line 110 is bounded by the vertices on the requested paths.
  - `CanonicalGraphBuilder.scala:31`/`32` `collectAsMap` plus broadcasts at 33/46 and `parallelize` at 42: O(number of mentions).
  - `CanonicalGraphBuilder.scala:70` `mentionToCanonical.collectAsMap()` plus broadcast: O(number of mentions).
  - `SemanticContextJob.scala:103` `bridgeScores.collectAsMap()` plus broadcast: O(V).
  - `SemanticContextJob.scala:146` `parallelize(summaries)`: bounded by the number of requested path pairs.

MANUAL ACTION REQUIRED: Route the formatting failure in the three Phase 11 adversarial specs listed above. Running `scalafmt` on those files, by their owner, should be enough. Then re-run `sbt -batch check`. Separately, decide whether to add a checkpoint-directory setting to `ScholarChainPipeline` (spark-engineer can do this in a follow-up work order).

BLOCKED-ON OWNER: test-engineer, who wrote the three files in commit 1437112. Those files sit in module test trees owned by hadoop-engineer, entity-resolution-engineer and service-engineer, so the orchestrator or human architect should decide who fixes them.

BLOCKED-ON EVIDENCE:
```
[warn] scalafmt: .../modules/entity-resolution: 1 files aren't formatted properly:
[warn] src/test/scala/edu/scholarchain/entityresolution/AdversarialEntityResolutionSpec.scala
[warn] scalafmt: .../modules/hadoop: 1 files aren't formatted properly:
[warn] src/test/scala/edu/scholarchain/hadoop/AdversarialHadoopSpec.scala
[warn] scalafmt: .../modules/services: 1 files aren't formatted properly:
[warn] src/test/scala/edu/scholarchain/services/AdversarialServiceSpec.scala
[error] (entityResolution / Test / scalafmtCheck) scalafmt: 1 files must be formatted
[error] (services / Test / scalafmtCheck) scalafmt: 1 files must be formatted
[error] (hadoop / Test / scalafmtCheck) scalafmt: 1 files must be formatted
```

NEXT GATE: `sbt -batch check` passing after the formatting fix, then a code-reviewer review of the Spark diff. After that, a local sample pipeline run with a checkpoint directory set, confirming the output schemas are unchanged.````

## Independent review

code-reviewer (subagent `a7e22fd1e29a3424e`, final report 2026-11-22T22:40:08Z):
**VERDICT: REQUEST_CHANGES**. The full report is in `review-report.md`, under
"Remediation review (repair-02)".

The review accepts the three target files as fixed. In those files nothing that grows
with V or E is collected to the driver, and the distributed Brandes and k-core match
the base-commit semantics. Its High findings are outside the three target files:

1. `CommunityDetectionJob` still collects a map with one entry per vertex to the driver and broadcasts it.
2. `ShortestPathJob:96` still collects all BFS vertices to the driver.
3. The production pipeline never sets a checkpoint directory, so EMR runs fall back to `localCheckpoint`.

It also lists Medium findings 4–6 (parameters not recorded in run outputs; the
`CanonicalGraphBuilder` and `SemanticContextJob` collections; no iteration bound on
k-core) and several Low ones.

The reviewer did not run sbt; its role does not allow builds.

## Required EMR and operational evidence still missing

None of the following exists for Phase 06, and AWS was not started:

- A bounded EMR Spark run: cluster and step IDs, input size, runtime.
- Spark stage DAGs.
- Partition counts and shuffle read/write metrics.
- Graph counts, component counts, community counts and metric distributions from a real run.
- Path-test results and temporal windows from a real run.
- Rejected integrity violations and output paths.
- Checkpointing configured for EMR: a reliable checkpoint directory and the Pregel checkpoint interval.
- Recorded betweenness parameters (k, seed, `maxGraphSize`) and whether the cap was hit.
- Evidence that peak driver memory does not grow with graph size.
- `sbt check` passing.
- A code-review APPROVE for Phase 06.
- The human approval and the accepted implementation commit (PENDING).

## Open decisions for the human architect

- Whether reviewer findings 1, 2 and 5 (driver collections outside the three target
  files) and finding 3 (the checkpoint directory) are in scope for Phase 06.
- Who should reformat the three Phase 11 adversarial specs that block `sbt check`.
  test-engineer wrote them, but they sit in test trees owned by hadoop-engineer,
  entity-resolution-engineer and service-engineer.
