# Phase 06 – Review Report

**The original Phase 06 code review was not recorded in the sources checked.**

The earlier copy of this file was the Phase 05 code review, byte-identical to
`phase-05/review-report.md`. It has been removed from this phase.

Sources checked:

- Git history of `docs/agent-runs/phase-06/`. Its only commit is
  `e59a92ddb7ee8b7c88431be643fa2c16aba44a56`, which added the misplaced Phase 05 copy.
- All saved Claude Code session transcripts for this project, including subagent
  transcripts. The last code-reviewer subagent invocation found is at
  2026-11-19T02:15Z (Phase 05), and it ended at the usage limit. None of these
  transcripts contains a code-reviewer invocation or a review verdict for Phase 06.

The instructor's Phase 6 gate requires "Code review returns APPROVE". Whether that
condition was met is not recorded.

## Remediation review (repair-02)

Added 2026-11-22. The note above about the original Phase 06 review still applies.
This section covers only the uncommitted Spark remediation described in `repair-02.md`.

The reviewer was code-reviewer (read-only), subagent `a7e22fd1e29a3424e`, which sent
its final report at 2026-11-22T22:40:08Z. Its verdict was **REQUEST_CHANGES**. The
report follows verbatim.

````text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the uncommitted Phase 06 Spark remediation in `modules/spark`: six changed main files plus the new `DistributedGraphAlgorithmsSpec.scala`.
VERDICT: REQUEST_CHANGES

## Summary

The three target files (`GraphUtils`, `BridgeAnalysisJob`, `KCoreJob`) look correct. They no longer collect anything that grows with the number of vertices (V) or edges (E) to the driver, and the new tests check hand-computed values. I'm asking for changes because the Phase 6 requirement "No complete graph is collected to the driver" is still not met. Two of the changed callers and two other production jobs still pull per-vertex maps to the driver. The production pipeline also never sets a checkpoint directory.

## Answers to the seven checks

**1. Driver collection in the target files.** No production path uses the `toLong`/`toStr` views. A grep of `modules/` shows they appear only in `AdversarialSparkSpec.scala:39` and `DistributedGraphAlgorithmsSpec.scala:124-125`. What remains in the target files is bounded:
- `resolve` and `resolveVertexIds` (`GraphUtils.scala:39,48`) grow only with the number of IDs requested.
- `takeSample` (`BridgeAnalysisJob.scala:166`) and `parallelize` (`:171`) hold k source IDs.
- `RddLookupView.iterator` uses `toLocalIterator`, which streams one partition at a time. It is only used by tests.
- `KCoreJob` sends only two numbers to the driver per iteration (alive count and minimum degree).

**2. Distributed Brandes (betweenness).** Correct, compared against HEAD.
- **Forward pass:** state is keyed by (source, vertex). Removing the current and previous levels is valid for undirected BFS, and sigma (shortest-path count) is summed over level-d predecessors only.
- **Backward pass:** runs from the deepest level to level 0. Joining against `levels(d-1)` keeps only true predecessors.
- **Endpoint exclusion:** `deltas.drop(1)` removes level 0, which holds only (s, s), so the source itself is excluded.
- **Normalisation:** same as HEAD (ordered pairs, no division by 2, scaled by n/k). Duplicate edges are removed and self-loops have no effect, as at HEAD.
- **Fallback:** same as HEAD. If the vertex count is above `maxGraphSize`, every score is 0.0 and cross-community degree is still computed.
- **Determinism:** `takeSample` gives the same sources for the same seed and partitioning. It picks different sources than HEAD's `rng.shuffle` did, so sampled numbers will not match earlier runs.

**3. Distributed k-core.** Correct.
- **Cascade:** vertices are removed in batches. A neighbour whose degree drops below the current level is removed in the next iteration with the same coreness.
- **The jump to max(k, minDegree+1):** when all live vertices have degree ≥ k, the current set is already the k-core, so skipping levels is safe.
- **Edge cases:** an isolated vertex gets degree 0 and coreness 0. An empty graph returns the empty initial state.
- **Termination:** each iteration removes at least the minimum-degree vertex.
- **Duplicate edges and self-loops:** they count toward degree, the same as HEAD's `outDegrees`.

**4. Caller semantics.**
- `ConnectedComponentsJob` and `CommunityDetectionJob` produce the same output as before, because their joins line up by vertex index.
- `ShortestPathJob`'s output is unchanged.
- Vertex IDs are now deterministic when a partition is recomputed. The values differ from HEAD, so component and community IDs (derived from vertex IDs) change value, though not schema.
- `buildGraph` now silently drops edges whose endpoints are missing from the mapping. HEAD threw an exception instead.

**5. Caching and checkpointing.** No unpersist leaks found. Every RDD is checkpointed before its first action, and nothing is unpersisted before the RDDs that depend on it have been checkpointed. The risk is `localCheckpoint`: see finding 3.

**6. Tests.** They assert real hand-computed values:
- Path P4 gives b=c=4. Path P5 gives 6, 8, 6. Cycle C4 gives 1 for each vertex.
- Two triangles joined through X give C=D=16 and X=18. The bowtie gives X=8. Cross-community degrees and coreness values also check out.
- Two tests compare against an independent single-machine reference implementation.
- One test proves nothing (finding 9).

**7. Hygiene.** No `var` or shared mutable state in production code. The only module touched among those relevant here is `modules/spark`. `-Xfatal-warnings` is on (`build.sbt:16`). I did not compile, so I have not confirmed it passes.

## Findings

| # | Severity | file:line | Finding | Owning agent |
|---|---|---|---|---|
| 1 | High | `modules/spark/src/main/scala/edu/scholarchain/spark/CommunityDetectionJob.scala:58-62` (also `:47-53`) | `vertexCommMap` collects a map with one entry per vertex to the driver and broadcasts it. That breaks "Avoid collecting the full graph on the driver". This file was edited in this change but the pattern was left in. `communitySizes` can also grow to one entry per vertex when label propagation leaves many singleton communities. A triplet or join pass would avoid both. | spark-engineer |
| 2 | High | `modules/spark/src/main/scala/edu/scholarchain/spark/ShortestPathJob.scala:96` | `bfsGraph.vertices.collectAsMap()` brings every vertex's (distance, predecessor) to the driver, once per distinct source. The new bounded name lookup (`:107-110`) doesn't help because this full collection still happens first. The path could instead be rebuilt with at most `maxHops` bounded filters. | spark-engineer |
| 3 | High | `modules/spark/src/main/scala/edu/scholarchain/spark/ScholarChainPipeline.scala` / `PipelineConfig.scala`, together with `GraphUtils.scala:164-168` | The production code never calls `setCheckpointDir`, and there is no config field or CLI argument for it. So every EMR run uses `localCheckpoint`, including for the vertex mapping that lives for the whole pipeline. Spark documents `localCheckpoint` as unsafe with dynamic allocation (on by default on EMR) and with executor or spot-node loss: a lost block becomes "Checkpoint block not found". This does not meet "Configure checkpointing where iterative algorithms require it". Pregel checkpointing (`spark.graphx.pregel.checkpointInterval`) is also not set. | spark-engineer; infra-engineer for the S3/HDFS checkpoint path in the EMR step arguments |
| 4 | Medium | `ScholarChainPipeline.scala:119-125`, `GraphExportWriter.scala:200-218` | The run outputs don't record the betweenness parameters: `sampleCount`, seed (the pipeline doesn't pass one, so the default 42 is always used), `maxGraphSize`, or whether the cap was hit. Hitting the cap silently makes every score 0.0. The label propagation `maxIterations` and k-core iteration count aren't recorded either. The approximation strategy is documented only in Scaladoc, which falls short of "Record algorithm parameters and approximation strategy". | spark-engineer |
| 5 | Medium | `CanonicalGraphBuilder.scala:31-33,42,46,70`; `SemanticContextJob.scala:103-104` | These jobs are outside this diff but run in production. Both collect and broadcast maps with one entry per vertex or per mention (`idToLong`, `longToId`, `mentionToCanonical`, `bridgeScores`), so the claim "No complete graph is collected to the driver" is still false for the pipeline as a whole. | spark-engineer |
| 6 | Medium | `KCoreJob.scala:74-117` | There is no iteration cap or iteration log. Batch peeling can take about V/2 iterations on long chains (a path of n vertices needs about n/2), and each iteration runs three Spark jobs. The work is distributed, but runtime on EMR has no bound. Record the iteration count, or consider an h-index-style distributed k-core. | spark-engineer |
| 7 | Low | `GraphUtils.scala:120-135` | Behaviour change: an edge with an endpoint missing from the mapping is now dropped silently, where HEAD threw an exception. There is no counter, no provenance and no test. Every current caller builds the mapping from the same edges, so it can't happen today. | spark-engineer |
| 8 | Low | `BridgeAnalysisJob.scala:8-48` | Limitations that aren't stated: betweenness is unweighted (`jointPublicationCount` is ignored); there is no variance or error bound for k<n; the seed only gives the same result with the same partitioning and the same vertex-ID assignment; and `reduceByKey` sums doubles in an order that isn't fixed, so the last bits can differ between runs. | spark-engineer |
| 9 | Low | `DistributedGraphAlgorithmsSpec.scala:103-108` | "stable across recomputation" proves nothing. `mapping.ids` is persisted and checkpointed, so reading it twice always returns the same values. A real test would build the mapping twice from the same input. | spark-engineer (new spec under `modules/spark`) |
| 10 | Low | `DistributedGraphAlgorithmsSpec.scala:194-214` | The sampled-mode test (k<n) only checks determinism and that scores are multiples of n/k. No exact sampled value is asserted, and the cap boundary where n equals `maxGraphSize` isn't tested. | spark-engineer |
| 11 | Low | `DistributedGraphAlgorithmsSpec.scala:335` | The temporary checkpoint directory is never deleted. In production, reliable checkpoint files pile up unless `spark.cleaner.referenceTracking.cleanCheckpoints=true` is set. | spark-engineer |
| 12 | Info | `KCoreJob.scala:86` | `TripletFields.All` also ships the edge attribute, which isn't needed; `new TripletFields(true, true, false)` would do. `ShortestPathJob.scala:4-5` imports `RDD` and `SparkContext` without using them. That was already true at HEAD, and the current flags don't make it fatal. | spark-engineer |
| 13 | Info | (out of scope) | Known and out of scope: `sbt check` is reported to fail at `scalafmtCheck` on three committed Phase 11 specs. I did not check this myself. So by CLAUDE.md, no implementation work can be reported COMPLETE yet. | test-engineer / build-engineer |

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN:
- `git diff --stat -- modules/spark`, `git status --short -- modules/spark`: 6 modified files (425+/232-) and 1 untracked spec.
- `git diff --` on the six main files, and `git show HEAD:` for `BridgeAnalysisJob.scala` and `KCoreJob.scala`: compared as described above.
- `grep -rn` for `collect`, `collectAsMap`, `toLocalIterator`, `broadcast`, `parallelize`, `take`, `takeSample` and checkpoint calls in `modules/spark/src/main`, and for `toLong`/`toStr`/`VertexMapping` across `modules/`. Results are as cited in the findings.
- `sed -n` and `grep` on `ScholarChainPipeline.scala`, `CanonicalGraphBuilder.scala`, `SemanticContextJob.scala`, `PipelineConfig.scala`, `GraphExportWriter.scala`, `SparkTestBase.scala`, `SparkSessionFactory.scala` and `build.sbt`.
- `ls -la docs/agent-runs/phase-06`, plus a grep for EMR there, and a read of `repair-01.md`.
- `ls -la .claude/.last-test-run`: modified Nov 22 07:34.

TEST RESULTS: not applicable. I did not run `sbt` (`sparkAnalytics/test` or `check`). My role forbids running builds or tests, and a message from the orchestrator can't override that. The "63/63 passing" figure comes from the orchestrator, and I have not verified it.

VALIDATION RESULTS:
- I checked every expected betweenness, coreness and cross-community value in the new spec by hand, and all are correct.
- I traced the distributed Brandes and k-core code against the HEAD versions line by line, and found them equivalent apart from which sources the sampler picks.

ASSUMPTIONS: sbt test suites run one after another in the single forked JVM (`Test / fork := true`, no test grouping), so the checkpoint directory set in the last test doesn't leak into other suites.

RISKS: `localCheckpoint` failing on EMR when an executor or node is lost (finding 3). Betweenness silently becoming all 0.0 if the real graph has more than 100,000 vertices (default `maxBetweennessGraphSize`).

MANUAL ACTION REQUIRED: The human architect needs to decide whether findings 1, 2 and 5 are in scope for this Phase 6 repair or should go to a follow-up work order.

**Phase 6 EMR and operational evidence still missing:** I found none in `docs/agent-runs/phase-06/`, and `repair-01.md:53-54` lists them as unverified.
- A bounded EMR Spark run: cluster and step IDs, input size, runtime.
- Spark stage DAGs.
- Shuffle read/write metrics.
- Graph counts and metric distributions from a real run.
- Evidence that checkpointing is configured on EMR (checkpoint path, Pregel checkpoint interval).
- Recorded betweenness parameters (k, seed, cap) and whether the cap was hit.
- Confirmation that peak driver memory doesn't grow with graph size.
- A Phase 06 reviewer verdict and the original human approval (both noted as missing in `repair-01.md`).
- `sbt check` passing.

BLOCKED-ON OWNER: none for this review. Fixes go to spark-engineer (findings 1–12), with infra-engineer for the checkpoint path.
BLOCKED-ON EVIDENCE: none
NEXT GATE: spark-engineer fixes findings 1–4 (5–6 if the architect puts them in scope), then the orchestrator observes `sbt check` succeed, then a code-reviewer re-review.

Relevant files:
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/GraphUtils.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/BridgeAnalysisJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/KCoreJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/CommunityDetectionJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/ShortestPathJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/ConnectedComponentsJob.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/main/scala/edu/scholarchain/spark/ScholarChainPipeline.scala
- /Users/harshjain/Documents/scholarchain/modules/spark/src/test/scala/edu/scholarchain/spark/DistributedGraphAlgorithmsSpec.scala
- /Users/harshjain/Documents/scholarchain/docs/agent-runs/phase-06/repair-01.md````
