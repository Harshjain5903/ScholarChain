package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EntityResolutionEngineSpec extends AnyFlatSpec with Matchers {

  import TestFixtures.*

  val policy: ScoringPolicy          = ScoringPolicy.default
  val adjudicator: StubLlmAdjudicator = new StubLlmAdjudicator()
  val engine: EntityResolutionEngine =
    new EntityResolutionEngine(policy, adjudicator, llmEnabled = false, runId = "test-run")

  "EntityResolutionEngine" should "accept exact DOI matches with confidence 1.0" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/abc")
    )
    val decision = engine.resolve(pair)
    decision.decision shouldBe MatchOutcome.Accepted
    decision.confidence shouldBe 1.0
    decision.reasonCodes should contain("path:exact_id")
    decision.policyVersion shouldBe "er-v1"
    decision.runId shouldBe "test-run"
  }

  it should "detect conflicts" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/xyz")
    )
    val decision = engine.resolve(pair)
    decision.decision shouldBe MatchOutcome.Conflict
    decision.confidence shouldBe 0.0
    decision.reasonCodes should contain("path:conflict")
  }

  it should "accept high-scoring bibliographic matches" in {
    val pair = makePair(
      sourceTitle = Some("Deep Learning for Natural Language Processing"),
      targetTitle = Some("Deep Learning for Natural Language Processing"),
      sourceAuthors = Vector("John Doe", "Jane Smith"),
      targetAuthors = Vector("John Doe", "Jane Smith"),
      sourceYear = Some(2023),
      targetYear = Some(2023),
      sourceVenue = Some("NeurIPS"),
      targetVenue = Some("NeurIPS"),
      sourceOrcid = Some("0000-0002-1825-0097"),
      targetOrcid = Some("0000-0002-1825-0097"),
      sourceAffiliation = Some("MIT"),
      targetAffiliation = Some("MIT")
    )
    val decision = engine.resolve(pair)
    // With all features at 1.0, score should be 1.0 -> Accepted
    decision.decision shouldBe MatchOutcome.Accepted
    decision.confidence should be >= 0.93
  }

  it should "reject low-scoring pairs" in {
    val pair = makePair(
      sourceTitle = Some("Quantum Computing Advances"),
      targetTitle = Some("Marine Biology Survey Methods"),
      sourceAuthors = Vector("Alice Wonder"),
      targetAuthors = Vector("Bob Builder"),
      sourceYear = Some(2020),
      targetYear = Some(2010),
      sourceVenue = Some("Physical Review"),
      targetVenue = Some("Nature Ecology")
    )
    val decision = engine.resolve(pair)
    decision.decision shouldBe MatchOutcome.Rejected
    decision.confidence should be <= 0.35
  }

  it should "mark unresolved when score is in the middle" in {
    val pair = makePair(
      sourceTitle = Some("Machine Learning Applications in Biology"),
      targetTitle = Some("Applications of Machine Learning"),
      sourceAuthors = Vector("John Doe", "Jane Smith"),
      targetAuthors = Vector("John Doe", "Robert Brown"),
      sourceYear = Some(2022),
      targetYear = Some(2022),
      sourceVenue = Some("ICML"),
      targetVenue = Some("NeurIPS")
    )
    val decision = engine.resolve(pair)
    // Partial overlap should put this in unresolved range
    decision.decision shouldBe MatchOutcome.Unresolved
    decision.reasonCodes should contain("llm_disabled")
  }

  it should "use LLM adjudication when enabled" in {
    val llmEngine =
      new EntityResolutionEngine(policy, adjudicator, llmEnabled = true, runId = "test-run-llm")
    val pair = makePair(
      sourceTitle = Some("Machine Learning Applications in Biology"),
      targetTitle = Some("Applications of Machine Learning"),
      sourceAuthors = Vector("John Doe", "Jane Smith"),
      targetAuthors = Vector("John Doe", "Robert Brown"),
      sourceYear = Some(2022),
      targetYear = Some(2022),
      sourceVenue = Some("ICML"),
      targetVenue = Some("NeurIPS")
    )
    val decision = llmEngine.resolve(pair)
    // Stub always abstains, so result stays Unresolved
    decision.decision shouldBe MatchOutcome.Unresolved
    decision.reasonCodes should contain("llm_decision:Abstain")
  }

  it should "process batch resolution" in {
    val pairs = Vector(
      makePair(
        sourceId = "src-1",
        targetId = "tgt-1",
        sourceDoi = Some("10.1234/abc"),
        targetDoi = Some("10.1234/abc")
      ),
      makePair(
        sourceId = "src-2",
        targetId = "tgt-2",
        sourceDoi = Some("10.1234/abc"),
        targetDoi = Some("10.1234/xyz")
      )
    )
    val decisions = engine.resolveBatch(pairs)
    decisions should have size 2
    decisions(0).decision shouldBe MatchOutcome.Accepted
    decisions(1).decision shouldBe MatchOutcome.Conflict
  }

  it should "include policy version in every decision" in {
    val pair     = makePair()
    val decision = engine.resolve(pair)
    decision.policyVersion shouldBe "er-v1"
  }

  it should "include run ID in every decision" in {
    val pair     = makePair()
    val decision = engine.resolve(pair)
    decision.runId shouldBe "test-run"
  }

  it should "handle precomputed features" in {
    val pair = makePair(
      precomputedFeatures = Map(
        "title_jaccard"     -> 1.0,
        "author_similarity" -> 1.0,
        "year_distance"     -> 1.0,
        "venue_match"       -> 1.0,
        "coauthor_overlap"  -> 1.0,
        "orcid_match"       -> 1.0,
        "affiliation_match" -> 1.0
      )
    )
    val decision = engine.resolve(pair)
    decision.decision shouldBe MatchOutcome.Accepted
    decision.confidence shouldBe 1.0 +- 0.001
  }

  it should "handle empty candidates gracefully" in {
    val pair     = makePair()
    val decision = engine.resolve(pair)
    // No IDs, no titles, no authors -- should produce low score
    decision.confidence should be >= 0.0
    decision.confidence should be <= 1.0
  }
}
