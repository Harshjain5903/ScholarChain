package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.hadoop.fs.Path
import org.apache.spark.graphx.PartitionStrategy
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.slf4j.LoggerFactory

/**
 * Main entry point that orchestrates all Spark analytics jobs in sequence.
 *
 * Inputs (Hadoop export and entity resolution):
 *   - `<hadoop-output>/pair_candidates`: CoauthorPairCandidate JSON lines (mention-level pairs)
 *   - `<hadoop-output>/teams`: PublicationTeam JSON lines (every work with its author mentions,
 *     including single-author works)
 *   - `<match-decisions>`: MatchDecision JSON lines; only `Accepted` decisions merge mentions
 *
 * Job DAG:
 * {{{
 *   DataLoader
 *       |
 *   CanonicalGraphBuilder (Job 1-2): mention mapping, canonical pairs, edges, authorships
 *       |
 *   CollaborationMetricsJob (Job 3)
 *       |
 *   ConnectedComponentsJob (Job 4) ----+
 *       |                              |
 *   CommunityDetectionJob (Job 6)      |
 *       |                              |
 *   BridgeAnalysisJob (Job 7) ---------+
 *       |
 *   KCoreJob (Job 8)
 *       |
 *   ShortestPathJob (Job 5) -- explicit pairs or deterministic sample in the largest component
 *       |
 *   TemporalAnalysisJob (Job 9) -- pair-level, per window
 *       |
 *   SemanticContextJob (Job 10)
 *       |
 *   GraphExportWriter (Job 11) + NeptuneBulkLoadExport (openCypher CSV) + run-stats
 * }}}
 *
 * Distributed-execution contract: per-vertex and per-edge data never reach the driver; the driver
 * only receives scalar counts and bounded samples (top-k lists, the requested shortest paths, and
 * per-window / per-outcome / per-coreness histograms). Iterative stages truncate lineage with
 * reliable checkpoints in [[PipelineConfig.effectiveCheckpointDir]], and GraphX Pregel checkpoints
 * every [[PipelineConfig.pregelCheckpointInterval]] supersteps.
 */
object ScholarChainPipeline:

  private val logger = LoggerFactory.getLogger(getClass)

  /** Sub-directory (under the output root) holding the run statistics JSON. */
  val RunStatsDir: String = "run-stats"

  /**
   * Run the full pipeline and return the run statistics (also written to `<output>/run-stats`).
   *
   * @param spark
   *   active SparkSession; for reliable checkpoints its context should have a checkpoint directory
   *   (set by [[SparkSessionFactory.forPipeline]])
   * @param config
   *   pipeline configuration
   */
  def run(spark: SparkSession, config: PipelineConfig): ujson.Obj =
    val sc        = spark.sparkContext
    val timestamp = config.runTimestamp.getOrElse(java.time.Instant.now().toString)
    val parts     = Some(config.numPartitions)
    val ws        = config.windowStartYear
    val we        = config.windowEndYear
    val out       = config.outputPath.stripSuffix("/")
    val t0        = System.nanoTime()

    logger.info(s"Loading data from Hadoop outputs: ${config.hadoopOutputPath}")
    val pairCandidates =
      DataLoader
        .loadCoauthorPairCandidates(spark, config.pairCandidatesPath, parts)
        .persist(StorageLevel.MEMORY_AND_DISK)
    val teams =
      DataLoader
        .loadPublicationTeams(spark, config.teamsPath, parts)
        .persist(StorageLevel.MEMORY_AND_DISK)
    val decisions = DataLoader.loadMatchDecisions(spark, config.matchDecisionsPath, parts)
    decisions.persist(StorageLevel.MEMORY_AND_DISK)
    val accepted = decisions.filter(_.decision == MatchOutcome.Accepted)

    val pairCandidatesIn = pairCandidates.count()
    val pairCandidatesInWindow =
      pairCandidates.filter(p => p.year >= ws && p.year <= we).count()
    val teamsIn          = teams.count()
    val teamsInWindow    = teams.filter(t => t.year >= ws && t.year <= we)
    val worksInWindow    = teamsInWindow.count()
    val mentionsInWindow = teamsInWindow.map(_.authorMentions.size.toLong).fold(0L)(_ + _)
    val decisionsByOutcome =
      decisions.map(d => (d.decision.toString, 1L)).reduceByKey(_ + _).collect().toMap
    val decisionPolicies = decisions.map(_.policyVersion).distinct().take(10).sorted.toVector
    val decisionRunIds   = decisions.map(_.runId).distinct().take(10).sorted.toVector
    val pairRunIds       = pairCandidates.map(_.provenance.pipelineRunId).distinct().take(10).sorted

    logger.info("Building canonical coauthorship graph (Jobs 1-2)")
    val mentionMapping = CanonicalGraphBuilder.matchedMentionMapping(accepted)
    GraphUtils.checkpointAndCount(mentionMapping)
    val mentionsInAcceptedMatches = mentionMapping.count()
    val mentionsMergedAway        = mentionMapping.filter { case (m, c) => m != c }.count()

    val canonicalPairs =
      CanonicalGraphBuilder.canonicalPairs(pairCandidates, mentionMapping, ws, we)
    val canonicalPairCount = GraphUtils.checkpointAndCount(canonicalPairs)

    val edges = CanonicalGraphBuilder.aggregatePairs(
      canonicalPairs,
      ws,
      we,
      config.runId,
      config.schemaVersion,
      config.policyVersion,
      timestamp
    )
    val edgeCount = GraphUtils.checkpointAndCount(edges)

    val authorships     = CanonicalGraphBuilder.canonicalAuthorships(teams, mentionMapping, ws, we)
    val authorshipCount = GraphUtils.checkpointAndCount(authorships)

    logger.info("Building GraphX graph")
    val (rawGraph, mapping) =
      GraphUtils.buildGraphWithIsolates(sc, edges, authorships.map(_.researcherId))
    val graph =
      rawGraph.partitionBy(PartitionStrategy.EdgePartition2D, config.numPartitions).cache()
    val vertexCount       = graph.numVertices
    val directedEdgeCount = graph.numEdges

    logger.info("Computing collaboration metrics (Job 3)")
    val initialMetrics = CollaborationMetricsJob.compute(
      sc,
      edges,
      authorships.map(a =>
        CollaborationMetricsJob.AuthorshipInfo(a.researcherId, a.workId, a.coauthorsOnPaper)
      ),
      ws,
      we,
      config.runId,
      config.schemaVersion,
      timestamp
    )

    logger.info("Computing connected components (Job 4)")
    val ccResult      = ConnectedComponentsJob.compute(graph, mapping)
    val metricsWithCC = ConnectedComponentsJob.enrichMetrics(initialMetrics, ccResult)

    logger.info("Computing communities (Job 6)")
    val communityResult = CommunityDetectionJob.compute(graph, mapping)
    val metricsWithComm = CommunityDetectionJob.enrichMetrics(metricsWithCC, communityResult)

    logger.info("Computing bridge analysis (Job 7)")
    val communityAssignments = communityResult.assignments.map { case (rid, commId, _) =>
      (rid, commId)
    }
    val bridgeResult = BridgeAnalysisJob.compute(
      graph,
      mapping,
      communityAssignments,
      sampleCount = config.betweennessSampleCount,
      maxGraphSize = config.maxBetweennessGraphSize
    )
    val metricsWithBridge = BridgeAnalysisJob.enrichMetrics(metricsWithComm, bridgeResult)

    logger.info("Computing k-core decomposition (Job 8)")
    val coreness     = KCoreJob.compute(graph, mapping)
    val finalMetrics = KCoreJob.enrichMetrics(metricsWithBridge, coreness)
    val metricsCount = GraphUtils.checkpointAndCount(finalMetrics)

    logger.info("Computing shortest paths (Job 5)")
    val explicitPairs =
      config.pathPairs ++ config.pathPairsFile.toSeq.flatMap(readPairsFile(spark, _))
    val pathPairSource =
      if explicitPairs.nonEmpty then "explicit"
      else
        s"deterministic-sample(top-weighted-degree, largest component, n=${config.pathSampleCount})"
    val pathPairs =
      if explicitPairs.nonEmpty then explicitPairs
      else
        ShortestPathJob.selectPairs(
          finalMetrics,
          ccResult.largestComponentId,
          config.pathSampleCount
        )
    val pathResults =
      if pathPairs.nonEmpty then
        ShortestPathJob.compute(graph, mapping, pathPairs, config.maxPathHops)
      else Seq.empty

    logger.info("Computing temporal analysis (Job 9)")
    val windows = TemporalAnalysisJob.generateWindows(
      ws,
      we,
      config.temporalWindowSize,
      config.temporalStep
    )
    val temporalSnapshots = TemporalAnalysisJob.computeFromPairs(
      sc,
      canonicalPairs,
      authorships,
      windows,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    GraphUtils.checkpointAndCount(temporalSnapshots)

    logger.info("Generating semantic context (Job 10)")
    val profiles = SemanticContextJob.researcherProfiles(
      sc,
      finalMetrics,
      edges,
      topN = 5,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    val commSummaries = SemanticContextJob.communitySummaries(
      sc,
      finalMetrics,
      bridgeResult.scores,
      topBridgeCount = 3,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    val pathSummaries = SemanticContextJob.pathSummaries(
      sc,
      pathResults,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    val allSummaries = profiles.union(commSummaries).union(pathSummaries)
    GraphUtils.checkpointAndCount(allSummaries)

    logger.info("Writing exports (Job 11)")
    GraphExportWriter.writeEdges(spark, edges, out)
    GraphExportWriter.writeMetrics(spark, finalMetrics, out)
    GraphExportWriter.writeSummaries(spark, allSummaries, out)
    GraphExportWriter.writePaths(
      spark,
      pathResults,
      out,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    GraphExportWriter.writeComponents(spark, ccResult.assignments, out)
    GraphExportWriter.writeCommunities(spark, communityResult.assignments, out)
    GraphExportWriter.writeTemporal(spark, temporalSnapshots, out)
    val neptuneCounts =
      if config.writeNeptuneCsv then
        Some(
          NeptuneBulkLoadExport.writeAll(
            spark,
            out,
            finalMetrics,
            teamsInWindow.map(t => (t.workId, t.provenance)),
            edges,
            authorships.map(a => (a.researcherId, a.workId)),
            ws,
            we,
            config.policyVersion,
            config.runId,
            config.schemaVersion,
            timestamp
          )
        )
      else None
    GraphExportWriter.writeManifest(spark, out, config.runId, timestamp, config.writeNeptuneCsv)

    // ---- Run statistics: scalars and bounded samples only.
    val byScoreThenId = Ordering.by[(String, Double), (Double, String)] { case (r, s) => (-s, r) }
    val corenessHistogram =
      finalMetrics.map(m => (m.coreness, 1L)).reduceByKey(_ + _).collect().sortBy(_._1)
    val largestCommunity = communityResult.assignments
      .map { case (_, cid, size) => (cid, size) }
      .distinct()
      .takeOrdered(1)(Ordering.by[(String, Long), (Long, String)] { case (c, s) => (-s, c) })
      .headOption
    val topBetweenness            = bridgeResult.scores.takeOrdered(5)(byScoreThenId)
    val betweennessComputed       = vertexCount <= config.maxBetweennessGraphSize
    val crossCommunityResearchers = bridgeResult.crossCommunityDegrees.filter(_._2 > 1).count()
    val summariesByType =
      allSummaries.map(s => (s.entityType, 1L)).reduceByKey(_ + _).collect().toMap
    val temporalByWindow = temporalSnapshots
      .map(t => ((t.windowStartYear, t.windowEndYear), 1L))
      .reduceByKey(_ + _)
      .collect()
      .sortBy(_._1)
    val pubSum = finalMetrics.map(_.publicationCount.toLong).fold(0L)(_ + _)

    val stats = ujson.Obj(
      "runId"     -> config.runId,
      "timestamp" -> timestamp,
      "master"    -> sc.master,
      "config" -> ujson.Obj(
        "hadoopOutputPath"        -> config.hadoopOutputPath,
        "matchDecisionsPath"      -> config.matchDecisionsPath,
        "outputPath"              -> out,
        "windowStartYear"         -> ws,
        "windowEndYear"           -> we,
        "numPartitions"           -> config.numPartitions,
        "betweennessSampleCount"  -> config.betweennessSampleCount,
        "maxBetweennessGraphSize" -> config.maxBetweennessGraphSize.toDouble,
        "schemaVersion"           -> config.schemaVersion,
        "policyVersion"           -> config.policyVersion,
        "checkpointDir" -> sc.getCheckpointDir.getOrElse[String]("<none: localCheckpoint>"),
        "pregelCheckpointInterval" -> sc.getConf
          .get(SparkSessionFactory.PregelCheckpointIntervalKey, "-1"),
        "temporalWindowSize" -> config.temporalWindowSize,
        "temporalStep"       -> config.temporalStep,
        "maxPathHops"        -> config.maxPathHops,
        "neptuneCsv"         -> config.writeNeptuneCsv
      ),
      "inputs" -> ujson.Obj(
        "pairCandidates"         -> pairCandidatesIn.toDouble,
        "pairCandidatesInWindow" -> pairCandidatesInWindow.toDouble,
        "pairCandidateRunIds"    -> ujson.Arr.from(pairRunIds.toSeq),
        "teams"                  -> teamsIn.toDouble,
        "teamsInWindow"          -> worksInWindow.toDouble,
        "authorMentionsInWindow" -> mentionsInWindow.toDouble,
        "matchDecisions" -> ujson.Obj.from(decisionsByOutcome.toSeq.sorted.map { case (k, v) =>
          k -> ujson.Num(v.toDouble)
        }),
        "matchDecisionPolicies" -> ujson.Arr.from(decisionPolicies),
        "matchDecisionRunIds"   -> ujson.Arr.from(decisionRunIds)
      ),
      "canonicalization" -> ujson.Obj(
        "mentionsInAcceptedMatches" -> mentionsInAcceptedMatches.toDouble,
        "mentionsMergedAway"        -> mentionsMergedAway.toDouble,
        "canonicalPairOccurrences"  -> canonicalPairCount.toDouble,
        "authorships"               -> authorshipCount.toDouble
      ),
      "graph" -> ujson.Obj(
        "vertices"            -> vertexCount.toDouble,
        "coauthorEdges"       -> edgeCount.toDouble,
        "graphxDirectedEdges" -> directedEdgeCount.toDouble,
        "researcherMetrics"   -> metricsCount.toDouble,
        "publicationCountSum" -> pubSum.toDouble
      ),
      "components" -> ujson.Obj(
        "count"           -> ccResult.componentCount.toDouble,
        "largestSize"     -> ccResult.largestComponentSize.toDouble,
        "largestFraction" -> ccResult.largestComponentFraction,
        "largestId"       -> ccResult.largestComponentId.getOrElse[String](""),
        "singletons"      -> ccResult.singletonCount.toDouble
      ),
      "communities" -> ujson.Obj(
        "algorithm" -> "deterministic label propagation (CommunityDetectionJob.propagateLabels, maxIterations=20)",
        "count"       -> communityResult.communityCount.toDouble,
        "largestId"   -> largestCommunity.map(_._1).getOrElse[String](""),
        "largestSize" -> largestCommunity.map(_._2.toDouble).getOrElse(0.0)
      ),
      "kcore" -> ujson.Obj(
        "maxCoreness" -> corenessHistogram.lastOption.map(_._1.toDouble).getOrElse(0.0),
        "histogram" -> ujson.Arr.from(corenessHistogram.map { case (k, n) =>
          ujson.Obj("coreness" -> k, "researchers" -> n.toDouble)
        })
      ),
      "bridges" -> ujson.Obj(
        "betweennessComputed" -> betweennessComputed,
        "betweennessSources" -> math
          .min(config.betweennessSampleCount.toLong, vertexCount)
          .toDouble,
        "betweennessSeed"                -> 42,
        "researchersCrossingCommunities" -> crossCommunityResearchers.toDouble,
        "topBetweenness" -> ujson.Arr.from(topBetweenness.map { case (r, s) =>
          ujson.Obj("researcherId" -> r, "score" -> s)
        })
      ),
      "shortestPaths" -> ujson.Obj(
        "pairSource" -> pathPairSource,
        "requested"  -> pathPairs.size,
        "found"      -> pathResults.size,
        "paths" -> ujson.Arr.from(pathResults.map { p =>
          ujson.Obj(
            "source"   -> p.sourceResearcherId,
            "target"   -> p.targetResearcherId,
            "length"   -> p.pathLength,
            "vertices" -> ujson.Arr.from(p.pathVertices)
          )
        })
      ),
      "temporal" -> ujson.Obj(
        "windows"   -> windows.size,
        "snapshots" -> temporalByWindow.map(_._2).sum.toDouble,
        "byWindow" -> ujson.Arr.from(temporalByWindow.map { case ((s, e), n) =>
          ujson.Obj("start" -> s, "end" -> e, "snapshots" -> n.toDouble)
        })
      ),
      "semanticSummaries" -> ujson.Obj.from(summariesByType.toSeq.sorted.map { case (k, v) =>
        k -> ujson.Num(v.toDouble)
      }),
      "neptune" -> neptuneCounts.fold[ujson.Value](ujson.Null) { c =>
        ujson.Obj(
          "researcherNodes"       -> c.researcherNodes.toDouble,
          "workNodes"             -> c.workNodes.toDouble,
          "coauthorRelationships" -> c.coauthorRelationships.toDouble,
          "authoredRelationships" -> c.authoredRelationships.toDouble
        )
      },
      "pipelineSeconds" -> (System.nanoTime() - t0).toDouble / 1e9
    )
    GraphExportWriter.writeJson(spark, stats, s"$out/$RunStatsDir")
    logger.info(s"Run statistics: ${ujson.write(stats)}")

    logger.info("Pipeline complete")
    graph.unpersist(blocking = false)
    pairCandidates.unpersist(blocking = false)
    teams.unpersist(blocking = false)
    decisions.unpersist(blocking = false)
    stats

  /** Read tab-separated `source<TAB>target` lines (blank lines and `#` comments skipped). */
  def readPairsFile(spark: SparkSession, path: String): Seq[(String, String)] =
    spark.sparkContext
      .textFile(path)
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .collect()
      .toSeq
      .map { line =>
        line.split('\t') match
          case Array(s, t) if s.trim.nonEmpty && t.trim.nonEmpty => (s.trim, t.trim)
          case _ =>
            throw new IllegalArgumentException(
              s"path pairs file $path: line '$line' must be 'source<TAB>target'"
            )
      }

  /**
   * Delete this run's checkpoint subdirectory (the per-context UUID directory Spark creates under
   * the configured checkpoint root), and the default `<output>/_checkpoints` root when it is then
   * empty. An explicit `--checkpoint-dir` root is never deleted, only the run's own subdirectory.
   */
  def cleanupCheckpoints(spark: SparkSession, config: PipelineConfig): Unit =
    spark.sparkContext.getCheckpointDir.foreach { dir =>
      val path = new Path(dir)
      val fs   = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
      fs.delete(path, true)
      if config.checkpointDir.isEmpty then
        val root = new Path(config.effectiveCheckpointDir)
        if fs.exists(root) && fs.listStatus(root).isEmpty then fs.delete(root, false)
    }

  /** Command-line entry point. Parses args and runs the pipeline. */
  def main(args: Array[String]): Unit =
    PipelineConfig.parse(args.toSeq) match
      case Left(error) =>
        System.err.println(s"ScholarChainPipeline: $error")
        System.err.println(PipelineConfig.Usage)
        sys.exit(2)
      case Right(config) =>
        val spark = SparkSessionFactory.forPipeline("ScholarChain-Pipeline", config)
        try
          run(spark, config)
          if !config.keepCheckpoints then cleanupCheckpoints(spark, config)
        finally spark.stop()
