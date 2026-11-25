package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome

/** Human gold label vocabulary. */
enum HumanLabel:
  case Match, NonMatch, Unsure

object HumanLabel:

  /** Parse a CSV cell. Empty -> Right(None); unknown text -> Left. */
  def parse(raw: String): Either[String, Option[HumanLabel]] =
    raw.trim.toUpperCase.replace('-', '_').replace(' ', '_') match
      case ""          => Right(None)
      case "MATCH"     => Right(Some(Match))
      case "NON_MATCH" => Right(Some(NonMatch))
      case "UNSURE"    => Right(Some(Unsure))
      case other       => Left(s"invalid human_label '$raw' (expected MATCH, NON_MATCH or UNSURE)")

/** One row of a (possibly partially) labeled gold CSV, reduced to what evaluation needs. */
case class GoldRow(
  pairId: String,
  split: String,
  stratum: String,
  hardNegativeStratum: String,
  matcherScore: Double,
  matcherDecision: MatchOutcome,
  matcherPath: String,
  policyVersion: String,
  seed: Long,
  label: Option[HumanLabel]
):
  def isScored: Boolean       = GoldSetSampler.ScoredPaths.contains(matcherPath)
  def oneSidedSuffix: Boolean = hardNegativeStratum == "dblp_suffix_one_sided"
  def decisive: Boolean = label.contains(HumanLabel.Match) || label.contains(HumanLabel.NonMatch)
  def isMatch: Boolean  = label.contains(HumanLabel.Match)

  def isHardNegative: Boolean =
    label.contains(HumanLabel.NonMatch) &&
      GoldSetSampler.HardNegativeStrata.contains(hardNegativeStratum)

/** Accept/reject thresholds applied to the weighted-score paths. */
case class Thresholds(accept: Double, reject: Double):
  require(reject < accept, s"reject ($reject) must be below accept ($accept)")

/**
 * Confusion counts over decisive gold labels (MATCH / NON_MATCH). Unresolved (and Conflict)
 * decisions are split by gold label instead of being forced into TP/FP/TN/FN.
 */
case class GoldConfusion(
  tp: Int,
  fp: Int,
  tn: Int,
  fn: Int,
  unresolvedMatch: Int,
  unresolvedNonMatch: Int
):
  def n: Int          = tp + fp + tn + fn + unresolvedMatch + unresolvedNonMatch
  def decided: Int    = tp + fp + tn + fn
  def unresolved: Int = unresolvedMatch + unresolvedNonMatch
  def positives: Int  = tp + fn + unresolvedMatch

/**
 * Metrics. precision = TP/(TP+FP); recall = TP/(all gold MATCH), so an Unresolved gold match counts
 * as missed; coverage = decided/n; unresolvedRate = unresolved/n. Undefined ratios are None.
 */
case class GoldMetrics(
  confusion: GoldConfusion,
  precision: Option[Double],
  recall: Option[Double],
  f1: Option[Double],
  coverage: Option[Double],
  unresolvedRate: Option[Double]
)

object GoldMetrics:

  private def ratio(a: Int, b: Int): Option[Double] =
    if b > 0 then Some(a.toDouble / b) else None

  def from(c: GoldConfusion): GoldMetrics =
    val p = ratio(c.tp, c.tp + c.fp)
    val r = ratio(c.tp, c.positives)
    val f = for
      pp <- p
      rr <- r
    yield if pp + rr > 0 then 2 * pp * rr / (pp + rr) else 0.0
    GoldMetrics(c, p, r, f, ratio(c.decided, c.n), ratio(c.unresolved, c.n))

/** One row of the threshold sensitivity table (calibration subset only). */
case class SensitivityPoint(
  accept: Double,
  reject: Double,
  metrics: GoldMetrics,
  scoredPrecision: Option[Double],
  scoredFalseOmission: Option[Double],
  feasible: Boolean
)

/** Frozen outcome of calibration. */
case class CalibrationResult(
  thresholds: Thresholds,
  source: String,
  calibrationMetrics: GoldMetrics,
  feasible: Boolean,
  gridSize: Int,
  feasibleCount: Int
)

/**
 * Threshold calibration on the gold set. The grid search sees ONLY calibration rows: `calibrate`
 * filters by split before any label is read, so held-out labels cannot influence the thresholds.
 */
object GoldSetCalibration:

  val DefaultMinPrecision: Double     = 0.95
  val DefaultMaxFalseOmission: Double = 0.05

  val AcceptGrid: Vector[Double] =
    (BigDecimal("0.050") to BigDecimal("1.000") by BigDecimal("0.025")).map(_.toDouble).toVector

  val RejectGrid: Vector[Double] =
    (BigDecimal("0.000") to BigDecimal("0.975") by BigDecimal("0.025")).map(_.toDouble).toVector

  /** Thresholds of the policy the gold candidates were sampled under. */
  def baseThresholds: Thresholds =
    val p = MentionResolver.mentionPolicy
    Thresholds(p.acceptThreshold, p.rejectThreshold)

  /**
   * Re-decide a row under thresholds. Rule paths (exact_id, dblp_person_key, name_gate, ...) are
   * threshold-independent and keep the recorded decision; weighted-score paths are re-classified,
   * including the runner's one-sided-homonym-suffix downgrade of Accepted to Unresolved.
   */
  def decide(row: GoldRow, t: Thresholds): (MatchOutcome, String) =
    if !row.isScored then (row.matcherDecision, row.matcherPath)
    else if row.matcherScore >= t.accept then
      if row.oneSidedSuffix then (MatchOutcome.Unresolved, "deterministic_bibliographic")
      else (MatchOutcome.Accepted, "deterministic_bibliographic")
    else if row.matcherScore <= t.reject then (MatchOutcome.Rejected, "rejected")
    else (MatchOutcome.Unresolved, "unresolved_band")

  def confusion(pairs: Vector[(MatchOutcome, Boolean)]): GoldConfusion =
    pairs.foldLeft(GoldConfusion(0, 0, 0, 0, 0, 0)) { case (c, (outcome, isMatch)) =>
      outcome match
        case MatchOutcome.Accepted =>
          if isMatch then c.copy(tp = c.tp + 1) else c.copy(fp = c.fp + 1)
        case MatchOutcome.Rejected =>
          if isMatch then c.copy(fn = c.fn + 1) else c.copy(tn = c.tn + 1)
        case _ =>
          if isMatch then c.copy(unresolvedMatch = c.unresolvedMatch + 1)
          else c.copy(unresolvedNonMatch = c.unresolvedNonMatch + 1)
    }

  /** Metrics of decisive rows under thresholds. */
  def metricsAt(rows: Vector[GoldRow], t: Thresholds): GoldMetrics =
    GoldMetrics.from(confusion(rows.filter(_.decisive).map(r => decide(r, t)._1 -> r.isMatch)))

  /** Metrics of decisive rows using the decisions the matcher actually emitted. */
  def baselineMetrics(rows: Vector[GoldRow]): GoldMetrics =
    GoldMetrics.from(confusion(rows.filter(_.decisive).map(r => r.matcherDecision -> r.isMatch)))

  /** Per-path metrics, path taken from the (re-)decided outcome. */
  def byPath(
    rows: Vector[GoldRow],
    decideFn: GoldRow => (MatchOutcome, String)
  ): Vector[(String, GoldMetrics)] =
    rows
      .filter(_.decisive)
      .map(r => (decideFn(r), r.isMatch))
      .groupBy(_._1._2)
      .toVector
      .sortBy(_._1)
      .map((path, xs) => path -> GoldMetrics.from(confusion(xs.map(x => x._1._1 -> x._2))))

  private def scoredSafety(rows: Vector[GoldRow], t: Thresholds): (Option[Double], Option[Double]) =
    val scored   = rows.filter(r => r.decisive && r.isScored)
    val accepted = scored.filter(r => decide(r, t)._1 == MatchOutcome.Accepted)
    val rejected = scored.filter(r => decide(r, t)._1 == MatchOutcome.Rejected)
    val prec =
      if accepted.isEmpty then None else Some(accepted.count(_.isMatch).toDouble / accepted.size)
    val fo =
      if rejected.isEmpty then None else Some(rejected.count(_.isMatch).toDouble / rejected.size)
    (prec, fo)

  def point(
    rows: Vector[GoldRow],
    t: Thresholds,
    minPrecision: Double,
    maxFalseOmission: Double
  ): SensitivityPoint =
    val (sp, fo) = scoredSafety(rows, t)
    val feasible = sp.forall(_ >= minPrecision) && fo.forall(_ <= maxFalseOmission)
    SensitivityPoint(t.accept, t.reject, metricsAt(rows, t), sp, fo, feasible)

  /**
   * Grid search over (accept, reject) with reject < accept, on calibration rows ONLY. Feasible
   * points keep the precision of score-accepted pairs >= minPrecision and the false-omission rate
   * (gold MATCH share) of score-rejected pairs <= maxFalseOmission; vacuous constraints hold. Among
   * feasible points: maximize overall calibration F1, then coverage, then higher accept, then lower
   * reject (the conservative side). If nothing is feasible, the most conservative grid corner is
   * returned and flagged. With no decisive calibration labels the base policy thresholds are kept.
   */
  def calibrate(
    rows: Vector[GoldRow],
    minPrecision: Double = DefaultMinPrecision,
    maxFalseOmission: Double = DefaultMaxFalseOmission
  ): CalibrationResult =
    val cal = rows.filter(_.split == GoldSetSampler.Calibration)
    val grid = for
      a <- AcceptGrid
      r <- RejectGrid
      if r < a
    yield Thresholds(a, r)
    if !cal.exists(_.decisive) then
      CalibrationResult(
        baseThresholds,
        "base_policy (no decisive calibration labels)",
        metricsAt(cal, baseThresholds),
        feasible = false,
        grid.size,
        0
      )
    else
      val points   = grid.map(t => t -> point(cal, t, minPrecision, maxFalseOmission))
      val feasible = points.filter(_._2.feasible)
      feasible.sortBy { (t, p) =>
        (-p.metrics.f1.getOrElse(-1.0), -p.metrics.coverage.getOrElse(0.0), -t.accept, t.reject)
      }.headOption match
        case Some((t, p)) =>
          CalibrationResult(t, "grid_search", p.metrics, true, grid.size, feasible.size)
        case None =>
          val t = Thresholds(AcceptGrid.max, RejectGrid.min)
          CalibrationResult(
            t,
            "conservative_corner (no feasible grid point)",
            metricsAt(cal, t),
            feasible = false,
            grid.size,
            0
          )

  /**
   * Calibration-only sensitivity: sweep accept at the chosen reject, and reject at the chosen
   * accept.
   */
  def sensitivity(
    rows: Vector[GoldRow],
    chosen: Thresholds,
    minPrecision: Double = DefaultMinPrecision,
    maxFalseOmission: Double = DefaultMaxFalseOmission
  ): (Vector[SensitivityPoint], Vector[SensitivityPoint]) =
    val cal = rows.filter(_.split == GoldSetSampler.Calibration)
    val acceptSweep = AcceptGrid
      .filter(_ > chosen.reject)
      .map(a => point(cal, Thresholds(a, chosen.reject), minPrecision, maxFalseOmission))
    val rejectSweep = RejectGrid
      .filter(_ < chosen.accept)
      .map(r => point(cal, Thresholds(chosen.accept, r), minPrecision, maxFalseOmission))
    (acceptSweep, rejectSweep)
