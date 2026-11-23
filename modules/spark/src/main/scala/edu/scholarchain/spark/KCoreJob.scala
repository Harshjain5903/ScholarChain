package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{Graph, TripletFields, VertexId}
import org.apache.spark.rdd.RDD

/**
 * Job 8: K-core decomposition by distributed iterative peeling.
 *
 * Coreness of a vertex is the maximum k such that the vertex belongs to the k-core (the maximal
 * subgraph in which every vertex has degree >= k). Isolated vertices have coreness 0.
 *
 * Algorithm (all per-vertex state stays in RDD/Graph form):
 *   1. State is an RDD of (vertexId, coreness) where [[Alive]] marks vertices not yet peeled.
 *   1. Each iteration joins the state onto a cached base graph and computes the remaining degree of
 *      every alive vertex with `aggregateMessages` (one message per directed edge whose endpoints
 *      are both alive, which equals `outDegrees` of the remaining subgraph).
 *   1. A single distributed aggregate returns two scalars to the driver: the alive count and the
 *      minimum remaining degree among alive vertices. If no vertex is alive the loop stops.
 *   1. The peeling level becomes k' = max(k, minDegree + 1). Every alive vertex with remaining
 *      degree < k' is removed and assigned coreness k' - 1. Jumping straight to minDegree + 1 is
 *      equivalent to incrementing k one step at a time, because no vertex would be removed at the
 *      skipped levels.
 *
 * Each iteration removes at least one vertex, so the loop terminates. Only scalars (counts and the
 * minimum degree) reach the driver. The state RDD is persisted and lineage-truncated every
 * iteration with [[GraphUtils.checkpointAndCount]] (reliable checkpoint when a checkpoint directory
 * is set, otherwise `localCheckpoint`).
 *
 * Tested against small hand-checkable graphs: a triangle has coreness 2, a star center and its
 * leaves have coreness 1, and a complete graph on n vertices has coreness n-1.
 */
object KCoreJob:

  /** Sentinel coreness for vertices that have not been peeled yet. */
  private val Alive: Int = -1

  /**
   * Compute coreness for every vertex in the graph.
   *
   * @param graph
   *   undirected GraphX graph; vertex attribute is the researcher ID
   * @param mapping
   *   vertex ID mapping; retained for source compatibility, researcher IDs are read from vertex
   *   attributes
   * @return
   *   RDD of (researcherId, coreness)
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping
  ): RDD[(String, Int)] =
    val base: Graph[Int, Int] = graph.mapVertices((_, _) => Alive).cache()

    // Plain RDD that shares the vertex partitioner, so joining it back onto `base` is narrow.
    val initial: RDD[(VertexId, Int)] =
      base.vertices.mapPartitions(_.map { case (vid, _) => (vid, Alive) }, true)
    GraphUtils.checkpointAndCount(initial)

    val coreness = peel(base, initial, k = 1)

    val result: RDD[(String, Int)] = graph.vertices
      .join(coreness)
      .map { case (_, (rid, core)) => (rid, core) }
    GraphUtils.checkpointAndCount(result)
    coreness.unpersist(blocking = false)
    base.unpersist(blocking = false)
    result

  /**
   * One peeling iteration per call. `state` is persisted and lineage-truncated by the caller.
   * Returns the final state, in which every vertex has its coreness.
   */
  @annotation.tailrec
  private def peel(
    base: Graph[Int, Int],
    state: RDD[(VertexId, Int)],
    k: Int
  ): RDD[(VertexId, Int)] =
    val current: Graph[Int, Int] =
      base.outerJoinVertices(state)((_, _, s) => s.getOrElse(Alive))

    val remainingDegree = current.aggregateMessages[Int](
      ctx => if ctx.srcAttr == Alive && ctx.dstAttr == Alive then ctx.sendToSrc(1),
      _ + _,
      TripletFields.All
    )

    val withDegree: RDD[(VertexId, (Int, Int))] = current.vertices
      .leftJoin(remainingDegree)((_, core, degOpt) => (core, degOpt.getOrElse(0)))
    withDegree.cache()

    // Scalars only: (alive count, minimum remaining degree among alive vertices).
    val (aliveCount, minDegree) = withDegree
      .filter { case (_, (core, _)) => core == Alive }
      .map { case (_, (_, deg)) => (1L, deg) }
      .fold((0L, Int.MaxValue)) { case ((c1, d1), (c2, d2)) => (c1 + c2, math.min(d1, d2)) }

    if aliveCount == 0L then
      withDegree.unpersist(blocking = false)
      current.unpersistVertices(blocking = false)
      state
    else
      val level = math.max(k, minDegree + 1)
      // A plain RDD (not a VertexRDD) so that lineage truncation applies to the data GraphX reads;
      // the vertex partitioner is preserved, keeping the next outerJoinVertices narrow.
      val next: RDD[(VertexId, Int)] = withDegree.mapPartitions(
        _.map { case (vid, (core, deg)) =>
          (vid, if core == Alive && deg < level then level - 1 else core)
        },
        preservesPartitioning = true
      )
      GraphUtils.checkpointAndCount(next)
      withDegree.unpersist(blocking = false)
      current.unpersistVertices(blocking = false)
      state.unpersist(blocking = false)
      peel(base, next, level)

  /** Update ResearcherMetrics with coreness values. */
  def enrichMetrics(
    metrics: RDD[ResearcherMetrics],
    corenessRdd: RDD[(String, Int)]
  ): RDD[ResearcherMetrics] =
    metrics
      .map(m => (m.researcherId, m))
      .leftOuterJoin(corenessRdd)
      .map { case (_, (m, coreOpt)) =>
        m.copy(coreness = coreOpt.getOrElse(m.coreness))
      }
