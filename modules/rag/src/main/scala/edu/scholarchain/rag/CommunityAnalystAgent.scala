package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class CommunityInfo(
  communityId: String,
  memberCount: Int,
  researcherIds: Vector[String],
  crossCommunityEdges: Int
) derives ReadWriter

/**
 * Analyzes community membership for a set of researchers. Metrics are read in batches (one batch
 * for the queried researchers, one for all of their coauthors), never one lookup per coauthor.
 */
class CommunityAnalystAgent(metrics: MetricsStore, graph: GraphStore):

  def analyze(
    researcherIds: Vector[String],
    windowStart: Int,
    windowEnd: Int
  ): Vector[CommunityInfo] =
    val ids = researcherIds.distinct
    val own =
      if ids.isEmpty then Map.empty else metrics.getMetricsBatch(ids, windowStart, windowEnd)
    val grouped = ids
      .flatMap(rid => own.get(rid).flatMap(_.communityId).map(cid => (cid, rid)))
      .groupBy(_._1)
    if grouped.isEmpty then Vector.empty
    else
      val coauthorsOf = grouped.values.flatten
        .map(_._2)
        .toVector
        .distinct
        .map { rid =>
          rid -> graph.getDirectCoauthors(rid, 100).map { edge =>
            if edge.researcherA == rid then edge.researcherB else edge.researcherA
          }
        }
        .toMap
      val others = coauthorsOf.values.flatten.toVector.distinct
      val otherCommunity =
        if others.isEmpty then Map.empty[String, String]
        else
          metrics
            .getMetricsBatch(others, windowStart, windowEnd)
            .collect { case (id, m) if m.communityId.isDefined => id -> m.communityId.get }

      grouped.toVector.sortBy(_._1).map { case (cid, pairs) =>
        val members     = pairs.map(_._2)
        val allMembers  = graph.getComponentMembers(cid, 500)
        val memberCount = if allMembers.nonEmpty then allMembers.size else members.size
        val crossEdges = members.map { rid =>
          coauthorsOf
            .getOrElse(rid, Vector.empty)
            .count(o => otherCommunity.get(o).exists(_ != cid))
        }.sum
        CommunityInfo(
          communityId = cid,
          memberCount = memberCount,
          researcherIds = members,
          crossCommunityEdges = crossEdges
        )
      }
