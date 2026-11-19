package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GoldSetEvaluatorSpec extends AnyFlatSpec with Matchers {

  val tolerance = 0.001

  def makeDecision(
      src: String,
      tgt: String,
      outcome: MatchOutcome,
      confidence: Double,
      path: String = "deterministic_bibliographic"
  ): MatchDecision =
    MatchDecision(
      sourceId = src,
      targetId = tgt,
      features = Map("title_jaccard" -> confidence),
      decision = outcome,
      confidence = confidence,
      reasonCodes = Vector(s"path:$path"),
      policyVersion = "er-v1",
      runId = "test-run"
    )

  val goldLabels: Vector[GoldLabel] = Vector(
    GoldLabel("a", "b", "Accepted"),
    GoldLabel("c", "d", "Accepted"),
    GoldLabel("e", "f", "Rejected"),
    GoldLabel("g", "h", "Rejected"),
    GoldLabel("i", "j", "Accepted")
  )

  "GoldSetEvaluator.evaluate" should "compute correct metrics for perfect decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.95),
      makeDecision("c", "d", MatchOutcome.Accepted, 0.95),
      makeDecision("e", "f", MatchOutcome.Rejected, 0.1),
      makeDecision("g", "h", MatchOutcome.Rejected, 0.1),
      makeDecision("i", "j", MatchOutcome.Accepted, 0.95)
    )
    val metrics = GoldSetEvaluator.evaluate(decisions, goldLabels)
    metrics.precision shouldBe 1.0 +- tolerance
    metrics.recall shouldBe 1.0 +- tolerance
    metrics.f1 shouldBe 1.0 +- tolerance
    metrics.coverage shouldBe 1.0 +- tolerance
    metrics.unresolvedRate shouldBe 0.0 +- tolerance
    metrics.confusion.truePositive shouldBe 3
    metrics.confusion.trueNegative shouldBe 2
    metrics.confusion.falsePositive shouldBe 0
    metrics.confusion.falseNegative shouldBe 0
  }

  it should "handle false positives and negatives" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.95), // TP
      makeDecision("c", "d", MatchOutcome.Rejected, 0.1),  // FN
      makeDecision("e", "f", MatchOutcome.Accepted, 0.95), // FP
      makeDecision("g", "h", MatchOutcome.Rejected, 0.1),  // TN
      makeDecision("i", "j", MatchOutcome.Accepted, 0.95)  // TP
    )
    val metrics = GoldSetEvaluator.evaluate(decisions, goldLabels)
    metrics.precision shouldBe (2.0 / 3.0) +- tolerance
    metrics.recall shouldBe (2.0 / 3.0) +- tolerance
    metrics.confusion.truePositive shouldBe 2
    metrics.confusion.falsePositive shouldBe 1
    metrics.confusion.falseNegative shouldBe 1
    metrics.confusion.trueNegative shouldBe 1
  }

  it should "account for unresolved decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.95),
      makeDecision("c", "d", MatchOutcome.Unresolved, 0.7),
      makeDecision("e", "f", MatchOutcome.Rejected, 0.1),
      makeDecision("g", "h", MatchOutcome.Rejected, 0.1),
      makeDecision("i", "j", MatchOutcome.Unresolved, 0.6)
    )
    val metrics = GoldSetEvaluator.evaluate(decisions, goldLabels)
    metrics.unresolvedRate shouldBe (2.0 / 5.0) +- tolerance
    metrics.coverage shouldBe (3.0 / 5.0) +- tolerance
  }

  "loadGoldSet" should "parse JSON gold set" in {
    val json = """{"pairs": [
      {"sourceId": "a", "targetId": "b", "expectedOutcome": "Accepted"},
      {"sourceId": "c", "targetId": "d", "expectedOutcome": "Rejected"}
    ]}"""
    val gs = GoldSetEvaluator.loadGoldSet(json)
    gs.pairs should have size 2
    gs.pairs(0).expectedOutcome shouldBe "Accepted"
  }

  "evaluateByPath" should "group metrics by match path" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.95, "exact_id"),
      makeDecision("c", "d", MatchOutcome.Accepted, 0.95, "deterministic_bibliographic"),
      makeDecision("e", "f", MatchOutcome.Rejected, 0.1, "rejected"),
      makeDecision("g", "h", MatchOutcome.Rejected, 0.1, "rejected"),
      makeDecision("i", "j", MatchOutcome.Accepted, 0.95, "exact_id")
    )
    val byPath = GoldSetEvaluator.evaluateByPath(decisions, goldLabels)
    byPath should not be empty
    byPath.map(_.path) should contain("exact_id")
  }
}
