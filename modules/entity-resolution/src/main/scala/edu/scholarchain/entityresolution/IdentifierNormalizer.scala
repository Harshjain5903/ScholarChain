package edu.scholarchain.entityresolution

/** Normalizes scholarly identifiers (DOI, arXiv, ORCID, OpenAlex, Semantic Scholar, URLs). */
object IdentifierNormalizer:

  private val DoiPrefixPattern = "^(?:doi:|https?://(?:dx\\.)?doi\\.org/)?(.+)$".r
  private val ArxivUrlPattern  = "^(?:https?://arxiv\\.org/abs/)?(.+)$".r
  private val OrcidUrlPattern  = "^(?:https?://orcid\\.org/)?(.+)$".r
  private val OrcidPattern     = "^\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]$".r

  /** Normalize a DOI: strip URL prefix, lowercase, trim whitespace. */
  def normalizeDoi(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty).map { s =>
      val stripped = s match
        case DoiPrefixPattern(doi) => doi
        case other                 => other
      stripped.toLowerCase.trim
    }

  /** Normalize an arXiv ID: strip URL prefix, lowercase, trim. */
  def normalizeArxivId(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty).map { s =>
      val stripped = s match
        case ArxivUrlPattern(id) => id
        case other               => other
      stripped.toLowerCase.trim
    }

  /** Validate and normalize an ORCID: strip URL prefix, uppercase, check format. */
  def normalizeOrcid(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty).flatMap { s =>
      val stripped = s match
        case OrcidUrlPattern(id) => id
        case other               => other
      val normalized = stripped.toUpperCase.trim
      OrcidPattern.findFirstIn(normalized)
    }

  /** Normalize an OpenAlex ID: strip URL prefix, uppercase the letter prefix, trim. */
  def normalizeOpenAlexId(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty).map { s =>
      val stripped =
        if s.toLowerCase.startsWith("https://openalex.org/") then s.drop(21)
        else s
      stripped.trim.toUpperCase
    }

  /** Normalize a Semantic Scholar ID: trim whitespace. */
  def normalizeSemanticScholarId(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty)

  /** Normalize a repository URL: lowercase, strip trailing slash. */
  def normalizeUrl(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty).map { s =>
      s.toLowerCase.stripSuffix("/")
    }

  /**
   * Normalize a name for comparison: lowercase, strip diacritics, collapse whitespace, remove
   * punctuation.
   */
  def normalizeName(raw: String): String =
    val transliterated = transliterate(raw.toLowerCase)
    val nfkd     = java.text.Normalizer.normalize(transliterated, java.text.Normalizer.Form.NFKD)
    val stripped = nfkd.replaceAll("\\p{M}", "")
    stripped
      .replaceAll("[^a-z0-9\\s]", " ")
      .trim
      .replaceAll("\\s+", " ")

  /**
   * Latin letters that Unicode NFKD does not decompose into base letter + combining mark. Without
   * this table they would be erased by the ASCII filter (e.g. "Fjaer" written with ae-ligature
   * would become "fj r").
   */
  private val NonDecomposingLetters: Map[Char, String] = Map(
    'æ' -> "ae", // ae ligature
    'ø' -> "o",  // o with stroke
    'œ' -> "oe", // oe ligature
    'ß' -> "ss", // sharp s
    'ł' -> "l",  // l with stroke
    'đ' -> "d",  // d with stroke
    'ð' -> "d",  // eth
    'þ' -> "th", // thorn
    'ı' -> "i"   // dotless i
  )

  /** True if the name contains a letter that needs explicit transliteration (see table above). */
  def hasNonDecomposingLetters(raw: String): Boolean =
    raw.toLowerCase.exists(NonDecomposingLetters.contains)

  private def transliterate(lower: String): String =
    if !lower.exists(NonDecomposingLetters.contains) then lower
    else lower.flatMap(c => NonDecomposingLetters.getOrElse(c, c.toString))

  private val DblpHomonymSuffix = "^(.*\\S)\\s+(\\d{4})$".r

  /**
   * Split a DBLP person name into (base name, homonym suffix). DBLP disambiguates homonymous
   * persons by appending a four-digit number (e.g. "Wei Wang 0001"); two names with different
   * suffixes are, by DBLP curation, different persons.
   */
  def splitDblpHomonymSuffix(rawName: String): (String, Option[String]) =
    rawName.trim match
      case DblpHomonymSuffix(base, suffix) => (base.trim, Some(suffix))
      case other                           => (other, None)

  /** Normalize a title for comparison: lowercase, strip punctuation, collapse whitespace. */
  def normalizeTitle(raw: String): String =
    val nfkd     = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKD)
    val stripped = nfkd.replaceAll("\\p{M}", "")
    stripped.toLowerCase
      .replaceAll("[^a-z0-9\\s]", " ")
      .trim
      .replaceAll("\\s+", " ")

  /** Extract surname and first initial from a normalized name. */
  def extractSurnameAndInitial(normalizedName: String): Option[(String, Char)] =
    val parts = normalizedName.split("\\s+").filter(_.nonEmpty)
    if parts.length >= 2 then Some((parts.last, parts.head.head))
    else if parts.length == 1 && parts.head.nonEmpty then Some((parts.head, parts.head.head))
    else None
