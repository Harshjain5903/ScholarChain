package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{EdgeDirection, EdgeTriplet, Graph, Pregel, VertexId}
import org.apache.spark.rdd.RDD

/**
 * Job 6: Collaboration communities using Label Propagation Algorithm (LPA).
 *
 * Uses a deterministic synchronous Label Propagation ([[propagateLabels]]) to assign community
 * labels. Reports community ID
 * (`community:<smallest member researcher ID>`), size, and cross-community edge count per
 * researcher. No per-vertex or per-community map is collected to the driver; only the community
 * count is.
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
   *   LPA superstep limit (default 20)
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    maxIterations: Int = 20
  ): CommunityResult =
    val lpaGraph = propagateLabels(graph, maxIterations)

    // (vertexId, (researcherId, label)); researcher IDs come from the graph's vertex attributes via
    // an index-aligned join.
    val vertexCommunities: RDD[(Long, (String, String))] = lpaGraph.vertices
      .innerJoin(graph.vertices)((_, communityLabel, researcherId) =>
        (researcherId, communityLabel)
      )
    vertexCommunities.cache()

    // Per label: (smallest member researcher ID, size). Communities are named after their smallest
    // member so IDs do not depend on GraphX vertex-ID assignment.
    val communityInfo: RDD[(String, (String, Long))] = vertexCommunities
      .map { case (_, (rid, label)) => (label, (rid, 1L)) }
      .reduceByKey { case ((r1, n1), (r2, n2)) => (if r1 <= r2 then r1 else r2, n1 + n2) }
    communityInfo.cache()

    val withNames: RDD[(Long, (String, String, Long))] = vertexCommunities
      .map { case (vid, (rid, label)) => (label, (vid, rid)) }
      .join(communityInfo)
      .map { case (_, ((vid, rid), (name, size))) => (vid, (rid, s"community:$name", size)) }
    withNames.cache()

    val assignments: RDD[(String, String, Long)] = withNames.values

    // Cross-community edges: community names are joined onto both endpoints (distributed), never
    // collected into a driver-side vertex map.
    val communityByVertex: RDD[(Long, String)] = withNames.mapValues(_._2)
    val crossComm = graph.edges
      .filter(e => e.srcId < e.dstId)
      .map(e => (e.srcId, e.dstId))
      .join(communityByVertex)
      .map { case (src, (dst, srcComm)) => (dst, (src, srcComm)) }
      .join(communityByVertex)
      .flatMap { case (dst, ((src, srcComm), dstComm)) =>
        if srcComm != dstComm then Iterator((src, 1), (dst, 1)) else Iterator.empty
      }
      .reduceByKey(_ + _)
      .join(graph.vertices)
      .map { case (_, (count, researcherId)) => (researcherId, count) }

    CommunityResult(
      assignments = assignments,
      crossCommunityEdges = crossComm,
      communityCount = communityInfo.count()
    )

  /**
   * Deterministic synchronous Label Propagation over GraphX Pregel.
   *
   * Every vertex starts with its own researcher ID as label. In each superstep a vertex counts the
   * labels of its neighbours (one vote per undirected neighbour) plus one vote for its own current
   * label, and adopts the label with the most votes, breaking ties by the smallest label.
   *
   * Differences from `org.apache.spark.graphx.lib.LabelPropagation`, found on real DBLP data where
   * it reported more communities (625) than connected components (498): GraphX's version ignores
   * the vertex's own label and breaks ties by map iteration order, so on a clique or a single edge
   * the labels swap every superstep and never converge, splitting one collaboration team into
   * several communities. Counting the own label and breaking ties deterministically makes cliques
   * and pairs converge to one community, and labels are researcher IDs so the result does not
   * depend on GraphX vertex-ID assignment or partitioning. Isolated vertices keep their own label.
   *
   * Pregel checkpoints according to `spark.graphx.pregel.checkpointInterval` when the context has
   * a checkpoint directory.
   */
  def propagateLabels(graph: Graph[String, Int], maxIterations: Int): Graph[String, Int] =
    require(maxIterations > 0, s"maxIterations must be > 0, got $maxIterations")
    val init: Graph[String, Int] = graph.mapVertices((_, rid) => rid)

    def sendMessage(t: EdgeTriplet[String, Int]): Iterator[(VertexId, Map[String, Long])] =
      // The graph holds both directions of every coauthor edge, so sending to the destination
      // only gives each vertex exactly one vote per neighbour.
      if t.srcId == t.dstId then Iterator.empty else Iterator((t.dstId, Map(t.srcAttr -> 1L)))

    def mergeMessage(a: Map[String, Long], b: Map[String, Long]): Map[String, Long] =
      b.foldLeft(a) { case (acc, (label, n)) => acc.updated(label, acc.getOrElse(label, 0L) + n) }

    def vertexProgram(vid: VertexId, current: String, votes: Map[String, Long]): String =
      if votes.isEmpty then current
      else
        val withSelf = votes.updated(current, votes.getOrElse(current, 0L) + 1L)
        val best     = withSelf.values.max
        withSelf.iterator.collect { case (label, n) if n == best => label }.min

    Pregel(init, Map.empty[String, Long], maxIterations, EdgeDirection.Either)(
      vertexProgram,
      sendMessage,
      mergeMessage
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
