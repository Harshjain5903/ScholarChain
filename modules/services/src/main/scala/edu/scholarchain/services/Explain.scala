package edu.scholarchain.services

import edu.scholarchain.rag.AuditRecord

/**
 * Builds the `/v1/explain/{queryId}` view from the durable audit record and the stored result.
 *
 * Stage statuses are read from the audit `verificationSummary` codes the rag workflow records
 * (`<step>Unavailable`, `semanticAbstained=<code>`, `abstained=<reason>`, `ranking=<metric>`,
 * `rankingUnavailable`). Nothing here exposes prompt text, model reasoning or claim statements.
 */
object Explain:

  /** Retrieval steps of the researcher path, in workflow order. */
  val RetrievalSteps: Vector[String] =
    Vector("metrics", "coauthors", "paths", "community", "semantic")

  private val EarlyAbstention = Set("unresolved-researcher", "ambiguous-researcher")

  private def codeAfter(summary: String, marker: String): Option[String] =
    val i = summary.indexOf(marker)
    if i < 0 then None
    else Some(summary.substring(i + marker.length).takeWhile(c => c != ';' && c != ' ' && c != ','))

  def stages(audit: AuditRecord, stored: Option[StoredQuery]): Vector[StagePayload] =
    val summary = audit.verificationSummary
    val abstainedAt =
      if summary.startsWith("abstained=") then codeAfter(summary, "abstained=") else None
    val early        = audit.abstentionReason.exists(EarlyAbstention.contains)
    val ranking      = summary.contains("ranking=")
    val noRetrieval  = summary.startsWith("abstained=") && !ranking
    val evidenceSize = audit.evidenceIds.size

    val plan = StagePayload(
      "plan",
      "ok",
      stored.flatMap(_.plan).map(p => s"operations=${p.operations.mkString(",")}")
    )
    val resolve =
      if early then StagePayload("resolve", "abstained", audit.abstentionReason)
      else StagePayload("resolve", "ok", Some(s"resolved=${audit.resolvedIds.size}"))

    val retrieval =
      if ranking then
        Vector(
          StagePayload(
            "ranking",
            if summary.contains("rankingUnavailable") then "unavailable" else "ok",
            codeAfter(summary, "ranking=").map(m => s"metric=$m")
          )
        )
      else if noRetrieval then RetrievalSteps.map(s => StagePayload(s, "skipped", abstainedAt))
      else
        RetrievalSteps.map { step =>
          if summary.contains(s"${step}Unavailable") then StagePayload(step, "unavailable")
          else if step == "semantic" then
            codeAfter(summary, "semanticAbstained=") match
              case Some(code) => StagePayload(step, "abstained", Some(code))
              case None       => StagePayload(step, "ok")
          else StagePayload(step, "ok")
        }

    val synthesisSkipped = noRetrieval || (ranking && summary.startsWith("abstained="))
    val tail = Vector(
      StagePayload("evidence", "ok", Some(s"items=$evidenceSize")),
      if synthesisSkipped then StagePayload("synthesis", "skipped", audit.abstentionReason)
      else StagePayload("synthesis", "ok", Some(s"mode=${audit.synthesisMode}")),
      if synthesisSkipped then StagePayload("verification", "skipped")
      else
        StagePayload(
          "verification",
          "ok",
          Some(s"removed=${audit.removedClaimIds.size}")
        )
      ,
      StagePayload("audit", "ok")
    )
    (plan +: resolve +: retrieval) ++ tail

  def retrievalModes(audit: AuditRecord): Vector[String] =
    val summary = audit.verificationSummary
    if summary.contains("ranking=") then Vector("metrics")
    else if summary.startsWith("abstained=") then Vector.empty
    else Vector("graph", "vector", "metrics")

  def build(audit: AuditRecord, stored: Option[StoredQuery]): ExplainResponse =
    val claims = stored.toVector.flatMap(_.workflow.response.claims).map { c =>
      ClaimVerificationPayload(c.claimId, c.verificationStatus.toString, c.supportingEvidence)
    }
    ExplainResponse(
      queryId = audit.queryId,
      queryPlan = stored.flatMap(_.plan),
      stages = stages(audit, stored),
      retrievalModes = retrievalModes(audit),
      promptVersions = Vector(audit.promptVersion),
      modelVersions = Vector(audit.modelVersion),
      promptHash = audit.promptHash,
      synthesisMode = audit.synthesisMode,
      resolvedIds = audit.resolvedIds,
      evidenceIds = audit.evidenceIds,
      claimVerifications = claims,
      removedClaimIds = audit.removedClaimIds,
      abstentionReason = audit.abstentionReason,
      verificationSummary = audit.verificationSummary,
      latencyMs = audit.latencyMs,
      cacheHits = 0
    )
