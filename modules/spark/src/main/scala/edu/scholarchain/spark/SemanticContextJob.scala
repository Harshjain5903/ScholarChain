package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 10: Semantic-context preparation.
 *
 * Generates text summaries for later embedding from computed graph data. All summaries are built
 * from real data -- nothing is invented.
 *
 * Three types of summaries:
 *   - Researcher profiles: top coauthors, publication count, topics, institutions
 *   - Community summaries: member count, average publications, and the key bridge researchers --
 *     only members with a positive computed betweenness are named; otherwise the summary states
 *     that none were identified and why (all scores zero, or betweenness not computed)
 *   - Collaboration-path summaries: path description between selected researcher pairs
 */
object SemanticContextJob:

  /** Job name recorded in the `producer` of summary provenance. */
  val Job: String = "SemanticContextJob"

  /** Bridge sentence of a community summary; pure so the wording is unit-testable. */
  def bridgeSentence(topBridges: Seq[(String, Double)], bridgeScoreMethod: String): String =
    if bridgeScoreMethod == BridgeScoreMethod.NotComputed then
      "No bridge researchers identified: betweenness was not computed for this run."
    else
      val positive = topBridges.filter(_._2 > 0.0)
      if positive.isEmpty then
        s"No bridge researchers identified: every member has $bridgeScoreMethod betweenness 0."
      else
        s"Key bridge researchers ($bridgeScoreMethod betweenness > 0): " +
          s"${positive.map(_._1).mkString(", ")}."

  /** A text summary with provenance for embedding. */
  case class SemanticSummary(
    entityId: String,
    entityType: String,
    summary: String,
    provenance: Provenance
  )

  /**
   * Generate researcher profile summaries.
   *
   * @param metrics
   *   per-researcher metrics
   * @param edges
   *   coauthor edges (for top-coauthor extraction)
   * @param topN
   *   number of top coauthors to include
   */
  def researcherProfiles(
    sc: SparkContext,
    metrics: RDD[ResearcherMetrics],
    edges: RDD[CoauthorEdge],
    topN: Int,
    lineage: Lineage
  ): RDD[SemanticSummary] =
    val topCoauthors: RDD[(String, Seq[String])] = edges
      .flatMap { e =>
        Iterator(
          (e.researcherA, (e.researcherB, e.jointPublicationCount)),
          (e.researcherB, (e.researcherA, e.jointPublicationCount))
        )
      }
      .groupByKey()
      .mapValues { coauthors =>
        // Highest joint publication count first; ties by researcher ID (deterministic).
        coauthors.toSeq
          .sortBy { case (rid, weight) => (-weight, rid) }
          .take(topN)
          .map(_._1)
      }

    metrics
      .map(m => (m.researcherId, m))
      .leftOuterJoin(topCoauthors)
      .map { case (rid, (m, coauthorsOpt)) =>
        val coauthorList = coauthorsOpt.getOrElse(Seq.empty)
        val coauthorStr  = if coauthorList.nonEmpty then coauthorList.mkString(", ") else "none"
        val summary = s"Researcher $rid has ${m.publicationCount} publications, " +
          s"${m.distinctCoauthorCount} distinct coauthors, " +
          s"weighted degree ${m.weightedDegree}, " +
          s"coreness ${m.coreness}. " +
          s"Top coauthors: $coauthorStr. " +
          s"Component: ${m.componentId.getOrElse("unknown")}. " +
          s"Community: ${m.communityId.getOrElse("unknown")}."
        val prov = lineage.prov(Job, "researcher-profile-summary", s"profile:$rid")
        SemanticSummary(rid, "researcher-profile", summary, prov)
      }

  /**
   * Generate community summaries.
   *
   * @param metrics
   *   per-researcher metrics (must include communityId)
   * @param bridgeScores
   *   per-researcher bridge scores
   * @param topBridgeCount
   *   maximum number of bridge researchers named per community (only positive scores qualify)
   * @param bridgeScoreMethod
   *   how `bridgeScores` were obtained ([[BridgeScoreMethod]]); `not-computed` scores are
   *   placeholders and never named
   */
  def communitySummaries(
    sc: SparkContext,
    metrics: RDD[ResearcherMetrics],
    bridgeScores: RDD[(String, Double)],
    topBridgeCount: Int,
    bridgeScoreMethod: String,
    lineage: Lineage
  ): RDD[SemanticSummary] =
    val method = bridgeScoreMethod
    // Bridge scores are joined onto members (distributed); no driver-side score map.
    val members: RDD[(String, (String, Int, Double))] = metrics
      .filter(_.communityId.isDefined)
      .map(m => (m.researcherId, (m.communityId.get, m.publicationCount)))
      .leftOuterJoin(bridgeScores)
      .map { case (rid, ((commId, pubs), score)) => (commId, (rid, pubs, score.getOrElse(0.0))) }

    // Per community: (member count, publication sum, top bridges). Top bridges are kept bounded at
    // `topBridgeCount` while aggregating: highest score first, ties by researcher ID. Only positive
    // computed scores are candidates; a 0.0 (measured or placeholder) never names a bridge.
    val k = math.max(0, topBridgeCount)
    def top(xs: Vector[(String, Double)]): Vector[(String, Double)] =
      xs.sortBy { case (rid, score) => (-score, rid) }.take(k)
    val computed = method != BridgeScoreMethod.NotComputed
    val aggregated = members.aggregateByKey((0L, 0L, Vector.empty[(String, Double)]))(
      { case ((n, pubs, best), (rid, p, score)) =>
        val candidate = if computed && score > 0.0 then best :+ (rid, score) else best
        (n + 1, pubs + p, top(candidate))
      },
      { case ((n1, p1, b1), (n2, p2, b2)) => (n1 + n2, p1 + p2, top(b1 ++ b2)) }
    )

    aggregated.map { case (commId, (count, pubSum, topBridges)) =>
      val avgPubs = if count > 0 then pubSum.toDouble / count.toDouble else 0.0
      val summary = s"Community $commId has $count members. " +
        s"Average publications per member: ${"%.1f".formatLocal(java.util.Locale.ROOT, avgPubs)}. " +
        bridgeSentence(topBridges, method)
      val prov = lineage.prov(Job, "community-summary", commId)
      SemanticSummary(commId, "community-summary", summary, prov)
    }

  /**
   * Generate collaboration-path summaries for specified researcher pairs.
   *
   * @param paths
   *   path results from ShortestPathJob
   */
  def pathSummaries(
    sc: SparkContext,
    paths: Seq[ShortestPathJob.PathResult],
    lineage: Lineage
  ): RDD[SemanticSummary] =
    val summaries = paths.map { p =>
      val pathStr = p.pathVertices.mkString(" -> ")
      val summary =
        s"Collaboration path from ${p.sourceResearcherId} to ${p.targetResearcherId}: " +
          s"$pathStr (${p.pathLength} hops)."
      val entityId = s"path:${p.sourceResearcherId}:${p.targetResearcherId}"
      val prov     = lineage.prov(Job, "collaboration-path-summary", entityId)
      SemanticSummary(entityId, "collaboration-path", summary, prov)
    }
    sc.parallelize(summaries)
