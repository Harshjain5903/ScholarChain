package edu.scholarchain.core

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for pair canonicalization in collaboration types. */
class PairOrderingSpec extends AnyFlatSpec with Matchers {
  import TestFixtures.sampleProvenance

  "CoauthorPairCandidate" should "swap reversed IDs to canonical order" in {
    val pair = CoauthorPairCandidate("B", "A", "w1", 2023, sampleProvenance)
    pair.mentionA shouldBe "A"
    pair.mentionB shouldBe "B"
  }

  it should "keep already-ordered IDs unchanged" in {
    val pair = CoauthorPairCandidate("A", "B", "w1", 2023, sampleProvenance)
    pair.mentionA shouldBe "A"
    pair.mentionB shouldBe "B"
  }

  it should "produce equal instances regardless of input order" in {
    val pair1 = CoauthorPairCandidate("B", "A", "w1", 2023, sampleProvenance)
    val pair2 = CoauthorPairCandidate("A", "B", "w1", 2023, sampleProvenance)
    pair1 shouldBe pair2
  }

  it should "reject equal IDs (self-pairs)" in {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorPairCandidate("X", "X", "w1", 2023, sampleProvenance)
    }
  }

  it should "reject copy() that swaps IDs to violate ordering" in {
    val pair = CoauthorPairCandidate("A", "B", "w1", 2023, sampleProvenance)
    an[IllegalArgumentException] should be thrownBy {
      pair.copy(mentionA = "Z", mentionB = "A")
    }
  }

  it should "reject copy() that sets equal IDs" in {
    val pair = CoauthorPairCandidate("A", "B", "w1", 2023, sampleProvenance)
    an[IllegalArgumentException] should be thrownBy {
      pair.copy(mentionA = "X", mentionB = "X")
    }
  }

  "CoauthorEdge" should "swap reversed researcher IDs to canonical order" in {
    val edge = CoauthorEdge(
      "researcher:Z",
      "researcher:A",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    edge.researcherA shouldBe "researcher:A"
    edge.researcherB shouldBe "researcher:Z"
  }

  it should "keep already-ordered researcher IDs unchanged" in {
    val edge = CoauthorEdge(
      "researcher:A",
      "researcher:Z",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    edge.researcherA shouldBe "researcher:A"
    edge.researcherB shouldBe "researcher:Z"
  }

  it should "produce equal instances regardless of input order" in {
    val edge1 = CoauthorEdge(
      "researcher:Z",
      "researcher:A",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    val edge2 = CoauthorEdge(
      "researcher:A",
      "researcher:Z",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    edge1 shouldBe edge2
  }

  it should "reject copy() that swaps researcher IDs to violate ordering" in {
    val edge = CoauthorEdge(
      "researcher:A",
      "researcher:Z",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    an[IllegalArgumentException] should be thrownBy {
      edge.copy(researcherA = "researcher:Z", researcherB = "researcher:A")
    }
  }

  it should "reject copy() that sets equal researcher IDs" in {
    val edge = CoauthorEdge(
      "researcher:A",
      "researcher:Z",
      3,
      2020,
      2023,
      Vector("w1"),
      sampleProvenance
    )
    an[IllegalArgumentException] should be thrownBy {
      edge.copy(researcherA = "researcher:A", researcherB = "researcher:A")
    }
  }
}
