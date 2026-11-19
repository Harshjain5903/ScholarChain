package edu.scholarchain.entityresolution

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FeatureFunctionsSpec extends AnyFlatSpec with Matchers {

  val tolerance = 0.001

  "titleJaccard" should "return 1.0 for identical titles" in {
    FeatureFunctions.titleJaccard(
      "Deep Learning for NLP",
      "Deep Learning for NLP"
    ) shouldBe 1.0 +- tolerance
  }

  it should "return 0.0 for completely different titles" in {
    FeatureFunctions.titleJaccard(
      "Quantum Computing Basics",
      "Marine Biology Survey"
    ) shouldBe 0.0 +- tolerance
  }

  it should "return a partial score for overlapping titles" in {
    val score = FeatureFunctions.titleJaccard(
      "Deep Learning for Natural Language Processing",
      "Deep Learning Methods for Text Processing"
    )
    score should be > 0.0
    score should be < 1.0
  }

  it should "handle case insensitivity" in {
    FeatureFunctions.titleJaccard(
      "DEEP LEARNING",
      "deep learning"
    ) shouldBe 1.0 +- tolerance
  }

  "nameSimilarity" should "return 1.0 for identical names" in {
    FeatureFunctions.nameSimilarity("John Doe", "John Doe") shouldBe 1.0 +- tolerance
  }

  it should "handle name reordering" in {
    FeatureFunctions.nameSimilarity("John Doe", "Doe John") shouldBe 1.0 +- tolerance
  }

  it should "return 0.0 for completely different names" in {
    FeatureFunctions.nameSimilarity("Alice Smith", "Bob Jones") shouldBe 0.0 +- tolerance
  }

  "initialsMatch" should "return 1.0 when initials match" in {
    FeatureFunctions.initialsMatch("John Doe", "James Davidson") shouldBe 1.0 // both "jd"
    FeatureFunctions.initialsMatch("John Doe", "John Doe") shouldBe 1.0
  }

  it should "return 0.0 for different initials" in {
    FeatureFunctions.initialsMatch("Alice Smith", "Bob Jones") shouldBe 0.0
  }

  "authorOrderSimilarity" should "return 1.0 for identical order" in {
    FeatureFunctions.authorOrderSimilarity(
      Vector("Alice", "Bob", "Charlie"),
      Vector("Alice", "Bob", "Charlie")
    ) shouldBe 1.0 +- tolerance
  }

  it should "return less than 1.0 for reordered authors" in {
    FeatureFunctions.authorOrderSimilarity(
      Vector("Alice", "Bob", "Charlie"),
      Vector("Charlie", "Bob", "Alice")
    ) should be < 1.0
  }

  it should "return 0.0 for empty lists" in {
    FeatureFunctions.authorOrderSimilarity(Vector.empty, Vector("Alice")) shouldBe 0.0
  }

  "bestMatchNameSimilarity" should "return 1.0 for identical author lists" in {
    FeatureFunctions.bestMatchNameSimilarity(
      Vector("John Doe", "Jane Smith"),
      Vector("John Doe", "Jane Smith")
    ) shouldBe 1.0 +- tolerance
  }

  it should "return 0.0 for empty lists" in {
    FeatureFunctions.bestMatchNameSimilarity(Vector.empty, Vector("Alice")) shouldBe 0.0
    FeatureFunctions.bestMatchNameSimilarity(Vector.empty, Vector.empty) shouldBe 0.0
  }

  it should "return partial score for partially matching authors" in {
    val score = FeatureFunctions.bestMatchNameSimilarity(
      Vector("John Doe", "Alice Wonder"),
      Vector("John Doe", "Bob Builder")
    )
    score should be > 0.0
    score should be < 1.0
  }

  it should "differ from coauthorOverlap for similar but non-identical names" in {
    val authors1 = Vector("J. Doe", "Jane Smith")
    val authors2 = Vector("John Doe", "J. Smith")
    val bestMatch = FeatureFunctions.bestMatchNameSimilarity(authors1, authors2)
    val jaccard   = FeatureFunctions.coauthorOverlap(authors1, authors2)
    // bestMatchNameSimilarity uses fuzzy name matching, coauthorOverlap uses exact set Jaccard
    // These should produce different values for similar-but-not-identical names
    bestMatch should not be jaccard +- 0.001
  }

  "coauthorOverlap" should "return 1.0 for identical sets" in {
    FeatureFunctions.coauthorOverlap(
      Vector("Alice", "Bob"),
      Vector("Alice", "Bob")
    ) shouldBe 1.0 +- tolerance
  }

  it should "return partial overlap" in {
    val score = FeatureFunctions.coauthorOverlap(
      Vector("Alice", "Bob", "Charlie"),
      Vector("Alice", "Bob", "David")
    )
    score shouldBe (2.0 / 4.0) +- tolerance
  }

  "affiliationMatch" should "return 0.0 when either is missing" in {
    FeatureFunctions.affiliationMatch(None, Some("MIT")) shouldBe 0.0
  }

  it should "compute similarity when both present" in {
    FeatureFunctions.affiliationMatch(
      Some("MIT Computer Science"),
      Some("MIT Computer Science Department")
    ) should be > 0.5
  }

  "orcidMatch" should "return 1.0 for matching ORCIDs" in {
    FeatureFunctions.orcidMatch(
      Some("0000-0002-1825-0097"),
      Some("0000-0002-1825-0097")
    ) shouldBe 1.0
  }

  it should "return 0.0 for different ORCIDs" in {
    FeatureFunctions.orcidMatch(
      Some("0000-0002-1825-0097"),
      Some("0000-0001-5109-3510")
    ) shouldBe 0.0
  }

  it should "return 0.0 when either is missing" in {
    FeatureFunctions.orcidMatch(None, Some("0000-0002-1825-0097")) shouldBe 0.0
  }

  "yearDistance" should "return 1.0 for same year" in {
    FeatureFunctions.yearDistance(Some(2020), Some(2020)) shouldBe 1.0 +- tolerance
  }

  it should "return 0.0 for 5+ year gap" in {
    FeatureFunctions.yearDistance(Some(2020), Some(2025)) shouldBe 0.0 +- tolerance
    FeatureFunctions.yearDistance(Some(2020), Some(2026)) shouldBe 0.0 +- tolerance
  }

  it should "decay linearly" in {
    FeatureFunctions.yearDistance(Some(2020), Some(2022)) shouldBe 0.6 +- tolerance
  }

  it should "return 0.5 for missing data" in {
    FeatureFunctions.yearDistance(None, Some(2020)) shouldBe 0.5 +- tolerance
  }

  "venueMatch" should "return similarity for similar venues" in {
    FeatureFunctions.venueMatch(
      Some("NeurIPS 2023"),
      Some("NeurIPS 2023")
    ) shouldBe 1.0 +- tolerance
  }

  it should "return 0.0 when either is missing" in {
    FeatureFunctions.venueMatch(None, Some("ICML")) shouldBe 0.0
  }

  "sourceReliabilityWeight" should "return expected weights" in {
    FeatureFunctions.sourceReliabilityWeight("ORCID") shouldBe 1.0
    FeatureFunctions.sourceReliabilityWeight("CROSSREF") shouldBe 0.95
    FeatureFunctions.sourceReliabilityWeight("unknown") shouldBe 0.5
  }

  "externalIdMatch" should "return 1.0 for matching IDs" in {
    FeatureFunctions.externalIdMatch(Some("abc123"), Some("abc123")) shouldBe 1.0
  }

  it should "return 0.0 for missing IDs" in {
    FeatureFunctions.externalIdMatch(None, Some("abc123")) shouldBe 0.0
  }
}
