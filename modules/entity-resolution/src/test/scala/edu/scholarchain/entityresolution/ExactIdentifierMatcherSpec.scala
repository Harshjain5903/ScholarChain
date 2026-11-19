package edu.scholarchain.entityresolution

import edu.scholarchain.core.MatchOutcome
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExactIdentifierMatcherSpec extends AnyFlatSpec with Matchers {

  import TestFixtures.*

  "ExactIdentifierMatcher" should "accept when DOIs match" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/abc")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Accepted
    result.matchedField shouldBe Some("doi")
    result.reasonCodes should contain("exact_id_match:doi")
  }

  it should "accept when arXiv IDs match" in {
    val pair = makePair(
      sourceArxivId = Some("2301.12345"),
      targetArxivId = Some("2301.12345")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Accepted
    result.matchedField shouldBe Some("arxiv_id")
  }

  it should "accept when ORCIDs match" in {
    val pair = makePair(
      sourceOrcid = Some("0000-0002-1825-0097"),
      targetOrcid = Some("0000-0002-1825-0097")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Accepted
    result.matchedField shouldBe Some("orcid")
  }

  it should "detect DOI conflicts" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/xyz")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Conflict
    result.conflictFields should contain("doi")
  }

  it should "detect multi-field conflicts" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/xyz"),
      sourceOrcid = Some("0000-0002-1825-0097"),
      targetOrcid = Some("0000-0001-5109-3510")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Conflict
    result.conflictFields should have size 2
  }

  it should "return Unresolved when no IDs available" in {
    val pair   = makePair()
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Unresolved
    result.matchedField shouldBe None
    result.reasonCodes should contain("no_exact_id_available")
  }

  it should "handle DOI normalization (URL prefix)" in {
    val pair = makePair(
      sourceDoi = Some("https://doi.org/10.1234/ABC"),
      targetDoi = Some("10.1234/abc")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Accepted
  }

  it should "handle ORCID URL normalization" in {
    val pair = makePair(
      sourceOrcid = Some("https://orcid.org/0000-0002-1825-0097"),
      targetOrcid = Some("0000-0002-1825-0097")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Accepted
  }

  it should "ignore fields where only one side has data" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = None
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    result.outcome shouldBe MatchOutcome.Unresolved
  }

  it should "handle conflict with one matching field" in {
    val pair = makePair(
      sourceDoi = Some("10.1234/abc"),
      targetDoi = Some("10.1234/abc"),
      sourceOrcid = Some("0000-0002-1825-0097"),
      targetOrcid = Some("0000-0001-5109-3510")
    )
    val result = ExactIdentifierMatcher.evaluate(pair)
    // Conflict takes precedence when any field conflicts
    result.outcome shouldBe MatchOutcome.Conflict
  }
}
