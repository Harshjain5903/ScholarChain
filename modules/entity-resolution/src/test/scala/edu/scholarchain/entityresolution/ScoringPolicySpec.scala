package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScoringPolicySpec extends AnyFlatSpec with Matchers {

  val policy: ScoringPolicy = ScoringPolicy.default

  "ScoringPolicy" should "compute weighted score correctly" in {
    val features = Map(
      "title_jaccard"     -> 1.0,
      "author_similarity" -> 1.0,
      "year_distance"     -> 1.0,
      "venue_match"       -> 1.0,
      "coauthor_overlap"  -> 1.0,
      "orcid_match"       -> 1.0,
      "affiliation_match" -> 1.0
    )
    policy.computeScore(features) shouldBe 1.0 +- 0.001
  }

  it should "return 0.0 for all-zero features" in {
    val features = Map(
      "title_jaccard"     -> 0.0,
      "author_similarity" -> 0.0,
      "year_distance"     -> 0.0,
      "venue_match"       -> 0.0,
      "coauthor_overlap"  -> 0.0,
      "orcid_match"       -> 0.0,
      "affiliation_match" -> 0.0
    )
    policy.computeScore(features) shouldBe 0.0 +- 0.001
  }

  it should "handle missing features as 0.0" in {
    val features = Map("title_jaccard" -> 1.0)
    val score    = policy.computeScore(features)
    score should be > 0.0
    score should be < 1.0
  }

  it should "ignore unknown features" in {
    val features = Map(
      "title_jaccard" -> 1.0,
      "unknown_feat"  -> 1.0
    )
    // unknown_feat has weight 0 so it doesn't contribute
    val score = policy.computeScore(features)
    score shouldBe policy.computeScore(Map("title_jaccard" -> 1.0)) +- 0.001
  }

  "classify" should "accept above threshold" in {
    policy.classify(0.95) shouldBe MatchOutcome.Accepted
    policy.classify(0.93) shouldBe MatchOutcome.Accepted
  }

  it should "reject below threshold" in {
    policy.classify(0.35) shouldBe MatchOutcome.Rejected
    policy.classify(0.1) shouldBe MatchOutcome.Rejected
  }

  it should "return Unresolved in the middle" in {
    policy.classify(0.5) shouldBe MatchOutcome.Unresolved
    policy.classify(0.7) shouldBe MatchOutcome.Unresolved
    policy.classify(0.92) shouldBe MatchOutcome.Unresolved
  }

  "matchPath" should "label correctly" in {
    policy.matchPath(0.95) shouldBe "deterministic_bibliographic"
    policy.matchPath(0.93) shouldBe "deterministic_bibliographic"
    policy.matchPath(0.90) shouldBe "high_probabilistic"
    policy.matchPath(0.86) shouldBe "high_probabilistic"
    policy.matchPath(0.85) shouldBe "unresolved_band"
    policy.matchPath(0.75) shouldBe "unresolved_band"
    policy.matchPath(0.5) shouldBe "low_probabilistic"
    policy.matchPath(0.35) shouldBe "rejected"
    policy.matchPath(0.1) shouldBe "rejected"
  }

  "policy version" should "be explicit" in {
    policy.policyVersion shouldBe "er-v1"
  }

  "threshold validation" should "reject invalid thresholds" in {
    an[IllegalArgumentException] should be thrownBy {
      ScoringPolicy(
        policyVersion = "test",
        weights = Map.empty,
        acceptThreshold = 0.3,
        rejectThreshold = 0.5,
        unresolvedBandMin = 0.4,
        unresolvedBandMax = 0.6
      )
    }
  }

  "custom weights" should "produce different scores" in {
    val customPolicy = ScoringPolicy(
      policyVersion = "er-v2",
      weights = Map("title_jaccard" -> 1.0),
      acceptThreshold = 0.93,
      rejectThreshold = 0.35,
      unresolvedBandMin = 0.65,
      unresolvedBandMax = 0.93
    )
    val features = Map("title_jaccard" -> 0.8, "author_similarity" -> 1.0)
    customPolicy.computeScore(features) shouldBe 0.8 +- 0.001
  }
}
