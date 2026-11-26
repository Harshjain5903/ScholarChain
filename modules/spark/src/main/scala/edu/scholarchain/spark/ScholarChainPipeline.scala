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
 * Every run validates its input lineage first (ER policy derived from the decisions, ER decisions
 * linked to the Hadoop run of the pairs), stamps lineage provenance on every output record, and
 * writes a manifest whose `run` section records code version, jar and input checksums, upstream run
 * IDs, ER policy, argv and config, betweenness method and seed, k-core and LPA convergence, and
 * output counts.
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

  /** Throw IllegalArgumentException with `message` when `check` is a Left; otherwise its value. */
  private def orFail[A](check: Either[String, A]): A =
    check.fold(e => throw new IllegalArgumentException(e), identity)

  /** Distinct values of a small-cardinality RDD, bounded; more than the bound is an input error. */
  private def boundedDistinct(rdd: RDD[String], what: String): Vector[String] =
    val values = rdd.distinct().take(InputLineage.MaxDistinctValues + 1).sorted.toVector
    if values.size > InputLineage.MaxDistinctValues then
      throw new IllegalArgumentException(
        s"more than ${InputLineage.MaxDistinctValues} distinct $what in the input"
      )
    values

  /**
   * Run the full pipeline and return the run statistics (also written to `<output>/run-stats`).
   *
   * Before any output is written the input lineage is validated (see [[InputLineage]]): the ER
   * policy version is derived from the match decisions (an explicit `--policy-version` must agree;
   * mixed policies are refused) and the decisions must name the Hadoop run of the pair candidates.
   * A violation throws IllegalArgumentException.
   *
   * @param spark
   *   active SparkSession; for reliable checkpoints its context should have a checkpoint directory
   *   (set by [[SparkSessionFactory.forPipeline]])
   * @param config
   *   pipeline configuration
   * @param argv
   *   the command line the configuration was parsed from, recorded in the manifest
   */
  def run(spark: SparkSession, config: PipelineConfig, argv: Seq[String] = Seq.empty): ujson.Obj =
    val sc        = spark.sparkContext
    val timestamp = config.runTimestamp.getOrElse(java.time.Instant.now().toString)
    val parts     = Some(config.numPartitions)
    val ws        = config.windowStartYear
    val we        = config.windowEndYear
    val out       = config.outputPath.stripSuffix("/")
    val t0        = System.nanoTime()

    logger.info(s"Loading data from Hadoop outputs: ${config.hadoopOutputPath}")
    val rawPairCandidates =
      DataLoader
        .loadCoauthorPairCandidates(spark, config.pairCandidatesPath, parts)
        .persist(StorageLevel.MEMORY_AND_DISK)
    val rawTeams =
      DataLoader
        .loadPublicationTeams(spark, config.teamsPath, parts)
        .persist(StorageLevel.MEMORY_AND_DISK)

    // Work records are optional: when the Hadoop export has them they supply titles and venues
    // and the DOIs used to drop cross-dataset duplicate works (WorkDeduplication).
    val workRecordsRoot = new Path(config.workRecordsPath)
    val workRecordsPresent =
      workRecordsRoot.getFileSystem(sc.hadoopConfiguration).exists(workRecordsRoot)
    val duplicateWorks: RDD[(String, String)] =
      if workRecordsPresent then
        WorkDeduplication
          .duplicateWorks(DataLoader.loadWorkRecords(spark, config.workRecordsPath))
          .persist(StorageLevel.MEMORY_AND_DISK)
      else sc.emptyRDD
    val duplicateWorkCount = duplicateWorks.count()
    val pairCandidates =
      WorkDeduplication
        .keepPairs(rawPairCandidates, duplicateWorks)
        .persist(StorageLevel.MEMORY_AND_DISK)
    val teams =
      WorkDeduplication.keepTeams(rawTeams, duplicateWorks).persist(StorageLevel.MEMORY_AND_DISK)
    val rawPairCount = rawPairCandidates.count()
    val rawTeamCount = rawTeams.count()
    logger.info(
      s"Work deduplication: $duplicateWorkCount duplicate works dropped " +
        s"(teams $rawTeamCount -> ${teams.count()}, pairs $rawPairCount -> ${pairCandidates.count()})"
    )
    val decisions = DataLoader.loadMatchDecisions(spark, config.matchDecisionsPath, parts)
    decisions.persist(StorageLevel.MEMORY_AND_DISK)
    val accepted = decisions.filter(_.decision == MatchOutcome.Accepted)

    // ---- Input lineage: validated before any output is written.
    val decisionCount    = decisions.count()
    val decisionPolicies = boundedDistinct(decisions.map(_.policyVersion), "ER policy versions")
    val policyVersion = orFail(
      InputLineage.resolvePolicyVersion(config.policyVersion, decisionPolicies)
    )
    val decisionRunIds = boundedDistinct(decisions.map(_.runId), "ER run IDs")
    val erInputRunIds = boundedDistinct(
      decisions.flatMap(d => InputLineage.erInputRunIds(d.reasonCodes)),
      "ER input run IDs"
    )
    val decisionsWithoutInputRun =
      decisions.filter(d => InputLineage.erInputRunIds(d.reasonCodes).isEmpty).count()
    val pairRunIds =
      boundedDistinct(rawPairCandidates.map(_.provenance.pipelineRunId), "pair run IDs")
    val teamRunIds = boundedDistinct(rawTeams.map(_.provenance.pipelineRunId), "team run IDs")
    val erLinkage = orFail(
      InputLineage.checkErLinkage(
        pairRunIds.toSet,
        erInputRunIds.toSet,
        decisionCount,
        decisionsWithoutInputRun,
        config.allowUnlinkedErInput
      )
    )
    val pairSources =
      boundedDistinct(pairCandidates.map(_.provenance.sourceDataset), "pair datasets")
    val teamSources    = boundedDistinct(teams.map(_.provenance.sourceDataset), "team datasets")
    val upstreamRunIds = (pairRunIds ++ teamRunIds ++ decisionRunIds).distinct.sorted
    val lineage = Lineage(
      runId = config.runId,
      schemaVersion = config.schemaVersion,
      timestamp = timestamp,
      sourceDataset = InputLineage.sourceDatasetOf(pairSources ++ teamSources),
      upstreamRunIds = upstreamRunIds,
      policyVersion = Some(policyVersion)
    )
    // Coauthor edges derive from pair candidates; researcher, work and authorship records from
    // publication teams. Each carries the source dataset its own input records declare.
    val edgeLineage = lineage.withSourceDataset(InputLineage.sourceDatasetOf(pairSources))
    val teamLineage = lineage.withSourceDataset(InputLineage.sourceDatasetOf(teamSources))
    logger.info(
      s"Input lineage: ER policy $policyVersion, ER linkage $erLinkage, upstream runs " +
        s"${upstreamRunIds.mkString(",")}, pair datasets ${pairSources.mkString(",")}, team " +
        s"datasets ${teamSources.mkString(",")}"
    )

    val pairCandidatesIn = pairCandidates.count()
    val pairCandidatesInWindow =
      pairCandidates.filter(p => p.year >= ws && p.year <= we).count()
    val teamsIn          = teams.count()
    val teamsInWindow    = teams.filter(t => t.year >= ws && t.year <= we)
    val worksInWindow    = teamsInWindow.count()
    val mentionsInWindow = teamsInWindow.map(_.authorMentions.size.toLong).fold(0L)(_ + _)
    val decisionsByOutcome =
      decisions.map(d => (d.decision.toString, 1L)).reduceByKey(_ + _).collect().toMap

    logger.info("Building canonical coauthorship graph (Jobs 1-2)")
    val mentionMapping = CanonicalGraphBuilder.matchedMentionMapping(accepted)
    GraphUtils.checkpointAndCount(mentionMapping)
    val mentionsInAcceptedMatches = mentionMapping.count()
    val mentionsMergedAway        = mentionMapping.filter { case (m, c) => m != c }.count()

    val canonicalPairs =
      CanonicalGraphBuilder.canonicalPairs(pairCandidates, mentionMapping, ws, we)
    val canonicalPairCount = GraphUtils.checkpointAndCount(canonicalPairs)

    val edges =
      CanonicalGraphBuilder.aggregatePairs(canonicalPairs, ws, we, policyVersion, edgeLineage)
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
      teamLineage
    )

    logger.info("Computing connected components (Job 4)")
    val ccResult = ConnectedComponentsJob.compute(graph, mapping)
    // componentId and componentSize are attached with a distributed join (no collect).
    val metricsWithCC = CollaborationMetricsJob.withComponents(initialMetrics, ccResult.assignments)

    logger.info("Computing communities (Job 6)")
    val communityResult = CommunityDetectionJob.compute(graph, mapping, config.lpaMaxIterations)
    val metricsWithComm = CommunityDetectionJob.enrichMetrics(metricsWithCC, communityResult)
    if !communityResult.converged then
      logger.warn(
        s"Label propagation did not converge within ${config.lpaMaxIterations} supersteps " +
          s"(${communityResult.unstableVertices} vertices unstable); communities are reported " +
          "as not converged"
      )

    logger.info("Computing bridge analysis (Job 7)")
    val communityAssignments = communityResult.assignments.map { case (rid, commId, _) =>
      (rid, commId)
    }
    val bridgeResult = BridgeAnalysisJob.compute(
      graph,
      mapping,
      communityAssignments,
      sampleCount = config.betweennessSampleCount,
      maxGraphSize = config.maxBetweennessGraphSize,
      seed = config.betweennessSeed
    )
    val metricsWithBridge = BridgeAnalysisJob.enrichMetrics(metricsWithComm, bridgeResult)
    logger.info(
      s"Betweenness method ${bridgeResult.method} (k=${bridgeResult.sources}, " +
        s"n=${bridgeResult.vertexCount}, seed=${bridgeResult.seed})"
    )

    logger.info("Computing k-core decomposition (Job 8)")
    val kcore = KCoreJob.computeWithStatus(
      graph,
      mapping,
      config.kcoreMaxIterations,
      config.kcoreCheckpointInterval
    )
    KCoreJob.requireConverged(kcore)
    val finalMetrics = KCoreJob.enrichMetrics(metricsWithBridge, kcore.coreness)
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
      lineage
    )
    val temporalCount = GraphUtils.checkpointAndCount(temporalSnapshots)

    logger.info("Generating semantic context (Job 10)")
    val profiles = SemanticContextJob.researcherProfiles(
      sc,
      finalMetrics,
      edges,
      topN = 5,
      lineage
    )
    val commSummaries = SemanticContextJob.communitySummaries(
      sc,
      finalMetrics,
      bridgeResult.scores,
      topBridgeCount = 3,
      bridgeResult.method,
      lineage
    )
    val pathSummaries = SemanticContextJob.pathSummaries(sc, pathResults, lineage)
    val allSummaries  = profiles.union(commSummaries).union(pathSummaries)
    val summaryCount  = GraphUtils.checkpointAndCount(allSummaries)

    logger.info("Writing exports (Job 11)")
    GraphExportWriter.writeEdges(spark, edges, out)
    GraphExportWriter.writeMetrics(spark, finalMetrics, out)
    GraphExportWriter.writeSummaries(spark, allSummaries, out)
    GraphExportWriter.writePaths(spark, pathResults, out, lineage)
    GraphExportWriter.writeComponents(spark, ccResult.assignments, out)
    GraphExportWriter.writeCommunities(spark, communityResult.assignments, out)
    GraphExportWriter.writeTemporal(spark, temporalSnapshots, out)

    val neptuneCounts =
      if config.writeNeptuneCsv then
        val descriptive: RDD[(String, (String, Option[String]))] =
          if workRecordsPresent then
            DataLoader
              .loadWorkRecords(spark, config.workRecordsPath)
              .map(w => (w.workId, (w.title, w.venueId)))
              .reduceByKey((a, b) => if Ordering[(String, Option[String])].lteq(a, b) then a else b)
          else sc.emptyRDD
        val works = teamsInWindow
          .map(t => (t.workId, t))
          .leftOuterJoin(descriptive)
          .map { case (workId, (t, desc)) =>
            NeptuneBulkLoadExport.WorkNode(
              workId = workId,
              provenance = lineage
                .withSourceDataset(InputLineage.sourceDatasetOf(Seq(t.provenance.sourceDataset)))
                .prov(NeptuneBulkLoadExport.Job, "work-vertex-export", t.provenance.sourceRecordId),
              title = desc.map(_._1).filter(_.trim.nonEmpty),
              year = Some(t.year),
              venue = desc.flatMap(_._2).filter(_.trim.nonEmpty)
            )
          }
        Some(
          NeptuneBulkLoadExport.writeAll(
            spark,
            out,
            finalMetrics,
            CanonicalGraphBuilder.researcherDisplayNames(teams, mentionMapping, ws, we),
            works,
            edges,
            authorships.map(a => (a.researcherId, a.workId)),
            ws,
            we,
            policyVersion,
            teamLineage
          )
        )
      else None

    // ---- Run statistics: scalars and bounded samples only.
    val byScoreThenId = Ordering.by[(String, Double), (Double, String)] { case (r, s) => (-s, r) }
    val corenessHistogram =
      finalMetrics.map(m => (m.coreness, 1L)).reduceByKey(_ + _).collect().sortBy(_._1)
    val largestCommunity = communityResult.assignments
      .map { case (_, cid, size) => (cid, size) }
      .distinct()
      .takeOrdered(1)(Ordering.by[(String, Long), (Long, String)] { case (c, s) => (-s, c) })
      .headOption
    val betweennessComputed = bridgeResult.method != BridgeScoreMethod.NotComputed
    // Not-computed scores are placeholders: never ranked. Zero scores are not "top" either.
    val topBetweenness =
      if betweennessComputed then
        bridgeResult.scores.filter(_._2 > 0.0).takeOrdered(5)(byScoreThenId).toSeq
      else Seq.empty
    val crossCommunityResearchers = bridgeResult.crossCommunityDegrees.filter(_._2 > 1).count()
    val summariesByType =
      allSummaries.map(s => (s.entityType, 1L)).reduceByKey(_ + _).collect().toMap
    val temporalByWindow = temporalSnapshots
      .map(t => ((t.windowStartYear, t.windowEndYear), 1L))
      .reduceByKey(_ + _)
      .collect()
      .sortBy(_._1)
    val pubSum    = finalMetrics.map(_.publicationCount.toLong).fold(0L)(_ + _)
    val sizeStats = ConnectedComponentsJob.componentSizeStats(finalMetrics)
    val sizeStatsConsistent =
      sizeStats.largestComponentSize == ccResult.largestComponentSize &&
        sizeStats.singletonCount == ccResult.singletonCount &&
        sizeStats.withComponentSize == sizeStats.researchers
    if !sizeStatsConsistent then
      logger.warn(
        s"componentSize statistics from researcher metrics ($sizeStats) disagree with the " +
          s"connected-components result (largest=${ccResult.largestComponentSize}, " +
          s"singletons=${ccResult.singletonCount})"
      )

    val configJson = ujson.Obj(
      "hadoopOutputPath"        -> config.hadoopOutputPath,
      "matchDecisionsPath"      -> config.matchDecisionsPath,
      "outputPath"              -> out,
      "runId"                   -> config.runId,
      "windowStartYear"         -> ws,
      "windowEndYear"           -> we,
      "numPartitions"           -> config.numPartitions,
      "betweennessSampleCount"  -> config.betweennessSampleCount,
      "betweennessSeed"         -> config.betweennessSeed.toDouble,
      "maxBetweennessGraphSize" -> config.maxBetweennessGraphSize.toDouble,
      "schemaVersion"           -> config.schemaVersion,
      "policyVersionFlag"       -> config.policyVersion.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "policyVersion"           -> policyVersion,
      "checkpointDir"           -> sc.getCheckpointDir.getOrElse[String]("<none: localCheckpoint>"),
      "pregelCheckpointInterval" -> sc.getConf
        .get(SparkSessionFactory.PregelCheckpointIntervalKey, "-1"),
      "kcoreMaxIterations"      -> config.kcoreMaxIterations,
      "kcoreCheckpointInterval" -> config.kcoreCheckpointInterval,
      "lpaMaxIterations"        -> config.lpaMaxIterations,
      "allowUnlinkedErInput"    -> config.allowUnlinkedErInput,
      "inputChecksums"          -> config.inputChecksums,
      "temporalWindowSize"      -> config.temporalWindowSize,
      "temporalStep"            -> config.temporalStep,
      "maxPathHops"             -> config.maxPathHops,
      "pathPairs"               -> ujson.Arr.from(config.pathPairs.map { case (a, b) => s"$a|$b" }),
      "pathPairsFile"           -> config.pathPairsFile.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "pathSampleCount"         -> config.pathSampleCount,
      "keepCheckpoints"         -> config.keepCheckpoints,
      "runTimestampFlag"        -> config.runTimestamp.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "neptuneCsv"              -> config.writeNeptuneCsv
    )

    val lineageJson = ujson.Obj(
      "erPolicyVersion"          -> policyVersion,
      "erLinkage"                -> erLinkage,
      "upstreamRunIds"           -> ujson.Arr.from(upstreamRunIds),
      "pairCandidateRunIds"      -> ujson.Arr.from(pairRunIds),
      "teamRunIds"               -> ujson.Arr.from(teamRunIds),
      "matchDecisionRunIds"      -> ujson.Arr.from(decisionRunIds),
      "matchDecisionInputRunIds" -> ujson.Arr.from(erInputRunIds),
      "decisionsWithoutInputRun" -> decisionsWithoutInputRun.toDouble,
      "pairSourceDatasets"       -> ujson.Arr.from(pairSources),
      "teamSourceDatasets"       -> ujson.Arr.from(teamSources),
      "edgeSourceDataset"        -> edgeLineage.sourceDataset,
      "researcherSourceDataset"  -> teamLineage.sourceDataset
    )

    val bridgesJson = ujson.Obj(
      "method"                         -> bridgeResult.method,
      "betweennessComputed"            -> betweennessComputed,
      "betweennessSources"             -> bridgeResult.sources.toDouble,
      "vertexCount"                    -> bridgeResult.vertexCount.toDouble,
      "maxGraphSize"                   -> config.maxBetweennessGraphSize.toDouble,
      "betweennessSeed"                -> bridgeResult.seed.toDouble,
      "researchersCrossingCommunities" -> crossCommunityResearchers.toDouble,
      "topBetweenness" -> ujson.Arr.from(topBetweenness.map { case (r, s) =>
        ujson.Obj("researcherId" -> r, "score" -> s)
      })
    )

    val kcoreJson = ujson.Obj(
      "converged"          -> kcore.converged,
      "iterations"         -> kcore.iterations,
      "maxIterations"      -> kcore.maxIterations,
      "checkpointInterval" -> kcore.checkpointInterval,
      "maxCoreness"        -> corenessHistogram.lastOption.map(_._1.toDouble).getOrElse(0.0),
      "histogram" -> ujson.Arr.from(corenessHistogram.map { case (k, n) =>
        ujson.Obj("coreness" -> k, "researchers" -> n.toDouble)
      })
    )

    val communitiesJson = ujson.Obj(
      "algorithm" -> s"deterministic label propagation (CommunityDetectionJob.propagateLabels, maxIterations=${config.lpaMaxIterations})",
      "converged"        -> communityResult.converged,
      "unstableVertices" -> communityResult.unstableVertices.toDouble,
      "maxIterations"    -> communityResult.maxIterations,
      "count"            -> communityResult.communityCount.toDouble,
      "largestId"        -> largestCommunity.map(_._1).getOrElse[String](""),
      "largestSize"      -> largestCommunity.map(_._2.toDouble).getOrElse(0.0)
    )

    val neptuneJson = neptuneCounts.fold[ujson.Value](ujson.Null) { c =>
      ujson.Obj(
        "researcherNodes"       -> c.researcherNodes.toDouble,
        "workNodes"             -> c.workNodes.toDouble,
        "coauthorRelationships" -> c.coauthorRelationships.toDouble,
        "authoredRelationships" -> c.authoredRelationships.toDouble
      )
    }

    val outputCounts = ujson.Obj(
      "edges"       -> edgeCount.toDouble,
      "metrics"     -> metricsCount.toDouble,
      "summaries"   -> summaryCount.toDouble,
      "paths"       -> pathResults.size,
      "components"  -> vertexCount.toDouble,
      "communities" -> vertexCount.toDouble,
      "temporal"    -> temporalCount.toDouble,
      "neptune"     -> neptuneJson
    )

    val code = RunManifest.resolveCodeVersion(
      config.gitSha,
      config.gitDirty,
      sys.env,
      () => RunManifest.gitProbe()
    )
    val inputFiles =
      if config.inputChecksums then
        ujson.Arr.from(
          RunManifest
            .inputFiles(
              sc.hadoopConfiguration,
              Seq(
                config.pairCandidatesPath,
                config.teamsPath,
                config.workRecordsPath,
                config.matchDecisionsPath
              ) ++ config.pathPairsFile.toSeq
            )
            .map(_.toJson)
        )
      else ujson.Str("disabled (--input-checksums false)")

    val runManifest = ujson.Obj(
      "runId"     -> config.runId,
      "timestamp" -> timestamp,
      "producer"  -> s"${Lineage.ProducerPrefix}/ScholarChainPipeline",
      "code"      -> code.toJson,
      "jar"       -> RunManifest.jarInfo(),
      "argv"      -> ujson.Arr.from(argv),
      "config"    -> configJson,
      "inputs" -> ujson.Obj(
        "files"              -> inputFiles,
        "workRecordsPresent" -> workRecordsPresent
      ),
      "lineage"          -> lineageJson,
      "erPolicyVersion"  -> policyVersion,
      "upstreamRunIds"   -> ujson.Arr.from(upstreamRunIds),
      "betweenness"      -> bridgesJson,
      "kcore"            -> kcoreJson,
      "labelPropagation" -> communitiesJson,
      "outputCounts"     -> outputCounts
    )
    GraphExportWriter.writeManifest(
      spark,
      out,
      config.runId,
      timestamp,
      config.writeNeptuneCsv,
      runManifest
    )

    val stats = ujson.Obj(
      "runId"     -> config.runId,
      "timestamp" -> timestamp,
      "master"    -> sc.master,
      "config"    -> configJson,
      "lineage"   -> lineageJson,
      "code"      -> code.toJson,
      "inputs" -> ujson.Obj(
        "pairCandidates"         -> pairCandidatesIn.toDouble,
        "pairCandidatesInWindow" -> pairCandidatesInWindow.toDouble,
        "pairCandidateRunIds"    -> ujson.Arr.from(pairRunIds),
        "teams"                  -> teamsIn.toDouble,
        "teamsInWindow"          -> worksInWindow.toDouble,
        "authorMentionsInWindow" -> mentionsInWindow.toDouble,
        "workRecordsPresent"     -> workRecordsPresent,
        "workDeduplication" -> ujson.Obj(
          "rule"                 -> "group work records by normalized DOI; keep the DBLP work",
          "duplicateWorks"       -> duplicateWorkCount.toDouble,
          "teamsBefore"          -> rawTeamCount.toDouble,
          "pairCandidatesBefore" -> rawPairCount.toDouble
        ),
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
        "singletons"      -> ccResult.singletonCount.toDouble,
        // componentSize(r) as carried on the researcher metrics (README statistics).
        "componentSize" -> ujson.Obj(
          "researchers"              -> sizeStats.researchers.toDouble,
          "withComponentSize"        -> sizeStats.withComponentSize.toDouble,
          "largestComponentSize"     -> sizeStats.largestComponentSize.toDouble,
          "largestComponentFraction" -> sizeStats.largestComponentFraction,
          "singletonCount"           -> sizeStats.singletonCount.toDouble,
          "consistentWithComponents" -> sizeStatsConsistent
        )
      ),
      "communities" -> communitiesJson,
      "kcore"       -> kcoreJson,
      "bridges"     -> bridgesJson,
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
      "neptune"         -> neptuneJson,
      "outputCounts"    -> outputCounts,
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
          run(spark, config, args.toSeq)
          if !config.keepCheckpoints then cleanupCheckpoints(spark, config)
        finally spark.stop()
