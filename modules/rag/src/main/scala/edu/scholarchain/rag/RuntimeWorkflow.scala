package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.{MetricWindow, RankableResearcherMetric}
import org.slf4j.LoggerFactory
import upickle.default.*

import scala.util.control.NonFatal

case class WorkflowResult(
  response: AnswerResponse,
  auditRecord: AuditRecord
) derives ReadWriter

/**
 * Orchestrates the full collaboration-analysis workflow from question to verified answer.
 *
 * Abstains (one abstention claim, no synthesis) when a named researcher is unknown or ambiguous.
 * After verification, UNSUPPORTED and CONFLICTING claims are removed from the response (their IDs
 * are recorded in the audit record); PARTIALLY_SUPPORTED claims stay and are labelled in
 * `generatedText`. If nothing survives, the response is a single abstention claim.
 *
 * Ranking questions (the plan carries [[QueryPlannerAgent.RankingRequestedKey]] and no researcher
 * is named) take the ranking path: the plan's ranking metric must be an allowlisted
 * [[RankableResearcherMetric]] wire name (anything else abstains with reason
 * `unsupported-ranking-metric` before any retrieval), the top researchers for the window are read
 * through [[CollaborationMetricsAgent.rank]], one metric evidence item is built per ranked
 * researcher, and the claims are synthesized and verified like any other answer.
 *
 * @param generationModelId
 *   generation model recorded in the audit record (`AppConfig.llm.modelId`)
 * @param defaultWindow
 *   window bounds used when the question names none (see `RuntimeStorage.metricWindow`)
 */
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
  audit: AuditStore,
  generationModelId: String,
  defaultWindow: MetricWindow = RuntimeWorkflow.DefaultWindow
):
  import RuntimeWorkflow.*

  private val log = LoggerFactory.getLogger(classOf[RuntimeWorkflow])

  def execute(
    question: String,
    constraints: Map[String, String]
  ): WorkflowResult =
    val startTime = System.currentTimeMillis()
    val runId     = IdGen.runId()

    // Step 1: Plan
    val queryPlan = planner.plan(question, constraints)
    val queryId   = queryPlan.queryId

    // Step 2: Resolve researcher names and explicit IDs from filters
    def filterValues(prefix: String): Vector[String] =
      queryPlan.filters.toVector.sortBy(_._1).collect {
        case (k, v) if k.startsWith(prefix) && v.trim.nonEmpty => v.trim
      }
    val resolved =
      resolver.resolve(filterValues("researcherName")) ++
        resolver.resolveIds(filterValues("researcherId"))

    // Step 3: Extract window
    val windowStart =
      queryPlan.filters.get("yearFrom").flatMap(_.toIntOption).getOrElse(defaultWindow.startYear)
    val windowEnd =
      queryPlan.filters.get("yearTo").flatMap(_.toIntOption).getOrElse(defaultWindow.endYear)

    val notes = Vector.newBuilder[String]

    def safely[A](step: String, empty: A)(f: => A): A =
      try f
      catch
        case NonFatal(e) =>
          log.warn(s"query $queryId: $step unavailable: ${e.getClass.getSimpleName}")
          notes += s"${step}Unavailable"
          empty

    val unclear = resolved.filterNot(_.isUnambiguous)
    if unclear.nonEmpty then return abstainEarly(queryPlan, resolved, unclear, runId, startTime)

    val rankingRequested =
      queryPlan.filters.get(QueryPlannerAgent.RankingRequestedKey).contains("true") ||
        queryPlan.filters.contains(QueryPlannerAgent.RankingMetricKey)
    if rankingRequested && resolved.isEmpty then
      return executeRanking(queryPlan, windowStart, windowEnd, runId, startTime)

    val resolvedIds = resolved.map(_.researcher.researcherId).distinct.take(MaxResearchers)

    // Step 4: Retrieval (metrics, coauthor edges, paths, community, semantic)
    val metricsResult =
      safely("metrics", Vector.empty[ResearcherMetrics])(
        metricsAgent.retrieve(resolvedIds, windowStart, windowEnd)
      )

    val coauthorEdges = safely("coauthors", Vector.empty[CoauthorEdge])(
      resolvedIds.flatMap(id => pathFinder.directCoauthors(id, CoauthorsPerResearcher))
    ).filter(e => e.lastCollaborationYear >= windowStart && e.firstCollaborationYear <= windowEnd)

    val pairs =
      if resolvedIds.size >= 2 then
        resolvedIds.combinations(2).map(pair => (pair(0), pair(1))).toVector
      else Vector.empty
    val pathResults =
      safely("paths", Vector.empty[PathResult])(pathFinder.findPaths(pairs))

    val communityIds = metricsResult.flatMap(_.communityId).distinct
    val communityResults =
      safely("community", Vector.empty[CommunityInfo])(
        community.analyze(resolvedIds, windowStart, windowEnd)
      )

    val semanticResult =
      safely("semantic", SemanticContextBundle(queryId, Nil, Nil, Nil, Nil, Nil, 0, false))(
        semantic.retrieve(resolvedIds, communityIds, queryPlan.originalQuery)
      )

    // Step 5: Build evidence
    val evidenceBundle = evidenceBuilder.build(
      queryId = queryId,
      metrics = metricsResult,
      paths = pathResults,
      communities = communityResults,
      semanticContext = semanticResult,
      resolvedResearchers = resolved,
      runId = runId,
      coauthorEdges = coauthorEdges
    )

    answer(
      queryPlan,
      evidenceBundle,
      resolved,
      resolvedIds,
      runId,
      startTime,
      semanticSummary(semanticResult) + notes.result().map(n => s"; $n").mkString
    )

  /** Steps 6-8: synthesize, verify and drop unsupported claims, audit. */
  private def answer(
    queryPlan: QueryPlan,
    evidenceBundle: EvidenceBundle,
    resolved: Vector[ResolvedResearcher],
    resolvedIds: Vector[String],
    runId: String,
    startTime: Long,
    summarySuffix: String
  ): WorkflowResult =
    val queryId = queryPlan.queryId
    val outcome = synthesis.synthesizeDetailed(queryPlan, evidenceBundle, resolved)

    val (abstentions, candidates) = outcome.claims.partition(AnswerSynthesisAgent.isAbstention)
    val checks                    = verification.verifyDetailed(candidates, evidenceBundle)
    val verified                  = checks.map(c => c.claim.copy(verificationStatus = c.status))
    val (kept, removed)           = verified.partition(c => Kept.contains(c.verificationStatus))
    val (finalClaims, abstentionReason) =
      if kept.nonEmpty then (kept, None)
      else if abstentions.nonEmpty then (abstentions, outcome.abstentionReason)
      else
        (
          Vector(
            AnswerSynthesisAgent.abstainClaim(
              queryId,
              "Insufficient evidence: no generated claim could be verified against the evidence.",
              outcome.promptVersion
            )
          ),
          Some("no-verified-claims")
        )

    val verificationSummary =
      summarizeVerification(verified) +
        s"; removed=${removed.size}; droppedUnknownEvidence=${outcome.droppedClaimIds.size}" +
        summarySuffix

    finish(
      queryPlan,
      runId,
      startTime,
      finalClaims,
      evidenceBundle,
      resolvedIds,
      outcome.promptVersion,
      outcome.promptHash,
      outcome.mode,
      removed.map(_.claimId),
      abstentionReason,
      verificationSummary
    )

  /**
   * Ranking path. The metric comes from the plan filter and must exactly match an allowlisted wire
   * name; the user's text never reaches a query. Ranked researchers become metric evidence items
   * (rank order, IDs from `IdGen.evidenceId`).
   */
  private def executeRanking(
    plan: QueryPlan,
    windowStart: Int,
    windowEnd: Int,
    runId: String,
    startTime: Long
  ): WorkflowResult =
    val requested = plan.filters.get(QueryPlannerAgent.RankingMetricKey).map(_.trim)
    requested.map(RankableResearcherMetric.fromName) match
      case None | Some(Left(_)) =>
        abstainWith(
          plan,
          runId,
          startTime,
          "unsupported-ranking-metric",
          "Cannot rank researchers by the requested measure. Supported ranking metrics: " +
            RankableResearcherMetric.AllowedNames.mkString(", ") + "."
        )
      case Some(Right(_)) if windowStart > windowEnd =>
        abstainWith(
          plan,
          runId,
          startTime,
          "invalid-window",
          "Cannot rank researchers: the start of the requested time window is after its end."
        )
      case Some(Right(metric)) =>
        val limit = plan.filters
          .get(QueryPlannerAgent.RankingLimitKey)
          .flatMap(_.trim.toIntOption)
          .getOrElse(QueryPlannerAgent.DefaultRankingLimit)
        val empty = MetricRanking(metric, windowStart, windowEnd, Vector.empty)
        val (ranking, note) =
          try (metricsAgent.rank(metric, windowStart, windowEnd, limit), "")
          catch
            case NonFatal(e) =>
              log.warn(s"query ${plan.queryId}: ranking unavailable: ${e.getClass.getSimpleName}")
              (empty, "; rankingUnavailable")
        val summary =
          s"; ranking=${metric.wireName}; window=$windowStart-$windowEnd; " +
            s"ranked=${ranking.ranked.size}$note"
        if ranking.ranked.isEmpty then
          val bridgeNote =
            if metric == RankableResearcherMetric.BridgeScore then
              " Researchers whose bridge score was not computed are excluded, not ranked as zero."
            else ""
          abstainWith(
            plan,
            runId,
            startTime,
            "no-ranked-metrics",
            s"Insufficient evidence: no stored researcher metrics rank by ${metric.wireName} for " +
              s"the window $windowStart-$windowEnd.$bridgeNote",
            summary
          )
        else
          val bundle = evidenceBuilder.build(
            queryId = plan.queryId,
            metrics = ranking.ranked,
            paths = Vector.empty,
            communities = Vector.empty,
            semanticContext =
              SemanticContextBundle(plan.queryId, Nil, Nil, Nil, Nil, Nil, 0, false),
            resolvedResearchers = Vector.empty,
            runId = runId,
            rankedBy = Some(metric)
          )
          answer(plan, bundle, Vector.empty, Vector.empty, runId, startTime, summary)

  /** A single abstention claim with no evidence and no retrieval. */
  private def abstainWith(
    plan: QueryPlan,
    runId: String,
    startTime: Long,
    reason: String,
    statement: String,
    summarySuffix: String = ""
  ): WorkflowResult =
    val claim = AnswerSynthesisAgent.abstainClaim(plan.queryId, statement, RankingVersion)
    val bundle = EvidenceBundle(
      bundleId = s"bundle-${plan.queryId}",
      queryId = plan.queryId,
      items = Vector.empty,
      totalScore = 0.0,
      provenance = Provenance(
        sourceDataset = "evidence-builder",
        sourceRecordId = plan.queryId,
        pipelineRunId = runId,
        schemaVersion = "v1",
        timestamp = java.time.Instant.now().toString
      )
    )
    finish(
      plan,
      runId,
      startTime,
      Vector(claim),
      bundle,
      Vector.empty,
      RankingVersion,
      "",
      SynthesisMode.Extractive,
      Vector.empty,
      Some(reason),
      s"abstained=$reason$summarySuffix"
    )

  private def abstainEarly(
    plan: QueryPlan,
    resolved: Vector[ResolvedResearcher],
    unclear: Vector[ResolvedResearcher],
    runId: String,
    startTime: Long
  ): WorkflowResult =
    val parts = unclear.map { r =>
      if r.researcher.researcherId.isEmpty then
        s"no researcher matches '${r.researcher.displayName}'"
      else
        val ids = (r.researcher +: r.alternates).map(_.researcherId).mkString(", ")
        s"'${r.researcher.displayName}' is ambiguous (candidates: $ids)"
    }
    val reason =
      if unclear.exists(_.researcher.researcherId.isEmpty) then "unresolved-researcher"
      else "ambiguous-researcher"
    val claim = AnswerSynthesisAgent.abstainClaim(
      plan.queryId,
      s"Cannot answer: ${parts.mkString("; ")}. Specify the researcher by ID.",
      "resolution-v1"
    )
    val bundle = evidenceBuilder.build(
      queryId = plan.queryId,
      metrics = Vector.empty,
      paths = Vector.empty,
      communities = Vector.empty,
      semanticContext = SemanticContextBundle(plan.queryId, Nil, Nil, Nil, Nil, Nil, 0, false),
      resolvedResearchers = resolved,
      runId = runId
    )
    finish(
      plan,
      runId,
      startTime,
      Vector(claim),
      bundle,
      resolved.filter(_.isUnambiguous).map(_.researcher.researcherId),
      "resolution-v1",
      "",
      SynthesisMode.Extractive,
      Vector.empty,
      Some(reason),
      s"abstained=$reason"
    )

  private def finish(
    plan: QueryPlan,
    runId: String,
    startTime: Long,
    claims: Vector[AnswerClaim],
    evidenceBundle: EvidenceBundle,
    resolvedIds: Vector[String],
    promptVersion: String,
    promptHash: String,
    mode: SynthesisMode,
    removedIds: Vector[String],
    abstentionReason: Option[String],
    verificationSummary: String
  ): WorkflowResult =
    val queryId = plan.queryId
    val now     = java.time.Instant.now().toString
    val text = claims
      .map { c =>
        if c.verificationStatus == VerificationStatus.PartiallySupported then
          s"${c.statement} [partially supported]"
        else c.statement
      }
      .mkString(" ")

    val response = AnswerResponse(
      responseId = s"resp-$queryId",
      queryId = queryId,
      claims = claims,
      evidenceBundle = evidenceBundle,
      generatedText = text,
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
      promptVersion = promptVersion,
      modelVersion =
        if mode == SynthesisMode.Extractive then s"extractive (configured $generationModelId)"
        else generationModelId,
      latencyMs = System.currentTimeMillis() - startTime,
      verificationSummary = verificationSummary,
      timestamp = now,
      promptHash = promptHash,
      synthesisMode = mode.toString.toLowerCase,
      removedClaimIds = removedIds,
      abstentionReason = abstentionReason
    )

    audit.persistAudit(auditRecord)

    WorkflowResult(response = response, auditRecord = auditRecord)

  /** Records a semantic-retrieval abstention reason code in the audit summary. */
  private def semanticSummary(bundle: SemanticContextBundle): String =
    bundle.failure.fold("")(f => s"; semanticAbstained=${f.reason.code}")

  private def summarizeVerification(claims: Vector[AnswerClaim]): String =
    val grouped = claims.groupBy(_.verificationStatus)
    VerificationStatus.values.toVector
      .map(s => s"$s=${grouped.getOrElse(s, Vector.empty).size}")
      .mkString(", ")

object RuntimeWorkflow:

  /** Statuses that stay in the response. */
  val Kept: Set[VerificationStatus] =
    Set(VerificationStatus.Verified, VerificationStatus.PartiallySupported)

  /** At most this many resolved researchers are analysed per query (paths are pairwise). */
  val MaxResearchers: Int = 5

  /** Direct coauthor edges fetched per researcher. */
  val CoauthorsPerResearcher: Int = 25

  /** Window used when a question names no years and no deployment window is configured. */
  val DefaultWindow: MetricWindow = MetricWindow(2000, 2026)

  /** Prompt version recorded for ranking abstentions (no synthesis ran). */
  val RankingVersion: String = "ranking-v1"
