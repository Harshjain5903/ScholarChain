package edu.scholarchain.rag

import edu.scholarchain.core.*

import scala.util.control.NonFatal

/**
 * What synthesis produced and how, for the response and the audit record.
 *
 * @param llmCalls
 *   model calls made (0 in extractive mode or on early abstention; at most 2)
 * @param droppedClaimIds
 *   model-assigned IDs of claims removed because they cited an evidence ID not in the bundle
 * @param abstentionReason
 *   set when the result is a single abstention claim
 */
case class SynthesisOutcome(
  claims: Vector[AnswerClaim],
  mode: SynthesisMode,
  promptVersion: String,
  promptHash: String,
  llmCalls: Int,
  droppedClaimIds: Vector[String],
  abstentionReason: Option[String]
)

object AnswerSynthesisAgent:

  /** Prefix of every abstention claim ID. */
  val AbstainPrefix: String = "abstain-"

  /** Prompt version recorded for extractive synthesis (no prompt is sent). */
  val ExtractiveVersion: String = "extractive-v1"

  /** Model calls allowed per synthesis: the first call plus one retry on invalid output. */
  val MaxLlmCalls: Int = 2

  def isAbstention(claim: AnswerClaim): Boolean = claim.claimId.startsWith(AbstainPrefix)

  /** Reader-facing name of each allowlisted ranking metric. */
  def rankingLabel(wireName: String): String = wireName match
    case "distinctCoauthorCount"      => "distinct coauthors"
    case "publicationCount"           => "publications"
    case "weightedDegree"             => "weighted degree"
    case "maximumCoauthorsOnOnePaper" => "maximum coauthors on one paper"
    case "bridgeScore"                => "bridge score"
    case "coreness"                   => "coreness"
    case other                        => other

  /**
   * One researcher's metrics in the README answer structure, every value copied from the metric
   * evidence: publications, distinct coauthors and the window; then average coauthors per paper,
   * total coauthor occurrences, maximum coauthors on one paper with the responsible work IDs,
   * weighted degree, component with its size, community, bridge score with its method, coreness,
   * and the metric version and run ID. A `sampled` bridge score is labelled approximate; a
   * `not-computed` one is stated as not computed and never as a value or as zero importance.
   */
  def metricStatement(m: EvidenceContent.MetricFacts, who: String => String): String =
    import EvidenceContent.Field.*
    val v                      = m.values
    def get(k: String): String = v.getOrElse(k, "?")
    val rank = m.rank.fold("") { r =>
      s"Rank $r by ${rankingLabel(v.getOrElse(RankedBy, "the requested metric"))}: "
    }
    val core =
      s"${rank}Researcher ${who(m.researcherId)} had ${get(Publications)} publications and " +
        s"${get(DistinctCoauthors)} distinct coauthors in ${m.windowStart}-${m.windowEnd}"
    val maxTeam = v.get(MaxCoauthorsOnOnePaper).map { n =>
      val works = m.maximumTeamWorkIds
      val source =
        if works.nonEmpty then s", on ${works.mkString(", ")}"
        else if n != "0" then ", responsible work IDs not recorded"
        else ""
      s"maximum coauthors on one paper $n$source"
    }
    val component = v.get(ComponentId) match
      case Some(c) =>
        val size =
          v.get(ComponentSize).fold(", component size not recorded")(n => s", component size $n")
        s"component $c$size"
      case None => "component not recorded"
    val community = v.get(CommunityId).fold("community not recorded")(c => s"community $c")
    val bridge = v.get(BridgeScore).map { b =>
      v.get(EvidenceContent.Field.BridgeScoreMethod) match
        case _ if m.bridgeNotComputed =>
          s"bridge score not computed, method ${EvidenceContent.NotComputed} (betweenness was " +
            "skipped for this run, so this is not a measured zero and says nothing about how " +
            "important the researcher is as a bridge)"
        case Some(edu.scholarchain.core.BridgeScoreMethod.Sampled) =>
          s"bridge score $b, method sampled (approximate betweenness estimated from sampled " +
            "source vertices)"
        case Some(method) => s"bridge score $b, method $method"
        case None         => s"bridge score $b"
    }
    val provenance = (v.get(MetricVersion), v.get(RunId)) match
      case (Some(ver), Some(run)) => Some(s"metric version $ver, run $run")
      case (Some(ver), None)      => Some(s"metric version $ver")
      case (None, Some(run))      => Some(s"run $run")
      case _                      => None
    val details = Vector(
      v.get(AverageCoauthorsPerPaper).map(x => s"average coauthors per paper $x"),
      v.get(TotalCoauthorOccurrences).map(x => s"total coauthor occurrences $x"),
      maxTeam,
      v.get(WeightedDegree).map(x => s"weighted degree $x"),
      Some(component),
      Some(community),
      bridge,
      v.get(Coreness).map(x => s"coreness $x"),
      provenance
    ).flatten
    s"$core (${details.mkString("; ")})."

  def abstainClaim(queryId: String, statement: String, schemaVersion: String): AnswerClaim =
    AnswerClaim(
      claimId = s"$AbstainPrefix$queryId",
      queryId = queryId,
      statement = statement,
      confidence = 0.0,
      supportingEvidence = Vector.empty,
      verificationStatus = VerificationStatus.Unsupported,
      provenance = Provenance(
        sourceDataset = "answer-synthesis",
        sourceRecordId = queryId,
        pipelineRunId = queryId,
        schemaVersion = schemaVersion,
        timestamp = java.time.Instant.now().toString
      )
    )

/**
 * Turns an EvidenceBundle into answer claims.
 *
 *   - `SynthesisMode.Llm`: renders [[SynthesisPrompt]] (answer-synthesis-v2), validates the reply
 *     against the v2 JSON schema, retries invalid output at most once, then abstains. Claims that
 *     cite any evidence ID not in the bundle are dropped. A model failure after the adapter's
 *     bounded retries is an abstention, never an exception.
 *   - `SynthesisMode.Extractive`: no model call. Renders one claim per metric, coauthor-edge and
 *     path evidence item directly from its [[EvidenceContent]] fields. Metric claims follow the
 *     README answer structure ([[AnswerSynthesisAgent.metricStatement]]); a ranked item is prefixed
 *     with its rank.
 *
 * An empty bundle is always an abstention.
 */
class AnswerSynthesisAgent(
  llm: LlmClient,
  mode: SynthesisMode = SynthesisMode.Llm,
  maxOutputTokens: Int = 1024
):
  import AnswerSynthesisAgent.*

  def synthesize(
    queryPlan: QueryPlan,
    evidence: EvidenceBundle,
    resolvedResearchers: Vector[ResolvedResearcher]
  ): Vector[AnswerClaim] =
    synthesizeDetailed(queryPlan, evidence, resolvedResearchers).claims

  def synthesizeDetailed(
    queryPlan: QueryPlan,
    evidence: EvidenceBundle,
    resolvedResearchers: Vector[ResolvedResearcher]
  ): SynthesisOutcome =
    val queryId = queryPlan.queryId
    if evidence.items.isEmpty then
      abstain(queryId, "no-evidence", "Insufficient evidence to produce claims.", 0, Vector.empty)
    else
      mode match
        case SynthesisMode.Extractive => extractive(queryId, evidence, resolvedResearchers)
        case SynthesisMode.Llm        => viaLlm(queryPlan, evidence, resolvedResearchers)

  private def version: String =
    if mode == SynthesisMode.Extractive then ExtractiveVersion else SynthesisPrompt.Version

  private def promptHash: String =
    if mode == SynthesisMode.Extractive then "" else SynthesisPrompt.hash

  private def abstain(
    queryId: String,
    reason: String,
    statement: String,
    calls: Int,
    dropped: Vector[String]
  ): SynthesisOutcome =
    SynthesisOutcome(
      Vector(abstainClaim(queryId, statement, version)),
      mode,
      version,
      promptHash,
      calls,
      dropped,
      Some(reason)
    )

  private def claimProv(queryId: String): Provenance =
    Provenance(
      sourceDataset = "answer-synthesis",
      sourceRecordId = queryId,
      pipelineRunId = queryId,
      schemaVersion = version,
      timestamp = java.time.Instant.now().toString
    )

  // ---- LLM mode ----

  private case class ModelClaim(
    modelClaimId: String,
    statement: String,
    confidence: Double,
    evidenceIds: Vector[String]
  )

  private enum Parsed:
    case Valid(claims: Vector[ModelClaim], abstain: Option[String])
    case Invalid(errors: Vector[String])

  private[rag] def stripFences(raw: String): String =
    val t = raw.trim
    if t.startsWith("```") then
      t.linesIterator.toVector
        .drop(1)
        .reverse
        .dropWhile(_.trim.startsWith("```"))
        .reverse
        .mkString("\n")
        .trim
    else t

  private def parse(raw: String): Parsed =
    val json =
      try Right(ujson.read(stripFences(raw)))
      catch case NonFatal(_) => Left(Vector("$: not valid JSON"))
    json match
      case Left(errs) => Parsed.Invalid(errs)
      case Right(v) =>
        val errors = SynthesisPrompt.schema.validate(v)
        if errors.nonEmpty then Parsed.Invalid(errors)
        else
          val claims = v("claims").arr.toVector.map { c =>
            ModelClaim(
              c("claimId").str,
              c("statement").str,
              c("confidence").num,
              c("evidenceIds").arr.map(_.str).toVector.distinct
            )
          }
          val abstainReason =
            if v.obj.get("abstain").contains(ujson.True) then
              Some(v.obj.get("abstainReason").flatMap(_.strOpt).getOrElse("model abstained"))
            else None
          Parsed.Valid(claims, abstainReason)

  private def viaLlm(
    plan: QueryPlan,
    evidence: EvidenceBundle,
    researchers: Vector[ResolvedResearcher]
  ): SynthesisOutcome =
    val queryId = plan.queryId
    val request = SynthesisPrompt.render(plan, evidence, researchers, maxOutputTokens)

    def call(req: LlmRequest): Either[String, String] =
      try Right(llm.complete(req))
      catch
        case NonFatal(e) =>
          Left(s"llm-unavailable: ${e.getClass.getSimpleName}")

    def attempt(req: LlmRequest, n: Int): (Either[String, Parsed], Int) =
      call(req) match
        case Left(reason) => (Left(reason), n)
        case Right(raw) =>
          parse(raw) match
            case Parsed.Invalid(errors) if n < MaxLlmCalls =>
              attempt(req.copy(user = req.user + SynthesisPrompt.repairInstruction(errors)), n + 1)
            case parsed => (Right(parsed), n)

    attempt(request, 1) match
      case (Left(reason), calls) =>
        abstain(
          queryId,
          reason,
          "The answer model is unavailable; no claims were generated.",
          calls,
          Vector.empty
        )
      case (Right(Parsed.Invalid(_)), calls) =>
        abstain(
          queryId,
          "malformed-model-output",
          "Insufficient evidence to produce claims: the model output was invalid.",
          calls,
          Vector.empty
        )
      case (Right(Parsed.Valid(modelClaims, abstainReason)), calls) =>
        val known           = evidence.items.map(_.evidenceId).toSet
        val (kept, dropped) = modelClaims.partition(_.evidenceIds.forall(known.contains))
        val droppedIds      = dropped.map(_.modelClaimId)
        if kept.isEmpty then
          val reason =
            if dropped.nonEmpty then "all-claims-cited-unknown-evidence"
            else abstainReason.map(r => s"model-abstained: ${r.take(200)}").getOrElse("no-claims")
          abstain(queryId, reason, "Insufficient evidence to produce claims.", calls, droppedIds)
        else
          val claims = kept.zipWithIndex.map { (c, i) =>
            AnswerClaim(
              claimId = s"$queryId:claim:$i",
              queryId = queryId,
              statement = c.statement,
              confidence = c.confidence,
              supportingEvidence = c.evidenceIds,
              verificationStatus = VerificationStatus.Unsupported,
              provenance = claimProv(queryId)
            )
          }
          SynthesisOutcome(claims, mode, version, promptHash, calls, droppedIds, None)

  // ---- extractive mode ----

  private def extractive(
    queryId: String,
    evidence: EvidenceBundle,
    researchers: Vector[ResolvedResearcher]
  ): SynthesisOutcome =
    val names = researchers
      .filter(_.researcher.researcherId.nonEmpty)
      .map(r => r.researcher.researcherId -> r.researcher.displayName)
      .toMap
    def who(id: String): String =
      names.get(id).filter(_.nonEmpty).fold(id)(n => s"$n ($id)")

    val statements: Vector[(String, String)] = evidence.items.flatMap { item =>
      val metric = EvidenceContent.parseMetric(item.content).map(metricStatement(_, who))
      val edge = EvidenceContent.parseCoauthor(item.content).map { e =>
        s"${who(e.researcherA)} and ${who(e.researcherB)} share ${e.jointPublications} joint " +
          s"publications between ${e.firstYear} and ${e.lastYear}."
      }
      val path = EvidenceContent.parsePath(item.content).map { p =>
        s"${who(p.fromId)} and ${who(p.toId)} are connected by a coauthorship path of " +
          s"${p.hops} hops: ${p.nodes.mkString(" -> ")}."
      }
      metric.orElse(edge).orElse(path).map(item.evidenceId -> _)
    }

    if statements.isEmpty then
      abstain(
        queryId,
        "no-structured-evidence",
        "Insufficient evidence to produce claims: no metric or path evidence was found.",
        0,
        Vector.empty
      )
    else
      val claims = statements.zipWithIndex.map { case ((evidenceId, statement), i) =>
        AnswerClaim(
          claimId = s"$queryId:claim:$i",
          queryId = queryId,
          statement = statement,
          confidence = 1.0,
          supportingEvidence = Vector(evidenceId),
          verificationStatus = VerificationStatus.Unsupported,
          provenance = claimProv(queryId)
        )
      }
      SynthesisOutcome(claims, mode, version, promptHash, 0, Vector.empty, None)
