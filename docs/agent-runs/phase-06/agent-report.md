# Phase 06 – Agent Report

Restored by repair-01 (see `repair-01.md`). The earlier copy of this file was the
Phase 00 agent report and has been replaced.

**Source:** the final message of spark-engineer subagent `a7d646aded1479f87`
(transcript `agent-a7d646aded1479f87.jsonl`, session
`7aafb09f-5927-4b74-9c74-70dface1b0d2`), sent 2026-11-19T23:56:19Z. It is copied
word for word below.

---

15 main source files, 7 test files. 33 tests total. All pass. Here is the report.

```
STATUS: BLOCKED-ON
OWNER: spark-engineer
WORK ORDER: Implement ScholarChain Spark collaboration-analysis pipeline (13 jobs + 33 tests)
FILES CREATED:
  modules/spark/src/main/scala/edu/scholarchain/spark/PipelineConfig.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/SparkSessionFactory.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/DataLoader.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/GraphUtils.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/CanonicalGraphBuilder.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/CollaborationMetricsJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/ConnectedComponentsJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/ShortestPathJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/CommunityDetectionJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/BridgeAnalysisJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/KCoreJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/TemporalAnalysisJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/SemanticContextJob.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/GraphExportWriter.scala
  modules/spark/src/main/scala/edu/scholarchain/spark/ScholarChainPipeline.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/SparkTestBase.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/TestFixtures.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/CanonicalGraphBuilderSpec.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/GraphAnalyticsSpec.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/TemporalAnalysisSpec.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/SemanticContextSpec.scala
  modules/spark/src/test/scala/edu/scholarchain/spark/GraphExportWriterSpec.scala
FILES MODIFIED: none
COMMANDS RUN:
  sbt sparkAnalytics/compile (success)
  sbt sparkAnalytics/Test/compile (success)
  sbt sparkAnalytics/scalafmtCheckAll (success)
  sbt sparkAnalytics/test (with forked JVM and --add-opens flags: 33/33 passed)
  sbt check (with forked JVM: fails due to pre-existing storage module formatting issues, not spark-related)
TEST RESULTS:
  33 tests, 33 succeeded, 0 failed across 5 test suites:
    CanonicalGraphBuilderSpec (6 tests): self-pair drop, duplicate aggregation, ordering, time window, empty graph, provenance
    GraphAnalyticsSpec (18 tests): coauthor count, weighted degree, team-size stats, connected components (3), shortest paths (3), community detection (2), bridge analysis (2), k-core (3), empty/single-node graphs (2)
    TemporalAnalysisSpec (3 tests): window generation, windowed snapshots, empty edges
    SemanticContextSpec (3 tests): researcher profiles, community summaries, path summaries
    GraphExportWriterSpec (3 tests): Parquet edges, metrics, JSON manifest
VALIDATION RESULTS:
  Test graph fixture (6 researchers, 5 edges):
    - 2 connected components (sizes 5, 1)
    - Largest component fraction: 83.3%
    - 1 singleton (R6)
    - Shortest path R1->R5: 3 hops
    - No path R1->R6 (disconnected)
    - Triangle R1-R2-R3: coreness 2
    - Leaf R5: coreness 1, Isolated R6: coreness 0
    - R2 (bridge) has higher betweenness than R5 (leaf)
    - R1 weighted degree: 3.0, R2 weighted degree: 4.0
    - Parquet exports verified readable
ASSUMPTIONS:
  - Broadcast variables are not explicitly destroyed (causes errors with lazy RDDs in Spark)
  - K-core uses outDegrees (undirected graph has symmetric edges in GraphX)
  - Approximate betweenness: Brandes algorithm runs on driver for graphs under maxBetweennessGraphSize (100k vertices); larger graphs get cross-community degree only
  - CoauthorPairCandidate mention IDs are mapped to canonical researcher IDs via connected components of accepted MatchDecisions
RISKS:
  - sbt check cannot pass without build.sbt changes (need fork + javaOptions for Spark tests on Java 17+)
  - Pre-existing storage module formatting failures block sbt check independently
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: build-engineer
BLOCKED-ON EVIDENCE:
  Spark 3.5.3 tests require forked JVM with Java module access flags on Java 17+. The sparkAnalytics module in build.sbt needs:
    Test / fork := true
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED"
    )
  Additionally, pre-existing scalafmt failures in storage module block `sbt check` (route to storage-engineer).
NEXT GATE: build-engineer adds fork/javaOptions to build.sbt; storage-engineer fixes formatting; then sbt check passes end-to-end.
```

**Job DAG** (implemented in `ScholarChainPipeline.scala`):

```
DataLoader -> CanonicalGraphBuilder (Jobs 1-2) -> CollaborationMetricsJob (Job 3)
  -> ConnectedComponentsJob (Job 4)
  -> CommunityDetectionJob (Job 6)
  -> BridgeAnalysisJob (Job 7)
  -> KCoreJob (Job 8)
  -> ShortestPathJob (Job 5)
  -> TemporalAnalysisJob (Job 9)
  -> SemanticContextJob (Job 10)
  -> GraphExportWriter (Job 11)
```

**Partition strategy**: Edges are partitioned by default Spark hash partitioning. GraphX uses EdgePartition2D when constructing graphs. Parquet exports partition edges by decade. Configurable via `numPartitions` in `PipelineConfig`.

**Key design decisions**:
- GraphX vertex IDs (Long) are assigned via `zipWithUniqueId` on distinct researcher ID strings, with bidirectional mapping maintained in `GraphUtils.VertexMapping`
- Undirected graphs use symmetric directed edges (both a->b and b->a) for GraphX compatibility
- Approximate betweenness uses sampled Brandes algorithm on driver (bounded by `maxBetweennessGraphSize`), with cross-community degree as a scalable complement
- K-core uses iterative peeling with tail-recursive accumulation
- All outputs carry `Provenance` tracing back to `spark-analytics` pipeline, run ID, and schema version
---

## Repair notes (added by repair-01; not part of the agent's report)

- **STATUS stays BLOCKED-ON.** This is the agent's final recorded status for Phase 06.
- **Later JVM fix.** Build-engineer subagent `a0dc01ce71eff5939` (same session,
  2026-11-19T23:56Z–2026-11-20T00:04Z) added `Test / fork := true` and the
  `--add-opens` `Test / javaOptions` to the `sparkAnalytics` project in `build.sbt`.
  Its report says `sparkAnalytics/test` passed (33 tests, 0 failures). It also says
  `sbt check` failed on pre-existing scalafmt violations in `modules/storage/`, and
  it set BLOCKED-ON owner to storage-engineer. This `build.sbt` change was committed
  in `54e0245`, together with the Spark sources.
- **Spark-engineer rerun: not recorded.** No saved session shows the spark-engineer
  being invoked after `a7d646aded1479f87`.
- **`sbt check` passing for Phase 06: not recorded.**
