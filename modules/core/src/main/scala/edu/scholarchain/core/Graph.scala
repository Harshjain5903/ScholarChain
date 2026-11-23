package edu.scholarchain.core

import upickle.default.*

/**
 * A node in the ScholarChain knowledge graph.
 *
 * `nodeType` is a free-form string for source compatibility, but values written to or queried from
 * Neptune must come from [[GraphSchema.VertexLabel]].
 */
case class GraphNode(
  nodeId: String,
  nodeType: String,
  properties: Map[String, String],
  provenance: Provenance
) derives ReadWriter:

  /** True when `nodeType` is a label declared in [[GraphSchema.VertexLabel]]. */
  def hasDeclaredVertexLabel: Boolean = GraphSchema.isDeclaredVertexLabel(nodeType)

/**
 * A directed edge in the ScholarChain knowledge graph.
 *
 * `edgeType` is a free-form string for source compatibility, but values written to or queried from
 * Neptune must come from [[GraphSchema.EdgeType]].
 */
case class GraphEdge(
  edgeId: String,
  sourceNodeId: String,
  targetNodeId: String,
  edgeType: String,
  properties: Map[String, String],
  provenance: Provenance
) derives ReadWriter:

  /** True when `edgeType` is a type declared in [[GraphSchema.EdgeType]]. */
  def hasDeclaredEdgeType: Boolean = GraphSchema.isDeclaredEdgeType(edgeType)
