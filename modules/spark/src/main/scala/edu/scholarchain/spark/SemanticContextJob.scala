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
        coauthors.toSeq
          .sortBy(-_._2)
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
    val withCommunity = metrics.filter(_.communityId.isDefined)

    val communityMembers: RDD[(String, Iterable[ResearcherMetrics])] =
      withCommunity.map(m => (m.communityId.get, m)).groupByKey()

    val bridgeMap = bridgeScores.collectAsMap()
    val bcBridge  = sc.broadcast(bridgeMap)

    val summaries = communityMembers.map { case (commId, members) =>
      val memberList = members.toSeq
      val count      = memberList.size
      val topBridges = memberList
        .sortBy(m => -bcBridge.value.getOrElse(m.researcherId, 0.0))
        .take(topBridgeCount)
        .map(_.researcherId)
      val avgPubs =
        if count > 0 then memberList.map(_.publicationCount).sum.toDouble / count else 0.0
      val summary = s"Community $commId has $count members. " +
        s"Average publications per member: ${"%.1f".format(avgPubs)}. " +
        s"Key bridge researchers: ${topBridges.mkString(", ")}."
      val prov = GraphUtils.makeProv(runId, schemaVersion, s"community:$commId", timestamp)
      SemanticSummary(commId, "community-summary", summary, prov)
    }

    summaries

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
