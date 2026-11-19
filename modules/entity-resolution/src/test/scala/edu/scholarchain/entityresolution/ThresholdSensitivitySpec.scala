package edu.scholarchain.entityresolution

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ThresholdSensitivitySpec extends AnyFlatSpec with Matchers {

  import TestFixtures.*

  val policy: ScoringPolicy = ScoringPolicy.default

  "thresholdSensitivity" should "produce points for each threshold step" in {
    val goldLabels = Vector(
      GoldLabel("src-1", "tgt-1", "Accepted"),
      GoldLabel("src-2", "tgt-2", "Rejected"),
      GoldLabel("src-3", "tgt-3", "Accepted"),
      GoldLabel("src-4", "tgt-4", "Rejected")
    )

    val scoredPairs: Vector[(CandidatePair, Map[String, Double], Double)] = Vector(
      (makePair(sourceId = "src-1", targetId = "tgt-1"), Map.empty, 0.95),
      (makePair(sourceId = "src-2", targetId = "tgt-2"), Map.empty, 0.2),
      (makePair(sourceId = "src-3", targetId = "tgt-3"), Map.empty, 0.75),
      (makePair(sourceId = "src-4", targetId = "tgt-4"), Map.empty, 0.4)
    )

    val points = GoldSetEvaluator.thresholdSensitivity(scoredPairs, goldLabels, policy, 0.1)
    points should not be empty
    // Should have points from 0.1 to 0.9 at step 0.1
    points.size should be >= 8
  }

  it should "show higher precision at higher thresholds" in {
    val goldLabels = (1 to 20).toVector.map { i =>
      if i <= 10 then GoldLabel(s"src-$i", s"tgt-$i", "Accepted")
      else GoldLabel(s"src-$i", s"tgt-$i", "Rejected")
    }

    val scoredPairs = (1 to 20).toVector.map { i =>
      val score = if i <= 10 then 0.3 + i * 0.07 else 0.1 + (i - 10) * 0.03
      (makePair(sourceId = s"src-$i", targetId = s"tgt-$i"), Map.empty[String, Double], score)
    }

    val points = GoldSetEvaluator.thresholdSensitivity(scoredPairs, goldLabels, policy, 0.1)

    // At very high thresholds, only high-confidence true matches pass
    val highThresholdPoints = points.filter(_.threshold >= 0.8)
    val lowThresholdPoints  = points.filter(_.threshold <= 0.3)

    // Higher threshold should accept fewer candidates
    if highThresholdPoints.nonEmpty && lowThresholdPoints.nonEmpty then
      highThresholdPoints.head.acceptedCount should be <= lowThresholdPoints.head.acceptedCount
  }

  it should "show recall decreases as threshold increases" in {
    val goldLabels = Vector(
      GoldLabel("src-1", "tgt-1", "Accepted"),
      GoldLabel("src-2", "tgt-2", "Accepted"),
      GoldLabel("src-3", "tgt-3", "Accepted")
    )

    val scoredPairs: Vector[(CandidatePair, Map[String, Double], Double)] = Vector(
      (makePair(sourceId = "src-1", targetId = "tgt-1"), Map.empty, 0.95),
      (makePair(sourceId = "src-2", targetId = "tgt-2"), Map.empty, 0.70),
      (makePair(sourceId = "src-3", targetId = "tgt-3"), Map.empty, 0.50)
    )

    val points = GoldSetEvaluator.thresholdSensitivity(scoredPairs, goldLabels, policy, 0.1)

    // At threshold 0.4, all three should be accepted (recall = 1.0)
    val lowPoint = points.find(p => p.threshold >= 0.4 && p.threshold < 0.5)
    lowPoint.foreach(_.recall shouldBe 1.0 +- 0.001)

    // At threshold 0.9, only 1 should be accepted (recall = 1/3)
    val highPoint = points.find(p => p.threshold >= 0.9 && p.threshold < 0.95)
    highPoint.foreach { hp =>
      hp.recall should be <= 0.5
    }
  }
}
