package edu.scholarchain.entityresolution

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IdentifierNormalizerSpec extends AnyFlatSpec with Matchers {

  "normalizeDoi" should "strip URL prefix and lowercase" in {
    IdentifierNormalizer.normalizeDoi("https://doi.org/10.1234/ABC") shouldBe Some("10.1234/abc")
    IdentifierNormalizer.normalizeDoi("https://dx.doi.org/10.1234/XYZ") shouldBe Some(
      "10.1234/xyz"
    )
    IdentifierNormalizer.normalizeDoi("10.1234/Test") shouldBe Some("10.1234/test")
  }

  it should "strip doi: prefix" in {
    IdentifierNormalizer.normalizeDoi("doi:10.1234/abc") shouldBe Some("10.1234/abc")
    IdentifierNormalizer.normalizeDoi("doi:10.1234/ABC") shouldBe Some("10.1234/abc")
  }

  it should "handle whitespace" in {
    IdentifierNormalizer.normalizeDoi("  10.1234/abc  ") shouldBe Some("10.1234/abc")
  }

  it should "return None for empty input" in {
    IdentifierNormalizer.normalizeDoi("") shouldBe None
    IdentifierNormalizer.normalizeDoi("   ") shouldBe None
  }

  "normalizeArxivId" should "strip URL prefix" in {
    IdentifierNormalizer.normalizeArxivId("https://arxiv.org/abs/2301.12345") shouldBe Some(
      "2301.12345"
    )
    IdentifierNormalizer.normalizeArxivId("2301.12345") shouldBe Some("2301.12345")
  }

  it should "lowercase the result" in {
    IdentifierNormalizer.normalizeArxivId("2301.12345V2") shouldBe Some("2301.12345v2")
  }

  "normalizeOrcid" should "validate format" in {
    IdentifierNormalizer.normalizeOrcid("0000-0002-1825-0097") shouldBe Some("0000-0002-1825-0097")
    IdentifierNormalizer.normalizeOrcid("https://orcid.org/0000-0002-1825-0097") shouldBe Some(
      "0000-0002-1825-0097"
    )
  }

  it should "accept X checksum digit" in {
    IdentifierNormalizer.normalizeOrcid("0000-0001-5109-351X") shouldBe Some("0000-0001-5109-351X")
  }

  it should "reject invalid formats" in {
    IdentifierNormalizer.normalizeOrcid("1234") shouldBe None
    IdentifierNormalizer.normalizeOrcid("not-an-orcid") shouldBe None
  }

  "normalizeOpenAlexId" should "strip URL prefix and uppercase" in {
    IdentifierNormalizer.normalizeOpenAlexId("https://openalex.org/a123") shouldBe Some("A123")
    IdentifierNormalizer.normalizeOpenAlexId("w456") shouldBe Some("W456")
  }

  "normalizeSemanticScholarId" should "trim whitespace" in {
    IdentifierNormalizer.normalizeSemanticScholarId("  abc123  ") shouldBe Some("abc123")
    IdentifierNormalizer.normalizeSemanticScholarId("") shouldBe None
  }

  "normalizeUrl" should "lowercase and strip trailing slash" in {
    IdentifierNormalizer.normalizeUrl("HTTPS://Example.COM/Path/") shouldBe Some(
      "https://example.com/path"
    )
  }

  "normalizeName" should "strip diacritics and normalize" in {
    IdentifierNormalizer.normalizeName("Jose Garcia-Lopez") shouldBe "jose garcia lopez"
    IdentifierNormalizer.normalizeName("Muller") shouldBe "muller"
  }

  it should "handle Unicode characters" in {
    IdentifierNormalizer.normalizeName("Rene Descartes") shouldBe "rene descartes"
  }

  it should "collapse whitespace" in {
    IdentifierNormalizer.normalizeName("  John   Doe  ") shouldBe "john doe"
  }

  "normalizeTitle" should "normalize scholarly titles" in {
    IdentifierNormalizer.normalizeTitle("Deep Learning: A Survey") shouldBe
      "deep learning a survey"
  }

  "extractSurnameAndInitial" should "extract from normalized name" in {
    IdentifierNormalizer.extractSurnameAndInitial("john doe") shouldBe Some(("doe", 'j'))
    IdentifierNormalizer.extractSurnameAndInitial("einstein") shouldBe Some(("einstein", 'e'))
    IdentifierNormalizer.extractSurnameAndInitial("") shouldBe None
  }
}
