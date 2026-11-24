package edu.scholarchain.core

/**
 * Single source of truth for the ScholarChain graph schema as stored in Neptune (review finding
 * N36).
 *
 * Every producer (Spark graph export, Neptune bulk loaders) and every consumer (storage openCypher
 * queries) must take vertex labels, edge types and property keys from this object instead of
 * hardcoding strings. The values are a wire contract: a loader that writes a different name makes
 * consumer queries silently return nothing, so changing any value here is a breaking change and is
 * pinned by `GraphSchemaSpec`.
 */
object GraphSchema:

  /** Neptune vertex labels (`~label` in bulk-load files, `(:Label)` in openCypher). */
  object VertexLabel:

    /** A resolved researcher; mirrors [[CanonicalResearcher]]. */
    val Researcher: String = "Researcher"

    /** A scholarly work; mirrors [[WorkRecord]]. */
    val Work: String = "Work"

    /** Every declared vertex label, in declaration order. */
    val All: Vector[String] = Vector(Researcher, Work)

  /** Neptune edge (relationship) types (`~label` on edges, `[:TYPE]` in openCypher). */
  object EdgeType:

    /**
     * Undirected collaboration between two `Researcher` vertices; mirrors [[CoauthorEdge]]. Stored
     * once per ordered pair (`researcherA` < `researcherB`) and matched without direction.
     */
    val Coauthor: String = "COAUTHOR"

    /** Directed `Researcher` -> `Work` authorship; mirrors [[AuthorshipRecord]]. */
    val Authored: String = "AUTHORED"

    /** Every declared edge type, in declaration order. */
    val All: Vector[String] = Vector(Coauthor, Authored)

  /** Property keys used for identity, lookup and edge parsing. */
  object PropertyKey:

    /** Generic node identity used by neighbor traversal; mirrors [[GraphNode.nodeId]]. */
    val NodeId: String = "nodeId"

    /** `Researcher` identity; mirrors [[CanonicalResearcher.researcherId]]. */
    val ResearcherId: String = "researcherId"

    /** `Researcher` connected-component membership; mirrors [[ResearcherMetrics.componentId]]. */
    val ComponentId: String = "componentId"

    /** `Work` identity; mirrors [[WorkRecord.workId]]. */
    val WorkId: String = "workId"

    /** `COAUTHOR` edge: lexicographically smaller researcher ID. */
    val ResearcherA: String = "researcherA"

    /** `COAUTHOR` edge: lexicographically larger researcher ID. */
    val ResearcherB: String = "researcherB"

    /** `COAUTHOR` edge: number of shared works; also the ordering key for direct coauthors. */
    val JointPublicationCount: String = "jointPublicationCount"

    /** `COAUTHOR` edge: earliest shared publication year. */
    val FirstCollaborationYear: String = "firstCollaborationYear"

    /** `COAUTHOR` edge: latest shared publication year. */
    val LastCollaborationYear: String = "lastCollaborationYear"

    /** `COAUTHOR` edge: sample of shared work IDs. */
    val RepresentativeWorkIds: String = "representativeWorkIds"

    /** Provenance metadata carried by every vertex and edge. */
    val Provenance: String = "provenance"

    /** Vertex identity and lookup keys, in declaration order. */
    val VertexKeys: Vector[String] = Vector(NodeId, ResearcherId, ComponentId, WorkId)

    /**
     * Properties a stored `COAUTHOR` edge must carry; storage parses an edge as a [[CoauthorEdge]],
     * which requires every one of these keys.
     */
    val CoauthorEdgeKeys: Vector[String] = Vector(
      ResearcherA,
      ResearcherB,
      JointPublicationCount,
      FirstCollaborationYear,
      LastCollaborationYear,
      RepresentativeWorkIds,
      Provenance
    )

    /** Every declared property key, without duplicates, in declaration order. */
    val All: Vector[String] = (VertexKeys ++ CoauthorEdgeKeys).distinct

  /**
   * Identifier pattern a relationship type must match to be placed in openCypher query text
   * (relationship types cannot be query parameters). Same pattern as the storage allowlist.
   */
  val IdentifierPattern: String = "^[A-Za-z_][A-Za-z0-9_]*$"

  private val IdentifierRegex = IdentifierPattern.r

  /**
   * True when `edgeType` is a plain identifier safe to splice into openCypher query text. This is a
   * syntactic check only; use [[isDeclaredEdgeType]] to also require a declared type.
   */
  def isValidEdgeTypeIdentifier(edgeType: String): Boolean =
    edgeType != null && IdentifierRegex.matches(edgeType)

  /** True when `edgeType` is one of [[EdgeType.All]] (exact, case-sensitive match). */
  def isDeclaredEdgeType(edgeType: String): Boolean = EdgeType.All.contains(edgeType)

  /** True when `label` is one of [[VertexLabel.All]] (exact, case-sensitive match). */
  def isDeclaredVertexLabel(label: String): Boolean = VertexLabel.All.contains(label)

  /**
   * Returns `edgeType` unchanged when it is a declared edge type; otherwise throws
   * IllegalArgumentException with a message listing the declared types.
   */
  def requireDeclaredEdgeType(edgeType: String): String =
    require(
      isDeclaredEdgeType(edgeType),
      s"undeclared edge type '$edgeType'; expected one of ${EdgeType.All.mkString(", ")}"
    )
    edgeType
