package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.{AuditRecord, WorkflowResult}

/** Port trait to decouple the service layer from the RAG workflow implementation. */
trait WorkflowPort:
  def execute(
      question: String,
      constraints: Map[String, String]
  ): WorkflowResult

/** Port trait for graph neighbor lookups used by the service layer. */
trait ServiceGraphStore:

  def getNeighbors(
      nodeId: String,
      edgeTypes: Vector[String],
      depth: Int,
      limit: Int
  ): Vector[GraphNode]

/** Port trait for audit record lookups used by the service layer. */
trait ServiceAuditStore:
  def getAudit(queryId: String): Option[AuditRecord]
