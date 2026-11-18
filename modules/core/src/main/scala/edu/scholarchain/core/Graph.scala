package edu.scholarchain.core

import upickle.default.*

/** A node in the ScholarChain knowledge graph. */
case class GraphNode(
    nodeId: String,
    nodeType: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter

/** A directed edge in the ScholarChain knowledge graph. */
case class GraphEdge(
    edgeId: String,
    sourceNodeId: String,
    targetNodeId: String,
    edgeType: String,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter
