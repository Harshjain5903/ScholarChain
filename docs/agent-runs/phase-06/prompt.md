# Phase 06 – Prompt

Restored by repair-01 (see `repair-01.md`). The earlier copy of this file was the
Phase 00 prompt and has been replaced. Both work orders below are copied word for
word from the saved Claude Code session transcripts named in each heading.

## Source A: original work order (session `3d8f182b-9b29-4a2f-9165-7969ff0f20bd`)

Pasted by the human architect at 2026-11-19T12:52:19Z (message
`b885f904-9e6e-4d0f-b586-4dfeb8ee8d39`). The same text was pasted again at
2026-11-19T16:53:25Z (message `b3a7b0b4-8a62-4904-9b8f-57f211fa0169`).

```text
Use the spark-engineer agent to build the complete Spark analytics pipeline under modules/spark/.

I need all the graph jobs from the spec — canonical researcher builder, temporal coauthorship graph, the 6 collaboration metrics (P_r, C_r, T_r, A_r, M_r, W_r), connected components, shortest paths with landmark sampling, label propagation communities, betweenness centrality bridge analysis, k-core decomposition, temporal window analysis, semantic context prep, and Parquet export with Neptune/DynamoDB/S3 Vectors manifests.

Use GraphX for components, paths, communities, and bridge analysis. Use DataFrame-based iterative peeling for k-core. All jobs should consume the Hadoop outputs from Phase 4 and entity resolution from Phase 5.

Include reference.conf with sensible defaults and hand-checkable small-graph tests for every job.

Run sbt check when done.
```

Outcome: at 2026-11-19T12:53Z the orchestrator delegated this to spark-engineer
subagent `a265cebae5e97e251`. That run stopped at the usage limit ("You've hit
your 5-hour limit") and returned no report. The orchestrator's delegated prompt for
that run is still in the session transcript but is not copied here.

## Source B: resumed work order (session `7aafb09f-5927-4b74-9c74-70dface1b0d2`)

### B1. Human instruction

Sent by the human architect at 2026-11-19T23:19:22Z (message
`2d53c5f2-9ffd-4c23-a6a9-4b4739ea76b9`).

```text
Phase 6 (Spark graph construction + analytics) if not done```

### B2. Work order the orchestrator delegated to spark-engineer

Sent at 2026-11-19T23:20:46Z to spark-engineer subagent `a7d646aded1479f87`. This is
the work order that produced the report in `agent-report.md`.

````text
Implement the ScholarChain Spark collaboration-analysis pipeline under modules/spark/.

## Context

You are the spark-engineer agent. Your owned artifacts are under `modules/spark/**`. The build is sbt multi-project Scala 3 (3.3.4). The spark module depends on core. Package: `edu.scholarchain.spark`.

## Build configuration

The sparkAnalytics module in build.sbt:
```scala
lazy val sparkAnalytics = (project in file("modules/spark"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "scholarchain-spark",
    libraryDependencies ++= sparkProvided,
    excludeDependencies += "org.scala-lang.modules" % "scala-xml_2.13",
    assembly / mainClass := None,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)              => MergeStrategy.discard
      case "reference.conf"                      => MergeStrategy.concat
      case _                                     => MergeStrategy.first
    }
  )
```

Spark dependencies (all Provided scope, cross-compiled for 2.13 from Scala 3):
- spark-core 3.5.3
- spark-sql 3.5.3
- spark-graphx 3.5.3

Other deps available from commonSettings: upickle 4.0.2, typesafe-config 1.4.3, slf4j 2.0.16, scalatest 3.2.19.

## Existing domain types (in `edu.scholarchain.core`)

All use `derives ReadWriter` (uPickle):
- `Provenance(sourceDataset, sourceRecordId, pipelineRunId, schemaVersion, timestamp)`
- `WorkRecord(workId, source: SourceType, sourceRecordId, title, normalizedTitle, year: Int, venueId, doi, arxivId, authors: Vector[AuthorMention], abstractTextRef, provenance)`
- `AuthorMention(mentionId, rawName, normalizedName, position: Int, orcid, openAlexId, affiliationRaw, sourceRecordId, provenance)`
- `CanonicalResearcher(researcherId, displayName, orcid, openAlexId, semanticScholarId, alternateNames, provenance)`
- `AuthorshipRecord(researcherId, workId, authorPosition: Int, confidence: Double, provenance)`
- `CoauthorPairCandidate(mentionA, mentionB, workId, year: Int, provenance)` — mentionA < mentionB enforced
- `CoauthorEdge(researcherA, researcherB, jointPublicationCount: Int, firstCollaborationYear: Int, lastCollaborationYear: Int, representativeWorkIds, provenance)` — researcherA < researcherB enforced
- `ResearcherMetrics(researcherId, publicationCount: Int, distinctCoauthorCount: Int, totalCoauthorOccurrences: Int, averageCoauthorsPerPaper: Double, maximumCoauthorsOnOnePaper: Int, weightedDegree: Double, componentId: Option[String], communityId: Option[String], bridgeScore: Double, coreness: Int, windowStartYear: Int, windowEndYear: Int, provenance)`
- `MatchDecision(sourceId, targetId, features, decision: MatchOutcome, confidence, reasonCodes, policyVersion, runId)`
- `GraphNode(nodeId, nodeType, properties: Map[String,String], provenance)`
- `GraphEdge(edgeId, sourceNodeId, targetNodeId, edgeType, properties, provenance)`
- `EvidenceItem(evidenceId, evidenceType: EvidenceType, sourceEntityId, content, score, provenance)`
- Enums: `SourceType`, `MatchOutcome`, `EvidenceType`, `CollaborationMetric`, `QueryOperation`, `VerificationStatus`
- `IdGen` with `workId`, `researcherId`, `coauthorEdgeId`, `chunkId`, `runId`
- `Topic(topicId, name, normalizedName, source, provenance)`, `Institution(institutionId, name, normalizedName, source, provenance)`, `Venue(venueId, name, normalizedName, source, provenance)`

## Hadoop outputs consumed by Spark

The Hadoop GraphExportJob writes partitioned JSON lines to S3:
- work_records (partitioned by source type)
- author_mentions (partitioned by source type)
- teams (partitioned by year decade — PublicationTeam records)
- pair_candidates (partitioned by year decade — CoauthorPairCandidate records)
- author_paper (partitioned by source — AuthorshipRecord-like)
- topics (Topic records)
- institutions (Institution records)

The entity-resolution module produces MatchDecision records (Accepted/Rejected/Unresolved/Conflict).

## What to implement

Create the following Spark jobs/objects under `modules/spark/src/main/scala/edu/scholarchain/spark/`:

### 1. `SparkSessionFactory.scala`
Factory to create a configured SparkSession. Accept app name, master (default "local[*]"), and optional configs. Useful for tests and production.

### 2. `DataLoader.scala`
Load Hadoop JSON-line outputs into Spark DataFrames or Datasets. Methods to load:
- WorkRecords, AuthorMentions, CoauthorPairCandidates, MatchDecisions, Topics, Institutions
- Use `spark.read.json(path)` or similar. Parse uPickle JSON into typed case classes via map operations.

### 3. `CanonicalGraphBuilder.scala`
**Job 1 & 2: Build canonical researcher/authorship tables and temporal weighted coauthorship graph**
- Read accepted MatchDecisions and Hadoop author-paper/pair-candidate outputs
- Map pair candidates onto canonical researcher IDs using accepted entity links
- Drop self-pairs created by identity resolution
- Canonicalize pair order (researcherA < researcherB)
- Aggregate duplicate pairs: sum jointPublicationCount, min firstCollaborationYear, max lastCollaborationYear, collect representativeWorkIds
- Support configurable year windows (windowStart, windowEnd parameters)
- Output: RDD/Dataset of CoauthorEdge

### 4. `CollaborationMetricsJob.scala`
**Job 3: Researcher collaboration breadth and team-size statistics**
For each researcher and time window compute:
- P_r = publicationCount
- C_r = distinctCoauthorCount  
- T_r = totalCoauthorOccurrences
- A_r = averageCoauthorsPerPaper
- M_r = maximumCoauthorsOnOnePaper (and the work IDs responsible)
- W_r = weightedDegree (sum of edge weights)
Keep every component visible — do NOT collapse into one opaque score.
Output: RDD/Dataset of ResearcherMetrics (partially filled, component/community/bridge filled by later jobs)

### 5. `ConnectedComponentsJob.scala`
**Job 4: Connected components**
- Use GraphX to compute connected components of the coauthorship graph
- Store componentId and componentSize per researcher
- Report fraction of researchers in largest component and number of singletons

### 6. `ShortestPathJob.scala`
**Job 5: Researcher connectivity and path support**
- Provide bounded shortest-path computation between researcher pairs using GraphX Pregel or BFS
- Materialize path evidence for benchmark queries (take a list of researcher pairs as input)
- Do NOT compute all-pairs shortest paths or collect the full graph on the driver

### 7. `CommunityDetectionJob.scala`
**Job 6: Collaboration communities**
- Implement Label Propagation Algorithm (LPA) using GraphX
- Store communityId, communitySize, and cross-community edge count per researcher

### 8. `BridgeAnalysisJob.scala`
**Job 7: Bridge analysis**
- Compute approximate betweenness centrality using sampling (pick k random source vertices, run BFS from each, count path contributions)
- Also compute cross-community degree (number of distinct communities a researcher's neighbors belong to)
- Document the approximation strategy and parameters

### 9. `KCoreJob.scala`
**Job 8: K-core decomposition**
- Iteratively remove vertices with degree < k until stable
- Record coreness (maximum k for which the vertex is in the k-core)
- Test against small hand-checkable graphs

### 10. `TemporalAnalysisJob.scala`
**Job 9: Temporal collaboration analysis**
- Recompute selected metrics over configured time windows (e.g., 5-year windows)
- Track changes in distinct coauthors, team sizes, component membership, community membership
- Output temporal metric snapshots

### 11. `SemanticContextJob.scala`
**Job 10: Semantic-context preparation**
- Generate researcher-profile summaries from computed data (top coauthors, publication count, topics, institutions)
- Generate community summaries (member count, dominant topics, key bridge researchers)
- Generate collaboration-path summaries for selected pairs
- Output text summaries for later embedding — generated from real data, not invented

### 12. `GraphExportWriter.scala`
**Job 11: Export**
- Write partitioned Parquet for nodes, edges, metrics, communities, components, path-evidence, semantic summaries
- Produce load manifests for Neptune, DynamoDB, S3 Vectors, and ordinary S3
- Output layout following s3://scholarchain-graph/ and s3://scholarchain-curated/ patterns

### 13. `ScholarChainPipeline.scala`
Main entry point that orchestrates all jobs in sequence. Accept config for:
- Input paths (Hadoop outputs, match decisions)
- Output paths
- Time window (windowStart, windowEnd)
- Run ID
- Number of partitions
- Approximate betweenness sample count

### Tests under `modules/spark/src/test/scala/edu/scholarchain/spark/`

Create a comprehensive test suite with at least 20 tests. Use a local SparkSession ("local[*]"). Include:

1. **Small known-graph tests** — build a hand-checkable graph (e.g., 5-6 researchers, known edges):
   - Verify distinct coauthor count matches expected values
   - Verify weighted degree matches expected values
   - Verify team-size statistics (max, average)
   - Verify connected components (number, sizes)
   - Verify shortest path between connected pairs
   - Verify no path between disconnected pairs
   - Verify community detection produces valid assignments
   - Verify k-core (e.g., triangle = coreness 2)
   - Verify bridge score (vertex connecting two cliques has higher bridge score)

2. **Pair canonicalization tests**:
   - Self-pairs are dropped
   - Duplicate pairs are aggregated correctly
   - researcherA < researcherB ordering is maintained

3. **Temporal window tests**:
   - Edges outside window are excluded
   - Metrics reflect only the windowed data

4. **Edge cases**:
   - Empty graph
   - Single-node graph
   - Disconnected graph with multiple components

5. **Semantic context generation**:
   - Researcher profile contains expected fields
   - Community summary is generated from real data

## Critical implementation rules

- Package: `edu.scholarchain.spark`
- Use Spark DataFrame/Dataset/RDD APIs and GraphX — do NOT collect the full graph to the driver
- Use `upickle.default.*` for JSON serialization of core types
- Spark 3.5.3 with Scala 2.13 cross-compiled from Scala 3 — use Java-compatible APIs where needed
- GraphX uses `Long` vertex IDs — you'll need a mapping from String researcher IDs to Long IDs
- Do NOT use `var` or mutable shared state outside of Spark-managed accumulators
- Keep `-Xfatal-warnings` compatibility
- Scala 3 syntax throughout
- Do NOT modify any files outside `modules/spark/`

## Verification

After creating all files:
1. Run `sbt sparkAnalytics/compile`
2. Run `sbt sparkAnalytics/test`
3. Run `sbt sparkAnalytics/scalafmtCheckAll`

Report: job DAGs, partition strategy, graph counts from test fixtures, metric distributions, and any compatibility issues.
````

## Difference from the instructor's Phase 6 work order

Neither Source A nor Source B is the instructor README's Phase 6 work order word for
word. For example, neither asks for the bounded EMR Spark run, or for stage DAGs and
shuffle metrics, that the README requires.
