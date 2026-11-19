package edu.scholarchain.hadoop.util

import java.text.Normalizer

/** Pure-function utilities for normalizing titles, names, DOIs, and identifiers. */
object NormalizationUtils:

  /** Characters that do not decompose under NFKD but should map to ASCII. */
  private val ExtraTransliterations: Map[Char, Char] = Map(
    'Ł' -> 'L', // Latin capital L with stroke
    'ł' -> 'l', // Latin small l with stroke
    'Ð' -> 'D', // Latin capital Eth
    'ð' -> 'd', // Latin small Eth
    'Ø' -> 'O', // Latin capital O with stroke
    'ø' -> 'o', // Latin small o with stroke
    'Đ' -> 'D', // Latin capital D with stroke
    'đ' -> 'd'  // Latin small d with stroke
  )

  /** Apply extra transliterations for characters that NFKD cannot decompose. */
  private def transliterate(s: String): String =
    val sb = new StringBuilder(s.length)
    s.foreach { c =>
      sb.append(ExtraTransliterations.getOrElse(c, c))
    }
    sb.toString()

  /**
   * Lowercase, strip punctuation, collapse whitespace. NFKD decomposition followed by
   * combining-mark removal ensures diacritics (accents, umlauts, cedillas) are stripped cleanly.
   */
  def normalizeTitle(raw: String): String =
    val nfkd = Normalizer.normalize(raw, Normalizer.Form.NFKD)
    transliterate(nfkd)
      .replaceAll("\\p{M}", "") // strip combining marks after decomposition
      .toLowerCase
      .replaceAll("[^a-z0-9\\s]", " ")
      .trim
      .replaceAll("\\s+", " ")

  /**
   * Normalize an author name: NFKD unicode normalization, lowercase, collapse whitespace, strip
   * trailing suffixes (jr, sr, ii, iii, iv).
   */
  def normalizeName(raw: String): String =
    val nfkd      = Normalizer.normalize(raw, Normalizer.Form.NFKD)
    val stripped  = transliterate(nfkd).replaceAll("\\p{M}", "") // strip combining marks
    val lowered   = stripped.toLowerCase.replaceAll("[^a-z\\s\\-']", " ").trim
    val collapsed = lowered.replaceAll("\\s+", " ")
    val suffixes  = Set("jr", "sr", "ii", "iii", "iv", "jr.", "sr.")
    val tokens    = collapsed.split("\\s+").toVector
    val cleaned =
      if tokens.nonEmpty && suffixes.contains(tokens.last)
      then tokens.init.mkString(" ")
      else tokens.mkString(" ")
    cleaned.trim

  /**
   * Normalize a DOI: lowercase, trim, strip common URL prefixes. Returns None if the result is
   * empty.
   */
  def normalizeDoi(raw: String): Option[String] =
    val trimmed = raw.trim.toLowerCase
    val stripped = trimmed
      .stripPrefix("https://doi.org/")
      .stripPrefix("http://doi.org/")
      .stripPrefix("https://dx.doi.org/")
      .stripPrefix("http://dx.doi.org/")
      .stripPrefix("doi:")
      .trim
    if stripped.isEmpty then None else Some(stripped)

  /**
   * Normalize an arXiv ID: strip URL prefixes, normalize format. Handles both old format
   * (hep-ph/0001234) and new format (1234.56789).
   */
  def normalizeArxivId(raw: String): Option[String] =
    val trimmed = raw.trim
    val stripped = trimmed
      .stripPrefix("https://arxiv.org/abs/")
      .stripPrefix("http://arxiv.org/abs/")
      .stripPrefix("arXiv:")
      .stripPrefix("arxiv:")
      .trim
    if stripped.isEmpty then None else Some(stripped)

  /** Validate ORCID format: XXXX-XXXX-XXXX-XXXX where X is digit or X. */
  def validateOrcid(raw: String): Option[String] =
    val trimmed = raw.trim
      .stripPrefix("https://orcid.org/")
      .stripPrefix("http://orcid.org/")
      .trim
    val pattern = "^\\d{4}-\\d{4}-\\d{4}-[\\dX]{4}$".r
    pattern.findFirstIn(trimmed)

  /** Normalize a venue name: lowercase, trim, collapse whitespace. */
  def normalizeVenue(raw: String): String =
    val nfkd = Normalizer.normalize(raw, Normalizer.Form.NFKD)
    transliterate(nfkd)
      .replaceAll("\\p{M}", "")
      .toLowerCase
      .replaceAll("\\s+", " ")
      .trim

  /**
   * Extract surname and first initial from a name string. Returns (normalized_surname,
   * first_initial) or None if unusable.
   */
  def extractSurnameAndInitial(
    name: String
  ): Option[(String, String)] =
    val normalized = normalizeName(name)
    val parts      = normalized.split("\\s+").toVector
    if parts.size < 2 then None
    else
      val surname = parts.last
      val initial = parts.head.take(1)
      Some((surname, initial))

  /**
   * Generate sorted normalized title tokens for blocking. Removes stop words and sorts
   * alphabetically.
   */
  def sortedTitleTokens(title: String): Vector[String] =
    val stopWords = Set(
      "a",
      "an",
      "the",
      "and",
      "or",
      "but",
      "in",
      "on",
      "of",
      "to",
      "for",
      "with",
      "by",
      "from",
      "at",
      "is",
      "are",
      "was",
      "were",
      "be",
      "been",
      "being",
      "have",
      "has",
      "had",
      "do",
      "does",
      "did",
      "will",
      "would",
      "could",
      "should",
      "may",
      "might",
      "can",
      "shall",
      "that",
      "this",
      "these",
      "those",
      "it",
      "its"
    )
    val tokens = normalizeTitle(title)
      .split("\\s+")
      .toVector
      .filter(t => t.nonEmpty && !stopWords.contains(t))
    tokens.sorted
