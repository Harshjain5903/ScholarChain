package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 8: K-core decomposition.
 *
 * Iteratively peel vertices: for each k starting at 1, repeatedly remove all vertices with degree
 * less than k until stable. Record coreness as the maximum k for which a vertex remains in the
 * k-core.
 *
 * Tested against small hand-checkable graphs: a triangle has coreness 2, a star center has coreness
 * 1, and a complete graph on n vertices has coreness n-1.
 */
object KCoreJob:

  /**
   * Compute coreness for every vertex in the graph.
   *
   * @return
   *   RDD of (researcherId, coreness)
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping
  ): RDD[(String, Int)] =
    val sc = graph.vertices.sparkContext

    val simpleGraph: Graph[Int, Int] =
      graph.mapVertices((_, _) => 0)

    val corenessMap = peel(sc, simpleGraph, 1, Map.empty)
    val results = corenessMap.toSeq.map { case (vid, core) =>
      (mapping.toStr.getOrElse(vid, s"unknown:$vid"), core)
    }
    sc.parallelize(results)

  /**
   * Recursive peeling: remove vertices with degree less than k, record their coreness as k-1,
   * repeat until no vertices removed, then increment k.
   */
  @annotation.tailrec
  private def peel(
    sc: SparkContext,
    graph: Graph[Int, Int],
    k: Int,
    accumulated: Map[VertexId, Int]
  ): Map[VertexId, Int] =
    val vertexCount = graph.numVertices
    if vertexCount == 0 then accumulated
    else
      val (remaining, removed) = removeLowDegree(graph, k)
      if removed.isEmpty then
        peel(sc, graph, k + 1, accumulated)
      else
        val newAccumulated = accumulated ++ removed.map(vid => (vid, k - 1))
        peel(sc, remaining, k, newAccumulated)

  /**
   * Remove all vertices with degree less than k. Returns the subgraph and the set of removed
   * vertex IDs. Iterates until no more vertices can be removed (cascading removal).
   */
  @annotation.tailrec
  private def removeLowDegree(
    graph: Graph[Int, Int],
    k: Int,
    accumulated: Set[VertexId] = Set.empty
  ): (Graph[Int, Int], Set[VertexId]) =
    val degrees  = graph.outDegrees
    val allVerts = graph.vertices.leftOuterJoin(degrees).mapValues {
      case (_, degOpt) => degOpt.getOrElse(0)
    }
    val lowDeg = allVerts.filter { case (_, deg) => deg < k }.map(_._1).collect().toSet

    if lowDeg.isEmpty then (graph, accumulated)
    else
      val subgraph = graph.subgraph(vpred = (vid, _) => !lowDeg.contains(vid))
      removeLowDegree(subgraph, k, accumulated ++ lowDeg)

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
