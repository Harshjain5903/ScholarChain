package edu.scholarchain.entityresolution

import edu.scholarchain.core.{IdGen, MatchDecision, MatchOutcome, Provenance}

/**
 * Orchestrates the full entity resolution pipeline: 1. Exact identifier match 2. Feature scoring
 * via ScoringPolicy 3. LLM adjudication for unresolved pairs (when enabled) 4. Produces
 * MatchDecision records with provenance
 */
class EntityResolutionEngine(
    policy: ScoringPolicy,
    adjudicator: LlmAdjudicator,
    llmEnabled: Boolean = false,
    runId: String = IdGen.runId()
):

  /** Resolve a candidate pair into a MatchDecision. */
  def resolve(pair: CandidatePair): MatchDecision =
    // Step 1: Try exact identifier match
    val exactResult = ExactIdentifierMatcher.evaluate(pair)
    exactResult.outcome match
      case MatchOutcome.Accepted =>
        makeDecision(
          pair = pair,
          features = pair.precomputedFeatures ++ Map("exact_id" -> 1.0),
          outcome = MatchOutcome.Accepted,
          confidence = 1.0,
          reasonCodes = exactResult.reasonCodes :+ "path:exact_id"
        )
      case MatchOutcome.Conflict =>
        makeDecision(
          pair = pair,
          features = pair.precomputedFeatures ++ Map("exact_id" -> 0.0),
          outcome = MatchOutcome.Conflict,
          confidence = 0.0,
          reasonCodes = exactResult.reasonCodes :+ "path:conflict"
        )
      case _ =>
        // Step 2: Compute features and score
        val computedFeatures = computeFeatures(pair)
        // Precomputed features take precedence over locally computed ones
        val allFeatures      = computedFeatures ++ pair.precomputedFeatures
        val score            = policy.computeScore(allFeatures)
        val classification   = policy.classify(score)
        val matchPath        = policy.matchPath(score)

        classification match
          case MatchOutcome.Accepted =>
            makeDecision(
              pair = pair,
              features = allFeatures,
              outcome = MatchOutcome.Accepted,
              confidence = score,
              reasonCodes =
                Vector(s"score:${"%.4f".format(score)}", s"path:$matchPath")
            )
          case MatchOutcome.Rejected =>
            makeDecision(
              pair = pair,
              features = allFeatures,
              outcome = MatchOutcome.Rejected,
              confidence = score,
              reasonCodes =
                Vector(s"score:${"%.4f".format(score)}", s"path:$matchPath")
            )
          case _ =>
            // Step 3: LLM adjudication for unresolved
            if llmEnabled then adjudicateWithLlm(pair, allFeatures, score, matchPath)
            else
              makeDecision(
                pair = pair,
                features = allFeatures,
                outcome = MatchOutcome.Unresolved,
                confidence = score,
                reasonCodes = Vector(
                  s"score:${"%.4f".format(score)}",
                  s"path:$matchPath",
                  "llm_disabled"
                )
              )

  /** Resolve a batch of candidate pairs. */
  def resolveBatch(pairs: Vector[CandidatePair]): Vector[MatchDecision] =
    pairs.map(resolve)

  private def adjudicateWithLlm(
      pair: CandidatePair,
      features: Map[String, Double],
      score: Double,
      matchPath: String
  ): MatchDecision =
    val request = AdjudicationRequest(
      sourceId = pair.sourceId,
      targetId = pair.targetId,
      features = features,
      sourceFields = extractFields("source", pair),
      targetFields = extractFields("target", pair),
      policyVersion = policy.policyVersion,
      promptVersion = "adjudication-v1"
    )
    val response = adjudicator.adjudicate(request)
    val llmOutcome = response.decision match
      case AdjudicationDecision.Accept  => MatchOutcome.Accepted
      case AdjudicationDecision.Reject  => MatchOutcome.Rejected
      case AdjudicationDecision.Abstain => MatchOutcome.Unresolved

    makeDecision(
      pair = pair,
      features = features,
      outcome = llmOutcome,
      confidence = if llmOutcome == MatchOutcome.Unresolved then score else response.confidence,
      reasonCodes = Vector(
        s"score:${"%.4f".format(score)}",
        s"path:llm_adjudicated",
        s"llm_decision:${response.decision}",
        s"llm_model:${response.modelVersion}"
      ) ++ response.reasonCodes
    )

  private def computeFeatures(pair: CandidatePair): Map[String, Double] =
    var features = Map.empty[String, Double]

    (pair.sourceTitle, pair.targetTitle) match
      case (Some(st), Some(tt)) =>
        features += ("title_jaccard" -> FeatureFunctions.titleJaccard(st, tt))
      case _ => ()

    if pair.sourceAuthors.nonEmpty && pair.targetAuthors.nonEmpty then
      features += ("author_similarity" -> FeatureFunctions.bestMatchNameSimilarity(
        pair.sourceAuthors,
        pair.targetAuthors
      ))
      features += ("coauthor_overlap" -> FeatureFunctions.coauthorOverlap(
        pair.sourceAuthors,
        pair.targetAuthors
      ))

    features += ("year_distance" -> FeatureFunctions.yearDistance(
      pair.sourceYear,
      pair.targetYear
    ))

    features += ("venue_match" -> FeatureFunctions.venueMatch(
      pair.sourceVenue,
      pair.targetVenue
    ))

    features += ("orcid_match" -> FeatureFunctions.orcidMatch(
      pair.sourceOrcid,
      pair.targetOrcid
    ))

    features += ("affiliation_match" -> FeatureFunctions.affiliationMatch(
      pair.sourceAffiliation,
      pair.targetAffiliation
    ))

    features

  private def extractFields(prefix: String, pair: CandidatePair): Map[String, String] =
    val fields = scala.collection.mutable.Map.empty[String, String]
    if prefix == "source" then
      pair.sourceTitle.foreach(v => fields += ("title" -> v))
      pair.sourceYear.foreach(v => fields += ("year" -> v.toString))
      pair.sourceDoi.foreach(v => fields += ("doi" -> v))
      pair.sourceOrcid.foreach(v => fields += ("orcid" -> v))
      if pair.sourceAuthors.nonEmpty then
        fields += ("authors" -> pair.sourceAuthors.mkString("; "))
    else
      pair.targetTitle.foreach(v => fields += ("title" -> v))
      pair.targetYear.foreach(v => fields += ("year" -> v.toString))
      pair.targetDoi.foreach(v => fields += ("doi" -> v))
      pair.targetOrcid.foreach(v => fields += ("orcid" -> v))
      if pair.targetAuthors.nonEmpty then
        fields += ("authors" -> pair.targetAuthors.mkString("; "))
    fields.toMap

  private def makeDecision(
      pair: CandidatePair,
      features: Map[String, Double],
      outcome: MatchOutcome,
      confidence: Double,
      reasonCodes: Vector[String]
  ): MatchDecision =
    MatchDecision(
      sourceId = pair.sourceId,
      targetId = pair.targetId,
      features = features,
      decision = outcome,
      confidence = math.min(1.0, math.max(0.0, confidence)),
      reasonCodes = reasonCodes,
      policyVersion = policy.policyVersion,
      runId = runId
    )
