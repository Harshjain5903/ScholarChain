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

/**
 * Parsing helpers for Neptune openCypher JSON responses.
 *
 * Result columns are the aliases bound by the [[NeptuneStorageClient]] queries ([[Column]]). Row
 * objects are decoded into core records whose field names are the [[GraphSchema.PropertyKey]] names
 * (pinned by the storage contract tests), so a property written under any other name fails to parse
 * rather than being silently dropped.
 */
object NeptuneResponseParser:

  /** Result-column aliases shared by the openCypher queries and this parser. */
  object Column:
    val Researcher: String    = "r"
    val Work: String          = "w"
    val Edge: String          = "e"
    val Path: String          = "path"
    val Neighbor: String      = "neighbor"
    val ResearcherIds: String = "rid"

    /** Every column alias, in declaration order. */
    val All: Vector[String] = Vector(Researcher, Work, Edge, Path, Neighbor, ResearcherIds)

  /** Extract results array from Neptune response. */
  def extractResults(response: ujson.Value): ujson.Arr =
    response.obj.get("results") match
      case Some(arr: ujson.Arr) => arr
      case _                    => ujson.Arr()

  /** Parse an optional researcher from query results. */
  def parseOptionalResearcher(response: ujson.Value): Option[CanonicalResearcher] =
    val results = extractResults(response)
    if results.arr.isEmpty then None
    else
      val r = results.arr.head.obj(Column.Researcher)
      Some(upickle.default.read[CanonicalResearcher](r))

  /** Parse an optional work from query results. */
  def parseOptionalWork(response: ujson.Value): Option[WorkRecord] =
    val results = extractResults(response)
    if results.arr.isEmpty then None
    else
      val w = results.arr.head.obj(Column.Work)
      Some(upickle.default.read[WorkRecord](w))

  /** Parse coauthor edges from query results. */
  def parseCoauthorEdges(response: ujson.Value): Vector[CoauthorEdge] =
    val results = extractResults(response)
    results.arr.map(row => upickle.default.read[CoauthorEdge](row.obj(Column.Edge))).toVector

  /** Parse a path as a vector of node IDs. */
  def parsePath(response: ujson.Value): Option[Vector[String]] =
    val results = extractResults(response)
    if results.arr.isEmpty then None
    else
      val path = results.arr.head.obj(Column.Path)
      Some(path.arr.map(_.str).toVector)

  /** Parse graph nodes from query results. */
  def parseGraphNodes(response: ujson.Value): Vector[GraphNode] =
    val results = extractResults(response)
    results.arr.map(row => upickle.default.read[GraphNode](row.obj(Column.Neighbor))).toVector

  /** Parse a list of string values from query results. */
  def parseStringList(response: ujson.Value, key: String): Vector[String] =
    val results = extractResults(response)
    results.arr.map(row => row.obj(key).str).toVector
