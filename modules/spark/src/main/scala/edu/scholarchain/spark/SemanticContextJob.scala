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
 *   - Community summaries: member count, dominant topics, key bridge researchers
 *   - Collaboration-path summaries: path description between selected researcher pairs
 */
object SemanticContextJob:

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
    runId: String,
    schemaVersion: String,
    timestamp: String
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
        val prov = GraphUtils.makeProv(runId, schemaVersion, s"profile:$rid", timestamp)
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
   *   number of top bridge researchers to include per community
   */
  def communitySummaries(
    sc: SparkContext,
    metrics: RDD[ResearcherMetrics],
    bridgeScores: RDD[(String, Double)],
    topBridgeCount: Int,
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[SemanticSummary] =
    // Bridge scores are joined onto members (distributed); no driver-side score map.
    val members: RDD[(String, (String, Int, Double))] = metrics
      .filter(_.communityId.isDefined)
      .map(m => (m.researcherId, (m.communityId.get, m.publicationCount)))
      .leftOuterJoin(bridgeScores)
      .map { case (rid, ((commId, pubs), score)) => (commId, (rid, pubs, score.getOrElse(0.0))) }

    // Per community: (member count, publication sum, top bridges). Top bridges are kept bounded at
    // `topBridgeCount` while aggregating: highest score first, ties by researcher ID.
    val k = math.max(0, topBridgeCount)
    def top(xs: Vector[(String, Double)]): Vector[(String, Double)] =
      xs.sortBy { case (rid, score) => (-score, rid) }.take(k)
    val aggregated = members.aggregateByKey((0L, 0L, Vector.empty[(String, Double)]))(
      { case ((n, pubs, best), (rid, p, score)) => (n + 1, pubs + p, top(best :+ (rid, score))) },
      { case ((n1, p1, b1), (n2, p2, b2)) => (n1 + n2, p1 + p2, top(b1 ++ b2)) }
    )

    aggregated.map { case (commId, (count, pubSum, topBridges)) =>
      val avgPubs = if count > 0 then pubSum.toDouble / count.toDouble else 0.0
      val summary = s"Community $commId has $count members. " +
        s"Average publications per member: ${"%.1f".formatLocal(java.util.Locale.ROOT, avgPubs)}. " +
        s"Key bridge researchers: ${topBridges.map(_._1).mkString(", ")}."
      val prov = GraphUtils.makeProv(runId, schemaVersion, commId, timestamp)
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
    runId: String,
    schemaVersion: String,
    timestamp: String
  ): RDD[SemanticSummary] =
    val summaries = paths.map { p =>
      val pathStr = p.pathVertices.mkString(" -> ")
      val summary =
        s"Collaboration path from ${p.sourceResearcherId} to ${p.targetResearcherId}: " +
          s"$pathStr (${p.pathLength} hops)."
      val entityId = s"path:${p.sourceResearcherId}:${p.targetResearcherId}"
      val prov     = GraphUtils.makeProv(runId, schemaVersion, entityId, timestamp)
      SemanticSummary(entityId, "collaboration-path", summary, prov)
    }
    sc.parallelize(summaries)
