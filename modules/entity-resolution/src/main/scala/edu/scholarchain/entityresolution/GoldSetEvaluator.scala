package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import upickle.default.*

/** A gold-labeled pair for evaluation. */
case class GoldLabel(
  sourceId: String,
  targetId: String,
  expectedOutcome: String,
  matchPath: Option[String] = None,
  notes: Option[String] = None
) derives ReadWriter

/** Container for a gold-set file. */
case class GoldSet(pairs: Vector[GoldLabel]) derives ReadWriter

/** Confusion matrix entry counts. */
case class ConfusionMatrix(
  truePositive: Int,
  falsePositive: Int,
  trueNegative: Int,
  falseNegative: Int,
  unresolvedCount: Int,
  conflictCount: Int
) derives ReadWriter

/** Evaluation metrics for a single threshold or overall. */
case class EvaluationMetrics(
  precision: Double,
  recall: Double,
  f1: Double,
  coverage: Double,
  unresolvedRate: Double,
  confusion: ConfusionMatrix
) derives ReadWriter

/** Per-path breakdown of metrics. */
case class PathMetrics(
  path: String,
  count: Int,
  precision: Double,
  recall: Double,
  f1: Double
) derives ReadWriter

/** Full evaluation report. */
case class EvaluationReport(
  overall: EvaluationMetrics,
  byPath: Vector[PathMetrics],
  thresholdSensitivity: Vector[ThresholdPoint]
) derives ReadWriter

/** A single point in threshold sensitivity analysis. */
case class ThresholdPoint(
  threshold: Double,
  precision: Double,
  recall: Double,
  f1: Double,
  acceptedCount: Int,
  rejectedCount: Int,
  unresolvedCount: Int
) derives ReadWriter

/** Evaluates engine decisions against a gold-labeled set. */
object GoldSetEvaluator:

  /** Load a gold set from JSON string. */
  def loadGoldSet(json: String): GoldSet =
    read[GoldSet](json)

  /** Compute overall evaluation metrics. */
  def evaluate(
    decisions: Vector[MatchDecision],
    goldLabels: Vector[GoldLabel]
  ): EvaluationMetrics =
    val goldMap     = goldLabels.map(g => (g.sourceId, g.targetId) -> g.expectedOutcome).toMap
    var tp          = 0
    var fp          = 0
    var tn          = 0
    var fn          = 0
    var unresolved  = 0
    var conflicts   = 0
    var totalJudged = 0

    for d <- decisions do
      val key = (d.sourceId, d.targetId)
      goldMap.get(key).foreach { expected =>
        val isMatch = expected == "Accepted"
        d.decision match
          case MatchOutcome.Accepted =>
            totalJudged += 1
            if isMatch then tp += 1 else fp += 1
          case MatchOutcome.Rejected =>
            totalJudged += 1
            if !isMatch then tn += 1 else fn += 1
          case MatchOutcome.Unresolved =>
            unresolved += 1
          case MatchOutcome.Conflict =>
            conflicts += 1
      }

    val total     = goldLabels.size
    val precision = if tp + fp > 0 then tp.toDouble / (tp + fp) else 0.0
    val recall    = if tp + fn > 0 then tp.toDouble / (tp + fn) else 0.0
    val f1 = if precision + recall > 0 then 2 * precision * recall / (precision + recall) else 0.0
    val coverage       = if total > 0 then totalJudged.toDouble / total else 0.0
    val unresolvedRate = if total > 0 then unresolved.toDouble / total else 0.0

    EvaluationMetrics(
      precision = precision,
      recall = recall,
      f1 = f1,
      coverage = coverage,
      unresolvedRate = unresolvedRate,
      confusion = ConfusionMatrix(tp, fp, tn, fn, unresolved, conflicts)
    )

  /** Compute metrics broken down by match path (extracted from reason codes). */
  def evaluateByPath(
    decisions: Vector[MatchDecision],
    goldLabels: Vector[GoldLabel]
  ): Vector[PathMetrics] =
    val goldMap = goldLabels.map(g => (g.sourceId, g.targetId) -> g.expectedOutcome).toMap

    val byPath = decisions.groupBy { d =>
      d.reasonCodes.find(_.startsWith("path:")).map(_.stripPrefix("path:")).getOrElse("unknown")
    }

    byPath.map { case (path, decs) =>
      var tp = 0
      var fp = 0
      var fn = 0
      for d <- decs do
        val key = (d.sourceId, d.targetId)
        goldMap.get(key).foreach { expected =>
          val isMatch = expected == "Accepted"
          d.decision match
            case MatchOutcome.Accepted =>
              if isMatch then tp += 1 else fp += 1
            case MatchOutcome.Rejected =>
              if isMatch then fn += 1
            case _ => ()
        }
      val precision = if tp + fp > 0 then tp.toDouble / (tp + fp) else 0.0
      val recall    = if tp + fn > 0 then tp.toDouble / (tp + fn) else 0.0
      val f1 =
        if precision + recall > 0 then 2 * precision * recall / (precision + recall) else 0.0
      PathMetrics(path, decs.size, precision, recall, f1)
    }.toVector

  /**
   * Threshold sensitivity analysis: sweep thresholds from 0.1 to 0.95, re-classifying decisions at
   * each threshold.
   */
  def thresholdSensitivity(
    pairs: Vector[(CandidatePair, Map[String, Double], Double)],
    goldLabels: Vector[GoldLabel],
    policy: ScoringPolicy,
    step: Double = 0.05
  ): Vector[ThresholdPoint] =
    val goldMap    = goldLabels.map(g => (g.sourceId, g.targetId) -> g.expectedOutcome).toMap
    val thresholds = (BigDecimal(0.1) to BigDecimal(0.95) by step).map(_.toDouble).toVector

    thresholds.map { thresh =>
      var tp         = 0
      var fp         = 0
      var fn         = 0
      var accepted   = 0
      var rejected   = 0
      var unresolved = 0

      for (pair, _, score) <- pairs do
        val outcome =
          if score >= thresh then MatchOutcome.Accepted
          else if score <= policy.rejectThreshold then MatchOutcome.Rejected
          else MatchOutcome.Unresolved

        outcome match
          case MatchOutcome.Accepted   => accepted += 1
          case MatchOutcome.Rejected   => rejected += 1
          case MatchOutcome.Unresolved => unresolved += 1
          case _                       => ()

        val key = (pair.sourceId, pair.targetId)
        goldMap.get(key).foreach { expected =>
          val isMatch = expected == "Accepted"
          outcome match
            case MatchOutcome.Accepted =>
              if isMatch then tp += 1 else fp += 1
            case MatchOutcome.Rejected =>
              if isMatch then fn += 1
            case MatchOutcome.Unresolved =>
              // Unresolved gold-Accepted pairs count as missed (FN)
              if isMatch then fn += 1
            case _ => ()
        }

      val precision = if tp + fp > 0 then tp.toDouble / (tp + fp) else 0.0
      val recall    = if tp + fn > 0 then tp.toDouble / (tp + fn) else 0.0
      val f1 =
        if precision + recall > 0 then 2 * precision * recall / (precision + recall) else 0.0

      ThresholdPoint(thresh, precision, recall, f1, accepted, rejected, unresolved)
    }
