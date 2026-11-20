package edu.scholarchain.spark

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
    val sources = pairs.map(_._1).distinct
    sources.flatMap { sourceId =>
      val targets = pairs.filter(_._1 == sourceId).map(_._2)
      computeFromSource(graph, mapping, sourceId, targets, maxHops)
    }

  /**
   * Run BFS from a single source to find shortest paths to specified targets. Uses Pregel to
   * compute distances and predecessors, then reconstructs paths on the driver.
   */
  private def computeFromSource(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping,
    sourceId: String,
    targetIds: Seq[String],
    maxHops: Int
  ): Seq[PathResult] =
    val sourceVertexId = mapping.toLong.get(sourceId) match
      case Some(id) => id
      case None     => return Seq.empty

    val targetVertexIds = targetIds.flatMap(t => mapping.toLong.get(t))
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
      mergeMsg = (a, b) => if a._1 <= b._1 then a else b
    )

    val results = bfsGraph.vertices.collectAsMap()

    targetVertexIds.flatMap { targetVid =>
      results.get(targetVid) match
        case Some((dist, _)) if dist < Infinity =>
          val path = reconstructPath(results, sourceVertexId, targetVid, mapping)
          Some(
            PathResult(
              sourceResearcherId = sourceId,
              targetResearcherId = mapping.toStr(targetVid),
              pathLength = dist,
              pathVertices = path
            )
          )
        case _ => None
    }

  /** Reconstruct the path by following predecessors from target to source. */
  private def reconstructPath(
    results: scala.collection.Map[VertexId, (Int, Long)],
    source: VertexId,
    target: VertexId,
    mapping: GraphUtils.VertexMapping
  ): Vector[String] =
    @annotation.tailrec
    def walk(current: VertexId, acc: Vector[String]): Vector[String] =
      val name = mapping.toStr(current)
      if current == source then name +: acc
      else
        results.get(current) match
          case Some((_, pred)) if pred >= 0 => walk(pred, name +: acc)
          case _                            => name +: acc
    walk(target, Vector.empty)
