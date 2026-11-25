package edu.scholarchain.core

import upickle.default.*

/** The full author team on a single publication. */
case class PublicationTeam(
  workId: String,
  year: Int,
  authorMentions: Vector[AuthorMention],
  teamSize: Int,
  provenance: Provenance
) derives ReadWriter:

  require(
    teamSize == authorMentions.size,
    s"PublicationTeam teamSize ($teamSize) must equal authorMentions.size (${authorMentions.size})"
  )

/**
 * An unresolved coauthor pair from a single publication. The constructor guarantees mentionA <
 * mentionB lexicographically.
 */
case class CoauthorPairCandidate(
  mentionA: String,
  mentionB: String,
  workId: String,
  year: Int,
  provenance: Provenance
):

  require(
    mentionA < mentionB,
    s"CoauthorPairCandidate: mentionA must be < mentionB, got ($mentionA, $mentionB)"
  )

object CoauthorPairCandidate:

  def apply(
    mentionA: String,
    mentionB: String,
    workId: String,
    year: Int,
    provenance: Provenance
  ): CoauthorPairCandidate =
    require(mentionA != mentionB, "CoauthorPairCandidate: self-pairs not allowed")
    if mentionA < mentionB
    then new CoauthorPairCandidate(mentionA, mentionB, workId, year, provenance)
    else new CoauthorPairCandidate(mentionB, mentionA, workId, year, provenance)

  given ReadWriter[CoauthorPairCandidate] =
    readwriter[ujson.Value].bimap[CoauthorPairCandidate](
      c =>
        ujson.Obj(
          "mentionA"   -> ujson.Str(c.mentionA),
          "mentionB"   -> ujson.Str(c.mentionB),
          "workId"     -> ujson.Str(c.workId),
          "year"       -> ujson.Num(c.year.toDouble),
          "provenance" -> writeJs(c.provenance)
        ),
      json =>
        CoauthorPairCandidate(
          json("mentionA").str,
          json("mentionB").str,
          json("workId").str,
          json("year").num.toInt,
          read[Provenance](json("provenance"))
        )
    )

/**
 * An aggregated collaboration edge between two resolved researchers. The constructor guarantees
 * researcherA < researcherB lexicographically and that firstCollaborationYear <=
 * lastCollaborationYear.
 */
case class CoauthorEdge(
  researcherA: String,
  researcherB: String,
  jointPublicationCount: Int,
  firstCollaborationYear: Int,
  lastCollaborationYear: Int,
  representativeWorkIds: Vector[String],
  provenance: Provenance
):

  require(
    researcherA < researcherB,
    s"CoauthorEdge: researcherA must be < researcherB, got ($researcherA, $researcherB)"
  )

object CoauthorEdge:

  def apply(
    researcherA: String,
    researcherB: String,
    jointPublicationCount: Int,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    provenance: Provenance
  ): CoauthorEdge =
    require(
      firstCollaborationYear <= lastCollaborationYear,
      s"firstCollaborationYear ($firstCollaborationYear) must be <= lastCollaborationYear ($lastCollaborationYear)"
    )
    require(researcherA != researcherB, "CoauthorEdge: self-edges not allowed")
    if researcherA < researcherB then
      new CoauthorEdge(
        researcherA,
        researcherB,
        jointPublicationCount,
        firstCollaborationYear,
        lastCollaborationYear,
        representativeWorkIds,
        provenance
      )
    else
      new CoauthorEdge(
        researcherB,
        researcherA,
        jointPublicationCount,
        firstCollaborationYear,
        lastCollaborationYear,
        representativeWorkIds,
        provenance
      )

  given ReadWriter[CoauthorEdge] =
    readwriter[ujson.Value].bimap[CoauthorEdge](
      c =>
        ujson.Obj(
          "researcherA"            -> ujson.Str(c.researcherA),
          "researcherB"            -> ujson.Str(c.researcherB),
          "jointPublicationCount"  -> ujson.Num(c.jointPublicationCount.toDouble),
          "firstCollaborationYear" -> ujson.Num(c.firstCollaborationYear.toDouble),
          "lastCollaborationYear"  -> ujson.Num(c.lastCollaborationYear.toDouble),
          "representativeWorkIds"  -> ujson.Arr(c.representativeWorkIds.map(ujson.Str(_))*),
          "provenance"             -> writeJs(c.provenance)
        ),
      json =>
        CoauthorEdge(
          json("researcherA").str,
          json("researcherB").str,
          json("jointPublicationCount").num.toInt,
          json("firstCollaborationYear").num.toInt,
          json("lastCollaborationYear").num.toInt,
          json("representativeWorkIds").arr.map(_.str).toVector,
          read[Provenance](json("provenance"))
        )
    )

/**
 * Allowed values of [[ResearcherMetrics.bridgeScoreMethod]]: how a researcher's `bridgeScore`
 * (betweenness centrality) was obtained (review finding Pass 6 N52).
 *
 * The values are plain strings rather than an enum so that the field stays a primitive in JSON and
 * Spark rows and existing `ResearcherMetrics(...)` call sites keep compiling unchanged.
 */
object BridgeScoreMethod:

  /** Exact betweenness over the full graph (or component). */
  val Exact: String = "exact"

  /** Approximate betweenness from a sample of source vertices. */
  val Sampled: String = "sampled"

  /**
   * Betweenness was skipped (for example, the graph exceeded the size budget). `bridgeScore` is
   * then a 0.0 placeholder, not a measured zero, and must not be ranked or reported as a value.
   */
  val NotComputed: String = "not-computed"

  /** Every allowed value, in declaration order. */
  val All: Vector[String] = Vector(Exact, Sampled, NotComputed)

  /** True when `method` is one of [[All]] (exact, case-sensitive match). */
  def isValid(method: String): Boolean = All.contains(method)

/**
 * Per-researcher collaboration metrics for a time window.
 *
 * @param maximumTeamWorkIds
 *   work IDs of the paper(s) on which this researcher had `maximumCoauthorsOnOnePaper` coauthors
 *   (the evidence behind M_r). Defaults to empty so JSON written before this field existed still
 *   reads; it must be empty when `maximumCoauthorsOnOnePaper` is 0. Producers may cap its length.
 * @param bridgeScoreMethod
 *   how `bridgeScore` was obtained; one of [[BridgeScoreMethod.All]]. Defaults to
 *   [[BridgeScoreMethod.Exact]] so JSON written before this field existed still reads. When it is
 *   [[BridgeScoreMethod.NotComputed]], `bridgeScore` must be the 0.0 placeholder. Caveat (review
 *   N59): legacy JSON without this field always reads back as `exact`, even when its `bridgeScore`
 *   was a skipped 0.0 placeholder; such records cannot be told apart from a measured zero. A legacy
 *   `bridgeScore` of 0.0 should therefore not be ranked or reported as a measured value.
 * @param componentSize
 *   number of researchers in this researcher's connected component (componentSize(r) in the
 *   README), when known. Defaults to `None` so JSON and constructor calls written before this field
 *   existed still work; when present it must be at least 1 (the component contains `r`).
 */
case class ResearcherMetrics(
  researcherId: String,
  publicationCount: Int,
  distinctCoauthorCount: Int,
  totalCoauthorOccurrences: Int,
  averageCoauthorsPerPaper: Double,
  maximumCoauthorsOnOnePaper: Int,
  weightedDegree: Double,
  componentId: Option[String],
  communityId: Option[String],
  bridgeScore: Double,
  coreness: Int,
  windowStartYear: Int,
  windowEndYear: Int,
  provenance: Provenance,
  maximumTeamWorkIds: Vector[String] = Vector.empty,
  bridgeScoreMethod: String = BridgeScoreMethod.Exact,
  componentSize: Option[Long] = None
) derives ReadWriter:

  require(
    componentSize.forall(_ >= 1L),
    s"componentSize must be >= 1 when present, got ${componentSize.getOrElse(0L)}"
  )

  require(
    windowStartYear <= windowEndYear,
    s"windowStartYear ($windowStartYear) must be <= windowEndYear ($windowEndYear)"
  )

  require(
    maximumCoauthorsOnOnePaper != 0 || maximumTeamWorkIds.isEmpty,
    s"maximumTeamWorkIds must be empty when maximumCoauthorsOnOnePaper is 0, got $maximumTeamWorkIds"
  )

  require(
    BridgeScoreMethod.isValid(bridgeScoreMethod),
    s"bridgeScoreMethod '$bridgeScoreMethod' must be one of ${BridgeScoreMethod.All.mkString(", ")}"
  )

  require(
    bridgeScoreMethod != BridgeScoreMethod.NotComputed || bridgeScore == 0.0,
    s"bridgeScore must be the 0.0 placeholder when bridgeScoreMethod is not-computed, got $bridgeScore"
  )

/**
 * Graph-property wire format for [[ResearcherMetrics]] stored on the Neptune `Researcher` vertex
 * (work order DOM-P13-02). Spark (writer) and storage (reader) must both go through this object so
 * the encoding lives in one place. Keys are [[GraphSchema.PropertyKey.ResearcherMetricKeys]].
 *
 * Encoding of every value as a string:
 *   - `Int` and `Long` fields: decimal digits with an optional leading `-` (`Int.toString`).
 *   - `Double` fields: `Double.toString` (for example `2.86`, `1.0E-4`, `NaN`); the reader also
 *     accepts plain integers such as `3`.
 *   - `Option` fields (`componentId`, `communityId`, `componentSize`): the key is omitted when
 *     `None`. The reader treats an absent key or an empty string as `None`.
 *   - `maximumTeamWorkIds`: a compact JSON array of strings, for example `["W1","W2"]`, always
 *     written (as `[]` when empty). The reader treats an absent key or an empty string as empty.
 *   - `bridgeScoreMethod`: one of [[BridgeScoreMethod.All]], always written. Unlike the JSON codec
 *     the reader does NOT default it to `exact` when absent (review N59): a missing method is an
 *     error so a skipped 0.0 placeholder is never read back as a measured score.
 *
 * `researcherId` and `provenance` are not metric properties: the vertex already carries them under
 * their own keys, so the reader takes them as arguments. Unrelated keys in the property map (for
 * example `displayName`) are ignored.
 */
object ResearcherMetrics:

  private val IntPattern    = "-?[0-9]+".r
  private val DoublePattern = "-?(?:[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?|Infinity)|NaN".r

  /**
   * Encodes `m` as `Researcher` vertex properties in
   * [[GraphSchema.PropertyKey.ResearcherMetricKeys]] order, omitting `None` options.
   *
   * @throws IllegalArgumentException
   *   if `componentId` or `communityId` is `Some("")`, which could not be told apart from `None`
   *   after a round-trip
   */
  def toGraphProperties(m: ResearcherMetrics): Vector[(String, String)] =
    import GraphSchema.PropertyKey as K
    require(
      m.componentId.forall(_.nonEmpty),
      s"ResearcherMetrics ${m.researcherId}: componentId must not be an empty string in graph properties"
    )
    require(
      m.communityId.forall(_.nonEmpty),
      s"ResearcherMetrics ${m.researcherId}: communityId must not be an empty string in graph properties"
    )
    Vector(
      Some(K.PublicationCount           -> m.publicationCount.toString),
      Some(K.DistinctCoauthorCount      -> m.distinctCoauthorCount.toString),
      Some(K.TotalCoauthorOccurrences   -> m.totalCoauthorOccurrences.toString),
      Some(K.AverageCoauthorsPerPaper   -> m.averageCoauthorsPerPaper.toString),
      Some(K.MaximumCoauthorsOnOnePaper -> m.maximumCoauthorsOnOnePaper.toString),
      Some(K.MaximumTeamWorkIds -> ujson.write(ujson.Arr(m.maximumTeamWorkIds.map(ujson.Str(_))*))),
      Some(K.WeightedDegree     -> m.weightedDegree.toString),
      m.componentId.map(K.ComponentId -> _),
      m.componentSize.map(s => K.ComponentSize -> s.toString),
      m.communityId.map(K.CommunityId -> _),
      Some(K.BridgeScore       -> m.bridgeScore.toString),
      Some(K.BridgeScoreMethod -> m.bridgeScoreMethod),
      Some(K.Coreness          -> m.coreness.toString),
      Some(K.WindowStartYear   -> m.windowStartYear.toString),
      Some(K.WindowEndYear     -> m.windowEndYear.toString)
    ).flatten

  /**
   * Decodes metrics from a `Researcher` vertex property map written by [[toGraphProperties]].
   *
   * @return
   *   `Right(metrics)`, or `Left(message)` naming the researcher and every missing or malformed key
   *   (all problems are reported together, separated by `; `), or the violated
   *   [[ResearcherMetrics]] invariant
   */
  def fromGraphProperties(
    researcherId: String,
    properties: Map[String, String],
    provenance: Provenance
  ): Either[String, ResearcherMetrics] =
    import GraphSchema.PropertyKey as K
    val errors = Vector.newBuilder[String]

    def present(key: String): Option[String] = properties.get(key).filter(_.nonEmpty)

    def required[A](key: String, kind: String)(parse: String => Option[A]): Option[A] =
      properties.get(key) match
        case None    => errors += s"missing required key '$key'"; None
        case Some(v) => parseOrRecord(key, kind, v)(parse)

    def optional[A](key: String, kind: String)(parse: String => Option[A]): Option[Option[A]] =
      present(key) match
        case None    => Some(None)
        case Some(v) => parseOrRecord(key, kind, v)(parse).map(Some(_))

    def parseOrRecord[A](key: String, kind: String, v: String)(parse: String => Option[A]) =
      val parsed = parse(v)
      if parsed.isEmpty then errors += s"key '$key' must be $kind, got '${truncate(v)}'"
      parsed

    def int(v: String): Option[Int]   = if IntPattern.matches(v) then v.toIntOption else None
    def long(v: String): Option[Long] = if IntPattern.matches(v) then v.toLongOption else None
    def double(v: String): Option[Double] =
      if DoublePattern.matches(v) then v.toDoubleOption else None
    def text(v: String): Option[String]   = Some(v)
    def method(v: String): Option[String] = Some(v).filter(BridgeScoreMethod.isValid)
    def workIds(v: String): Option[Vector[String]] =
      scala.util.Try(ujson.read(v)).toOption.flatMap {
        case ujson.Arr(items) if items.forall(_.strOpt.isDefined) =>
          Some(items.map(_.str).toVector)
        case _ => None
      }

    val publicationCount  = required(K.PublicationCount, "an integer")(int)
    val distinctCoauthors = required(K.DistinctCoauthorCount, "an integer")(int)
    val totalOccurrences  = required(K.TotalCoauthorOccurrences, "an integer")(int)
    val average           = required(K.AverageCoauthorsPerPaper, "a number")(double)
    val maxCoauthors      = required(K.MaximumCoauthorsOnOnePaper, "an integer")(int)
    val teamWorkIds =
      optional(K.MaximumTeamWorkIds, "a JSON array of strings")(workIds)
        .map(_.getOrElse(Vector.empty))
    val weightedDegree = required(K.WeightedDegree, "a number")(double)
    val componentId    = optional(K.ComponentId, "a string")(text)
    val componentSize  = optional(K.ComponentSize, "a 64-bit integer")(long)
    val communityId    = optional(K.CommunityId, "a string")(text)
    val bridgeScore    = required(K.BridgeScore, "a number")(double)
    val bridgeMethod = required(
      K.BridgeScoreMethod,
      s"one of ${BridgeScoreMethod.All.mkString(", ")}"
    )(method)
    val coreness    = required(K.Coreness, "an integer")(int)
    val windowStart = required(K.WindowStartYear, "an integer")(int)
    val windowEnd   = required(K.WindowEndYear, "an integer")(int)

    val problems = errors.result()
    val prefix   = s"invalid ResearcherMetrics graph properties for researcher '$researcherId'"
    if problems.nonEmpty then Left(s"$prefix: ${problems.mkString("; ")}")
    else
      scala.util
        .Try(
          ResearcherMetrics(
            researcherId = researcherId,
            publicationCount = publicationCount.get,
            distinctCoauthorCount = distinctCoauthors.get,
            totalCoauthorOccurrences = totalOccurrences.get,
            averageCoauthorsPerPaper = average.get,
            maximumCoauthorsOnOnePaper = maxCoauthors.get,
            weightedDegree = weightedDegree.get,
            componentId = componentId.get,
            communityId = communityId.get,
            bridgeScore = bridgeScore.get,
            coreness = coreness.get,
            windowStartYear = windowStart.get,
            windowEndYear = windowEnd.get,
            provenance = provenance,
            maximumTeamWorkIds = teamWorkIds.get,
            bridgeScoreMethod = bridgeMethod.get,
            componentSize = componentSize.get
          )
        )
        .toEither
        .left
        .map(e => s"$prefix: ${e.getMessage}")

  private def truncate(v: String): String = if v.length <= 80 then v else v.take(80) + "..."
