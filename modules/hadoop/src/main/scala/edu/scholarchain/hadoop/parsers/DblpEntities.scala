package edu.scholarchain.hadoop.parsers

/**
 * Named character entities declared by the DBLP DTD (`dblp.dtd`, dated May 10, 2023: `reg`,
 * `micro`, `times` and the ISO 8879 Added Latin 1 set).
 *
 * The DBLP dump encodes every non-ASCII character as one of these entities. Because records are
 * parsed one at a time (without the DTD, to avoid external DTD loading), the parser rewrites each
 * known entity to its numeric character reference before XML parsing. Unknown entities are left
 * unchanged so they still surface as `MALFORMED_XML` rejects instead of being silently dropped.
 */
object DblpEntities:

  /** Entity name -> Unicode code point, exactly as declared in dblp.dtd. */
  val CodePoints: Map[String, Int] = Map(
    "reg" -> 174,
    "micro" -> 181,
    "times" -> 215,
    "Agrave" -> 192,
    "Aacute" -> 193,
    "Acirc" -> 194,
    "Atilde" -> 195,
    "Auml" -> 196,
    "Aring" -> 197,
    "AElig" -> 198,
    "Ccedil" -> 199,
    "Egrave" -> 200,
    "Eacute" -> 201,
    "Ecirc" -> 202,
    "Euml" -> 203,
    "Igrave" -> 204,
    "Iacute" -> 205,
    "Icirc" -> 206,
    "Iuml" -> 207,
    "ETH" -> 208,
    "Ntilde" -> 209,
    "Ograve" -> 210,
    "Oacute" -> 211,
    "Ocirc" -> 212,
    "Otilde" -> 213,
    "Ouml" -> 214,
    "Oslash" -> 216,
    "Ugrave" -> 217,
    "Uacute" -> 218,
    "Ucirc" -> 219,
    "Uuml" -> 220,
    "Yacute" -> 221,
    "THORN" -> 222,
    "szlig" -> 223,
    "agrave" -> 224,
    "aacute" -> 225,
    "acirc" -> 226,
    "atilde" -> 227,
    "auml" -> 228,
    "aring" -> 229,
    "aelig" -> 230,
    "ccedil" -> 231,
    "egrave" -> 232,
    "eacute" -> 233,
    "ecirc" -> 234,
    "euml" -> 235,
    "igrave" -> 236,
    "iacute" -> 237,
    "icirc" -> 238,
    "iuml" -> 239,
    "eth" -> 240,
    "ntilde" -> 241,
    "ograve" -> 242,
    "oacute" -> 243,
    "ocirc" -> 244,
    "otilde" -> 245,
    "ouml" -> 246,
    "oslash" -> 248,
    "ugrave" -> 249,
    "uacute" -> 250,
    "ucirc" -> 251,
    "uuml" -> 252,
    "yacute" -> 253,
    "thorn" -> 254,
    "yuml" -> 255
  )

  private val EntityRef = """&([A-Za-z][A-Za-z0-9]*);""".r

  /**
   * Replace every DTD-declared named entity with a numeric character reference (`&eacute;` ->
   * `&#233;`). XML predefined entities (`amp`, `lt`, `gt`, `quot`, `apos`) and unknown names are
   * left untouched.
   */
  def resolveNamedEntities(xml: String): String =
    if xml.indexOf('&') < 0 then xml
    else
      EntityRef.replaceAllIn(
        xml,
        m =>
          CodePoints.get(m.group(1)) match
            case Some(cp) => s"&#$cp;"
            case None     => scala.util.matching.Regex.quoteReplacement(m.matched)
      )
