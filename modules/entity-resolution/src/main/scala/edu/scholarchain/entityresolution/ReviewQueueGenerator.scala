package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import upickle.default.*

/** An item in the human review queue. */
case class ReviewItem(
  sourceId: String,
  targetId: String,
  features: Map[String, Double],
  confidence: Double,
  reasonCodes: Vector[String],
  suggestedLabel: String,
  policyVersion: String
) derives ReadWriter

/** A batch of items for human review. */
case class ReviewQueue(items: Vector[ReviewItem]) derives ReadWriter

/** Generates a structured review queue from unresolved and low-confidence decisions. */
object ReviewQueueGenerator:

  /**
   * Select decisions that need human review: unresolved pairs and low-confidence accepted/rejected
   * pairs.
   *
   * @param decisions
   *   all match decisions
   * @param confidenceThreshold
   *   pairs with confidence below this are flagged for review even if not Unresolved
   * @param maxItems
   *   maximum number of items in the review queue
   */
  def generate(
    decisions: Vector[MatchDecision],
    confidenceThreshold: Double = 0.7,
    maxItems: Int = 500
  ): ReviewQueue =
    val candidates = decisions
      .filter { d =>
        d.decision == MatchOutcome.Unresolved ||
        d.decision == MatchOutcome.Conflict ||
        (d.decision == MatchOutcome.Accepted && d.confidence < confidenceThreshold) ||
        (d.decision == MatchOutcome.Rejected && d.confidence > (1.0 - confidenceThreshold))
      }
      .sortBy(d => -ambiguityScore(d))
      .take(maxItems)

    ReviewQueue(candidates.map(toReviewItem))

  /** Convert to JSON string. */
  def toJson(queue: ReviewQueue): String =
    write(queue, indent = 2)

  private def toReviewItem(d: MatchDecision): ReviewItem =
    ReviewItem(
      sourceId = d.sourceId,
      targetId = d.targetId,
      features = d.features,
      confidence = d.confidence,
      reasonCodes = d.reasonCodes,
      suggestedLabel = suggestLabel(d),
      policyVersion = d.policyVersion
    )

  private def suggestLabel(d: MatchDecision): String =
    d.decision match
      case MatchOutcome.Unresolved => "needs_review"
      case MatchOutcome.Conflict   => "conflict_review"
      case MatchOutcome.Accepted   => "verify_accept"
      case MatchOutcome.Rejected   => "verify_reject"

  /** Higher ambiguity score = more uncertain = higher priority for review. */
  private def ambiguityScore(d: MatchDecision): Double =
    d.decision match
      case MatchOutcome.Conflict   => 1.0
      case MatchOutcome.Unresolved => 0.9 - math.abs(d.confidence - 0.5) * 0.5
      case _                       => 0.5 - math.abs(d.confidence - 0.5)
