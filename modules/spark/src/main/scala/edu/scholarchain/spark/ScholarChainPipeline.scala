package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

/**
 * Main entry point that orchestrates all Spark analytics jobs in sequence.
 *
 * Job DAG:
 * {{{
 *   DataLoader
 *       |
 *   CanonicalGraphBuilder (Job 1-2)
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
 *   ShortestPathJob (Job 5) -- (requires specific pairs)
 *       |
 *   TemporalAnalysisJob (Job 9)
 *       |
 *   SemanticContextJob (Job 10)
 *       |
 *   GraphExportWriter (Job 11)
 * }}}
 */
object ScholarChainPipeline:

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Run the full pipeline.
   *
   * @param spark
   *   active SparkSession
   * @param config
   *   pipeline configuration
   * @param researcherPairsForPaths
   *   optional pairs for shortest-path computation
   * @param temporalWindowSize
   *   size of temporal windows in years (default 5)
   * @param temporalStep
   *   step between temporal windows (default 5)
   */
  def run(
    spark: SparkSession,
    config: PipelineConfig,
    researcherPairsForPaths: Seq[(String, String)] = Seq.empty,
    temporalWindowSize: Int = 5,
    temporalStep: Int = 5
  ): Unit =
    val sc        = spark.sparkContext
    val timestamp = java.time.Instant.now().toString

    logger.info("Loading data from Hadoop outputs")
    val pairCandidates =
      DataLoader.loadCoauthorPairCandidates(spark, s"${config.hadoopOutputPath}/pair_candidates")
    val matchDecisions =
      DataLoader.loadMatchDecisions(spark, config.matchDecisionsPath)
    val acceptedMatches = matchDecisions.filter(_.decision == MatchOutcome.Accepted)

    logger.info("Building canonical coauthorship graph (Jobs 1-2)")
    val edges = CanonicalGraphBuilder.build(
      sc,
      pairCandidates,
      acceptedMatches,
      config.windowStartYear,
      config.windowEndYear,
      config.runId,
      config.schemaVersion,
      config.policyVersion,
      timestamp
    )
    edges.cache()

    logger.info("Building GraphX graph")
    val mapping = GraphUtils.buildVertexMapping(edges)
    val graph   = GraphUtils.buildGraph(sc, edges, mapping)
    graph.cache()

    logger.info("Computing collaboration metrics (Job 3)")
    val authorships = pairCandidates
      .flatMap(p => Iterator(p.mentionA, p.mentionB).map(m => (m, p.workId, p.year)))
      .map { case (mentionId, workId, year) =>
        CollaborationMetricsJob.AuthorshipInfo(mentionId, workId, 1)
      }
    val initialMetrics = CollaborationMetricsJob.compute(
      sc,
      edges,
      authorships,
      config.windowStartYear,
      config.windowEndYear,
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
    finalMetrics.cache()

    logger.info("Computing shortest paths (Job 5)")
    val pathResults =
      if researcherPairsForPaths.nonEmpty then
        ShortestPathJob.compute(graph, mapping, researcherPairsForPaths)
      else Seq.empty

    logger.info("Computing temporal analysis (Job 9)")
    val windows = TemporalAnalysisJob.generateWindows(
      config.windowStartYear,
      config.windowEndYear,
      temporalWindowSize,
      temporalStep
    )
    val temporalAuthorships = pairCandidates
      .flatMap(p => Iterator(p.mentionA, p.mentionB).map(m => (m, p.workId, 1, p.year)))
    val temporalSnapshots = TemporalAnalysisJob.compute(
      sc,
      edges,
      temporalAuthorships,
      windows,
      config.runId,
      config.schemaVersion,
      timestamp
    )

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

    logger.info("Writing exports (Job 11)")
    GraphExportWriter.writeEdges(spark, edges, config.outputPath)
    GraphExportWriter.writeMetrics(spark, finalMetrics, config.outputPath)
    GraphExportWriter.writeSummaries(spark, allSummaries, config.outputPath)
    GraphExportWriter.writePaths(
      spark,
      pathResults,
      config.outputPath,
      config.runId,
      config.schemaVersion,
      timestamp
    )
    GraphExportWriter.writeComponents(spark, ccResult.assignments, config.outputPath)
    GraphExportWriter.writeCommunities(spark, communityResult.assignments, config.outputPath)
    GraphExportWriter.writeManifest(spark, config.outputPath, config.runId, timestamp)

    logger.info("Pipeline complete")
    edges.unpersist()
    graph.unpersist()
    finalMetrics.unpersist()

  /** Command-line entry point. Parses args and runs the pipeline. */
  def main(args: Array[String]): Unit =
    val config = parseArgs(args)
    val spark  = SparkSessionFactory.create("ScholarChain-Pipeline")
    try run(spark, config)
    finally spark.stop()

  private def parseArgs(args: Array[String]): PipelineConfig =
    val argsMap = args
      .sliding(2, 2)
      .collect { case Array(key, value) => (key.stripPrefix("--"), value) }
      .toMap
    PipelineConfig(
      hadoopOutputPath =
        argsMap.getOrElse("hadoop-output", PipelineConfig.Default.hadoopOutputPath),
      matchDecisionsPath =
        argsMap.getOrElse("match-decisions", PipelineConfig.Default.matchDecisionsPath),
      outputPath = argsMap.getOrElse("output", PipelineConfig.Default.outputPath),
      windowStartYear =
        argsMap.getOrElse("window-start", PipelineConfig.Default.windowStartYear.toString).toInt,
      windowEndYear =
        argsMap.getOrElse("window-end", PipelineConfig.Default.windowEndYear.toString).toInt,
      runId = argsMap.getOrElse("run-id", IdGen.runId()),
      numPartitions =
        argsMap.getOrElse("partitions", PipelineConfig.Default.numPartitions.toString).toInt,
      betweennessSampleCount = argsMap
        .getOrElse("betweenness-samples", PipelineConfig.Default.betweennessSampleCount.toString)
        .toInt,
      schemaVersion = argsMap.getOrElse("schema-version", PipelineConfig.Default.schemaVersion),
      policyVersion = argsMap.getOrElse("policy-version", PipelineConfig.Default.policyVersion),
      maxBetweennessGraphSize = argsMap
        .getOrElse(
          "max-betweenness-graph-size",
          PipelineConfig.Default.maxBetweennessGraphSize.toString
        )
        .toLong
    )
