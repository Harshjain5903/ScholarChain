package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

class ReviewQueueGeneratorSpec extends AnyFlatSpec with Matchers {

  def makeDecision(
    src: String,
    tgt: String,
    outcome: MatchOutcome,
    confidence: Double
  ): MatchDecision =
    MatchDecision(
      sourceId = src,
      targetId = tgt,
      features = Map("title_jaccard" -> confidence),
      decision = outcome,
      confidence = confidence,
      reasonCodes = Vector("test"),
      policyVersion = "er-v1",
      runId = "test-run"
    )

  "ReviewQueueGenerator" should "include unresolved decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Unresolved, 0.7),
      makeDecision("c", "d", MatchOutcome.Accepted, 0.95)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    queue.items should have size 1
    queue.items.head.sourceId shouldBe "a"
    queue.items.head.suggestedLabel shouldBe "needs_review"
  }

  it should "include conflict decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Conflict, 0.0)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    queue.items should have size 1
    queue.items.head.suggestedLabel shouldBe "conflict_review"
  }

  it should "include low-confidence accepted decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.5)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    queue.items should have size 1
    queue.items.head.suggestedLabel shouldBe "verify_accept"
  }

  it should "exclude high-confidence accepted decisions" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Accepted, 0.95)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    queue.items shouldBe empty
  }

  it should "respect maxItems" in {
    val decisions = (1 to 100).toVector.map { i =>
      makeDecision(s"src-$i", s"tgt-$i", MatchOutcome.Unresolved, 0.7)
    }
    val queue = ReviewQueueGenerator.generate(decisions, maxItems = 10)
    queue.items should have size 10
  }

  it should "serialize to JSON" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Unresolved, 0.7)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    val json  = ReviewQueueGenerator.toJson(queue)
    json should include("needs_review")
    // Verify it round-trips
    val parsed = read[ReviewQueue](json)
    parsed.items should have size 1
  }

  it should "include policy version in review items" in {
    val decisions = Vector(
      makeDecision("a", "b", MatchOutcome.Unresolved, 0.7)
    )
    val queue = ReviewQueueGenerator.generate(decisions)
    queue.items.head.policyVersion shouldBe "er-v1"
  }
}
