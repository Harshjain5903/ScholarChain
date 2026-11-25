package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class GoldSetEvaluationSpec extends AnyFlatSpec with Matchers {

  private val Seed = 11L
  private val Tol  = 1e-9

  private def label(isMatch: Boolean): Option[HumanLabel] =
    Some(if isMatch then HumanLabel.Match else HumanLabel.NonMatch)

  /**
   * Synthetic labeled gold set of `n` rows: rule-path rows plus weighted-score rows whose
   * (simulated human) label is MATCH iff score >= 0.6. Splits come from the sampler's pre-label
   * assignment.
   */
  private def synthetic(n: Int = 170): Vector[GoldRow] =
    val raw = (0 until n).toVector.map { i =>
      val id = f"gp-$i%04d"
      if i < 10 then (id, 1.0, MatchOutcome.Accepted, "exact_id", "same_full_name", label(true))
      else if i < 20 then
        (id, 0.0, MatchOutcome.Rejected, "dblp_person_key", "dblp_suffix_differs", label(false))
      else if i < 70 then
        (id, 0.2, MatchOutcome.Rejected, "name_gate", "incompatible_given_name", label(false))
      else
        val score = ((i - 70) % 100) / 100.0
        val hn   = if score >= 0.4 && score < 0.6 then "same_full_name" else "compatible_given_name"
        val base = MentionResolver.mentionPolicy
        val dec  = base.classify(score)
        (id, score, dec, base.matchPath(score), hn, label(score >= 0.6))
    }
    val withStrata = raw.map { (id, score, dec, path, hn, lab) =>
      (id, score, dec, path, hn, lab, s"$path|${GoldSetSampler.scoreBand(score)}|$hn")
    }
    val splits = GoldSetSampler.assignSplits(withStrata.map(r => r._1 -> r._7), Seed)
    withStrata.map { (id, score, dec, path, hn, lab, stratum) =>
      GoldRow(
        id,
        splits(id),
        stratum,
        hn,
        score,
        dec,
        path,
        "er-mention-v1-uncalibrated",
        Seed,
        lab
      )
    }

  private def toCsv(rows: Vector[GoldRow]): String =
    val header = GoldSetEvaluation.RequiredColumns :+ "notes"
    GoldSetIO.writeCsv(
      header,
      rows.map { r =>
        Vector(
          r.pairId,
          r.split,
          r.stratum,
          r.hardNegativeStratum,
          r.matcherScore.toString,
          r.matcherDecision.toString,
          r.matcherPath,
          r.policyVersion,
          r.seed.toString,
          r.label match
            case Some(HumanLabel.Match)    => "MATCH"
            case Some(HumanLabel.NonMatch) => "NON_MATCH"
            case Some(HumanLabel.Unsure)   => "UNSURE"
            case None                      => ""
          ,
          ""
        )
      }
    )

  private def writeGold(rows: Vector[GoldRow]): (Path, Path) =
    val tmp  = Files.createTempDirectory("gold-eval-spec")
    val gold = tmp.resolve("gold.csv")
    Files.writeString(gold, toCsv(rows))
    (gold, tmp.resolve("eval"))

  "GoldMetrics" should "match a hand-checked confusion matrix" in {
    // TP=8 FP=2 TN=5 FN=3 unresolved MATCH=1 unresolved NON_MATCH=1 (n=20, gold MATCH=12)
    val pairs =
      Vector.fill(8)(MatchOutcome.Accepted -> true) ++ Vector.fill(2)(
        MatchOutcome.Accepted -> false
      ) ++
        Vector.fill(5)(MatchOutcome.Rejected -> false) ++ Vector.fill(3)(
          MatchOutcome.Rejected -> true
        ) ++
        Vector(MatchOutcome.Unresolved -> true, MatchOutcome.Conflict -> false)
    val c = GoldSetCalibration.confusion(pairs)
    c shouldBe GoldConfusion(8, 2, 5, 3, 1, 1)
    val m = GoldMetrics.from(c)
    m.precision.get shouldBe 0.8 +- Tol       // 8 / 10
    m.recall.get shouldBe (8.0 / 12.0) +- Tol // 8 / (8 + 3 + 1)
    m.f1.get shouldBe (16.0 / 22.0) +- Tol    // 2PR/(P+R) = 2*8/(2*8+2+4)
    m.coverage.get shouldBe 0.9 +- Tol        // 18 / 20
    m.unresolvedRate.get shouldBe 0.1 +- Tol  // 2 / 20
  }

  it should "report undefined ratios as None rather than zero" in {
    val m = GoldMetrics.from(GoldSetCalibration.confusion(Vector(MatchOutcome.Rejected -> false)))
    m.precision shouldBe None
    m.recall shouldBe None
    m.f1 shouldBe None
    m.coverage shouldBe Some(1.0)
  }

  "decide" should "keep rule paths fixed and apply the one-sided suffix downgrade" in {
    val rows = synthetic()
    val rule = rows.find(_.matcherPath == "name_gate").get
    GoldSetCalibration.decide(rule, Thresholds(0.1, 0.05)) shouldBe
      (MatchOutcome.Rejected -> "name_gate")
    val scored = rows.find(r => r.isScored && r.matcherScore == 0.8).get
    GoldSetCalibration.decide(scored, Thresholds(0.75, 0.3))._1 shouldBe MatchOutcome.Accepted
    GoldSetCalibration
      .decide(scored.copy(hardNegativeStratum = "dblp_suffix_one_sided"), Thresholds(0.75, 0.3))
      ._1 shouldBe
      MatchOutcome.Unresolved
    GoldSetCalibration.decide(scored, Thresholds(0.9, 0.8))._1 shouldBe MatchOutcome.Rejected
    GoldSetCalibration.decide(scored, Thresholds(0.9, 0.5)) shouldBe
      (MatchOutcome.Unresolved -> "unresolved_band")
  }

  "calibrate" should "find a separating threshold on calibration rows" in {
    val r = GoldSetCalibration.calibrate(synthetic())
    r.source shouldBe "grid_search"
    r.feasible shouldBe true
    r.thresholds.accept should (be > 0.55 and be <= 0.6 + Tol)
    r.calibrationMetrics.precision.get shouldBe 1.0 +- Tol
  }

  it should "not change when held-out labels are perturbed (perturbation test)" in {
    val rows = synthetic()
    val base = GoldSetCalibration.calibrate(rows)
    val opts =
      Vector(Some(HumanLabel.Match), Some(HumanLabel.NonMatch), Some(HumanLabel.Unsure), None)
    (1 to 40).foreach { s =>
      val rnd = scala.util.Random(s)
      val perturbed = rows.map { r =>
        if r.split == GoldSetSampler.Holdout then r.copy(label = opts(rnd.nextInt(opts.size)))
        else r
      }
      val again = GoldSetCalibration.calibrate(perturbed)
      again shouldBe base
      GoldSetCalibration.sensitivity(perturbed, again.thresholds) shouldBe
        GoldSetCalibration.sensitivity(rows, base.thresholds)
    }
    // Non-vacuity: perturbing CALIBRATION labels does move the thresholds.
    val flipped = rows.map { r =>
      if r.split == GoldSetSampler.Calibration && r.isScored && r.matcherScore >= 0.6 && r.matcherScore < 0.8
      then r.copy(label = Some(HumanLabel.NonMatch))
      else r
    }
    GoldSetCalibration.calibrate(flipped).thresholds should not be base.thresholds
  }

  "evaluate" should "write evaluation.json and evaluation.md for a complete gold set" in {
    val rows        = synthetic()
    val (gold, out) = writeGold(rows)
    val res         = GoldSetEvaluation.evaluate(EvaluationConfig(gold, out))
    res.isRight shouldBe true
    val o = res.toOption.get
    Files.exists(out.resolve("evaluation.json")) shouldBe true
    Files.exists(out.resolve("evaluation.md")) shouldBe true
    o.json("status").str shouldBe "FINAL"
    o.json("gold_file")("sha256").str shouldBe GoldSetIO.sha256File(gold)
    o.json("policy_version").str shouldBe "er-mention-v1-uncalibrated"
    o.json("label_counts")("calibration")("labeled").num.toInt shouldBe 102
    o.json("label_counts")("holdout")("labeled").num.toInt shouldBe 68
    val byPath = o.json("holdout")("tuned")("by_path").obj.keySet
    byPath should contain allOf ("exact_id", "dblp_person_key", "name_gate")
    o.json("threshold_sensitivity")("accept_sweep").arr should not be empty
    o.markdown should include("Threshold sensitivity")
    o.markdown should include(GoldSetIO.sha256File(gold))
    o.markdown should not include "PRELIMINARY"
  }

  it should "freeze the same thresholds whatever the held-out labels say" in {
    val rows     = synthetic()
    val (g1, o1) = writeGold(rows)
    val (g2, o2) = writeGold(rows.map { r =>
      if r.split == GoldSetSampler.Holdout then r.copy(label = label(!r.isMatch)) else r
    })
    val a = GoldSetEvaluation.evaluate(EvaluationConfig(g1, o1)).toOption.get
    val b = GoldSetEvaluation.evaluate(EvaluationConfig(g2, o2)).toOption.get
    a.json("calibration")("frozen_thresholds") shouldBe b.json("calibration")("frozen_thresholds")
    a.json("threshold_sensitivity") shouldBe b.json("threshold_sensitivity")
    a.json("holdout") should not be b.json("holdout")
  }

  it should "refuse fewer than 170 labeled rows unless --preliminary" in {
    val rows        = synthetic()
    val oneMissing  = rows.updated(5, rows(5).copy(label = None))
    val (gold, out) = writeGold(oneMissing)
    val refused     = GoldSetEvaluation.evaluate(EvaluationConfig(gold, out))
    refused.isLeft shouldBe true
    refused.left.toOption.get.mkString should include("only 169 labeled rows")
    Files.exists(out.resolve("evaluation.json")) shouldBe false

    val prelim = GoldSetEvaluation.evaluate(EvaluationConfig(gold, out, preliminary = true))
    prelim.isRight shouldBe true
    val o = prelim.toOption.get
    o.json("status").str shouldBe "PRELIMINARY"
    o.json("preliminary").bool shouldBe true
    o.markdown.linesIterator.filter(_.startsWith("#")).foreach(_ should include("PRELIMINARY"))
    Files.readString(out.resolve("evaluation.json")) should include("PRELIMINARY")
  }

  it should "refuse a small gold set and a split other than 102/68" in {
    val small    = synthetic(60)
    val (g1, o1) = writeGold(small)
    GoldSetEvaluation.evaluate(EvaluationConfig(g1, o1)).left.toOption.get.mkString should
      include("at least 170 required")
    val big      = synthetic(175)
    val (g2, o2) = writeGold(big)
    GoldSetEvaluation.evaluate(EvaluationConfig(g2, o2)).left.toOption.get.mkString should
      include("labeled split is 105/70")
  }

  it should "refuse a split without positives or hard negatives" in {
    val rows = synthetic().map { r =>
      if r.split == GoldSetSampler.Holdout && r.isMatch then r.copy(label = Some(HumanLabel.Unsure))
      else r
    }
    val (gold, out) = writeGold(rows)
    GoldSetEvaluation.evaluate(EvaluationConfig(gold, out)).left.toOption.get.mkString should
      include("holdout split has no MATCH")
  }

  it should "always refuse a split edited after sampling, even with --preliminary" in {
    val rows        = synthetic()
    val i           = rows.indexWhere(_.split == GoldSetSampler.Holdout)
    val bad         = rows.updated(i, rows(i).copy(split = GoldSetSampler.Calibration))
    val (gold, out) = writeGold(bad)
    val res         = GoldSetEvaluation.evaluate(EvaluationConfig(gold, out, preliminary = true))
    res.left.toOption.get.mkString should include("split column differs")
  }

  it should "reject invalid labels and output inside modules" in {
    val (gold, out) = writeGold(synthetic())
    val text        = Files.readString(gold).replaceFirst(",MATCH,", ",YES,")
    Files.writeString(gold, text)
    GoldSetEvaluation.evaluate(EvaluationConfig(gold, out)).left.toOption.get.mkString should
      include("invalid human_label")
    GoldSetEvaluation
      .parseArgs(Vector("--gold", "g.csv", "--output", "modules/er/eval"))
      .isLeft shouldBe
      true
    val ok =
      GoldSetEvaluation.parseArgs(Vector("--preliminary", "--gold", "g.csv", "--output", "/tmp/e"))
    ok.map(_.preliminary) shouldBe Right(true)
  }
}
