package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{EdgeTriplet, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 7: Bridge analysis.
 *
 * Computes two bridge metrics per researcher:
 *   - Cross-community degree: the number of distinct communities among a researcher's neighbors.
 *   - Approximate betweenness centrality using Brandes' algorithm with sampled source vertices.
 *
 * Approximation strategy: pick k random source vertices, run BFS from each on the driver, compute
 * betweenness contributions using Brandes' backward accumulation, sum across sources, and scale by
 * n/k where n is the total vertex count. The graph is collected to the driver for the Brandes
 * passes; if the vertex count exceeds maxGraphSize, only cross-community degree is computed and
 * betweenness is set to 0.
 */
object BridgeAnalysisJob:

  /** Bridge analysis results per researcher. */
  case class BridgeResult(
    scores: RDD[(String, Double)],
    crossCommunityDegrees: RDD[(String, Int)]
  )

  /**
   * Compute bridge scores.
   *
   * @param graph
   *   undirected GraphX graph
   * @param mapping
   *   vertex ID mapping
   * @param communityAssignments
   *   RDD of (researcherId, communityId)
   * @param sampleCount
   *   number of random source vertices for betweenness approximation
   * @param maxGraphSize
   *   maximum vertex count for driver-side betweenness (default 100000)
   * @param seed
   *   random seed for reproducibility
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    communityAssignments: RDD[(String, String)],
    sampleCount: Int = 50,
    maxGraphSize: Long = 100000L,
    seed: Long = 42L
  ): BridgeResult =
    val sc = graph.vertices.sparkContext
    val crossCommDeg =
      computeCrossCommunityDegree(graph, mapping, communityAssignments)
    val vertexCount = graph.numVertices

    val betweenness =
      if vertexCount <= maxGraphSize then
        computeApproximateBetweenness(graph, mapping, sampleCount, seed)
      else sc.parallelize(mapping.toStr.toSeq).map { case (_, rid) => (rid, 0.0) }

    BridgeResult(scores = betweenness, crossCommunityDegrees = crossCommDeg)

  /** Compute the number of distinct communities among each vertex's neighbors. */
  def computeCrossCommunityDegree(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    communityAssignments: RDD[(String, String)]
  ): RDD[(String, Int)] =
    val sc      = graph.vertices.sparkContext
    val commMap = communityAssignments.collectAsMap()
    val bcComm  = sc.broadcast(commMap)
    val bcToStr = sc.broadcast(mapping.toStr)

    val neighborCommunities = graph.edges
      .filter(e => e.srcId < e.dstId)
      .flatMap { e =>
        val srcRid  = bcToStr.value(e.srcId)
        val dstRid  = bcToStr.value(e.dstId)
        val srcComm = bcComm.value.getOrElse(srcRid, srcRid)
        val dstComm = bcComm.value.getOrElse(dstRid, dstRid)
        Iterator(
          (srcRid, dstComm),
          (dstRid, srcComm)
        )
      }
      .distinct()
      .map { case (rid, _) => (rid, 1) }
      .reduceByKey(_ + _)

    neighborCommunities

  /**
   * Approximate betweenness centrality using sampled Brandes on the driver. Collects the graph
   * adjacency structure and runs Brandes' algorithm for each sampled source.
   */
  def computeApproximateBetweenness(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    sampleCount: Int,
    seed: Long
  ): RDD[(String, Double)] =
    val sc        = graph.vertices.sparkContext
    val vertexIds = mapping.toLong.values.toArray
    val n         = vertexIds.length
    if n == 0 then return sc.emptyRDD[(String, Double)]

    val adjacency: Map[Long, Array[Long]] = graph.edges
      .flatMap(e => Iterator((e.srcId, e.dstId), (e.dstId, e.srcId)))
      .distinct()
      .groupByKey()
      .mapValues(_.toArray)
      .collectAsMap()
      .toMap

    val rng     = new scala.util.Random(seed)
    val k       = math.min(sampleCount, n)
    val sources = rng.shuffle(vertexIds.toIndexedSeq).take(k)
    val scale   = if k > 0 then n.toDouble / k.toDouble else 1.0

    val betweenness = new java.util.HashMap[Long, Double]()
    vertexIds.foreach(v => betweenness.put(v, 0.0))

    sources.foreach { s =>
      brandesSingleSource(s, vertexIds, adjacency, betweenness)
    }

    val results = vertexIds.map { v =>
      val rid   = mapping.toStr(v)
      val score = betweenness.get(v) * scale
      (rid, score)
    }
    sc.parallelize(results.toSeq)

  /**
   * Single-source Brandes betweenness computation. Mutates the betweenness accumulator on the
   * driver. Uses BFS forward pass and backward dependency accumulation.
   */
  private def brandesSingleSource(
    source: Long,
    allVertices: Array[Long],
    adjacency: Map[Long, Array[Long]],
    betweenness: java.util.HashMap[Long, Double]
  ): Unit =
    val dist  = new java.util.HashMap[Long, Int]()
    val sigma = new java.util.HashMap[Long, Long]()
    val delta = new java.util.HashMap[Long, Double]()
    allVertices.foreach { v =>
      dist.put(v, -1)
      sigma.put(v, 0L)
      delta.put(v, 0.0)
    }
    dist.put(source, 0)
    sigma.put(source, 1L)

    val queue = new java.util.ArrayDeque[Long]()
    val stack = new java.util.ArrayDeque[Long]()
    queue.add(source)

    val predecessors = new java.util.HashMap[Long, java.util.ArrayList[Long]]()
    allVertices.foreach(v => predecessors.put(v, new java.util.ArrayList[Long]()))

    while !queue.isEmpty do
      val v = queue.poll()
      stack.push(v)
      val neighbors = adjacency.getOrElse(v, Array.empty[Long])
      neighbors.foreach { w =>
        if dist.get(w) < 0 then
          dist.put(w, dist.get(v) + 1)
          queue.add(w)
        if dist.get(w) == dist.get(v) + 1 then
          sigma.put(w, sigma.get(w) + sigma.get(v))
          predecessors.get(w).add(v)
      }

    while !stack.isEmpty do
      val w     = stack.pop()
      val preds = predecessors.get(w)
      val it    = preds.iterator()
      while it.hasNext do
        val v     = it.next()
        val coeff = (sigma.get(v).toDouble / sigma.get(w).toDouble) * (1.0 + delta.get(w))
        delta.put(v, delta.get(v) + coeff)
      if w != source then betweenness.put(w, betweenness.get(w) + delta.get(w))

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
