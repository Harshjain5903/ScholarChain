package edu.scholarchain.rag

import edu.scholarchain.core.*

/**
 * Canonical, parseable text of structured evidence. [[EvidenceBuilderAgent]] writes it, and the
 * extractive synthesizer and [[ClaimVerificationAgent]] read it back, so every number a claim may
 * state comes from exactly one rendering.
 */
object EvidenceContent:

  /** Metric fields as written in evidence text, in display order. */
  object Field:
    val Publications             = "publications"
    val DistinctCoauthors        = "distinctCoauthors"
    val TotalCoauthorOccurrences = "totalCoauthorOccurrences"
    val AverageCoauthorsPerPaper = "averageCoauthorsPerPaper"
    val MaxCoauthorsOnOnePaper   = "maximumCoauthorsOnOnePaper"
    val MaxTeamWorkIds           = "maximumTeamWorkIds"
    val WeightedDegree           = "weightedDegree"
    val ComponentId              = "componentId"
    val ComponentSize            = "componentSize"
    val CommunityId              = "communityId"
    val BridgeScore              = "bridgeScore"
    val BridgeScoreMethod        = "bridgeScoreMethod"
    val Coreness                 = "coreness"
    val MetricVersion            = "metricVersion"
    val RunId                    = "runId"
    val Rank                     = "rank"
    val RankedBy                 = "rankedBy"
    val JointPublications        = "jointPublications"
    val FirstYear                = "firstYear"
    val LastYear                 = "lastYear"
    val MemberCount              = "memberCount"
    val CrossCommunityEdges      = "crossCommunityEdges"

  /** Value written for a bridge score that was not computed (never a measured zero). */
  val NotComputed: String = BridgeScoreMethod.NotComputed

  case class MetricFacts(
    researcherId: String,
    windowStart: Int,
    windowEnd: Int,
    values: Map[String, String]
  ):
    def number(field: String): Option[BigDecimal] = values.get(field).flatMap(parseNumber)

    /** Work IDs responsible for M_r (empty when none were recorded). */
    def maximumTeamWorkIds: Vector[String] =
      values
        .get(Field.MaxTeamWorkIds)
        .toVector
        .flatMap(_.split('|').toVector)
        .map(_.trim)
        .filter(_.nonEmpty)

    def bridgeMethod: Option[String] = values.get(Field.BridgeScoreMethod)

    /** True when the bridge score is a not-computed placeholder, not a measurement. */
    def bridgeNotComputed: Boolean =
      values.get(Field.BridgeScore).contains(NotComputed) || bridgeMethod.contains(NotComputed)

    def rank: Option[Int] = values.get(Field.Rank).flatMap(_.toIntOption)

  case class PathFacts(fromId: String, toId: String, hops: Int, nodes: Vector[String])

  case class CoauthorFacts(
    researcherA: String,
    researcherB: String,
    jointPublications: Int,
    firstYear: Int,
    lastYear: Int
  )

  def parseNumber(s: String): Option[BigDecimal] =
    scala.util.Try(BigDecimal(s)).toOption

  private def num(d: Double): String =
    if d == math.rint(d) && !d.isInfinite then BigDecimal(d).setScale(1).toString
    else
      BigDecimal(d).round(new java.math.MathContext(6)).bigDecimal.stripTrailingZeros.toPlainString

  // ---- metrics ----

  private val MetricPattern = """^Researcher (\S+) window=(\d{4})-(\d{4}): (.*)$""".r

  /** Separator between work IDs in the `maximumTeamWorkIds` field. */
  val WorkIdSeparator: String = "|"

  /** Field values may not contain the field separators; IDs never do in practice. */
  private def safe(v: String): String = v.replaceAll("[\\s,=|]", "_")

  /**
   * Metric evidence for one researcher: every stored value, the window, the provenance (metric
   * version = `schemaVersion`, run ID = `pipelineRunId`) and, for rankings, the rank and the
   * allowlisted metric ranked by. A not-computed bridge score is written as `not-computed`, never
   * as a number. Absent optional values (component, community, component size) are omitted.
   */
  def metric(
    m: ResearcherMetrics,
    rank: Option[Int] = None,
    rankedBy: Option[String] = None
  ): String =
    import Field.*
    val bridge =
      if m.bridgeScoreMethod == NotComputed then NotComputed
      else num(m.bridgeScore)
    val fields = Vector(
      Some(Publications             -> m.publicationCount.toString),
      Some(DistinctCoauthors        -> m.distinctCoauthorCount.toString),
      Some(TotalCoauthorOccurrences -> m.totalCoauthorOccurrences.toString),
      Some(AverageCoauthorsPerPaper -> num(m.averageCoauthorsPerPaper)),
      Some(MaxCoauthorsOnOnePaper   -> m.maximumCoauthorsOnOnePaper.toString),
      Option.when(m.maximumTeamWorkIds.nonEmpty)(
        MaxTeamWorkIds -> m.maximumTeamWorkIds.map(safe).mkString(WorkIdSeparator)
      ),
      Some(WeightedDegree -> num(m.weightedDegree)),
      m.componentId.filter(_.nonEmpty).map(c => ComponentId -> safe(c)),
      m.componentSize.map(n => ComponentSize -> n.toString),
      m.communityId.filter(_.nonEmpty).map(c => CommunityId -> safe(c)),
      Some(BridgeScore             -> bridge),
      Some(Field.BridgeScoreMethod -> m.bridgeScoreMethod),
      Some(Coreness                -> m.coreness.toString),
      Some(MetricVersion           -> safe(m.provenance.schemaVersion)),
      Some(RunId                   -> safe(m.provenance.pipelineRunId)),
      rank.map(r => Rank -> r.toString),
      rankedBy.map(r => RankedBy -> safe(r))
    ).flatten
    s"Researcher ${m.researcherId} window=${m.windowStartYear}-${m.windowEndYear}: " +
      fields.map((k, v) => s"$k=$v").mkString(", ")

  def parseMetric(content: String): Option[MetricFacts] =
    content match
      case MetricPattern(id, start, end, rest) =>
        val values = rest
          .split(", ")
          .toVector
          .flatMap { kv =>
            kv.split("=", 2) match
              case Array(k, v) => Some(k.trim -> v.trim)
              case _           => None
          }
          .toMap
        Some(MetricFacts(id, start.toInt, end.toInt, values))
      case _ => None

  // ---- paths ----

  private val PathPattern = """^Path (\S+) to (\S+), hops=(\d+), nodes (.*)$""".r

  def path(fromId: String, toId: String, nodes: Vector[String]): String =
    s"Path $fromId to $toId, hops=${math.max(0, nodes.size - 1)}, nodes ${nodes.mkString(" -> ")}"

  def parsePath(content: String): Option[PathFacts] =
    content match
      case PathPattern(from, to, hops, chain) =>
        Some(PathFacts(from, to, hops.toInt, chain.split(" -> ").toVector.map(_.trim)))
      case _ => None

  // ---- coauthor edges ----

  private val CoauthorPattern =
    """^Coauthor edge (\S+) -- (\S+): jointPublications=(\d+), firstYear=(\d+), lastYear=(\d+)$""".r

  def coauthor(e: CoauthorEdge): String =
    s"Coauthor edge ${e.researcherA} -- ${e.researcherB}: " +
      s"${Field.JointPublications}=${e.jointPublicationCount}, " +
      s"${Field.FirstYear}=${e.firstCollaborationYear}, ${Field.LastYear}=${e.lastCollaborationYear}"

  def parseCoauthor(content: String): Option[CoauthorFacts] =
    content match
      case CoauthorPattern(a, b, j, f, l) => Some(CoauthorFacts(a, b, j.toInt, f.toInt, l.toInt))
      case _                              => None

  // ---- communities ----

  def community(c: CommunityInfo): String =
    s"Community ${c.communityId}: ${Field.MemberCount}=${c.memberCount}, " +
      s"${Field.CrossCommunityEdges}=${c.crossCommunityEdges}, " +
      s"queriedMembers=${c.researcherIds.mkString("|")}"
