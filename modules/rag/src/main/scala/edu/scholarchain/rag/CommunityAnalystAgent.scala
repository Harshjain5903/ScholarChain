package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class CommunityInfo(
  communityId: String,
  memberCount: Int,
  researcherIds: Vector[String],
  crossCommunityEdges: Int
) derives ReadWriter

/** Analyzes community membership for a set of researchers. */
class CommunityAnalystAgent(metrics: MetricsStore, graph: GraphStore):

  def analyze(
    researcherIds: Vector[String],
    windowStart: Int,
    windowEnd: Int
  ): Vector[CommunityInfo] =
    val grouped = researcherIds
      .flatMap { rid =>
        metrics
          .getMetrics(rid, windowStart, windowEnd)
          .flatMap(_.communityId)
          .map(cid => (cid, rid))
      }
      .groupBy(_._1)

    grouped.map { case (cid, pairs) =>
      val members     = pairs.map(_._2)
      val allMembers  = graph.getComponentMembers(cid, 500)
      val memberCount = if allMembers.nonEmpty then allMembers.size else members.size
      val crossEdges  = countCrossEdges(members, cid, windowStart, windowEnd)
      CommunityInfo(
        communityId = cid,
        memberCount = memberCount,
        researcherIds = members,
        crossCommunityEdges = crossEdges
      )
    }.toVector

  private def countCrossEdges(
    members: Vector[String],
    communityId: String,
    windowStart: Int,
    windowEnd: Int
  ): Int =
    members.map { rid =>
      val coauthors = graph.getDirectCoauthors(rid, 100)
      coauthors.count { edge =>
        val otherId =
          if edge.researcherA == rid then edge.researcherB else edge.researcherA
        metrics
          .getMetrics(otherId, windowStart, windowEnd)
          .flatMap(_.communityId)
          .exists(_ != communityId)
      }
    }.sum
