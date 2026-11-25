package edu.scholarchain.hadoop.util

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NormalizationUtilsSpec extends AnyFunSuite with Matchers:

  // -- Title normalization --

  test("normalizeTitle lowercases and strips punctuation") {
    NormalizationUtils.normalizeTitle(
      "Scalable Graph Partitioning: A Survey!"
    ) shouldBe "scalable graph partitioning a survey"
  }

  test("normalizeTitle collapses whitespace") {
    NormalizationUtils.normalizeTitle(
      "  Multiple   Spaces   Here  "
    ) shouldBe "multiple spaces here"
  }

  test("normalizeTitle handles unicode") {
    NormalizationUtils.normalizeTitle(
      "Uber die Konvergenz"
    ) shouldBe "uber die konvergenz"
  }

  test("normalizeTitle empty input") {
    NormalizationUtils.normalizeTitle("") shouldBe ""
  }

  // -- Name normalization --

  test("normalizeName lowercases and normalizes") {
    NormalizationUtils.normalizeName(
      "Liang Chen"
    ) shouldBe "liang chen"
  }

  test("normalizeName handles unicode characters") {
    NormalizationUtils.normalizeName(
      "Carlos Martinez"
    ) shouldBe "carlos martinez"
  }

  test("normalizeName strips suffixes") {
    NormalizationUtils.normalizeName(
      "John Smith Jr"
    ) shouldBe "john smith"
  }

  test("normalizeName handles initials") {
    NormalizationUtils.normalizeName(
      "J. Smith"
    ) shouldBe "j smith"
  }

  test("normalizeName handles apostrophe in name") {
    NormalizationUtils.normalizeName(
      "Fiona O'Connor"
    ) shouldBe "fiona o'connor"
  }

  // -- DOI normalization --

  test("normalizeDoi lowercases") {
    NormalizationUtils.normalizeDoi(
      "10.1109/TSE.2023.0001234"
    ) shouldBe Some("10.1109/tse.2023.0001234")
  }

  test("normalizeDoi strips https prefix") {
    NormalizationUtils.normalizeDoi(
      "https://doi.org/10.1109/TSE.2023.0001234"
    ) shouldBe Some("10.1109/tse.2023.0001234")
  }

  test("normalizeDoi strips http prefix") {
    NormalizationUtils.normalizeDoi(
      "http://doi.org/10.1234/test"
    ) shouldBe Some("10.1234/test")
  }

  test("normalizeDoi strips dx.doi.org prefix") {
    NormalizationUtils.normalizeDoi(
      "https://dx.doi.org/10.1234/test"
    ) shouldBe Some("10.1234/test")
  }

  test("normalizeDoi trims whitespace") {
    NormalizationUtils.normalizeDoi(
      "  10.1234/test  "
    ) shouldBe Some("10.1234/test")
  }

  test("normalizeDoi returns None for empty") {
    NormalizationUtils.normalizeDoi("") shouldBe None
  }

  // -- arXiv ID normalization --

  test("normalizeArxivId strips URL prefix") {
    NormalizationUtils.normalizeArxivId(
      "https://arxiv.org/abs/1234.56789"
    ) shouldBe Some("1234.56789")
  }

  test("normalizeArxivId handles arXiv: prefix") {
    NormalizationUtils.normalizeArxivId(
      "arXiv:1234.56789"
    ) shouldBe Some("1234.56789")
  }

  test("normalizeArxivId handles old format") {
    NormalizationUtils.normalizeArxivId(
      "hep-ph/0001234"
    ) shouldBe Some("hep-ph/0001234")
  }

  // -- ORCID validation --

  test("validateOrcid accepts valid ORCID") {
    NormalizationUtils.validateOrcid(
      "0000-0003-4567-8901"
    ) shouldBe Some("0000-0003-4567-8901")
  }

  test("validateOrcid strips URL prefix") {
    NormalizationUtils.validateOrcid(
      "https://orcid.org/0000-0003-4567-8901"
    ) shouldBe Some("0000-0003-4567-8901")
  }

  test("validateOrcid rejects invalid format") {
    NormalizationUtils.validateOrcid("1234-5678") shouldBe None
  }

  test("validateOrcid accepts X checksum digit") {
    NormalizationUtils.validateOrcid(
      "0000-0001-2345-678X"
    ) shouldBe Some("0000-0001-2345-678X")
  }

  // -- Surname and initial extraction --

  test("N60: orcidChecksumValid implements ISO 7064 MOD 11-2") {
    NormalizationUtils.orcidChecksumValid("0000-0002-1825-0097") shouldBe true
    NormalizationUtils.orcidChecksumValid("0000-0001-5109-3700") shouldBe true
    NormalizationUtils.orcidChecksumValid("0000-0002-1694-233X") shouldBe true
    NormalizationUtils.orcidChecksumValid("0000-0002-1825-0098") shouldBe false
    NormalizationUtils.orcidChecksumValid("0000-0002-1694-2339") shouldBe false
    NormalizationUtils.orcidChecksumValid("0000-000X-1825-0097") shouldBe false
    NormalizationUtils.orcidChecksumValid("1234") shouldBe false
  }

  test("N60: validateOrcidChecksummed returns None for bad format or bad checksum") {
    NormalizationUtils.validateOrcidChecksummed(
      "https://orcid.org/0000-0002-1825-0097"
    ) shouldBe Some("0000-0002-1825-0097")
    NormalizationUtils.validateOrcidChecksummed("0000-0002-1825-0098") shouldBe None
    NormalizationUtils.validateOrcidChecksummed("not-an-orcid") shouldBe None
  }

  test("extractSurnameAndInitial basic case") {
    NormalizationUtils.extractSurnameAndInitial(
      "Liang Chen"
    ) shouldBe Some(("chen", "l"))
  }

  test("extractSurnameAndInitial returns None for single name") {
    NormalizationUtils.extractSurnameAndInitial(
      "Madonna"
    ) shouldBe None
  }

  test("extractSurnameAndInitial with middle name") {
    NormalizationUtils.extractSurnameAndInitial(
      "John Michael Smith"
    ) shouldBe Some(("smith", "j"))
  }

  // -- Sorted title tokens --

  test("sortedTitleTokens removes stop words and sorts") {
    val tokens = NormalizationUtils.sortedTitleTokens(
      "Scalable Graph Partitioning for Distributed Knowledge Bases"
    )
    tokens should not contain "for"
    tokens shouldBe sorted
    tokens should contain("scalable")
    tokens should contain("graph")
  }

  // -- Unicode diacritics --

  test("normalizeName strips Spanish diacritics") {
    NormalizationUtils.normalizeName(
      "José García"
    ) shouldBe "jose garcia"
  }

  test("normalizeName strips French cedilla and German umlaut") {
    NormalizationUtils.normalizeName(
      "François Müller"
    ) shouldBe "francois muller"
  }

  test("normalizeName strips Polish characters") {
    NormalizationUtils.normalizeName(
      "Łukasz Wójcik"
    ) shouldBe "lukasz wojcik"
  }

  test("normalizeTitle strips diacritics from title text") {
    NormalizationUtils.normalizeTitle(
      "Über die Konvergenz"
    ) shouldBe "uber die konvergenz"
  }

  // -- Venue normalization --

  test("normalizeVenue lowercases and trims") {
    NormalizationUtils.normalizeVenue(
      "  IEEE Trans. Software Eng.  "
    ) shouldBe "ieee trans. software eng."
  }
