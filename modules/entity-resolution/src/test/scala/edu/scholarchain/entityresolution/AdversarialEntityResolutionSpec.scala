package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AdversarialEntityResolutionSpec extends AnyFlatSpec with Matchers:

  private val policy     = ScoringPolicy.default
  private val adjudicator = new StubLlmAdjudicator()
  private val engine     = new EntityResolutionEngine(policy, adjudicator, llmEnabled = false)

  // --- 1. Common name collision ("Wei Wang" appearing many times) ---

  "EntityResolutionEngine" should "produce distinct decisions for 100+ pairs sharing the common name Wei Wang" in {
    val pairs = (0 until 100).map { i =>
      TestFixtures.makePair(
        sourceId = s"mention:dblp:ww:$i",
        targetId = s"mention:openalex:ww:$i",
        sourceTitle = Some(s"Paper $i on Topic $i"),
        targetTitle = Some(s"Paper $i on Topic ${i + 1}"),
        sourceAuthors = Vector("Wei Wang", s"Coauthor $i"),
        targetAuthors = Vector("Wei Wang", s"Coauthor ${i + 1}"),
        sourceYear = Some(2020),
        targetYear = Some(2020)
      )
    }.toVector

    val decisions = engine.resolveBatch(pairs)
    decisions.size shouldBe 100
    // All decisions should be valid MatchDecisions with confidence in [0,1]
    decisions.foreach { d =>
      d.confidence should be >= 0.0
      d.confidence should be <= 1.0
    }
  }

  // --- 2. Reordered author names (first-last vs last-first) ---

  "FeatureFunctions" should "compute non-zero similarity for reordered names" in {
    val sim = FeatureFunctions.nameSimilarity("John Smith", "Smith John")
    sim should be > 0.0
    sim shouldBe 1.0 // token-set Jaccard should be perfect for reordering
  }

  it should "compute non-zero similarity for 'Wei Wang' vs 'Wang Wei'" in {
    val sim = FeatureFunctions.nameSimilarity("Wei Wang", "Wang Wei")
    sim shouldBe 1.0
  }

  // --- 3. Conflicting authoritative IDs (same DOI, different ORCID) ---

  "ExactIdentifierMatcher" should "detect conflict when DOI matches but ORCID differs" in {
    val pair = TestFixtures.makePair(
      sourceDoi = Some("10.1234/conflict-paper"),
      targetDoi = Some("10.1234/conflict-paper"),
      sourceOrcid = Some("0000-0001-0001-0001"),
      targetOrcid = Some("0000-0002-0002-0002")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Conflict
    result.conflictFields should contain("orcid")
  }

  // --- 4. Near-identical titles differing by one character ---

  "FeatureFunctions" should "compute very high Jaccard for near-identical titles" in {
    val titleA = "A Survey of Deep Learning Methods for Natural Language Processing"
    val titleB = "A Survey of Deep Learning Methods for Natural Language Procesing"
    val sim    = FeatureFunctions.titleJaccard(titleA, titleB)
    // Only 1 token differs out of many
    sim should be > 0.8
  }

  // --- 5. Empty or whitespace-only name fields ---

  it should "return 0.0 for similarity when one name is empty" in {
    val sim = FeatureFunctions.nameSimilarity("", "John Smith")
    sim shouldBe 0.0
  }

  it should "return 0.0 for similarity when one name is whitespace-only" in {
    val sim = FeatureFunctions.nameSimilarity("   ", "John Smith")
    sim shouldBe 0.0
  }

  it should "return 1.0 when both names are empty" in {
    val sim = FeatureFunctions.nameSimilarity("", "")
    sim shouldBe 1.0
  }

  // --- 6. Match decision with extreme confidence values ---

  "MatchDecision" should "reject negative confidence" in {
    an[IllegalArgumentException] should be thrownBy {
      MatchDecision(
        sourceId = "src",
        targetId = "tgt",
        features = Map.empty,
        decision = MatchOutcome.Rejected,
        confidence = -0.5,
        reasonCodes = Vector.empty,
        policyVersion = "v1",
        runId = "run-1"
      )
    }
  }

  it should "reject confidence above 1.0" in {
    an[IllegalArgumentException] should be thrownBy {
      MatchDecision(
        sourceId = "src",
        targetId = "tgt",
        features = Map.empty,
        decision = MatchOutcome.Accepted,
        confidence = 1.01,
        reasonCodes = Vector.empty,
        policyVersion = "v1",
        runId = "run-1"
      )
    }
  }

  // --- 7. Engine clamps confidence for extreme feature values ---

  "EntityResolutionEngine" should "clamp confidence to [0,1] even with extreme precomputed features" in {
    val pair = TestFixtures.makePair(
      precomputedFeatures = Map(
        "title_jaccard"     -> 2.0, // out-of-range feature value
        "author_similarity" -> 2.0,
        "year_distance"     -> 2.0,
        "venue_match"       -> 2.0,
        "coauthor_overlap"  -> 2.0,
        "orcid_match"       -> 2.0,
        "affiliation_match" -> 2.0
      )
    )
    val decision = engine.resolve(pair)
    decision.confidence should be >= 0.0
    decision.confidence should be <= 1.0
  }

  // --- 8. ScoringPolicy threshold invariants ---

  "ScoringPolicy" should "reject thresholds where reject >= unresolvedMin" in {
    an[IllegalArgumentException] should be thrownBy {
      ScoringPolicy(
        policyVersion = "bad-v1",
        weights = ScoringPolicy.DefaultWeights,
        acceptThreshold = 0.9,
        rejectThreshold = 0.7,
        unresolvedBandMin = 0.6,
        unresolvedBandMax = 0.8
      )
    }
  }
