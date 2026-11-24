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
 * Each iteration removes at least one vertex, so the loop terminates after at most V iterations; on
 * graphs with long peeling chains (a path of length L needs about L/2 iterations) that can be many.
 * The loop is therefore capped at `maxIterations` (default [[DefaultMaxIterations]]). When the cap
 * is reached before every vertex is peeled the result is marked NOT converged
 * ([[KCoreResult.converged]] = false) and every unpeeled vertex carries coreness [[Unknown]] (-1)
 * rather than a guessed value; [[compute]] and the pipeline fail in that case instead of publishing
 * wrong coreness.
 *
 * Only scalars (counts and the minimum degree) reach the driver. The state RDD is persisted every
 * iteration and lineage-truncated with [[GraphUtils.checkpointAndCount]] (reliable checkpoint when
 * a checkpoint directory is set, otherwise `localCheckpoint`) every `checkpointInterval`
 * iterations.
 *
 * Tested against small hand-checkable graphs: a triangle has coreness 2, a star center and its
 * leaves have coreness 1, and a complete graph on n vertices has coreness n-1.
 */
object KCoreJob:

  /** Sentinel coreness for vertices that have not been peeled yet. */
  private val Alive: Int = -1

  /** Coreness reported for vertices left unpeeled when the iteration cap is hit. */
  val Unknown: Int = Alive

  /** Default peeling-iteration cap (`--kcore-max-iterations`). */
  val DefaultMaxIterations: Int = 5000

  /** Default lineage-truncation interval in iterations (`--kcore-checkpoint-interval`). */
  val DefaultCheckpointInterval: Int = 10

  /**
   * K-core result.
   *
   * @param coreness
   *   (researcherId, coreness); [[Unknown]] for vertices left unpeeled when not converged
   * @param iterations
   *   peeling iterations performed
   * @param converged
   *   true when every vertex was peeled within `maxIterations`
   */
  final case class KCoreResult(
    coreness: RDD[(String, Int)],
    iterations: Int,
    converged: Boolean,
    maxIterations: Int,
    checkpointInterval: Int
  )

  /**
   * Compute coreness for every vertex in the graph and fail if the iteration cap is reached.
   *
   * @param graph
   *   undirected GraphX graph; vertex attribute is the researcher ID
   * @param mapping
   *   vertex ID mapping; retained for source compatibility, researcher IDs are read from vertex
   *   attributes
   * @return
   *   RDD of (researcherId, coreness)
   * @throws IllegalStateException
   *   when peeling did not finish within `maxIterations`
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    maxIterations: Int = DefaultMaxIterations,
    checkpointInterval: Int = DefaultCheckpointInterval
  ): RDD[(String, Int)] =
    val result = computeWithStatus(graph, mapping, maxIterations, checkpointInterval)
    requireConverged(result)
    result.coreness

  /** Throw IllegalStateException unless `result` converged. */
  def requireConverged(result: KCoreResult): Unit =
    if !result.converged then
      throw new IllegalStateException(
        s"k-core peeling did not converge within ${result.maxIterations} iterations; coreness " +
          "would be wrong, so it is not published. Raise --kcore-max-iterations."
      )

  /**
   * Compute coreness and report convergence. Never throws on the cap; callers must check
   * [[KCoreResult.converged]] before using the values.
   */
  def computeWithStatus(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    maxIterations: Int = DefaultMaxIterations,
    checkpointInterval: Int = DefaultCheckpointInterval
  ): KCoreResult =
    require(maxIterations > 0, s"maxIterations must be > 0, got $maxIterations")
    require(checkpointInterval > 0, s"checkpointInterval must be > 0, got $checkpointInterval")
    val base: Graph[Int, Int] = graph.mapVertices((_, _) => Alive).cache()

    // Plain RDD that shares the vertex partitioner, so joining it back onto `base` is narrow.
    val initial: RDD[(VertexId, Int)] =
      base.vertices.mapPartitions(_.map { case (vid, _) => (vid, Alive) }, true)
    GraphUtils.checkpointAndCount(initial)

    val (coreness, iterations, converged, pending) =
      peel(base, initial, k = 1, iteration = 0, maxIterations, checkpointInterval, Nil)

    val result: RDD[(String, Int)] = graph.vertices
      .join(coreness)
      .map { case (_, (rid, core)) => (rid, core) }
    GraphUtils.checkpointAndCount(result)
    coreness.unpersist(blocking = false)
    pending.foreach(_.unpersist(blocking = false))
    base.unpersist(blocking = false)
    KCoreResult(result, iterations, converged, maxIterations, checkpointInterval)

  /**
   * One peeling iteration per call. `state` is persisted (and periodically lineage-truncated) by
   * the caller. States produced since the last lineage truncation are ancestors of the current one
   * and stay persisted in `pending` (a locally checkpointed ancestor cannot be recomputed once
   * unpersisted); they are released at the next truncation. Returns (final state, iterations
   * performed, converged, states still to release).
   */
  @annotation.tailrec
  private def peel(
    base: Graph[Int, Int],
    state: RDD[(VertexId, Int)],
    k: Int,
    iteration: Int,
    maxIterations: Int,
    checkpointInterval: Int,
    pending: List[RDD[(VertexId, Int)]]
  ): (RDD[(VertexId, Int)], Int, Boolean, List[RDD[(VertexId, Int)]]) =
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

    if aliveCount == 0L || iteration >= maxIterations then
      withDegree.unpersist(blocking = false)
      current.unpersistVertices(blocking = false)
      (state, iteration, aliveCount == 0L, pending)
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
      val done       = iteration + 1
      val truncation = done % checkpointInterval == 0
      if truncation then GraphUtils.checkpointAndCount(next) else GraphUtils.persistAndCount(next)
      withDegree.unpersist(blocking = false)
      current.unpersistVertices(blocking = false)
      val stillPending =
        if truncation then
          (state :: pending).foreach(_.unpersist(blocking = false))
          Nil
        else state :: pending
      peel(base, next, level, done, maxIterations, checkpointInterval, stillPending)

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
