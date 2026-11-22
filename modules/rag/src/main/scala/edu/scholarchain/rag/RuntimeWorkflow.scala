package edu.scholarchain.rag

import edu.scholarchain.core.*
import upickle.default.*

case class WorkflowResult(
    response: AnswerResponse,
    auditRecord: AuditRecord
) derives ReadWriter

/** Orchestrates the full collaboration-analysis workflow from question to verified answer. */
class RuntimeWorkflow(
    planner: QueryPlannerAgent,
    resolver: ResearcherResolverAgent,
    metricsAgent: CollaborationMetricsAgent,
    pathFinder: PathFinderAgent,
    community: CommunityAnalystAgent,
    semantic: SemanticContextAgent,
    evidenceBuilder: EvidenceBuilderAgent,
    synthesis: AnswerSynthesisAgent,
    verification: ClaimVerificationAgent,
    audit: AuditStore
):

  def execute(
      question: String,
      constraints: Map[String, String]
  ): WorkflowResult =
    val startTime = System.currentTimeMillis()
    val runId     = IdGen.runId()

    // Step 1: Plan
    val queryPlan = planner.plan(question, constraints)
    val queryId   = queryPlan.queryId

    // Step 2: Resolve researcher names from filters
    val names = queryPlan.filters.collect {
      case (k, v) if k.startsWith("researcherName") => v
    }.toVector
    val resolved = resolver.resolve(names)

    // Step 3: Extract window
    val windowStart = queryPlan.filters.get("yearFrom").flatMap(_.toIntOption).getOrElse(2000)
    val windowEnd   = queryPlan.filters.get("yearTo").flatMap(_.toIntOption).getOrElse(2026)

    val resolvedIds =
      resolved.filter(_.confidence > 0.0).map(_.researcher.researcherId)

    // Step 4: Parallel retrieval (metrics, paths, community, semantic)
    val metricsResult = metricsAgent.retrieve(resolvedIds, windowStart, windowEnd)

    val pairs =
      if resolvedIds.size >= 2 then
        resolvedIds
          .combinations(2)
          .map(pair => (pair(0), pair(1)))
          .toVector
      else Vector.empty
    val pathResults = pathFinder.findPaths(pairs)

    val communityIds = resolved.flatMap(r =>
      metricsResult
        .find(_.researcherId == r.researcher.researcherId)
        .flatMap(_.communityId)
    )
    val communityResults =
      community.analyze(resolvedIds, windowStart, windowEnd)

    val semanticResult =
      semantic.retrieve(resolvedIds, communityIds, question)

    // Step 5: Build evidence
    val evidenceBundle = evidenceBuilder.build(
      queryId,
      metricsResult,
      pathResults,
      communityResults,
      semanticResult,
      resolved,
      runId
    )

    // Step 6: Synthesize
    val rawClaims = synthesis.synthesize(queryPlan, evidenceBundle, resolved)

    // Step 7: Verify
    val verifiedClaims = verification.verify(rawClaims, evidenceBundle)

    val latencyMs = System.currentTimeMillis() - startTime
    val now       = java.time.Instant.now().toString

    val verificationSummary = summarizeVerification(verifiedClaims)

    val response = AnswerResponse(
      responseId = s"resp-$queryId",
      queryId = queryId,
      claims = verifiedClaims,
      evidenceBundle = evidenceBundle,
      generatedText = verifiedClaims.map(_.statement).mkString(" "),
      provenance = Provenance(
        sourceDataset = "runtime-workflow",
        sourceRecordId = queryId,
        pipelineRunId = runId,
        schemaVersion = "v1",
        timestamp = now
      )
    )

    val auditRecord = AuditRecord(
      queryId = queryId,
      resolvedIds = resolvedIds,
      evidenceIds = evidenceBundle.items.map(_.evidenceId),
      promptVersion = "synthesis-v1",
      modelVersion = "bedrock-v1",
      latencyMs = latencyMs,
      verificationSummary = verificationSummary,
      timestamp = now
    )

    audit.persistAudit(auditRecord)

    WorkflowResult(response = response, auditRecord = auditRecord)

  private def summarizeVerification(claims: Vector[AnswerClaim]): String =
    val grouped = claims.groupBy(_.verificationStatus)
    val counts = Vector(
      VerificationStatus.Verified,
      VerificationStatus.PartiallySupported,
      VerificationStatus.Unsupported,
      VerificationStatus.Conflicting
    ).map(s => s"$s=${grouped.getOrElse(s, Vector.empty).size}")
    counts.mkString(", ")
