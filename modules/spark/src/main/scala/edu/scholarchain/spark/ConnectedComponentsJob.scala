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
    componentCount: Long
  )

  /**
   * Compute connected components.
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
    val cc         = graph.connectedComponents()
    val bcToStr    = graph.vertices.sparkContext.broadcast(mapping.toStr)
    val components = cc.vertices.map { case (vertexId, componentId) =>
      (componentId, bcToStr.value(vertexId))
    }

    val componentSizes: RDD[(Long, Long)] = components
      .map { case (compId, _) => (compId, 1L) }
      .reduceByKey(_ + _)

    val sizeMap          = componentSizes.collectAsMap()
    val totalVertices    = sizeMap.values.sum.toDouble
    val largestSize      = if sizeMap.nonEmpty then sizeMap.values.max else 0L
    val singletons       = sizeMap.values.count(_ == 1L)
    val largestFraction  = if totalVertices > 0 then largestSize / totalVertices else 0.0

    val bcSizeMap = graph.vertices.sparkContext.broadcast(sizeMap)
    val assignments = components.map { case (compId, researcherId) =>
      val size = bcSizeMap.value(compId)
      (researcherId, s"component:$compId", size)
    }


    ComponentResult(
      assignments = assignments,
      largestComponentFraction = largestFraction,
      singletonCount = singletons,
      componentCount = sizeMap.size.toLong
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
