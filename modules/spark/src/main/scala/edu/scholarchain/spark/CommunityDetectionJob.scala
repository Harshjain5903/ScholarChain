package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.Graph
import org.apache.spark.graphx.lib.LabelPropagation
import org.apache.spark.rdd.RDD

/**
 * Job 6: Collaboration communities using Label Propagation Algorithm (LPA).
 *
 * Uses GraphX's built-in LabelPropagation to assign community labels. Reports community ID, size,
 * and cross-community edge count per researcher.
 */
object CommunityDetectionJob:

  /** Community detection results. */
  case class CommunityResult(
    assignments: RDD[(String, String, Long)],
    crossCommunityEdges: RDD[(String, Int)],
    communityCount: Long
  )

  /**
   * Run LPA community detection.
   *
   * @param graph
   *   undirected GraphX graph
   * @param mapping
   *   vertex ID mapping
   * @param maxIterations
   *   LPA iteration limit (default 20)
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    maxIterations: Int = 20
  ): CommunityResult =
    val sc       = graph.vertices.sparkContext
    val lpaGraph = LabelPropagation.run(graph, maxIterations)

    val bcToStr = sc.broadcast(mapping.toStr)
    val vertexCommunities: RDD[(Long, (String, Long))] = lpaGraph.vertices.map {
      case (vid, communityLabel) =>
        (vid, (bcToStr.value(vid), communityLabel))
    }

    val communitySizes: Map[Long, Long] = vertexCommunities
      .map { case (_, (_, commId)) => (commId, 1L) }
      .reduceByKey(_ + _)
      .collectAsMap()
      .toMap

    val bcSizes       = sc.broadcast(communitySizes)
    val assignments   = vertexCommunities.map { case (_, (rid, commId)) =>
      (rid, s"community:$commId", bcSizes.value.getOrElse(commId, 0L))
    }

    val vertexCommMap: Map[Long, Long] = vertexCommunities
      .map { case (vid, (_, commId)) => (vid, commId) }
      .collectAsMap()
      .toMap
    val bcVertexComm = sc.broadcast(vertexCommMap)

    val crossComm = graph.edges
      .filter { e =>
        val srcComm = bcVertexComm.value.getOrElse(e.srcId, -1L)
        val dstComm = bcVertexComm.value.getOrElse(e.dstId, -1L)
        srcComm != dstComm && e.srcId < e.dstId
      }
      .flatMap { e =>
        Iterator(
          (bcToStr.value(e.srcId), 1),
          (bcToStr.value(e.dstId), 1)
        )
      }
      .reduceByKey(_ + _)


    CommunityResult(
      assignments = assignments,
      crossCommunityEdges = crossComm,
      communityCount = communitySizes.size.toLong
    )

  /** Update ResearcherMetrics with community IDs. */
  def enrichMetrics(
    metrics: RDD[ResearcherMetrics],
    result: CommunityResult
  ): RDD[ResearcherMetrics] =
    val commMap = result.assignments.map { case (rid, commId, _) =>
      (rid, commId)
    }
    metrics
      .map(m => (m.researcherId, m))
      .leftOuterJoin(commMap)
      .map { case (_, (m, commOpt)) =>
        m.copy(communityId = commOpt.orElse(m.communityId))
      }
