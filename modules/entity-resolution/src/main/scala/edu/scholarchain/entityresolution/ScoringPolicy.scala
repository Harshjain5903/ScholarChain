package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome

/**
 * Versioned scoring policy that combines features into a single confidence score and applies
 * threshold-based decision logic with an evidence hierarchy.
 */
case class ScoringPolicy(
    policyVersion: String,
    weights: Map[String, Double],
    acceptThreshold: Double,
    rejectThreshold: Double,
    unresolvedBandMin: Double,
    unresolvedBandMax: Double
):
  require(
    rejectThreshold < unresolvedBandMin && unresolvedBandMin <= unresolvedBandMax &&
      unresolvedBandMax <= acceptThreshold,
    s"Thresholds must satisfy reject < unresolvedMin <= unresolvedMax <= accept, " +
      s"got $rejectThreshold < $unresolvedBandMin <= $unresolvedBandMax <= $acceptThreshold"
  )

  /** Compute the weighted score from a feature map. Unknown features get weight 0. */
  def computeScore(features: Map[String, Double]): Double =
    val totalWeight = weights.values.sum
    if totalWeight == 0.0 then 0.0
    else
      val weighted = weights.map { case (name, w) =>
        w * features.getOrElse(name, 0.0)
      }.sum
      math.min(1.0, math.max(0.0, weighted / totalWeight))

  /**
   * Classify a score into an outcome, applying the evidence hierarchy: 1. Exact ID match (handled
   * externally before calling this) 2. Deterministic bibliographic (high-confidence score) 3.
   * Probabilistic (medium range) 4. Unresolved (needs LLM or human)
   */
  def classify(score: Double): MatchOutcome =
    if score >= acceptThreshold then MatchOutcome.Accepted
    else if score <= rejectThreshold then MatchOutcome.Rejected
    else MatchOutcome.Unresolved

  /** Determine the match path label for reporting. */
  def matchPath(score: Double): String =
    if score >= acceptThreshold then "deterministic_bibliographic"
    else if score > unresolvedBandMax then "high_probabilistic"
    else if score >= unresolvedBandMin then "unresolved_band"
    else if score > rejectThreshold then "low_probabilistic"
    else "rejected"

object ScoringPolicy:

  /** Default feature weights from reference configuration. */
  val DefaultWeights: Map[String, Double] = Map(
    "title_jaccard"      -> 0.30,
    "author_similarity"  -> 0.25,
    "year_distance"      -> 0.10,
    "venue_match"        -> 0.10,
    "coauthor_overlap"   -> 0.10,
    "orcid_match"        -> 0.10,
    "affiliation_match"  -> 0.05
  )

  /** Create a default scoring policy. */
  def default: ScoringPolicy = ScoringPolicy(
    policyVersion = "er-v1",
    weights = DefaultWeights,
    acceptThreshold = 0.93,
    rejectThreshold = 0.35,
    unresolvedBandMin = 0.65,
    unresolvedBandMax = 0.85
  )
