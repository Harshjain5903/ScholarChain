package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.HashPartitioner

/**
 * Job 7: Bridge analysis.
 *
 * Computes two bridge metrics per researcher:
 *   - Cross-community degree: the number of distinct communities among a researcher's neighbors.
 *   - Approximate betweenness centrality using a distributed, source-sampled Brandes algorithm.
 *
 * Neither metric collects the graph to the driver.
 *
 * '''Cross-community degree''' is computed with a distributed join of community assignments onto
 * graph vertices followed by a pass over edge triplets. A researcher with no community assignment
 * is treated as its own singleton community (its researcher ID is used as the community ID).
 * Vertices with no incident edges are absent from the output.
 *
 * '''Betweenness approximation strategy''' (parameters: `sampleCount`, `seed`, `maxGraphSize`):
 *   1. Let n be the vertex count. Choose k = min(sampleCount, n) source vertices without
 *      replacement by seeded hash of the researcher ID ([[selectSources]]). These k Long IDs are
 *      the only per-vertex data held on the driver (O(k), independent of graph size).
 *   1. Forward phase: a level-synchronous BFS runs for all k sources at once. State is a
 *      distributed RDD keyed by (source, vertex) holding the shortest-path count sigma. Level d+1
 *      is produced by joining level d with the symmetric adjacency RDD, summing sigma per (source,
 *      vertex), and subtracting levels d and d-1 (in an undirected BFS a neighbour of a level-d
 *      vertex lies at level d-1, d or d+1).
 *   1. Backward phase: Brandes dependency accumulation runs level by level from the deepest level
 *      to level 0. delta(s, v) = sum over successors w of (sigma(s, v) / sigma(s, w)) * (1 +
 *      delta(s, w)), computed with distributed joins.
 *   1. Betweenness(w) = (n / k) * sum over sources s with w != s of delta(s, w).
 *
 * Normalisation: scores sum over ordered (source, target) pairs and are not divided by 2, and
 * endpoints are excluded. When k >= n every vertex is a source and the result equals exact Brandes
 * betweenness under this normalisation (for example, path a-b-c gives b = 2.0).
 *
 * Cost: the distributed state is O(k * V) (source, vertex) entries, and the number of iterations is
 * bounded by the graph diameter. Each level RDD is persisted and lineage-truncated with
 * [[GraphUtils.checkpointAndCount]] (reliable checkpoint when a checkpoint directory is set,
 * otherwise `localCheckpoint`). Only scalar counts reach the driver during the iterations.
 *
 * '''Size cap''': if the vertex count exceeds `maxGraphSize`, betweenness is NOT computed and every
 * researcher receives 0.0. Cross-community degree is still computed. This cap disables betweenness
 * entirely on large graphs; raise it explicitly (and size k accordingly) for large-scale runs.
 */
object BridgeAnalysisJob:

  /** Bridge analysis results per researcher. */
  case class BridgeResult(
    scores: RDD[(String, Double)],
    crossCommunityDegrees: RDD[(String, Int)]
  )

  /** A (source vertex, vertex) key in the multi-source BFS state. */
  private type SV = (VertexId, VertexId)

  /**
   * Compute bridge scores.
   *
   * @param graph
   *   undirected GraphX graph; vertex attribute is the researcher ID
   * @param mapping
   *   vertex ID mapping; retained for source compatibility, researcher IDs are read from vertex
   *   attributes
   * @param communityAssignments
   *   RDD of (researcherId, communityId)
   * @param sampleCount
   *   number of sampled source vertices k for the betweenness approximation (k = min(sampleCount,
   *   n); k >= n gives exact betweenness)
   * @param maxGraphSize
   *   vertex-count cap above which betweenness is skipped and reported as 0.0 for every vertex
   *   (default 100000). This disables betweenness at large scale.
   * @param seed
   *   random seed for source sampling; the same seed on the same graph gives the same scores
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    communityAssignments: RDD[(String, String)],
    sampleCount: Int = 50,
    maxGraphSize: Long = 100000L,
    seed: Long = 42L
  ): BridgeResult =
    val crossCommDeg =
      computeCrossCommunityDegree(graph, mapping, communityAssignments)
    val vertexCount = graph.numVertices

    val betweenness =
      if vertexCount <= maxGraphSize then
        computeApproximateBetweenness(graph, mapping, sampleCount, seed)
      else graph.vertices.map { case (_, rid) => (rid, 0.0) }

    BridgeResult(scores = betweenness, crossCommunityDegrees = crossCommDeg)

  /**
   * Compute the number of distinct communities among each vertex's neighbors, using distributed
   * joins only. A researcher with no assignment uses its own researcher ID as its community. If a
   * researcher has several assignments, the lexicographically smallest community ID is used.
   */
  def computeCrossCommunityDegree(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    communityAssignments: RDD[(String, String)]
  ): RDD[(String, Int)] =
    val oneCommunityPerResearcher: RDD[(String, String)] =
      communityAssignments.reduceByKey((a, b) => if a <= b then a else b)

    val communityByVertex: RDD[(VertexId, String)] = graph.vertices
      .map { case (vid, rid) => (rid, vid) }
      .join(oneCommunityPerResearcher)
      .map { case (_, (vid, comm)) => (vid, comm) }

    val labelled: Graph[(String, String), Int] =
      graph.outerJoinVertices(communityByVertex) { (_, rid, commOpt) =>
        (rid, commOpt.getOrElse(rid))
      }

    labelled.triplets
      .filter(t => t.srcId < t.dstId)
      .flatMap { t =>
        val (srcRid, srcComm) = t.srcAttr
        val (dstRid, dstComm) = t.dstAttr
        Iterator((srcRid, dstComm), (dstRid, srcComm))
      }
      .distinct()
      .map { case (rid, _) => (rid, 1) }
      .reduceByKey(_ + _)

  /**
   * Approximate betweenness centrality with distributed multi-source Brandes. See the object-level
   * documentation for the algorithm, normalisation and cost.
   *
   * @return
   *   RDD of (researcherId, betweenness) covering every vertex of the graph
   */
  def computeApproximateBetweenness(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    sampleCount: Int,
    seed: Long
  ): RDD[(String, Double)] =
    val sc = graph.vertices.sparkContext
    val n  = graph.vertices.count()
    val k  = math.max(0L, math.min(sampleCount.toLong, n)).toInt
    if n == 0L || k == 0 then return graph.vertices.map { case (_, rid) => (rid, 0.0) }

    val numParts =
      math.max(1, math.max(graph.edges.getNumPartitions, graph.vertices.getNumPartitions))
    val part = new HashPartitioner(numParts)

    // Symmetric, de-duplicated adjacency without self-loops, partitioned by vertex.
    val adjacency: RDD[(VertexId, VertexId)] = graph.edges
      .flatMap { e =>
        if e.srcId == e.dstId then Iterator.empty
        else Iterator((e.srcId, e.dstId), (e.dstId, e.srcId))
      }
      .distinct()
      .partitionBy(part)
    GraphUtils.checkpointAndCount(adjacency)

    // O(k) source IDs on the driver, chosen deterministically from the seed (see selectSources).
    val sources: Array[VertexId] = selectSources(graph, k, seed)
    val scale = n.toDouble / sources.length.toDouble

    // ---- Forward phase: levels(d) holds ((source, vertex), sigma) for vertices at distance d.
    val level0: RDD[(SV, Long)] = sc
      .parallelize(sources.toIndexedSeq.map(s => ((s, s), 1L)))
      .partitionBy(part)
    GraphUtils.checkpointAndCount(level0)

    @annotation.tailrec
    def forward(levels: Vector[RDD[(SV, Long)]]): Vector[RDD[(SV, Long)]] =
      val current  = levels.last
      val previous = if levels.length >= 2 then Some(levels(levels.length - 2)) else None
      val candidates: RDD[(SV, Long)] = current
        .map { case ((s, v), sigma) => (v, (s, sigma)) }
        .join(adjacency)
        .map { case (_, ((s, sigma), w)) => ((s, w), sigma) }
        .reduceByKey(part, _ + _)
      val withoutCurrent = candidates.subtractByKey(current, part)
      val next           = previous.fold(withoutCurrent)(p => withoutCurrent.subtractByKey(p, part))
      if GraphUtils.checkpointAndCount(next) == 0L then
        next.unpersist(blocking = false)
        levels
      else forward(levels :+ next)

    val levels = forward(Vector(level0))

    // ---- Backward phase: deltas(d) holds ((source, vertex), (sigma, delta)) for level d.
    val deepest: RDD[(SV, (Long, Double))] = levels.last.mapValues(sigma => (sigma, 0.0))
    GraphUtils.checkpointAndCount(deepest)

    @annotation.tailrec
    def backward(
      d: Int,
      deltaAtD: RDD[(SV, (Long, Double))],
      acc: List[RDD[(SV, (Long, Double))]]
    ): List[RDD[(SV, (Long, Double))]] =
      if d == 0 then deltaAtD :: acc
      else
        val predecessors = levels(d - 1)
        val contributions: RDD[(SV, Double)] = deltaAtD
          .map { case ((s, w), (sigmaW, deltaW)) => (w, (s, sigmaW, deltaW)) }
          .join(adjacency)
          .map { case (_, ((s, sigmaW, deltaW), v)) => ((s, v), (sigmaW, deltaW)) }
          .join(predecessors, part)
          .mapValues { case ((sigmaW, deltaW), sigmaV) =>
            (sigmaV.toDouble / sigmaW.toDouble) * (1.0 + deltaW)
          }
          .reduceByKey(part, _ + _)
        val deltaAtPrev: RDD[(SV, (Long, Double))] = predecessors
          .leftOuterJoin(contributions, part)
          .mapValues { case (sigmaV, contribOpt) => (sigmaV, contribOpt.getOrElse(0.0)) }
        GraphUtils.checkpointAndCount(deltaAtPrev)
        backward(d - 1, deltaAtPrev, deltaAtD :: acc)

    // deltas(0) is level 0 (each source itself), deltas(d) for d >= 1 are non-source vertices.
    val deltas = backward(levels.length - 1, deepest, Nil)

    // Sum dependencies over sources, excluding the source itself (w != s), i.e. levels >= 1.
    val nonSourceDeltas = deltas.drop(1)
    val summed: RDD[(VertexId, Double)] =
      if nonSourceDeltas.isEmpty then sc.emptyRDD[(VertexId, Double)]
      else
        sc.union(nonSourceDeltas.map(_.map { case ((_, w), (_, delta)) => (w, delta) }))
          .reduceByKey(part, _ + _)

    val scores: RDD[(String, Double)] = graph.vertices
      .leftOuterJoin(summed)
      .map { case (_, (rid, bOpt)) => (rid, bOpt.getOrElse(0.0) * scale) }
    GraphUtils.checkpointAndCount(scores)

    // Intermediate state is no longer referenced by the (truncated) result.
    levels.foreach(_.unpersist(blocking = false))
    deltas.foreach(_.unpersist(blocking = false))
    adjacency.unpersist(blocking = false)
    scores

  /**
   * Choose `k` betweenness sources: the vertices with the smallest seeded MurmurHash3 of their
   * researcher ID (ties by researcher ID), via one bounded `takeOrdered(k)` job. Keyed on researcher
   * IDs rather than GraphX vertex IDs, so the sample (and hence the scores) does not change with
   * partitioning or vertex-ID assignment.
   */
  def selectSources(graph: Graph[String, Int], k: Int, seed: Long): Array[VertexId] =
    val hashSeed = (seed ^ (seed >>> 32)).toInt
    graph.vertices
      .map { case (vid, rid) => (scala.util.hashing.MurmurHash3.stringHash(rid, hashSeed), rid, vid) }
      .takeOrdered(k)(Ordering.by[(Int, String, VertexId), (Int, String)](t => (t._1, t._2)))
      .map(_._3)

  /** Update ResearcherMetrics with bridge scores. */
  def enrichMetrics(
    metrics: RDD[ResearcherMetrics],
    result: BridgeResult
  ): RDD[ResearcherMetrics] =
    metrics
      .map(m => (m.researcherId, m))
      .leftOuterJoin(result.scores)
      .map { case (_, (m, scoreOpt)) =>
        m.copy(bridgeScore = scoreOpt.getOrElse(m.bridgeScore))
      }
