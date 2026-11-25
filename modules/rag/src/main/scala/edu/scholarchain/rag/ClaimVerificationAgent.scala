package edu.scholarchain.rag

import edu.scholarchain.core.*

/**
 * Verification of one claim: its status and the deterministic reasons behind it.
 *
 * @param reasons
 *   one entry per failed or conflicting check (empty when VERIFIED)
 */
case class ClaimCheck(claim: AnswerClaim, status: VerificationStatus, reasons: Vector[String])

/**
 * Deterministically checks each claim against the evidence it cites. No model is involved.
 *
 * Checks, all against the CITED evidence items only:
 *   - evidence existence: every cited ID is in the bundle;
 *   - researcher IDs: every `researcher:<scheme>:<id>` token, and every bundle entity ID, named in
 *     the statement appears in cited evidence;
 *   - numbers: every non-year number appears (numerically) in cited evidence text;
 *   - time windows: every year lies inside a cited metric window, or appears in cited text;
 *   - path hops: every `a -> b` hop in the statement is consecutive in a cited path;
 *   - metric values: a number next to a metric name (publications, distinct coauthors, average
 *     coauthors per paper, total coauthor occurrences, maximum coauthors on one paper, weighted
 *     degree, component size, bridge score, coreness, rank, joint publications, hops) must equal
 *     that field in the cited evidence, even if the same number appears elsewhere in it;
 *   - M_r work IDs: every `work:...` ID in the statement appears in cited evidence;
 *   - component and community IDs named in the statement are the cited ones;
 *   - bridge scores: a numeric bridge score for a cited `not-computed` score, a "not computed"
 *     statement for a measured one, or a different bridge method, is a conflict.
 *
 * Status:
 *   - CONFLICTING: a metric value, M_r work ID, component/community ID or bridge method differs
 *     from the cited metric evidence;
 *   - UNSUPPORTED: no cited evidence exists, or every factual check failed;
 *   - PARTIALLY_SUPPORTED: some cited IDs are unknown, or some checks failed and some passed;
 *   - VERIFIED: everything cited exists and every check passed.
 */
class ClaimVerificationAgent:

  def verify(
    claims: Vector[AnswerClaim],
    evidence: EvidenceBundle
  ): Vector[AnswerClaim] =
    verifyDetailed(claims, evidence).map(c => c.claim.copy(verificationStatus = c.status))

  def verifyDetailed(claims: Vector[AnswerClaim], evidence: EvidenceBundle): Vector[ClaimCheck] =
    val byId = evidence.items.map(i => i.evidenceId -> i).toMap
    val bundleEntityIds =
      evidence.items.map(_.sourceEntityId).filter(_.nonEmpty).toSet ++
        evidence.items.flatMap(i => ClaimVerificationAgent.researcherIds(i.content))
    claims.map(c => check(c, byId, bundleEntityIds))

  private def check(
    claim: AnswerClaim,
    byId: Map[String, EvidenceItem],
    bundleEntityIds: Set[String]
  ): ClaimCheck =
    import ClaimVerificationAgent.*
    val cited   = claim.supportingEvidence.flatMap(byId.get)
    val missing = claim.supportingEvidence.filterNot(byId.contains)
    if cited.isEmpty then
      val why =
        if claim.supportingEvidence.isEmpty then "cites no evidence"
        else s"cited evidence not in bundle: ${missing.mkString(", ")}"
      ClaimCheck(claim, VerificationStatus.Unsupported, Vector(why))
    else
      val text        = claim.statement
      val citedText   = cited.map(_.content).mkString("\n")
      val citedIds    = cited.map(_.sourceEntityId).toSet ++ researcherIds(citedText)
      val metrics     = cited.flatMap(i => EvidenceContent.parseMetric(i.content))
      val paths       = cited.flatMap(i => EvidenceContent.parsePath(i.content).map(_.nodes))
      val edges       = cited.flatMap(i => EvidenceContent.parseCoauthor(i.content))
      val evidenceNos = numbers(stripIds(citedText))

      // researcher IDs
      val namedIds =
        (researcherIds(text) ++ bundleEntityIds.filter(id => mentions(text, id))).distinct
      val idResults =
        namedIds.map { id =>
          (
            citedIds.contains(id) || mentions(citedText, id),
            s"entity ID $id not in cited evidence"
          )
        }

      // numbers and years
      val stripped        = stripIds(text)
      val (ranges, rest)  = yearRanges(stripped)
      val statementNos    = numbers(rest)
      val (years, values) = statementNos.partition(isYear)
      val allYears        = years ++ ranges.flatMap((a, b) => Vector(BigDecimal(a), BigDecimal(b)))
      val windows         = metrics.map(m => (m.windowStart, m.windowEnd))
      val yearResults = allYears.distinct.map { y =>
        val inWindow = windows.exists((s, e) => y >= s && y <= e)
        (inWindow || evidenceNos.contains(y), s"year $y outside cited windows")
      }
      val unmatched = values.distinct.filterNot(evidenceNos.contains)
      val valueResults = values.distinct.map { v =>
        (!unmatched.contains(v), s"number $v not in cited evidence")
      }

      // path hops
      val hops = hopPairs(text)
      val hopResults = hops.map { (a, b) =>
        val adjacent =
          paths.exists(p => p.sliding(2).exists(w => w == Vector(a, b) || w == Vector(b, a))) ||
            edges.exists(e => Set(e.researcherA, e.researcherB) == Set(a, b))
        (adjacent, s"hop $a -> $b not in a cited path")
      }

      // M_r work IDs
      val citedWorkIds = workIds(citedText).toSet
      val statedWorks  = workIds(text).distinct
      val mrWorkIds    = metrics.flatMap(_.maximumTeamWorkIds).toSet
      val workResults =
        statedWorks.map(w => (citedWorkIds.contains(w), s"work ID $w not in cited evidence"))
      val workConflicts = statedWorks.filterNot(citedWorkIds.contains).collect {
        case w if metrics.exists(_.values.contains(EvidenceContent.Field.MaxCoauthorsOnOnePaper)) =>
          val known =
            if mrWorkIds.isEmpty then "none recorded" else mrWorkIds.toVector.sorted.mkString(", ")
          s"stated work ID $w but cited maximum-team work IDs are $known"
      }

      // component and community IDs
      val groupRefs = groupIds(text)
      val groupResults = groupRefs.map { (kind, id) =>
        (mentions(citedText, id), s"$kind ID $id not in cited evidence")
      }
      val groupConflicts = groupRefs.flatMap { (kind, id) =>
        val field =
          if kind == "component" then EvidenceContent.Field.ComponentId
          else EvidenceContent.Field.CommunityId
        val known = metrics.flatMap(_.values.get(field)).distinct
        Option.when(known.nonEmpty && !known.contains(id) && !mentions(citedText, id))(
          s"stated $kind $id but cited evidence has ${known.mkString(", ")}"
        )
      }

      val conflicts =
        conflictsFor(rest, values.distinct, metrics, paths.map(p => p.size - 1), edges) ++
          workConflicts ++ groupConflicts ++ bridgeConflicts(text, metrics)
      val results =
        idResults ++ yearResults ++ valueResults ++ hopResults ++ workResults ++ groupResults
      val failures = results.filterNot(_._1).map(_._2)
      val passed   = results.count(_._1)
      val missingReason =
        if missing.nonEmpty then Vector(s"cited evidence not in bundle: ${missing.mkString(", ")}")
        else Vector.empty

      if conflicts.nonEmpty then
        ClaimCheck(claim, VerificationStatus.Conflicting, conflicts ++ failures ++ missingReason)
      else if failures.isEmpty && missing.isEmpty then
        ClaimCheck(claim, VerificationStatus.Verified, Vector.empty)
      else if failures.nonEmpty && passed == 0 then
        ClaimCheck(claim, VerificationStatus.Unsupported, failures ++ missingReason)
      else ClaimCheck(claim, VerificationStatus.PartiallySupported, failures ++ missingReason)

object ClaimVerificationAgent:

  private val ResearcherIdPattern =
    """researcher:(?:orcid|openalex|sc):[A-Za-z0-9._\-]*[A-Za-z0-9]""".r
  private val OtherIdPattern = """(?:evidence|work|run|chunk|coauthor-edge):\S*[A-Za-z0-9]""".r
  private val NumberPattern  = """(?<![\w:.\-/])\d+(?:\.\d+)?(?![\w:])""".r
  private val WorkIdPattern  = """work:[^\s,;()|]*[A-Za-z0-9]""".r

  private val GroupIdPattern =
    """(?i)\b(component|community)\s+([A-Za-z0-9][\w:.\-/]*[A-Za-z0-9]|[A-Za-z0-9])""".r

  private val MethodPattern =
    """(?i)\bmethod\s+(exact|sampled|not-computed)(?![\w-])""".r
  private val NotComputedPattern = """(?i)bridge\s+score\s+(?:was\s+|is\s+)?not[\s-]+computed""".r

  private val YearRangePattern =
    """(?<![\w:.\-/])((?:19|20)\d{2})\s*(?:-|–|to|and)\s*((?:19|20)\d{2})(?![\w:])""".r
  private val HopChainPattern = """[^\s>]+(?:\s*(?:->|→)\s*[^\s>]+)+""".r

  /** Statement metric vocabulary: phrase -> evidence field. */
  private val MetricWords: Vector[(String, String)] =
    import EvidenceContent.Field.*
    Vector(
      "joint publication"           -> JointPublications,
      "average coauthors per paper" -> AverageCoauthorsPerPaper,
      "total coauthor occurrences"  -> TotalCoauthorOccurrences,
      "component size"              -> ComponentSize,
      "rank"                        -> Rank,
      "distinct coauthor"           -> DistinctCoauthors,
      "coauthors on one paper"      -> MaxCoauthorsOnOnePaper,
      "weighted degree"             -> WeightedDegree,
      "bridge score"                -> BridgeScore,
      "coreness"                    -> Coreness,
      "publication"                 -> Publications,
      "coauthor"                    -> DistinctCoauthors,
      "collaborator"                -> DistinctCoauthors
    )

  def researcherIds(text: String): Vector[String] =
    ResearcherIdPattern.findAllIn(text).toVector.distinct

  /** `work:...` IDs in the text (evidence separates M_r work IDs with `|`). */
  def workIds(text: String): Vector[String] = WorkIdPattern.findAllIn(text).toVector.distinct

  /**
   * `(kind, id)` for every "component X" / "community X" whose X looks like an identifier (has a
   * digit or a colon and is not a bare number, which the number checks cover).
   */
  def groupIds(text: String): Vector[(String, String)] =
    GroupIdPattern
      .findAllMatchIn(text)
      .map(m => (m.group(1).toLowerCase, m.group(2)))
      .filter((_, id) => (id.exists(_.isDigit) || id.contains(':')) && !id.forall(_.isDigit))
      .toVector
      .distinct

  /** Bridge-method and not-computed conflicts against the cited metric evidence. */
  private def bridgeConflicts(
    text: String,
    metrics: Vector[EvidenceContent.MetricFacts]
  ): Vector[String] =
    val methods = metrics.flatMap(_.bridgeMethod).distinct
    val stated  = MethodPattern.findAllMatchIn(text).map(_.group(1).toLowerCase).toVector.distinct
    val methodConflicts = stated.filter(m => methods.nonEmpty && !methods.contains(m)).map { m =>
      s"stated bridge method $m but cited evidence has ${methods.mkString(", ")}"
    }
    val notComputedConflict = Option.when(
      NotComputedPattern.findFirstIn(text).isDefined && metrics.nonEmpty &&
        metrics.forall(m => m.values.contains(EvidenceContent.Field.BridgeScore)) &&
        !metrics.exists(_.bridgeNotComputed)
    )("stated the bridge score was not computed but cited evidence has a measured bridge score")
    methodConflicts ++ notComputedConflict

  /** Remove identifier tokens so their digits are not read as numbers. */
  def stripIds(text: String): String =
    OtherIdPattern.replaceAllIn(ResearcherIdPattern.replaceAllIn(text, " "), " ")

  def numbers(text: String): Vector[BigDecimal] =
    NumberPattern.findAllIn(text).toVector.flatMap(EvidenceContent.parseNumber)

  private def isYear(n: BigDecimal): Boolean =
    n.isWhole && n >= 1900 && n <= 2100

  /** Year ranges like `2015-2025` or `2015 to 2025`, and the text with them removed. */
  def yearRanges(text: String): (Vector[(Int, Int)], String) =
    val ranges =
      YearRangePattern.findAllMatchIn(text).map(m => (m.group(1).toInt, m.group(2).toInt)).toVector
    (ranges, YearRangePattern.replaceAllIn(text, " "))

  private def mentions(text: String, id: String): Boolean =
    val i = text.indexOf(id)
    i >= 0 && {
      val before = if i == 0 then ' ' else text.charAt(i - 1)
      val end    = i + id.length
      val after  = if end >= text.length then ' ' else text.charAt(end)
      !before.isLetterOrDigit && !after.isLetterOrDigit
    }

  /** Consecutive pairs of every `a -> b -> c` chain in the text. */
  def hopPairs(text: String): Vector[(String, String)] =
    HopChainPattern.findAllIn(text).toVector.flatMap { chain =>
      val nodes = chain
        .split("->|→")
        .toVector
        .map(_.trim.stripPrefix("(").replaceAll("[.,;:)]+$", ""))
        .filter(_.nonEmpty)
      nodes.sliding(2).collect { case Vector(a, b) => (a, b) }.toVector
    }

  /** Metric word right after the number ("420 publications") or right before ("coreness of 9"). */
  private def metricNear(text: String, start: Int, end: Int): Option[String] =
    val after =
      text.substring(end, math.min(text.length, end + 30)).toLowerCase.takeWhile(!_.isDigit)
    val before = text
      .substring(math.max(0, start - 30), start)
      .toLowerCase
      .reverse
      .takeWhile(!_.isDigit)
      .reverse
    def near(w: String): Boolean =
      after.trim.startsWith(w) || after.matches(
        s"^\\s*(?:distinct |joint |total )?${java.util.regex.Pattern.quote(w)}.*"
      ) ||
        before.matches(
          s".*${java.util.regex.Pattern.quote(w)}s?(?:\\s+(?:count|score|value))?\\s*(?:of|is|was|=|:|equal to)?\\s*$$"
        )
    MetricWords
      .collectFirst { case (w, f) if near(w) => f }
      .orElse(Option.when(near("hop"))(HopField))

  private val HopField = "hops"

  /**
   * Numbers next to a metric name that differ from that field in the cited evidence. Every non-year
   * number is checked, not only those missing from the evidence text, so a value that happens to
   * appear under another field still conflicts. A numeric bridge score where the cited score is
   * `not-computed` is a conflict: a skipped score is not a measured zero.
   */
  private def conflictsFor(
    text: String,
    candidates: Vector[BigDecimal],
    metrics: Vector[EvidenceContent.MetricFacts],
    hopCounts: Vector[Int],
    edges: Vector[EvidenceContent.CoauthorFacts]
  ): Vector[String] =
    if candidates.isEmpty then Vector.empty
    else
      NumberPattern
        .findAllMatchIn(text)
        .toVector
        .flatMap { m =>
          EvidenceContent.parseNumber(m.matched).filter(candidates.contains).flatMap { n =>
            metricNear(text, m.start, m.end).flatMap { field =>
              val known: Vector[BigDecimal] =
                if field == HopField then hopCounts.map(BigDecimal(_))
                else if field == EvidenceContent.Field.JointPublications then
                  edges.map(e => BigDecimal(e.jointPublications))
                else metrics.flatMap(_.number(field))
              if known.nonEmpty && !known.contains(n) then
                Some(
                  s"stated $n but cited evidence has ${known.distinct.mkString(", ")} for $field"
                )
              else if known.isEmpty && field == EvidenceContent.Field.BridgeScore &&
                metrics.exists(_.bridgeNotComputed)
              then Some(s"stated bridge score $n but the cited bridge score was not computed")
              else None
            }
          }
        }
        .distinct
