package edu.scholarchain.spark

import edu.scholarchain.core.*
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

/**
 * Shared graph utilities: vertex ID mapping (String to Long) and GraphX graph construction from
 * CoauthorEdge RDDs.
 */
object GraphUtils:

  /** Bidirectional mapping between String researcher IDs and Long GraphX vertex IDs. */
  case class VertexMapping(
    toLong: Map[String, Long],
    toStr: Map[Long, String]
  )

  /** Build a vertex ID mapping from an RDD of CoauthorEdge. Collects to driver. */
  def buildVertexMapping(edges: RDD[CoauthorEdge]): VertexMapping =
    val distinctIds = edges
      .flatMap(e => Iterator(e.researcherA, e.researcherB))
      .distinct()
      .zipWithUniqueId()
      .collect()
    val toLong = distinctIds.toMap
    val toStr  = distinctIds.map(_.swap).toMap
    VertexMapping(toLong, toStr)

  /**
   * Build a vertex ID mapping from an explicit set of researcher IDs. Useful when isolated vertices
   * must be included.
   */
  def buildVertexMappingFromIds(ids: RDD[String]): VertexMapping =
    val distinctIds = ids.distinct().zipWithUniqueId().collect()
    val toLong      = distinctIds.toMap
    val toStr       = distinctIds.map(_.swap).toMap
    VertexMapping(toLong, toStr)

  /**
   * Build an undirected GraphX graph from CoauthorEdge RDD. Vertex attribute is the researcher ID
   * string. Edge attribute is the joint publication count.
   */
  def buildGraph(
    sc: SparkContext,
    edges: RDD[CoauthorEdge],
    mapping: VertexMapping
  ): Graph[String, Int] =
    val bcMap = sc.broadcast(mapping.toLong)
    val graphEdges: RDD[Edge[Int]] = edges.flatMap { e =>
      val aId = bcMap.value(e.researcherA)
      val bId = bcMap.value(e.researcherB)
      Iterator(
        Edge(aId, bId, e.jointPublicationCount),
        Edge(bId, aId, e.jointPublicationCount)
      )
    }
    val vertices: RDD[(VertexId, String)] =
      sc.parallelize(mapping.toStr.toSeq)
    Graph(vertices, graphEdges)

  /**
   * Build an undirected GraphX graph including isolated vertices (researchers with no edges).
   * Useful when the full researcher set is larger than the edge endpoints.
   */
  def buildGraphWithIsolates(
    sc: SparkContext,
    edges: RDD[CoauthorEdge],
    allResearcherIds: RDD[String]
  ): (Graph[String, Int], VertexMapping) =
    val mapping = buildVertexMappingFromIds(
      allResearcherIds.union(
        edges.flatMap(e => Iterator(e.researcherA, e.researcherB))
      )
    )
    val graph = buildGraph(sc, edges, mapping)
    (graph, mapping)

  /** Create provenance for a Spark pipeline output. */
  def makeProv(
    runId: String,
    schemaVersion: String,
    sourceRecordId: String,
    timestamp: String
  ): Provenance =
    Provenance(
      sourceDataset = "spark-analytics",
      sourceRecordId = sourceRecordId,
      pipelineRunId = runId,
      schemaVersion = schemaVersion,
      timestamp = timestamp
    )
