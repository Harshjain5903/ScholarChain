package edu.scholarchain.storage

import edu.scholarchain.core.*

import java.util.Locale

/**
 * A Neptune openCypher response, or an entity inside it, that does not have the expected shape or
 * lacks a required property. The message names the entity (by `~id` when known) and the property;
 * it never echoes property values.
 */
final class NeptuneResponseFormatException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

/**
 * Parsing helpers for Neptune openCypher JSON responses (review finding N41).
 *
 * Neptune's openCypher HTTPS endpoint wraps rows as `{"results":[{<column>: <value>, ...}]}`. Each
 * returned node or relationship is an entity object:
 * {{{
 *   {"~id": "...", "~entityType": "node", "~labels": ["Researcher"], "~properties": {...}}
 *   {"~id": "...", "~entityType": "relationship", "~start": "...", "~end": "...",
 *    "~type": "COAUTHORED_WITH", "~properties": {...}}
 * }}}
 * A path value is a JSON array alternating node, relationship, node, ... entities.
 *
 * Properties are read by their [[GraphSchema.PropertyKey]] names. The Spark `NeptuneBulkLoadExport`
 * stores `provenance` (an object) and `representativeWorkIds` (a list) as JSON text in String
 * properties, so both are decoded from a JSON string; native JSON objects and arrays are accepted
 * too. Integer properties accept JSON numbers (the bulk-load `:Int` type) or numeric strings.
 *
 * Missing descriptive properties (`displayName`; `title`, `year`, `venue`) do not fail parsing.
 * Missing identity keys, a missing `provenance`, or any missing key of
 * [[GraphSchema.PropertyKey.CoauthorEdgeKeys]] on a `COAUTHORED_WITH` relationship fails with
 * [[NeptuneResponseFormatException]].
 *
 * A plain JSON object without `~` system keys (for example a map projection) is read as the
 * property map itself.
 */
object NeptuneResponseParser:

  private val Key   = GraphSchema.PropertyKey
  private val Label = GraphSchema.VertexLabel
  private val Rel   = GraphSchema.EdgeType

  /** Result-column aliases shared by the openCypher queries and this parser. */
  object Column:
    val Researcher: String    = "r"
    val Work: String          = "w"
    val Edge: String          = "e"
    val Path: String          = "path"
    val Neighbor: String      = "neighbor"
    val ResearcherIds: String = "rid"

    /** A raw openCypher path value (`RETURN p`): alternating node and relationship entities. */
    val PathEntities: String = "p"

    /** Every column alias, in declaration order. */
    val All: Vector[String] =
      Vector(Researcher, Work, Edge, Path, Neighbor, ResearcherIds, PathEntities)

  /** Neptune openCypher entity system keys and `~entityType` values. */
  object Entity:
    val Id: String           = "~id"
    val EntityType: String   = "~entityType"
    val Labels: String       = "~labels"
    val Properties: String   = "~properties"
    val Start: String        = "~start"
    val End: String          = "~end"
    val Type: String         = "~type"
    val Node: String         = "node"
    val Relationship: String = "relationship"

  /** A node or relationship entity decoded into its system fields and property map. */
  final case class NeptuneEntity(
    id: Option[String],
    entityType: Option[String],
    labels: Vector[String],
    relType: Option[String],
    start: Option[String],
    end: Option[String],
    properties: Map[String, ujson.Value]
  ):

    /** Short description for error messages: kind plus `~id` when known. */
    def describe(kind: String): String = id.fold(kind)(i => s"$kind '$i'")

    def isNode: Boolean =
      entityType.contains(Entity.Node) || (entityType.isEmpty && labels.nonEmpty)

    def isRelationship: Boolean =
      entityType.contains(Entity.Relationship) || (entityType.isEmpty && relType.isDefined)

  private def fail(message: String, cause: Throwable = null): Nothing =
    throw NeptuneResponseFormatException(message, cause)

  private def kindOf(v: ujson.Value): String = v match
    case _: ujson.Obj  => "object"
    case _: ujson.Arr  => "array"
    case _: ujson.Str  => "string"
    case _: ujson.Num  => "number"
    case _: ujson.Bool => "boolean"
    case ujson.Null    => "null"

  private def hasSystemKeys(o: ujson.Obj): Boolean = o.value.keysIterator.exists(_.startsWith("~"))

  /** Decode a value into a [[NeptuneEntity]]; `what` names it in error messages. */
  def entity(value: ujson.Value, what: String): NeptuneEntity =
    value match
      case o: ujson.Obj =>
        val m                              = o.value
        def sys(k: String): Option[String] = m.get(k).collect { case ujson.Str(s) => s }
        val labels = m.get(Entity.Labels) match
          case Some(ujson.Arr(items)) => items.collect { case ujson.Str(s) => s }.toVector
          case Some(ujson.Str(s))     => Vector(s)
          case _                      => Vector.empty
        val props: Map[String, ujson.Value] =
          if !hasSystemKeys(o) then m.toMap
          else
            m.get(Entity.Properties) match
              case Some(p: ujson.Obj)      => p.value.toMap
              case None | Some(ujson.Null) => Map.empty
              case Some(other) =>
                fail(s"$what: '${Entity.Properties}' must be an object, got ${kindOf(other)}")
        NeptuneEntity(
          id = sys(Entity.Id),
          entityType = sys(Entity.EntityType),
          labels = labels,
          relType = sys(Entity.Type),
          start = sys(Entity.Start),
          end = sys(Entity.End),
          properties = props.filter((_, v) => v != ujson.Null)
        )
      case other => fail(s"$what: expected a JSON object, got ${kindOf(other)}")

  // ---- typed property readers ----

  private def missing(ctx: String, key: String): Nothing =
    fail(s"$ctx is missing required property '$key'")

  private def numberText(d: Double): String =
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString else d.toString

  private def stringValue(ctx: String, key: String, v: ujson.Value): String = v match
    case ujson.Str(s) => s
    case n: ujson.Num => numberText(n.num)
    case other        => fail(s"$ctx: property '$key' must be a string, got ${kindOf(other)}")

  private def intValue(ctx: String, key: String, v: ujson.Value): Int = v match
    case ujson.Num(d) if d.isWhole && d >= Int.MinValue && d <= Int.MaxValue => d.toInt
    case ujson.Str(s) if s.trim.toIntOption.isDefined                        => s.trim.toInt
    case other => fail(s"$ctx: property '$key' must be an integer, got ${kindOf(other)}")

  /** Required string property. */
  def requiredString(props: Map[String, ujson.Value], ctx: String, key: String): String =
    props.get(key).map(stringValue(ctx, key, _)).getOrElse(missing(ctx, key))

  /** Optional string property; absent, null and empty values are None. */
  def optionalString(props: Map[String, ujson.Value], ctx: String, key: String): Option[String] =
    props.get(key).map(stringValue(ctx, key, _)).filter(_.nonEmpty)

  /** Required integer property (JSON number or numeric string). */
  def requiredInt(props: Map[String, ujson.Value], ctx: String, key: String): Int =
    props.get(key).map(intValue(ctx, key, _)).getOrElse(missing(ctx, key))

  /** Optional integer property; absent, null and empty-string values are None. */
  def optionalInt(props: Map[String, ujson.Value], ctx: String, key: String): Option[Int] =
    props.get(key) match
      case None | Some(ujson.Str("")) => None
      case Some(v)                    => Some(intValue(ctx, key, v))

  /** A JSON value that may have been stored as JSON text in a String property. */
  private def decodeJsonText(ctx: String, key: String, v: ujson.Value): ujson.Value = v match
    case ujson.Str(text) =>
      try ujson.read(text)
      catch case e: Exception => fail(s"$ctx: property '$key' is a string but not valid JSON", e)
    case other => other

  /** Required list-of-strings property, as a native array or JSON-array text. */
  def requiredStringVector(
    props: Map[String, ujson.Value],
    ctx: String,
    key: String
  ): Vector[String] =
    props.get(key) match
      case None => missing(ctx, key)
      case Some(raw) =>
        decodeJsonText(ctx, key, raw) match
          case ujson.Arr(items) =>
            items.map {
              case ujson.Str(s) => s
              case other =>
                fail(s"$ctx: property '$key' must hold strings, found ${kindOf(other)}")
            }.toVector
          case other => fail(s"$ctx: property '$key' must be a JSON array, got ${kindOf(other)}")

  /** Required [[Provenance]] property, as a native object or JSON-object text. */
  def requiredProvenance(props: Map[String, ujson.Value], ctx: String): Provenance =
    val key = Key.Provenance
    props.get(key) match
      case None => missing(ctx, key)
      case Some(raw) =>
        decodeJsonText(ctx, key, raw) match
          case o: ujson.Obj =>
            try upickle.default.read[Provenance](o)
            catch
              case e: Exception =>
                fail(s"$ctx: property '$key' is not a valid Provenance: ${e.getMessage}", e)
          case other =>
            fail(s"$ctx: property '$key' must be a JSON object, got ${kindOf(other)}")

  // ---- entity decoders ----

  private def requireNodeLabel(e: NeptuneEntity, label: String, ctx: String): Unit =
    if e.isRelationship then fail(s"$ctx: expected a node, got a relationship")
    if e.labels.nonEmpty && !e.labels.contains(label) then
      fail(s"$ctx: expected label '$label', got ${e.labels.mkString("[", ", ", "]")}")

  /**
   * Decode a `Researcher` node. `researcherId` and `provenance` are required; a missing
   * `displayName` becomes `""`. Fields Neptune does not store (`orcid`, `openAlexId`,
   * `semanticScholarId`, `alternateNames`) are empty.
   */
  def researcherFromEntity(value: ujson.Value): CanonicalResearcher =
    val e   = entity(value, s"${Label.Researcher} node")
    val ctx = e.describe(s"${Label.Researcher} node")
    requireNodeLabel(e, Label.Researcher, ctx)
    val p = e.properties
    CanonicalResearcher(
      researcherId = requiredString(p, ctx, Key.ResearcherId),
      displayName = optionalString(p, ctx, Key.DisplayName).getOrElse(""),
      orcid = None,
      openAlexId = None,
      semanticScholarId = None,
      alternateNames = Vector.empty,
      provenance = requiredProvenance(p, ctx)
    )

  /**
   * Decode a `Work` node. `workId` and `provenance` are required. Missing descriptive properties
   * become `title = ""`, `year = 0` (unknown) and `venueId = None`; `venue` maps to `venueId`.
   * `source` and `sourceRecordId` are recovered from a `work:<source>:<recordId>` work ID (the
   * `IdGen.workId` format); otherwise `source` is `ScholarChain` and `sourceRecordId` comes from
   * the provenance. Fields Neptune does not store (`doi`, `arxivId`, `authors`, `abstractTextRef`)
   * are empty.
   */
  def workFromEntity(value: ujson.Value): WorkRecord =
    val e   = entity(value, s"${Label.Work} node")
    val ctx = e.describe(s"${Label.Work} node")
    requireNodeLabel(e, Label.Work, ctx)
    val p          = e.properties
    val workId     = requiredString(p, ctx, Key.WorkId)
    val provenance = requiredProvenance(p, ctx)
    val title      = optionalString(p, ctx, Key.Title).getOrElse("")
    val year       = optionalInt(p, ctx, Key.Year).getOrElse(0)
    if year < 0 then fail(s"$ctx: property '${Key.Year}' must be non-negative")
    val (source, sourceRecordId) =
      sourceOf(workId).getOrElse((SourceType.ScholarChain, provenance.sourceRecordId))
    WorkRecord(
      workId = workId,
      source = source,
      sourceRecordId = sourceRecordId,
      title = title,
      normalizedTitle = title.trim.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "),
      year = year,
      venueId = optionalString(p, ctx, Key.Venue),
      doi = None,
      arxivId = None,
      authors = Vector.empty,
      abstractTextRef = None,
      provenance = provenance
    )

  private def sourceOf(workId: String): Option[(SourceType, String)] =
    workId.split(":", 3) match
      case Array("work", src, rec) if rec.nonEmpty =>
        SourceType.values.find(_.toString.equalsIgnoreCase(src)).map(_ -> rec)
      case _ => None

  /**
   * Decode a `COAUTHORED_WITH` relationship. Every key in
   * [[GraphSchema.PropertyKey.CoauthorEdgeKeys]] is required. A relationship whose `~type` is
   * present and differs from [[GraphSchema.EdgeType.Coauthor]] is rejected.
   */
  def coauthorEdgeFromEntity(value: ujson.Value): CoauthorEdge =
    val e   = entity(value, s"${Rel.Coauthor} relationship")
    val ctx = e.describe(s"${Rel.Coauthor} relationship")
    if e.isNode then fail(s"$ctx: expected a relationship, got a node")
    e.relType.foreach { t =>
      if t != Rel.Coauthor then fail(s"$ctx: expected type '${Rel.Coauthor}', got '$t'")
    }
    val p = e.properties
    // Read in CoauthorEdgeKeys order so the first missing key is reported deterministically.
    val researcherA = requiredString(p, ctx, Key.ResearcherA)
    val researcherB = requiredString(p, ctx, Key.ResearcherB)
    val joint       = requiredInt(p, ctx, Key.JointPublicationCount)
    val first       = requiredInt(p, ctx, Key.FirstCollaborationYear)
    val last        = requiredInt(p, ctx, Key.LastCollaborationYear)
    val workIds     = requiredStringVector(p, ctx, Key.RepresentativeWorkIds)
    val provenance  = requiredProvenance(p, ctx)
    try CoauthorEdge(researcherA, researcherB, joint, first, last, workIds, provenance)
    catch case ex: IllegalArgumentException => fail(s"$ctx: ${ex.getMessage}", ex)

  /**
   * Decode a node into a [[GraphNode]]. `nodeId` falls back to `researcherId`, `workId`, then
   * `~id`; `nodeType` is the first declared label (else the first label). Properties other than
   * `nodeId` and `provenance` are kept as strings (JSON text for non-scalars). A plain object
   * without system keys is read as a serialized [[GraphNode]].
   */
  def graphNodeFromEntity(value: ujson.Value): GraphNode =
    value match
      case o: ujson.Obj if !hasSystemKeys(o) =>
        try upickle.default.read[GraphNode](o)
        catch case ex: Exception => fail(s"graph node: not a valid GraphNode: ${ex.getMessage}", ex)
      case _ =>
        val e   = entity(value, "graph node")
        val ctx = e.describe("graph node")
        if e.isRelationship then fail(s"$ctx: expected a node, got a relationship")
        val p = e.properties
        val nodeId = optionalString(p, ctx, Key.NodeId)
          .orElse(optionalString(p, ctx, Key.ResearcherId))
          .orElse(optionalString(p, ctx, Key.WorkId))
          .orElse(e.id)
          .getOrElse(missing(ctx, Key.NodeId))
        val nodeType = e.labels
          .find(GraphSchema.isDeclaredVertexLabel)
          .orElse(e.labels.headOption)
          .getOrElse(fail(s"$ctx has no '${Entity.Labels}'"))
        val rest = (p - Key.NodeId - Key.Provenance).map { (k, v) =>
          val text = v match
            case ujson.Str(s) => s
            case n: ujson.Num => numberText(n.num)
            case other        => other.render()
          k -> text
        }
        GraphNode(nodeId, nodeType, rest, requiredProvenance(p, ctx))

  /** Identity of a path node: `researcherId`, then `nodeId`, `workId`, then `~id`. */
  private def pathNodeId(e: NeptuneEntity, ctx: String): String =
    optionalString(e.properties, ctx, Key.ResearcherId)
      .orElse(optionalString(e.properties, ctx, Key.NodeId))
      .orElse(optionalString(e.properties, ctx, Key.WorkId))
      .orElse(e.id)
      .getOrElse(fail(s"$ctx has no '${Key.ResearcherId}' property and no '${Entity.Id}'"))

  /**
   * Node IDs of a path value: either a list of ID strings (the `path` projection the client's query
   * returns) or an openCypher path, an array alternating node and relationship entities.
   */
  def pathNodeIds(value: ujson.Value): Vector[String] =
    value match
      case ujson.Arr(items) if items.forall(_.isInstanceOf[ujson.Str]) =>
        items.map(_.str).toVector
      case ujson.Arr(items) =>
        items.zipWithIndex.flatMap { (item, i) =>
          val e        = entity(item, s"path element $i")
          val ctx      = e.describe(s"path element $i")
          val wantNode = i % 2 == 0
          if wantNode && e.isRelationship then
            fail(s"$ctx: expected a node at an even path position, got a relationship")
          if !wantNode && e.isNode then
            fail(s"$ctx: expected a relationship at an odd path position, got a node")
          if wantNode then Some(pathNodeId(e, ctx)) else None
        }.toVector
      case other => fail(s"path: expected a JSON array, got ${kindOf(other)}")

  // ---- response-level parsers ----

  /** Extract results array from Neptune response; anything else yields an empty array. */
  def extractResults(response: ujson.Value): ujson.Arr =
    response match
      case o: ujson.Obj =>
        o.value.get("results") match
          case Some(arr: ujson.Arr) => arr
          case _                    => ujson.Arr()
      case _ => ujson.Arr()

  private def column(row: ujson.Value, col: String, rowIndex: Int): ujson.Value =
    row match
      case o: ujson.Obj =>
        o.value.getOrElse(col, fail(s"result row $rowIndex has no column '$col'"))
      case other => fail(s"result row $rowIndex: expected a JSON object, got ${kindOf(other)}")

  /** Parse an optional researcher from query results (column [[Column.Researcher]]). */
  def parseOptionalResearcher(response: ujson.Value): Option[CanonicalResearcher] =
    extractResults(response).arr.headOption.map { row =>
      researcherFromEntity(column(row, Column.Researcher, 0))
    }

  /** Parse an optional work from query results (column [[Column.Work]]). */
  def parseOptionalWork(response: ujson.Value): Option[WorkRecord] =
    extractResults(response).arr.headOption.map(row => workFromEntity(column(row, Column.Work, 0)))

  /** Parse coauthor edges from query results (column [[Column.Edge]]). */
  def parseCoauthorEdges(response: ujson.Value): Vector[CoauthorEdge] =
    extractResults(response).arr.zipWithIndex.map { (row, i) =>
      coauthorEdgeFromEntity(column(row, Column.Edge, i))
    }.toVector

  /**
   * Parse a path as a vector of node IDs, from column [[Column.Path]] (an ID list or path entities)
   * or [[Column.PathEntities]] (a raw openCypher path).
   */
  def parsePath(response: ujson.Value): Option[Vector[String]] =
    extractResults(response).arr.headOption.map { row =>
      val value = row match
        case o: ujson.Obj =>
          o.value
            .get(Column.Path)
            .orElse(o.value.get(Column.PathEntities))
            .getOrElse(
              fail(s"result row 0 has no column '${Column.Path}' or '${Column.PathEntities}'")
            )
        case other => fail(s"result row 0: expected a JSON object, got ${kindOf(other)}")
      pathNodeIds(value)
    }

  /** Parse graph nodes from query results (column [[Column.Neighbor]]). */
  def parseGraphNodes(response: ujson.Value): Vector[GraphNode] =
    extractResults(response).arr.zipWithIndex.map { (row, i) =>
      graphNodeFromEntity(column(row, Column.Neighbor, i))
    }.toVector

  /** Parse a list of string values from query results. */
  def parseStringList(response: ujson.Value, key: String): Vector[String] =
    extractResults(response).arr.zipWithIndex.map { (row, i) =>
      stringValue(s"result row $i", key, column(row, key, i))
    }.toVector

  // ---- Researcher metrics (STO-P13-02) ----

  /**
   * Metric keys the Spark bulk-load export writes as `:Int` / `:Long`. Neptune returns them as JSON
   * numbers, which are rendered as plain integers (`3`, never `3.0`) for the core codec.
   */
  val IntegerMetricKeys: Set[String] = Set(
    Key.PublicationCount,
    Key.DistinctCoauthorCount,
    Key.TotalCoauthorOccurrences,
    Key.MaximumCoauthorsOnOnePaper,
    Key.ComponentSize,
    Key.Coreness,
    Key.WindowStartYear,
    Key.WindowEndYear
  )

  /** Metric keys exported as `:Double`; JSON numbers are rendered with `Double.toString`. */
  val DoubleMetricKeys: Set[String] =
    Set(Key.AverageCoauthorsPerPaper, Key.WeightedDegree, Key.BridgeScore)

  /** Metric keys exported as `:String` (IDs and `bridgeScoreMethod`). */
  val StringMetricKeys: Set[String] = Set(Key.ComponentId, Key.CommunityId, Key.BridgeScoreMethod)

  /** Metric key exported as JSON-array text in a `:String` column. */
  val JsonArrayMetricKeys: Set[String] = Set(Key.MaximumTeamWorkIds)

  /**
   * Metric keys the core codec treats as optional. `componentId` is also a vertex lookup key, so a
   * vertex without metrics may still carry it.
   */
  val OptionalMetricKeys: Set[String] =
    Set(Key.ComponentId, Key.ComponentSize, Key.CommunityId, Key.MaximumTeamWorkIds)

  /** Metric keys every metrics-bearing `Researcher` vertex must carry, in declaration order. */
  val RequiredMetricKeys: Vector[String] =
    Key.ResearcherMetricKeys.filterNot(OptionalMetricKeys.contains)

  /** Largest integer a JSON number (an IEEE double) represents exactly: 2^53. */
  private val MaxExactJsonInteger: Double = 9007199254740992.0

  /**
   * Converts one Neptune-typed metric property value into the string form
   * [[ResearcherMetrics.fromGraphProperties]] parses:
   *   - integer keys: a whole JSON number within +/-2^53 becomes its decimal digits (`3`, never
   *     `3.0`); a fractional or out-of-range number fails;
   *   - double keys: a JSON number becomes `Double.toString` (`2.5`, `3.0`, `1.0E-4`);
   *   - `maximumTeamWorkIds`: JSON-array text passes through, a native array is written compactly;
   *   - string keys: strings pass through; a number is rendered like other string properties.
   *
   * Strings on numeric keys pass through unchanged so the codec validates them. Booleans, objects
   * and arrays on scalar keys fail. Errors name the key, never the value.
   */
  def metricPropertyText(ctx: String, key: String, v: ujson.Value): String =
    def wrongKind(expected: String): Nothing =
      fail(s"$ctx: metric property '$key' must be $expected, got ${kindOf(v)}")
    if IntegerMetricKeys.contains(key) then
      v match
        case ujson.Num(d) if d.isWhole && math.abs(d) <= MaxExactJsonInteger => d.toLong.toString
        case ujson.Num(_) =>
          fail(
            s"$ctx: metric property '$key' must be an integer, got a fractional or too large number"
          )
        case ujson.Str(s) => s
        case _            => wrongKind("an integer")
    else if DoubleMetricKeys.contains(key) then
      v match
        case ujson.Num(d) => java.lang.Double.toString(d)
        case ujson.Str(s) => s
        case _            => wrongKind("a number")
    else if JsonArrayMetricKeys.contains(key) then
      v match
        case ujson.Str(s) => s
        case a: ujson.Arr => ujson.write(a)
        case _            => wrongKind("a JSON array of strings")
    else
      v match
        case ujson.Str(s) => s
        case n: ujson.Num => numberText(n.num)
        case _            => wrongKind("a string")

  /** `key 'x'` and `missing required key 'x'` mentions in a core codec error message. */
  private val CodecKeyMention = """(?:^|[ :;])key '([A-Za-z_][A-Za-z0-9_]*)'""".r

  /**
   * Key names (only declared metric keys) a codec error mentions. The codec message itself may
   * quote property values, so it is reduced to key names before it reaches an exception message.
   */
  private def codecProblemKeys(message: String): Vector[String] =
    CodecKeyMention
      .findAllMatchIn(message)
      .map(_.group(1))
      .filter(Key.ResearcherMetricKeys.contains)
      .toVector
      .distinct

  /**
   * Decode the [[ResearcherMetrics]] stored on a `Researcher` node.
   *
   * @return
   *   `None` when the vertex carries none of [[RequiredMetricKeys]] (metrics not loaded); otherwise
   *   the decoded metrics. `researcherId` and `provenance` come from the vertex's own properties.
   * @throws NeptuneResponseFormatException
   *   when only some required metric keys are present, a value has the wrong Neptune type, or the
   *   core codec rejects the values (malformed text or a violated [[ResearcherMetrics]] invariant).
   *   The message names the vertex and the offending keys, never the values.
   */
  def researcherMetricsFromEntity(value: ujson.Value): Option[ResearcherMetrics] =
    val e   = entity(value, s"${Label.Researcher} node")
    val ctx = e.describe(s"${Label.Researcher} node")
    requireNodeLabel(e, Label.Researcher, ctx)
    val p            = e.properties
    val researcherId = requiredString(p, ctx, Key.ResearcherId)
    if !RequiredMetricKeys.exists(p.contains) then None
    else
      val absent = RequiredMetricKeys.filterNot(p.contains)
      if absent.nonEmpty then
        fail(s"$ctx: incomplete researcher metrics, missing ${absent.mkString("'", "', '", "'")}")
      val provenance = requiredProvenance(p, ctx)
      val text = Key.ResearcherMetricKeys.flatMap { k =>
        p.get(k).map(v => k -> metricPropertyText(ctx, k, v))
      }.toMap
      ResearcherMetrics.fromGraphProperties(researcherId, text, provenance) match
        case Right(m) => Some(m)
        case Left(message) =>
          val keys = codecProblemKeys(message)
          val detail =
            if keys.isEmpty then "values violate a ResearcherMetrics invariant"
            else s"malformed ${keys.mkString("'", "', '", "'")}"
          fail(s"$ctx: malformed researcher metrics: $detail")

  /**
   * Parse the metrics of at most one `Researcher` vertex (column [[Column.Researcher]]). No rows or
   * a vertex without metrics is `None`; more than one row means duplicate vertices for one
   * researcher ID and fails rather than picking one arbitrarily.
   */
  def parseOptionalResearcherMetrics(response: ujson.Value): Option[ResearcherMetrics] =
    val rows = extractResults(response).arr
    if rows.size > 1 then
      fail(s"expected at most one ${Label.Researcher} row for researcher metrics, got ${rows.size}")
    rows.headOption.flatMap(row => researcherMetricsFromEntity(column(row, Column.Researcher, 0)))

  /**
   * Parse one metrics row per `Researcher` vertex (column [[Column.Researcher]]), in response
   * order. Each element is the vertex's `researcherId` paired with its metrics, `None` when the
   * vertex has none.
   */
  def parseResearcherMetricsRows(
    response: ujson.Value
  ): Vector[(String, Option[ResearcherMetrics])] =
    extractResults(response).arr.zipWithIndex.map { (row, i) =>
      val node = column(row, Column.Researcher, i)
      val e    = entity(node, s"result row $i")
      val id   = requiredString(e.properties, e.describe(s"result row $i"), Key.ResearcherId)
      id -> researcherMetricsFromEntity(node)
    }.toVector
