package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.Graph
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Job 4: Connected components of the coauthorship graph.
 *
 * Uses GraphX connected components. Reports component ID and size per researcher, fraction of
 * researchers in the largest component, and number of singletons.
 */
object ConnectedComponentsJob:

  /** Results: per-vertex component assignment plus summary statistics. */
  case class ComponentResult(
    assignments: RDD[(String, String, Long)],
    largestComponentFraction: Double,
    singletonCount: Long,
    componentCount: Long,
    largestComponentSize: Long = 0L
  ):

    /**
     * The ID of the largest component (ties broken by the smallest ID), or None for an empty graph.
     * One bounded `takeOrdered(1)` job.
     */
    def largestComponentId: Option[String] =
      assignments
        .map { case (_, cid, size) => (cid, size) }
        .distinct()
        .takeOrdered(1)(Ordering.by[(String, Long), (Long, String)] { case (c, s) => (-s, c) })
        .headOption
        .map(_._1)

  /**
   * Compute connected components. Component IDs are `component:<smallest researcher ID in the
   * component>`; sizes are attached with a distributed join and only scalar statistics reach the
   * driver.
   *
   * @param graph
   *   undirected GraphX graph (vertex attr = researcher ID string)
   * @param mapping
   *   vertex ID mapping (Long to String)
   * @return
   *   ComponentResult with assignments and summary stats
   */
  def compute(
    graph: Graph[String, Int],
    mapping: GraphUtils.VertexMapping
  ): ComponentResult =
    val cc = graph.connectedComponents()
    // Researcher IDs come from the graph's vertex attributes via an index-aligned join.
    val components: RDD[(Long, String)] = cc.vertices
      .innerJoin(graph.vertices)((_, componentId, researcherId) => (componentId, researcherId))
      .map(_._2)

    // Per component: (smallest researcher ID, size). Naming a component after its smallest
    // researcher ID keeps component IDs independent of GraphX vertex-ID assignment/partitioning.
    val componentInfo: RDD[(Long, (String, Long))] = components
      .mapValues(rid => (rid, 1L))
      .reduceByKey { case ((r1, n1), (r2, n2)) => (if r1 <= r2 then r1 else r2, n1 + n2) }
    componentInfo.cache()
    val componentSizes: RDD[(Long, Long)] = componentInfo.mapValues(_._2)

    // Scalars only: (component count, vertex count, largest size, singleton count).
    val (componentCount, totalVertices, largestSize, singletons) = componentSizes
      .map { case (_, size) => (1L, size, size, if size == 1L then 1L else 0L) }
      .fold((0L, 0L, 0L, 0L)) { case ((c1, t1, l1, s1), (c2, t2, l2, s2)) =>
        (c1 + c2, t1 + t2, math.max(l1, l2), s1 + s2)
      }
    val largestFraction =
      if totalVertices > 0 then largestSize.toDouble / totalVertices.toDouble else 0.0

    // Sizes are attached with a distributed join, not a driver-side map of all components.
    val assignments = components
      .join(componentInfo)
      .map { case (_, (researcherId, (name, size))) => (researcherId, s"component:$name", size) }

    ComponentResult(
      assignments = assignments,
      largestComponentFraction = largestFraction,
      singletonCount = singletons,
      componentCount = componentCount,
      largestComponentSize = largestSize
    )

  /** Update ResearcherMetrics with component IDs. */
  def enrichMetrics(
    metrics: RDD[ResearcherMetrics],
    result: ComponentResult
  ): RDD[ResearcherMetrics] =
    val compMap = result.assignments.map { case (rid, compId, _) =>
      (rid, compId)
    }
    metrics
      .map(m => (m.researcherId, m))
      .leftOuterJoin(compMap)
      .map { case (_, (m, compOpt)) =>
        m.copy(componentId = compOpt.orElse(m.componentId))
      }
