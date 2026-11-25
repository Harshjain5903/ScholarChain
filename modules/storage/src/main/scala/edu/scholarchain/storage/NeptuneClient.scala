package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration}

/** A failed Neptune openCypher request. The message is credential-free. */
final class NeptuneRequestException(val status: Int, message: String)
    extends RuntimeException(message)

/**
 * Adapter for Neptune openCypher queries over HTTPS.
 *
 * The cluster has IAM database authentication enabled, so every request is SigV4-signed with
 * signing name `neptune-db` and the configured region. Queries are sent in the documented form
 * encoding, `query=<cypher>&parameters=<json>`; values are always passed as parameters, never
 * spliced into the query text.
 *
 * @param credentialsProvider
 *   resolved per request, never logged
 * @param baseUriOverride
 *   replaces `https://<endpoint>:<port>` (local fake servers in tests)
 */
class NeptuneStorageClient(
  endpoint: String,
  port: Int,
  region: String,
  queryTimeoutMs: Long,
  transport: HttpTransport,
  credentialsProvider: AwsCredentialsProvider,
  clock: Clock = Clock.systemUTC(),
  baseUriOverride: Option[URI] = None
):

  private val log = LoggerFactory.getLogger(classOf[NeptuneStorageClient])

  private val signer =
    SigV4Signer(credentialsProvider, region, NeptuneStorageClient.SigningName, clock)

  /** The openCypher HTTPS endpoint. */
  val openCypherUri: URI =
    val base = baseUriOverride.getOrElse(URI("https", null, endpoint, port, null, null, null))
    URI(base.getScheme, null, base.getHost, base.getPort, "/openCypher", null, null)

  /** Execute a parameterized openCypher query and return the JSON response. */
  def executeCypher(
    query: String,
    parameters: Map[String, ujson.Value]
  ): ujson.Value =
    val form =
      "query=" + urlEncode(query) +
        "&parameters=" + urlEncode(ujson.write(ujson.Obj.from(parameters)))
    val call = HttpCall(
      "POST",
      openCypherUri,
      Vector("Content-Type" -> "application/x-www-form-urlencoded"),
      form.getBytes(StandardCharsets.UTF_8)
    )
    val reply =
      try transport.execute(signer.sign(call), Some(Duration.ofMillis(queryTimeoutMs)))
      catch
        case e: Exception =>
          val msg = signer.redact(
            s"Neptune openCypher request failed: ${e.getClass.getSimpleName}: ${e.getMessage}"
          )
          log.warn(msg)
          throw NeptuneRequestException(-1, msg)
    if !reply.isSuccess then
      val code = scala.util
        .Try(ujson.read(reply.body).obj.get("code").map(_.str))
        .toOption
        .flatten
        .getOrElse("unknown")
      val msg =
        signer.redact(s"Neptune openCypher request failed: status=${reply.status} code=$code")
      log.warn(msg)
      throw NeptuneRequestException(reply.status, msg)
    ujson.read(reply.body)

  private def urlEncode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  override def toString: String = s"NeptuneStorageClient(uri=$openCypherUri, region=$region)"

  /** Look up a researcher by ID. */
  def getResearcher(researcherId: String): Option[CanonicalResearcher] =
    val result = executeCypher(
      NeptuneStorageClient.getResearcherQuery,
      Map("rid" -> ujson.Str(researcherId))
    )
    NeptuneResponseParser.parseOptionalResearcher(result)

  /** Look up a work by ID. */
  def getWork(workId: String): Option[WorkRecord] =
    val result = executeCypher(
      NeptuneStorageClient.getWorkQuery,
      Map("wid" -> ujson.Str(workId))
    )
    NeptuneResponseParser.parseOptionalWork(result)

  /** Get direct coauthors for a researcher, with configurable limit. */
  def getDirectCoauthors(researcherId: String, limit: Int): Vector[CoauthorEdge] =
    val boundedLimit = math.max(1, math.min(limit, 500))
    val result = executeCypher(
      NeptuneStorageClient.directCoauthorsQuery,
      Map("rid" -> ujson.Str(researcherId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseCoauthorEdges(result)

  /**
   * Find a shortest path between two researchers, bounded depth 1-10.
   *
   * Neptune openCypher does not implement the `shortestPath()` function (per the Neptune openCypher
   * compliance documentation; not verified against a live cluster), so this uses a bounded
   * variable-length match ordered by path length; see [[NeptuneStorageClient.shortestPathQuery]].
   * Researcher IDs are passed as parameters. Relationship types and the depth bound cannot be
   * Cypher parameters, so edge types must be declared in [[GraphSchema.EdgeType]] (checked by
   * [[NeptuneStorageClient.edgeTypeFilter]]) and the depth is a clamped integer.
   *
   * @param edgeTypes
   *   relationship types the path may traverse; empty means
   *   [[NeptuneStorageClient.DefaultPathEdgeTypes]] (coauthorship only), never "any type"
   * @throws IllegalArgumentException
   *   before any request when an edge type is malformed or undeclared
   */
  def shortestPath(
    fromId: String,
    toId: String,
    maxDepth: Int,
    edgeTypes: Vector[String] = Vector.empty
  ): Option[Vector[String]] =
    val (query, parameters) =
      NeptuneStorageClient.shortestPathQuery(fromId, toId, maxDepth, edgeTypes)
    NeptuneResponseParser.parsePath(executeCypher(query, parameters))

  /**
   * Get neighbors by edge types, bounded depth 1-3 and limit 1-500.
   *
   * @param edgeTypes
   *   relationship types to traverse; each must be declared in [[GraphSchema.EdgeType]]. Empty
   *   means any type (unchanged behavior).
   * @throws IllegalArgumentException
   *   before any request when an edge type is malformed or undeclared
   */
  def getNeighbors(
    nodeId: String,
    edgeTypes: Vector[String],
    depth: Int,
    limit: Int
  ): Vector[GraphNode] =
    val boundedLimit = math.max(1, math.min(limit, 500))
    val result = executeCypher(
      NeptuneStorageClient.neighborsQuery(edgeTypes, depth),
      Map("nid" -> ujson.Str(nodeId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseGraphNodes(result)

  /** Get members of a graph component. */
  def getComponentMembers(componentId: String, limit: Int): Vector[String] =
    val boundedLimit = math.max(1, math.min(limit, 500))
    val result = executeCypher(
      NeptuneStorageClient.componentMembersQuery,
      Map("cid" -> ujson.Str(componentId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseStringList(result, NeptuneResponseParser.Column.ResearcherIds)

  /**
   * Spark-computed [[ResearcherMetrics]] stored on the `Researcher` vertex (STO-P13-02), via the
   * parameterized [[NeptuneStorageClient.researcherMetricsQuery]].
   *
   * @return
   *   `None` when no vertex has this ID or the vertex carries no metric properties
   * @throws NeptuneResponseFormatException
   *   when the metric properties are incomplete or malformed, when more than one vertex has this
   *   ID, or when the returned vertex has a different `researcherId`
   */
  def getResearcherMetrics(researcherId: String): Option[ResearcherMetrics] =
    val result = executeCypher(
      NeptuneStorageClient.researcherMetricsQuery,
      Map("rid" -> ujson.Str(researcherId))
    )
    val metrics = NeptuneResponseParser.parseOptionalResearcherMetrics(result)
    metrics.foreach { m =>
      if m.researcherId != researcherId then
        throw NeptuneResponseFormatException(
          "researcher metrics lookup returned a vertex for a different researcherId"
        )
    }
    metrics

  /**
   * Metrics for up to [[NeptuneStorageClient.MaxMetricsBatchSize]] distinct researcher IDs in one
   * parameterized query ([[NeptuneStorageClient.researcherMetricsBatchQuery]]). Duplicate IDs are
   * collapsed before the cap is applied; an empty input sends no request.
   *
   * @return
   *   metrics keyed by researcher ID; IDs with no vertex or no metric properties are absent
   * @throws IllegalArgumentException
   *   before any request when there are more than `MaxMetricsBatchSize` distinct IDs
   * @throws NeptuneResponseFormatException
   *   when any returned vertex has incomplete or malformed metrics, an ID was not requested, or one
   *   ID matches more than one vertex
   */
  def getResearcherMetricsBatch(researcherIds: Seq[String]): Map[String, ResearcherMetrics] =
    val ids = researcherIds.distinct.toVector
    require(
      ids.size <= NeptuneStorageClient.MaxMetricsBatchSize,
      s"researcher metrics batch has ${ids.size} distinct IDs; at most " +
        s"${NeptuneStorageClient.MaxMetricsBatchSize} are allowed"
    )
    if ids.isEmpty then Map.empty
    else
      val result = executeCypher(
        NeptuneStorageClient.researcherMetricsBatchQuery,
        Map(
          "rids" -> ujson.Arr.from(ids.map(ujson.Str(_))),
          // One spare row so a duplicate vertex is always visible instead of silently cut off.
          "lim" -> ujson.Num((ids.size + 1).toDouble)
        )
      )
      val rows      = NeptuneResponseParser.parseResearcherMetricsRows(result)
      val requested = ids.toSet
      rows.foreach { (id, _) =>
        if !requested.contains(id) then
          throw NeptuneResponseFormatException(
            "researcher metrics batch returned a vertex whose researcherId was not requested"
          )
      }
      if rows.map(_._1).distinct.size != rows.size then
        throw NeptuneResponseFormatException(
          "researcher metrics batch returned more than one vertex for one researcherId"
        )
      rows.collect { case (id, Some(m)) => id -> m }.toMap

  /**
   * Top researchers by an allowlisted metric for one metric window, highest first (ties by
   * `researcherId` ascending), via [[NeptuneStorageClient.topResearchersQuery]].
   *
   * For [[RankableResearcherMetric.BridgeScore]], vertices whose `bridgeScoreMethod` is
   * `not-computed` (a 0.0 placeholder, not a measured score) or absent are excluded in the query,
   * and any such row is also dropped client-side.
   *
   * @throws IllegalArgumentException
   *   before any request when `limit` is outside 1..[[NeptuneStorageClient.MaxRankingLimit]]
   * @throws NeptuneResponseFormatException
   *   when a returned vertex has missing, incomplete or malformed metrics
   */
  def topResearchersBy(
    metric: RankableResearcherMetric,
    window: MetricWindow,
    limit: Int
  ): Vector[ResearcherMetrics] =
    require(
      limit >= 1 && limit <= NeptuneStorageClient.MaxRankingLimit,
      s"ranking limit must be between 1 and ${NeptuneStorageClient.MaxRankingLimit}, got $limit"
    )
    val (query, parameters) = NeptuneStorageClient.topResearchersQuery(metric, window, limit)
    val rows = NeptuneResponseParser.parseResearcherMetricsRows(executeCypher(query, parameters))
    val metrics = rows.map {
      case (_, Some(m)) => m
      case (_, None) =>
        throw NeptuneResponseFormatException(
          s"ranking by '${metric.wireName}' returned a ${GraphSchema.VertexLabel.Researcher} vertex without metrics"
        )
    }
    if metric == RankableResearcherMetric.BridgeScore then
      metrics.filterNot(_.bridgeScoreMethod == BridgeScoreMethod.NotComputed)
    else metrics

  /**
   * [[topResearchersBy]] for a metric named by text. The name must exactly match one of
   * [[RankableResearcherMetric.AllowedNames]]; it is only used to select the allowlisted case and
   * never reaches the query text.
   *
   * @throws IllegalArgumentException
   *   before any request when the name is not allowlisted or `limit` is out of range
   */
  def topResearchersBy(
    metricName: String,
    window: MetricWindow,
    limit: Int
  ): Vector[ResearcherMetrics] =
    RankableResearcherMetric.fromName(metricName) match
      case Right(metric) => topResearchersBy(metric, window, limit)
      case Left(message) => throw IllegalArgumentException(message)

  /** Generate a Neptune bulk-load manifest. */
  def generateBulkLoadManifest(s3Prefix: String, format: String): ujson.Value =
    ujson.Obj(
      "source"      -> ujson.Str(s3Prefix),
      "format"      -> ujson.Str(format),
      "iamRoleArn"  -> ujson.Str(""),
      "region"      -> ujson.Str(""),
      "failOnError" -> ujson.Str("FALSE")
    )

object NeptuneStorageClient:

  /** SigV4 signing name for Neptune IAM database authentication. */
  val SigningName: String = "neptune-db"

  private val Label = GraphSchema.VertexLabel
  private val Edge  = GraphSchema.EdgeType
  private val Key   = GraphSchema.PropertyKey
  private val Col   = NeptuneResponseParser.Column

  /**
   * True for a plain relationship-type identifier (safe to place in query text). Syntactic only;
   * delegates to [[GraphSchema.isValidEdgeTypeIdentifier]]. Traversal additionally requires a
   * declared type, see [[edgeTypeFilter]].
   */
  def isValidEdgeType(edgeType: String): Boolean = GraphSchema.isValidEdgeTypeIdentifier(edgeType)

  /** openCypher text for [[NeptuneStorageClient.getResearcher]] (parameter `$rid`). */
  val getResearcherQuery: String =
    s"MATCH (${Col.Researcher}:${Label.Researcher} {${Key.ResearcherId}: $$rid}) RETURN ${Col.Researcher}"

  /** openCypher text for [[NeptuneStorageClient.getWork]] (parameter `$wid`). */
  val getWorkQuery: String =
    s"MATCH (${Col.Work}:${Label.Work} {${Key.WorkId}: $$wid}) RETURN ${Col.Work}"

  /** openCypher text for [[NeptuneStorageClient.getDirectCoauthors]] (`$rid`, `$lim`). */
  val directCoauthorsQuery: String =
    s"""MATCH (r:${Label.Researcher} {${Key.ResearcherId}: $$rid})-[${Col.Edge}:${Edge.Coauthor}]-(other:${Label.Researcher})
       |RETURN ${Col.Edge} ORDER BY ${Col.Edge}.${Key.JointPublicationCount} DESC LIMIT $$lim""".stripMargin

  /** openCypher text for [[NeptuneStorageClient.getComponentMembers]] (`$cid`, `$lim`). */
  val componentMembersQuery: String =
    s"MATCH (r:${Label.Researcher} {${Key.ComponentId}: $$cid}) RETURN r.${Key.ResearcherId} AS ${Col.ResearcherIds} LIMIT $$lim"

  /**
   * openCypher text for [[NeptuneStorageClient.getNeighbors]] (`$nid`, `$lim`); depth clamped to
   * 1..3. Edge types go through [[edgeTypeFilter]], so undeclared types are rejected here.
   */
  def neighborsQuery(edgeTypes: Vector[String], depth: Int): String =
    val boundedDepth = math.max(1, math.min(depth, 3))
    val edgeFilter   = edgeTypeFilter(edgeTypes)
    s"""MATCH (start {${Key.NodeId}: $$nid})-[e$edgeFilter*1..$boundedDepth]-(${Col.Neighbor})
       |RETURN DISTINCT ${Col.Neighbor} LIMIT $$lim""".stripMargin

  /**
   * Upper bound on [[NeptuneStorageClient.shortestPath]] depth. Kept at 10 because the rag
   * `PathFinderAgent` accepts caller depths up to 10 (default 6); lowering it here would silently
   * change that contract.
   */
  val MaxPathDepth: Int = 10

  /**
   * Relationship types a shortest path traverses when the caller passes none. Restricting to the
   * coauthorship type ([[GraphSchema.EdgeType.Coauthor]], wire label `COAUTHORED_WITH`, also used
   * by [[NeptuneStorageClient.getDirectCoauthors]]) bounds the fan-out of the undirected
   * variable-length match; an unrestricted `-[*1..d]-` over every edge type grows exponentially
   * with depth (review finding N35).
   */
  val DefaultPathEdgeTypes: Vector[String] = Vector(GraphSchema.EdgeType.Coauthor)

  /**
   * `:A|B` relationship-type filter for query text, or `""` for any type. Every type must pass
   * [[isValidEdgeType]] and be declared in [[GraphSchema.EdgeType]] (review finding N36: a
   * well-formed but undeclared type would silently match nothing); otherwise
   * IllegalArgumentException is thrown before any request is sent.
   */
  def edgeTypeFilter(edgeTypes: Vector[String]): String =
    edgeTypes.foreach { t =>
      require(isValidEdgeType(t), s"invalid edge type '$t'; expected [A-Za-z_][A-Za-z0-9_]*")
      GraphSchema.requireDeclaredEdgeType(t)
    }
    if edgeTypes.isEmpty then "" else ":" + edgeTypes.mkString("|")

  /**
   * openCypher text and parameters for a bounded shortest-path lookup without `shortestPath()`:
   * {{{
   * MATCH p = (a:Researcher {researcherId: $fromId})-[:COAUTHORED_WITH*1..d]-(b:Researcher {researcherId: $toId})
   * WITH p ORDER BY length(p) ASC LIMIT 1
   * RETURN [n IN nodes(p) | n.researcherId] AS path
   * }}}
   * The path is ordered and limited in a `WITH` clause, while `p` is still in scope, before the
   * projection in `RETURN` (ordering after `RETURN` by the unprojected `p` is not portable). The
   * depth is clamped to 1..[[MaxPathDepth]]; an empty `edgeTypes` means [[DefaultPathEdgeTypes]];
   * IDs are only ever parameters (`$fromId`, `$toId`).
   */
  def shortestPathQuery(
    fromId: String,
    toId: String,
    maxDepth: Int,
    edgeTypes: Vector[String] = Vector.empty
  ): (String, Map[String, ujson.Value]) =
    val boundedDepth = math.max(1, math.min(maxDepth, MaxPathDepth))
    val types        = if edgeTypes.isEmpty then DefaultPathEdgeTypes else edgeTypes
    val edgeFilter   = edgeTypeFilter(types)
    val query =
      s"""MATCH p = (a:${Label.Researcher} {${Key.ResearcherId}: $$fromId})-[$edgeFilter*1..$boundedDepth]-(b:${Label.Researcher} {${Key.ResearcherId}: $$toId})
         |WITH p ORDER BY length(p) ASC LIMIT 1
         |RETURN [n IN nodes(p) | n.${Key.ResearcherId}] AS ${Col.Path}""".stripMargin
    (query, Map("fromId" -> ujson.Str(fromId), "toId" -> ujson.Str(toId)))

  /** Largest number of distinct IDs [[NeptuneStorageClient.getResearcherMetricsBatch]] accepts. */
  val MaxMetricsBatchSize: Int = 100

  /** Largest `limit` [[NeptuneStorageClient.topResearchersBy]] accepts. */
  val MaxRankingLimit: Int = 100

  /**
   * openCypher text for [[NeptuneStorageClient.getResearcherMetrics]] (parameter `$rid`). Returns
   * the whole vertex; `LIMIT 2` bounds the response while still exposing duplicate vertices.
   */
  val researcherMetricsQuery: String =
    s"MATCH (${Col.Researcher}:${Label.Researcher} {${Key.ResearcherId}: $$rid}) RETURN ${Col.Researcher} LIMIT 2"

  /** openCypher text for [[NeptuneStorageClient.getResearcherMetricsBatch]] (`$rids`, `$lim`). */
  val researcherMetricsBatchQuery: String =
    s"""MATCH (${Col.Researcher}:${Label.Researcher})
       |WHERE ${Col.Researcher}.${Key.ResearcherId} IN $$rids
       |RETURN ${Col.Researcher} LIMIT $$lim""".stripMargin

  /**
   * openCypher text and parameters for [[NeptuneStorageClient.topResearchersBy]]:
   * {{{
   * MATCH (r:Researcher)
   * WHERE r.windowStartYear = $ws AND r.windowEndYear = $we AND r.<key> IS NOT NULL
   * RETURN r ORDER BY r.<key> DESC, r.researcherId ASC LIMIT $lim
   * }}}
   * `<key>` is [[RankableResearcherMetric.propertyKey]], the only text spliced in; it is re-checked
   * here to be a declared metric key and a plain identifier. For `bridgeScore` the `WHERE` clause
   * also has `AND r.bridgeScoreMethod <> $excludedMethod` (`not-computed`), which also drops
   * vertices without a method. The window and limit are parameters; the limit is clamped to
   * 1..[[MaxRankingLimit]].
   */
  def topResearchersQuery(
    metric: RankableResearcherMetric,
    window: MetricWindow,
    limit: Int
  ): (String, Map[String, ujson.Value]) =
    val prop = metric.propertyKey
    require(
      Key.ResearcherMetricKeys.contains(prop) && GraphSchema.isValidEdgeTypeIdentifier(prop),
      s"ranking property for '${metric.wireName}' is not a declared metric key"
    )
    val r            = Col.Researcher
    val boundedLimit = math.max(1, math.min(limit, MaxRankingLimit))
    val isBridge     = metric == RankableResearcherMetric.BridgeScore
    val bridgeFilter =
      if isBridge then s" AND $r.${Key.BridgeScoreMethod} <> $$excludedMethod" else ""
    val query =
      s"""MATCH ($r:${Label.Researcher})
         |WHERE $r.${Key.WindowStartYear} = $$ws AND $r.${Key.WindowEndYear} = $$we AND $r.$prop IS NOT NULL$bridgeFilter
         |RETURN $r ORDER BY $r.$prop DESC, $r.${Key.ResearcherId} ASC LIMIT $$lim""".stripMargin
    val base = Map[String, ujson.Value](
      "ws"  -> ujson.Num(window.startYear.toDouble),
      "we"  -> ujson.Num(window.endYear.toDouble),
      "lim" -> ujson.Num(boundedLimit.toDouble)
    )
    val parameters =
      if isBridge then base + ("excludedMethod" -> ujson.Str(BridgeScoreMethod.NotComputed))
      else base
    (query, parameters)

  /** Production client: SigV4 with the default credential chain unless a provider is injected. */
  def create(
    config: NeptuneStorageConfig,
    credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.create()
  ): NeptuneStorageClient =
    NeptuneStorageClient(
      endpoint = config.endpoint,
      port = config.port,
      region = config.region,
      queryTimeoutMs = config.queryTimeoutMs,
      transport = JavaHttpTransport.create(Duration.ofMillis(config.queryTimeoutMs)),
      credentialsProvider = credentialsProvider
    )
