package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.{AuditRecord, WorkflowResult}

/** Composes runtime dependencies for the service layer. */
object RuntimeComposer:

  /** Creates stub implementations of all ports for local testing. */
  def composeStubs(): (WorkflowPort, ServiceGraphStore, ServiceAuditStore) =
    val stubWorkflow = new WorkflowPort:

      def execute(
        question: String,
        constraints: Map[String, String]
      ): WorkflowResult =
        val now     = java.time.Instant.now().toString
        val queryId = s"q-${java.util.UUID.randomUUID()}"
        val runId   = s"run-${java.util.UUID.randomUUID()}"
        val prov = Provenance(
          sourceDataset = "stub",
          sourceRecordId = queryId,
          pipelineRunId = runId,
          schemaVersion = "v1",
          timestamp = now
        )
        val bundle = EvidenceBundle(
          bundleId = s"bundle-$queryId",
          queryId = queryId,
          items = Vector.empty,
          totalScore = 0.0,
          provenance = prov
        )
        val response = AnswerResponse(
          responseId = s"resp-$queryId",
          queryId = queryId,
          claims = Vector.empty,
          evidenceBundle = bundle,
          generatedText = s"Stub answer for: $question",
          provenance = prov
        )
        val audit = AuditRecord(
          queryId = queryId,
          resolvedIds = Vector.empty,
          evidenceIds = Vector.empty,
          promptVersion = "stub-v1",
          modelVersion = "stub-v1",
          latencyMs = 0L,
          verificationSummary = "stub",
          timestamp = now
        )
        WorkflowResult(response = response, auditRecord = audit)

    val stubGraph = new ServiceGraphStore:

      def getNeighbors(
        nodeId: String,
        edgeTypes: Vector[String],
        depth: Int,
        limit: Int
      ): Vector[GraphNode] = Vector.empty

    val stubAudit = new ServiceAuditStore:
      def getAudit(queryId: String): Option[AuditRecord] = None

    (stubWorkflow, stubGraph, stubAudit)
