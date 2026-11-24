package edu.scholarchain.spark

import edu.scholarchain.core.ResearcherMetrics
import org.apache.spark.graphx.{EdgeTriplet, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 5: Bounded shortest-path computation between researcher pairs using GraphX Pregel BFS.
 *
 * Does NOT compute all-pairs shortest paths. Takes a list of (source, target) researcher pairs and
 * computes shortest paths only for those pairs. The path evidence includes the intermediate vertex
 * sequence.
 */
object ShortestPathJob:

  /** A shortest-path result between two researchers. */
  case class PathResult(
    sourceResearcherId: String,
    targetResearcherId: String,
    pathLength: Int,
    pathVertices: Vector[String]
  )

  private val Infinity: Int = Int.MaxValue

  /**
   * Compute shortest paths for the given researcher pairs.
   *
   * @param graph
   *   undirected GraphX graph (vertex attr = researcher ID string, edge attr = weight)
   * @param mapping
   *   vertex ID mapping
   * @param pairs
   *   list of (sourceResearcherId, targetResearcherId) to compute paths for
   * @param maxHops
   *   maximum path length to explore (default 10)
   * @return
   *   RDD of PathResult for reachable pairs within maxHops
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    pairs: Seq[(String, String)],
    maxHops: Int = 10
  ): Seq[PathResult] =
    // Bounded lookup: only the requested researcher IDs are resolved (distributed filter).
    val resolved = mapping.resolve(pairs.flatMap { case (s, t) => Seq(s, t) })
    val sources  = pairs.map(_._1).distinct
    sources.flatMap { sourceId =>
      val targets = pairs.filter(_._1 == sourceId).map(_._2)
      computeFromSource(graph, resolved, sourceId, targets, maxHops)
    }

  /**
   * Run BFS from a single source to find shortest paths to specified targets. Uses Pregel to
   * compute distances and predecessors, then reconstructs paths on the driver.
   */
  private def computeFromSource(
    graph: Graph[String, Int],
    resolved: Map[String, VertexId],
    sourceId: String,
    targetIds: Seq[String],
    maxHops: Int
  ): Seq[PathResult] =
    val sourceVertexId = resolved.get(sourceId) match
      case Some(id) => id
      case None     => return Seq.empty

    val targetVertexIds = targetIds.flatMap(t => resolved.get(t).map(vid => (t, vid)))
    if targetVertexIds.isEmpty then return Seq.empty

    val initialGraph: Graph[(Int, Long), Int] =
      graph.mapVertices { (vid, _) =>
        if vid == sourceVertexId then (0, -1L)
        else (Infinity, -1L)
      }

    val bfsGraph = initialGraph.pregel((Infinity, -1L), maxHops)(
      vprog = (_, attr, msg) =>
        if msg._1 < attr._1 then msg
        else attr,
      sendMsg = (triplet: EdgeTriplet[(Int, Long), Int]) => {
        val fwd =
          if triplet.srcAttr._1 != Infinity && triplet.srcAttr._1 + 1 < triplet.dstAttr._1
          then Iterator((triplet.dstId, (triplet.srcAttr._1 + 1, triplet.srcId)))
          else Iterator.empty
        val rev =
          if triplet.dstAttr._1 != Infinity && triplet.dstAttr._1 + 1 < triplet.srcAttr._1
          then Iterator((triplet.srcId, (triplet.dstAttr._1 + 1, triplet.dstId)))
          else Iterator.empty
        fwd ++ rev
      },
      // Shorter distance wins; equal distances keep the smaller predecessor ID (deterministic).
      mergeMsg = (a, b) => if a._1 < b._1 || (a._1 == b._1 && a._2 <= b._2) then a else b
    )
    val bfsVertices = bfsGraph.vertices.cache()

    // Bounded lookups only: the target states, then one filter job per path level walking the
    // predecessor chains back to the source. Driver memory is O(targets * (maxHops + 1)), never
    // O(V).
    val targetStates = lookup(bfsVertices, targetVertexIds.map(_._2).toSet)
    val reachableTargets = targetVertexIds.flatMap { case (targetId, targetVid) =>
      targetStates.get(targetVid).collect {
        case (dist, _) if dist < Infinity => (targetId, targetVid, dist)
      }
    }
    val predecessors = predecessorStates(bfsVertices, targetStates, sourceVertexId)
    bfsVertices.unpersist(blocking = false)

    val reachable = reachableTargets.map { case (targetId, targetVid, dist) =>
      (targetId, dist, predecessorChain(predecessors, sourceVertexId, targetVid))
    }

    // Researcher names for path vertices only: a filter on the graph's vertex attributes bounded by
    // the number of vertices on the requested paths (<= targets * (maxHops + 1)).
    val pathVids = reachable.flatMap(_._3).toSet
    val names: Map[VertexId, String] =
      if pathVids.isEmpty then Map.empty
      else graph.vertices.filter { case (vid, _) => pathVids.contains(vid) }.collect().toMap

    reachable.map { case (targetId, dist, chain) =>
      PathResult(
        sourceResearcherId = sourceId,
        targetResearcherId = targetId,
        pathLength = dist,
        pathVertices = chain.map(names)
      )
    }

  /** Fetch the BFS state of a bounded set of vertices with a distributed filter. */
  private def lookup(
    vertices: RDD[(VertexId, (Int, Long))],
    wanted: Set[VertexId]
  ): Map[VertexId, (Int, Long)] =
    if wanted.isEmpty then Map.empty
    else vertices.filter { case (vid, _) => wanted.contains(vid) }.collect().toMap

  /**
   * Walk predecessor chains backwards from the reachable targets, fetching one level of
   * predecessors per job, until every chain reaches the source. Returns the BFS state of every
   * vertex on the chains (bounded by targets * (path length + 1)).
   */
  private def predecessorStates(
    vertices: RDD[(VertexId, (Int, Long))],
    targets: Map[VertexId, (Int, Long)],
    source: VertexId
  ): Map[VertexId, (Int, Long)] =
    @annotation.tailrec
    def loop(
      known: Map[VertexId, (Int, Long)],
      frontier: Set[VertexId]
    ): Map[VertexId, (Int, Long)] =
      val next = frontier
        .flatMap(v => known.get(v))
        .collect { case (dist, pred) if dist < Infinity && pred >= 0 => pred }
        .filterNot(known.contains)
      if next.isEmpty then known
      else
        val fetched = lookup(vertices, next)
        loop(known ++ fetched, fetched.keySet)
    val reachable = targets.filter { case (_, (dist, _)) => dist < Infinity }
    loop(reachable, reachable.keySet - source)

  /**
   * Select up to `count` deterministic (source, target) pairs for path evidence: the `2 * count`
   * researchers with the highest weighted degree (ties by researcher ID) in the component
   * `componentId` are paired i with i + n. Bounded: one `takeOrdered(2 * count)` job.
   */
  def selectPairs(
    metrics: RDD[ResearcherMetrics],
    componentId: Option[String],
    count: Int
  ): Seq[(String, String)] =
    componentId match
      case None => Seq.empty
      case Some(cid) if count > 0 =>
        val ranked = metrics
          .filter(_.componentId.contains(cid))
          .map(m => (m.researcherId, m.weightedDegree))
          .takeOrdered(2 * count)(Ordering.by[(String, Double), (Double, String)] { case (rid, w) =>
            (-w, rid)
          })
          .map(_._1)
        val half = ranked.length / 2
        (0 until half).map(i => (ranked(i), ranked(i + half)))
      case _ => Seq.empty

  /** Reconstruct the vertex-ID path by following predecessors from target to source. */
  private def predecessorChain(
    results: scala.collection.Map[VertexId, (Int, Long)],
    source: VertexId,
    target: VertexId
  ): Vector[VertexId] =
    @annotation.tailrec
    def walk(current: VertexId, acc: Vector[VertexId]): Vector[VertexId] =
      if current == source then current +: acc
      else
        results.get(current) match
          case Some((_, pred)) if pred >= 0 => walk(pred, current +: acc)
          case _                            => current +: acc
    walk(target, Vector.empty)
