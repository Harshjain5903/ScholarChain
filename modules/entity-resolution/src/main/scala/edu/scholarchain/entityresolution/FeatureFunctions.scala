package edu.scholarchain.entityresolution

/** Pure feature-computation functions. Each returns a value in [0.0, 1.0]. */
object FeatureFunctions:

  /** Token-level Jaccard similarity on two strings. */
  def titleJaccard(a: String, b: String): Double =
    val tokA = IdentifierNormalizer.normalizeTitle(a).split("\\s+").toSet.filter(_.nonEmpty)
    val tokB = IdentifierNormalizer.normalizeTitle(b).split("\\s+").toSet.filter(_.nonEmpty)
    if tokA.isEmpty && tokB.isEmpty then 1.0
    else if tokA.isEmpty || tokB.isEmpty then 0.0
    else
      val inter = tokA.intersect(tokB).size.toDouble
      val union = tokA.union(tokB).size.toDouble
      inter / union

  /** Name similarity: token-level Jaccard on normalized names. */
  def nameSimilarity(a: String, b: String): Double =
    val tokA = IdentifierNormalizer.normalizeName(a).split("\\s+").toSet.filter(_.nonEmpty)
    val tokB = IdentifierNormalizer.normalizeName(b).split("\\s+").toSet.filter(_.nonEmpty)
    if tokA.isEmpty && tokB.isEmpty then 1.0
    else if tokA.isEmpty || tokB.isEmpty then 0.0
    else
      val inter = tokA.intersect(tokB).size.toDouble
      val union = tokA.union(tokB).size.toDouble
      inter / union

  /** Initials match: 1.0 if both names share the same initials, 0.0 otherwise. */
  def initialsMatch(a: String, b: String): Double =
    val extractInitials = (s: String) =>
      IdentifierNormalizer
        .normalizeName(s)
        .split("\\s+")
        .filter(_.nonEmpty)
        .map(_.head)
        .mkString
    val iA = extractInitials(a)
    val iB = extractInitials(b)
    if iA.isEmpty || iB.isEmpty then 0.0
    else if iA == iB then 1.0
    else 0.0

  /**
   * Author order comparison: returns 1.0 if the matching authors appear in similar positions, 0.0
   * if positions differ maximally. Uses normalized name matching.
   */
  def authorOrderSimilarity(
      sourceAuthors: Vector[String],
      targetAuthors: Vector[String]
  ): Double =
    if sourceAuthors.isEmpty || targetAuthors.isEmpty then 0.0
    else
      val srcNorm = sourceAuthors.map(IdentifierNormalizer.normalizeName)
      val tgtNorm = targetAuthors.map(IdentifierNormalizer.normalizeName)
      val maxLen  = math.max(srcNorm.size, tgtNorm.size).toDouble
      val positionDiffs = for
        (name, srcIdx) <- srcNorm.zipWithIndex
        tgtIdx = tgtNorm.indexOf(name) if tgtIdx >= 0
      yield math.abs(srcIdx - tgtIdx).toDouble / maxLen
      if positionDiffs.isEmpty then 0.0
      else 1.0 - (positionDiffs.sum / positionDiffs.size)

  /**
   * Best-match name similarity: for each author in the shorter list, find the best nameSimilarity
   * match in the longer list, then average those best scores. Measures how similar author NAME
   * strings are (as opposed to coauthorOverlap which measures set overlap via Jaccard).
   */
  def bestMatchNameSimilarity(a: Vector[String], b: Vector[String]): Double =
    if a.isEmpty && b.isEmpty then 0.0
    else if a.isEmpty || b.isEmpty then 0.0
    else
      val (shorter, longer) = if a.size <= b.size then (a, b) else (b, a)
      val bestScores = shorter.map { sa =>
        longer.map(lb => nameSimilarity(sa, lb)).max
      }
      bestScores.sum / bestScores.size

  /** Coauthor overlap: Jaccard on normalized author name sets. */
  def coauthorOverlap(
      sourceAuthors: Vector[String],
      targetAuthors: Vector[String]
  ): Double =
    val srcSet = sourceAuthors.map(IdentifierNormalizer.normalizeName).toSet
    val tgtSet = targetAuthors.map(IdentifierNormalizer.normalizeName).toSet
    if srcSet.isEmpty && tgtSet.isEmpty then 0.0
    else if srcSet.isEmpty || tgtSet.isEmpty then 0.0
    else
      val inter = srcSet.intersect(tgtSet).size.toDouble
      val union = srcSet.union(tgtSet).size.toDouble
      inter / union

  /** Affiliation compatibility: token Jaccard on normalized affiliations. */
  def affiliationMatch(a: Option[String], b: Option[String]): Double =
    (a, b) match
      case (Some(affA), Some(affB)) => nameSimilarity(affA, affB)
      case _                        => 0.0

  /** Binary ORCID match: 1.0 if both present and equal after normalization, else 0.0. */
  def orcidMatch(a: Option[String], b: Option[String]): Double =
    (a.flatMap(IdentifierNormalizer.normalizeOrcid), b.flatMap(IdentifierNormalizer.normalizeOrcid)) match
      case (Some(oa), Some(ob)) if oa == ob => 1.0
      case _                                => 0.0

  /** Binary external ID match (generic): 1.0 if equal after trimming, else 0.0. */
  def externalIdMatch(a: Option[String], b: Option[String]): Double =
    (a.map(_.trim), b.map(_.trim)) match
      case (Some(ia), Some(ib)) if ia.nonEmpty && ia == ib => 1.0
      case _                                               => 0.0

  /** Year distance: 1.0 for same year, decays linearly. 0.0 at distance >= 5. */
  def yearDistance(a: Option[Int], b: Option[Int]): Double =
    (a, b) match
      case (Some(ya), Some(yb)) =>
        val diff = math.abs(ya - yb)
        math.max(0.0, 1.0 - diff / 5.0)
      case _ => 0.5 // neutral when missing

  /** Venue compatibility: token Jaccard on normalized venue names. */
  def venueMatch(a: Option[String], b: Option[String]): Double =
    (a, b) match
      case (Some(va), Some(vb)) => nameSimilarity(va, vb)
      case _                    => 0.0

  /** Source reliability weight: returns a fixed weight per source type name. */
  def sourceReliabilityWeight(source: String): Double =
    source.toUpperCase match
      case "ORCID"           => 1.0
      case "CROSSREF"        => 0.95
      case "OPENALEX"        => 0.9
      case "DBLP"            => 0.85
      case "SEMANTICSCHOLAR" => 0.8
      case "SCHOLARCHAIN"    => 0.7
      case _                 => 0.5
