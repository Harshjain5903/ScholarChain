package edu.scholarchain.spark

import edu.scholarchain.core.CoauthorEdge
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.SparkContext

/**
 * Shared graph utilities: distributed vertex ID mapping (String to Long), GraphX graph construction
 * from CoauthorEdge RDDs, and lineage truncation for iterative algorithms.
 *
 * Scalability contract: no function in this object materialises an O(V) or O(E) structure on the
 * driver. The vertex mapping lives in an RDD; edges are resolved to vertex IDs with distributed
 * joins; vertices are produced from the mapping RDD.
 */
object GraphUtils:

  /**
   * Distributed bidirectional mapping between String researcher IDs and Long GraphX vertex IDs.
   *
   * The mapping is held entirely in the `ids` RDD (persisted and lineage-truncated when built by
   * [[buildVertexMapping]] or [[buildVertexMappingFromIds]], so vertex IDs are assigned exactly
   * once and stay stable across reuse).
   *
   * @param ids
   *   RDD of (researcherId, vertexId); researcher IDs and vertex IDs are both unique
   */
  final case class VertexMapping(ids: RDD[(String, VertexId)]):

    /** The mapping keyed by vertex ID. */
    def byVertexId: RDD[(VertexId, String)] = ids.map(_.swap)

    /**
     * Resolve a bounded set of researcher IDs to vertex IDs with a distributed filter. Only the
     * requested entries reach the driver, so driver memory is O(|researcherIds|), not O(V). Unknown
     * researcher IDs are absent from the result.
     */
    def resolve(researcherIds: Iterable[String]): Map[String, VertexId] =
      val wanted = researcherIds.toSet
      if wanted.isEmpty then Map.empty
      else ids.filter { case (rid, _) => wanted.contains(rid) }.collect().toMap

    /**
     * Resolve a bounded set of vertex IDs to researcher IDs with a distributed filter. Driver
     * memory is O(|vertexIds|). Unknown vertex IDs are absent from the result.
     */
    def resolveVertexIds(vertexIds: Iterable[VertexId]): Map[VertexId, String] =
      val wanted = vertexIds.toSet
      if wanted.isEmpty then Map.empty
      else ids.filter { case (_, vid) => wanted.contains(vid) }.map(_.swap).collect().toMap

    /**
     * Lookup view from researcher ID to vertex ID, kept for source compatibility. Nothing is
     * materialised on the driver: each `get`/`contains` runs a distributed filter job, and
     * iteration streams the RDD one partition at a time. Production code must use [[ids]] with
     * joins, or [[resolve]] for bounded lookups. Package-private: diagnostics and tests only.
     */
    private[spark] def toLong: scala.collection.Map[String, VertexId] = new RddLookupView(ids)

    /**
     * Lookup view from vertex ID to researcher ID, kept for source compatibility. Same cost model
     * as [[toLong]]; production code must use graph vertex attributes, joins, or
     * [[resolveVertexIds]]. Package-private: diagnostics and tests only.
     */
    private[spark] def toStr: scala.collection.Map[VertexId, String] = new RddLookupView(byVertexId)

  /**
   * Read-only `Map` view over a pair RDD with unique keys. `get` launches a distributed filter that
   * returns at most one row; `iterator` streams via `toLocalIterator` (one partition in driver
   * memory at a time). Intended for diagnostics and tests, not for production hot paths.
   */
  final private class RddLookupView[K, V](rdd: RDD[(K, V)])
      extends scala.collection.AbstractMap[K, V]:

    override def get(key: K): Option[V] =
      rdd.filter { case (k, _) => k == key }.take(1).headOption.map(_._2)

    override def iterator: Iterator[(K, V)] = rdd.toLocalIterator

    // Removal would require materialising the mapping on the driver; the view is read-only.
    def -(key: K): scala.collection.Map[K, V] =
      throw new UnsupportedOperationException("RddLookupView is read-only")

    def -(key1: K, key2: K, keys: K*): scala.collection.Map[K, V] =
      throw new UnsupportedOperationException("RddLookupView is read-only")

  /** Build a vertex ID mapping from the endpoints of an RDD of CoauthorEdge. Fully distributed. */
  def buildVertexMapping(edges: RDD[CoauthorEdge]): VertexMapping =
    buildVertexMappingFromIds(edges.flatMap(e => Iterator(e.researcherA, e.researcherB)))

  /**
   * Build a vertex ID mapping from an explicit set of researcher IDs. Useful when isolated vertices
   * must be included.
   *
   * IDs are assigned with `zipWithUniqueId` after sorting each post-`distinct` partition, which
   * makes the assignment deterministic even if a partition is recomputed. The result is persisted
   * and lineage-truncated (see [[checkpointAndCount]]) so all consumers see a single assignment.
   */
  def buildVertexMappingFromIds(ids: RDD[String]): VertexMapping =
    val assigned = ids
      .distinct()
      .mapPartitions(it => it.toArray.sorted.iterator, preservesPartitioning = true)
      .zipWithUniqueId()
    checkpointAndCount(assigned)
    VertexMapping(assigned)

  /**
   * Build an undirected GraphX graph from CoauthorEdge RDD. Vertex attribute is the researcher ID
   * string. Edge attribute is the joint publication count. Each coauthor edge yields one GraphX
   * edge in each direction.
   *
   * Edge endpoints are resolved to vertex IDs with two distributed joins against `mapping.ids`; no
   * broadcast map is used. Edges whose endpoints are absent from the mapping are dropped (the
   * mapping built by [[buildVertexMapping]] always contains every endpoint).
   *
   * @param sc
   *   retained for source compatibility; not used
   */
  def buildGraph(
    sc: SparkContext,
    edges: RDD[CoauthorEdge],
    mapping: VertexMapping
  ): Graph[String, Int] =
    val idRdd = mapping.ids
    val graphEdges: RDD[Edge[Int]] = edges
      .map(e => (e.researcherA, (e.researcherB, e.jointPublicationCount)))
      .join(idRdd)
      .map { case (_, ((ridB, weight), aId)) => (ridB, (aId, weight)) }
      .join(idRdd)
      .flatMap { case (_, ((aId, weight), bId)) =>
        Iterator(Edge(aId, bId, weight), Edge(bId, aId, weight))
      }
    val vertices: RDD[(VertexId, String)] = mapping.byVertexId
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

  /**
   * Persist an RDD, truncate its lineage, and materialise it. Returns the element count (a scalar,
   * the only value sent to the driver).
   *
   * Checkpointing strategy for iterative algorithms: if the SparkContext has a checkpoint directory
   * (`sc.setCheckpointDir`), a reliable checkpoint is written there and survives executor loss.
   * Otherwise `localCheckpoint()` is used: lineage is truncated using blocks persisted on executors
   * (MEMORY_AND_DISK), which is fast but not resilient to executor loss. Must be called before any
   * other action on `rdd`.
   */
  private[spark] def checkpointAndCount[T](rdd: RDD[T]): Long =
    rdd.persist(StorageLevel.MEMORY_AND_DISK)
    if rdd.sparkContext.getCheckpointDir.isDefined then rdd.checkpoint()
    else rdd.localCheckpoint()
    rdd.count()

  /**
   * Persist an RDD and materialise it without truncating its lineage (the cheap counterpart of
   * [[checkpointAndCount]] for iterations between checkpoints). Returns the element count.
   */
  private[spark] def persistAndCount[T](rdd: RDD[T]): Long =
    rdd.persist(StorageLevel.MEMORY_AND_DISK)
    rdd.count()
