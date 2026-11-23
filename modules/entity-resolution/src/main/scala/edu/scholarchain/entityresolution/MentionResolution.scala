package edu.scholarchain.entityresolution

import edu.scholarchain.core.{MatchDecision, MatchOutcome, WorkRecord}

/**
 * An author mention together with the work-level context needed to compare it with another
 * mention (coauthors, venue, year, title).
 */
case class MentionContext(
  mentionId: String,
  workId: String,
  sourceRecordId: String,
  rawName: String,
  baseName: String,
  normalizedName: String,
  dblpHomonymSuffix: Option[String],
  orcid: Option[String],
  coauthorNames: Set[String],
  venue: Option[String],
  year: Option[Int],
  title: String
)

object MentionContext:

  /**
   * Explode a WorkRecord into one MentionContext per author. Names are re-normalized from the raw
   * DBLP name (not the upstream normalizedName) so that the DBLP homonym suffix is kept as a
   * separate signal and non-decomposing letters are transliterated. Mentions whose name normalizes
   * to the empty string are dropped.
   */
  def fromWork(work: WorkRecord): Vector[MentionContext] =
    val parsed = work.authors.map { a =>
      val (base, suffix) = IdentifierNormalizer.splitDblpHomonymSuffix(a.rawName)
      (a, base, suffix, IdentifierNormalizer.normalizeName(base))
    }
    parsed.collect {
      case (a, base, suffix, norm) if norm.nonEmpty =>
        val coauthors = parsed.collect {
          case (other, _, _, otherNorm) if other.mentionId != a.mentionId && otherNorm.nonEmpty =>
            otherNorm
        }.toSet
        MentionContext(
          mentionId = a.mentionId,
          workId = work.workId,
          sourceRecordId = work.sourceRecordId,
          rawName = a.rawName,
          baseName = base,
          normalizedName = norm,
          dblpHomonymSuffix = suffix,
          orcid = a.orcid.flatMap(IdentifierNormalizer.normalizeOrcid),
          coauthorNames = coauthors,
          venue = work.venueId.map(_.trim.toLowerCase).filter(_.nonEmpty),
          year = Some(work.year).filter(_ > 0),
          title = work.title
        )
    }

/** A pair of mentions sharing a blocking key, ordered so that source.mentionId < target.mentionId. */
case class MentionPair(source: MentionContext, target: MentionContext, blockKey: String):
  require(source.mentionId < target.mentionId, "MentionPair must be ordered by mentionId")

/** Result of mention blocking, including blocks that were skipped for size. */
case class MentionBlockingResult(
  pairs: Vector[MentionPair],
  blockCount: Int,
  blockSizes: Map[String, Int],
  oversizedBlocks: Map[String, Int],
  sameWorkPairsSkipped: Int
)

/**
 * Mention-level blocking on (surname, first initial) of the suffix-stripped normalized name.
 * Homonym suffixes are deliberately NOT part of the key, so that DBLP-distinguished homonyms land
 * in the same block and are explicitly rejected by the policy rather than silently never compared.
 */
object MentionBlocker:

  def blockKey(m: MentionContext): Option[String] =
    IdentifierNormalizer
      .extractSurnameAndInitial(m.normalizedName)
      .map { case (surname, initial) => s"name:$surname:$initial" }

  def block(mentions: Vector[MentionContext], maxBlockSize: Int): MentionBlockingResult =
    require(maxBlockSize >= 2, "maxBlockSize must be at least 2")
    val distinct = mentions.distinctBy(_.mentionId)
    val blocks = distinct
      .flatMap(m => blockKey(m).map(_ -> m))
      .groupMap(_._1)(_._2)
    val (oversized, usable) = blocks.partition(_._2.size > maxBlockSize)
    var sameWork            = 0
    val pairs = usable.toVector.sortBy(_._1).flatMap { case (key, members) =>
      val sorted = members.sortBy(_.mentionId)
      val out    = Vector.newBuilder[MentionPair]
      for
        i <- sorted.indices
        j <- (i + 1) until sorted.size
      do
        val a = sorted(i)
        val b = sorted(j)
        if a.workId == b.workId then sameWork += 1
        else out += MentionPair(a, b, key)
      out.result()
    }
    MentionBlockingResult(
      pairs = pairs,
      blockCount = blocks.size,
      blockSizes = blocks.view.mapValues(_.size).toMap,
      oversizedBlocks = oversized.view.mapValues(_.size).toMap,
      sameWorkPairsSkipped = sameWork
    )

/**
 * Mention-level resolution policy. Evidence hierarchy:
 *   1. ORCID on both mentions: equal -> Accepted, different -> Rejected.
 *   2. DBLP homonym suffix on both mentions: same base name and same suffix -> Accepted (DBLP
 *      person key); different suffixes -> Rejected.
 *   3. Given-name gate: incompatible first given names (not equal, not an initial of one another)
 *      -> Rejected.
 *   4. Weighted feature score via ScoringPolicy thresholds. If exactly one mention carries a DBLP
 *      homonym suffix, an Accepted score is downgraded to Unresolved (DBLP has explicitly split
 *      this name; an unsuffixed mention may belong to any of the homonyms).
 *   5. Unresolved pairs go to the LLM adjudicator only when llmEnabled; otherwise they stay
 *      Unresolved for human review.
 */
class MentionResolver(
  policy: ScoringPolicy,
  adjudicator: LlmAdjudicator,
  llmEnabled: Boolean,
  runId: String,
  extraReasonCodes: Vector[String] = Vector.empty
):

  def computeFeatures(p: MentionPair): Map[String, Double] =
    val s = p.source
    val t = p.target
    Map(
      "name_similarity"   -> FeatureFunctions.nameSimilarity(s.normalizedName, t.normalizedName),
      "coauthor_overlap"  -> MentionResolver.overlapCoefficient(s.coauthorNames, t.coauthorNames),
      "venue_match"       -> FeatureFunctions.venueMatch(s.venue, t.venue),
      "year_proximity"    -> FeatureFunctions.yearDistance(s.year, t.year),
      "title_jaccard"     -> FeatureFunctions.titleJaccard(s.title, t.title),
      "orcid_match"       -> FeatureFunctions.orcidMatch(s.orcid, t.orcid),
      "dblp_suffix_match" -> MentionResolver.suffixFeature(s, t)
    )

  def resolve(p: MentionPair): MatchDecision =
    val s        = p.source
    val t        = p.target
    val features = computeFeatures(p)
    val base     = Vector(s"block:${p.blockKey}") ++ extraReasonCodes

    (s.orcid, t.orcid) match
      case (Some(a), Some(b)) if a == b =>
        decision(p, features, MatchOutcome.Accepted, 1.0, base ++ Vector(
          "exact_id_match:orcid",
          "path:exact_id"
        ))
      case (Some(_), Some(_)) =>
        decision(p, features, MatchOutcome.Rejected, 0.0, base ++ Vector(
          "orcid_mismatch",
          "path:exact_id"
        ))
      case _ =>
        (s.dblpHomonymSuffix, t.dblpHomonymSuffix) match
          case (Some(a), Some(b)) if a == b && s.normalizedName == t.normalizedName =>
            decision(p, features, MatchOutcome.Accepted, 1.0, base ++ Vector(
              "dblp_person_key_match",
              "path:dblp_person_key"
            ))
          case (Some(a), Some(b)) if a != b =>
            decision(p, features, MatchOutcome.Rejected, 0.0, base ++ Vector(
              "dblp_homonym_suffix_mismatch",
              "path:dblp_person_key"
            ))
          case _ if !MentionResolver.givenNamesCompatible(s.normalizedName, t.normalizedName) =>
            val score = policy.computeScore(features)
            decision(p, features, MatchOutcome.Rejected, score, base ++ Vector(
              s"score:${"%.4f".format(score)}",
              "given_name_incompatible",
              "path:name_gate"
            ))
          case _ => scored(p, features, base)

  private def scored(
    p: MentionPair,
    features: Map[String, Double],
    base: Vector[String]
  ): MatchDecision =
    val score     = policy.computeScore(features)
    val path      = policy.matchPath(score)
    val scoreCode = s"score:${"%.4f".format(score)}"
    val oneSided  = p.source.dblpHomonymSuffix.isDefined != p.target.dblpHomonymSuffix.isDefined
    policy.classify(score) match
      case MatchOutcome.Accepted if oneSided =>
        decision(p, features, MatchOutcome.Unresolved, score, base ++ Vector(
          scoreCode,
          s"path:$path",
          "dblp_homonym_one_sided"
        ))
      case MatchOutcome.Accepted =>
        decision(p, features, MatchOutcome.Accepted, score, base ++ Vector(scoreCode, s"path:$path"))
      case MatchOutcome.Rejected =>
        decision(p, features, MatchOutcome.Rejected, score, base ++ Vector(scoreCode, s"path:$path"))
      case _ if llmEnabled =>
        adjudicate(p, features, score, base :+ scoreCode)
      case _ =>
        decision(p, features, MatchOutcome.Unresolved, score, base ++ Vector(
          scoreCode,
          s"path:$path",
          "llm_disabled"
        ))

  private def adjudicate(
    p: MentionPair,
    features: Map[String, Double],
    score: Double,
    codes: Vector[String]
  ): MatchDecision =
    def fields(m: MentionContext): Map[String, String] =
      Map("name" -> m.rawName, "title" -> m.title, "coauthors" -> m.coauthorNames.toVector.sorted
        .mkString("; ")) ++ m.venue.map("venue" -> _) ++ m.year.map("year" -> _.toString)
    val response = adjudicator.adjudicate(
      AdjudicationRequest(
        sourceId = p.source.mentionId,
        targetId = p.target.mentionId,
        features = features,
        sourceFields = fields(p.source),
        targetFields = fields(p.target),
        policyVersion = policy.policyVersion,
        promptVersion = "adjudication-v1"
      )
    )
    val outcome = response.decision match
      case AdjudicationDecision.Accept  => MatchOutcome.Accepted
      case AdjudicationDecision.Reject  => MatchOutcome.Rejected
      case AdjudicationDecision.Abstain => MatchOutcome.Unresolved
    val conf = if outcome == MatchOutcome.Unresolved then score else response.confidence
    decision(p, features, outcome, conf, codes ++ Vector(
      "path:llm_adjudicated",
      s"llm_decision:${response.decision}",
      s"llm_model:${response.modelVersion}"
    ) ++ response.reasonCodes)

  private def decision(
    p: MentionPair,
    features: Map[String, Double],
    outcome: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String]
  ): MatchDecision =
    MatchDecision(
      sourceId = p.source.mentionId,
      targetId = p.target.mentionId,
      features = features,
      decision = outcome,
      confidence = math.min(1.0, math.max(0.0, confidence)),
      reasonCodes = reasonCodes,
      policyVersion = policy.policyVersion,
      runId = runId
    )

object MentionResolver:

  /**
   * Mention-level policy. UNCALIBRATED: weights and thresholds are engineering defaults, not fitted
   * against a human-reviewed gold set.
   */
  val MentionWeights: Map[String, Double] = Map(
    "name_similarity"  -> 0.40,
    "coauthor_overlap" -> 0.30,
    "venue_match"      -> 0.15,
    "year_proximity"   -> 0.10,
    "title_jaccard"    -> 0.05
  )

  def mentionPolicy: ScoringPolicy = ScoringPolicy(
    policyVersion = "er-mention-v1-uncalibrated",
    weights = MentionWeights,
    acceptThreshold = 0.70,
    rejectThreshold = 0.35,
    unresolvedBandMin = 0.45,
    unresolvedBandMax = 0.65
  )

  /**
   * Given-name compatibility of two normalized "given ... surname" names. The first given-name
   * tokens must be equal, or one must be a single-letter initial of the other, or the full given
   * names must be equal once spaces are removed ("tsong yueh" vs "tsongyueh"). A name with no
   * given-name token is compatible with anything.
   */
  def givenNamesCompatible(a: String, b: String): Boolean =
    val ga = a.split("\\s+").filter(_.nonEmpty).dropRight(1)
    val gb = b.split("\\s+").filter(_.nonEmpty).dropRight(1)
    if ga.isEmpty || gb.isEmpty then true
    else
      val x = ga.head
      val y = gb.head
      x == y ||
      (x.length == 1 && y.startsWith(x)) ||
      (y.length == 1 && x.startsWith(y)) ||
      ga.mkString == gb.mkString

  /** |A intersect B| / min(|A|, |B|); 0.0 when either set is empty. */
  def overlapCoefficient(a: Set[String], b: Set[String]): Double =
    if a.isEmpty || b.isEmpty then 0.0
    else a.intersect(b).size.toDouble / math.min(a.size, b.size).toDouble

  /** 1.0 same suffix, 0.0 different suffixes, 0.5 when either side has none. */
  def suffixFeature(a: MentionContext, b: MentionContext): Double =
    (a.dblpHomonymSuffix, b.dblpHomonymSuffix) match
      case (Some(x), Some(y)) => if x == y then 1.0 else 0.0
      case _                  => 0.5
