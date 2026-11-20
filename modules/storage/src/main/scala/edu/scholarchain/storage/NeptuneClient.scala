package edu.scholarchain.storage

import edu.scholarchain.core.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Adapter for Neptune openCypher queries over HTTPS. */
class NeptuneStorageClient(
  endpoint: String,
  port: Int,
  queryTimeoutMs: Long,
  httpClient: HttpClient
):

  private val baseUri = s"https://$endpoint:$port/openCypher"

  /** Execute a parameterized openCypher query and return the JSON response. */
  def executeCypher(
    query: String,
    parameters: Map[String, ujson.Value]
  ): ujson.Value =
    val body = ujson.Obj(
      "query"      -> ujson.Str(query),
      "parameters" -> ujson.Obj.from(parameters)
    )
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(baseUri))
      .timeout(Duration.ofMillis(queryTimeoutMs))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
      .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    ujson.read(response.body())

  /** Look up a researcher by ID. */
  def getResearcher(researcherId: String): Option[CanonicalResearcher] =
    val result = executeCypher(
      "MATCH (r:Researcher {researcherId: $rid}) RETURN r",
      Map("rid" -> ujson.Str(researcherId))
    )
    NeptuneResponseParser.parseOptionalResearcher(result)

  /** Look up a work by ID. */
  def getWork(workId: String): Option[WorkRecord] =
    val result = executeCypher(
      "MATCH (w:Work {workId: $wid}) RETURN w",
      Map("wid" -> ujson.Str(workId))
    )
    NeptuneResponseParser.parseOptionalWork(result)

  /** Get direct coauthors for a researcher, with configurable limit. */
  def getDirectCoauthors(researcherId: String, limit: Int): Vector[CoauthorEdge] =
    val boundedLimit = math.max(1, math.min(limit, 500))
    val result = executeCypher(
      """MATCH (r:Researcher {researcherId: $rid})-[e:COAUTHOR]-(other:Researcher)
        |RETURN e ORDER BY e.jointPublicationCount DESC LIMIT $lim""".stripMargin,
      Map("rid" -> ujson.Str(researcherId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseCoauthorEdges(result)

  /** Find shortest path between two nodes, bounded depth 1-10. */
  def shortestPath(
    fromId: String,
    toId: String,
    maxDepth: Int
  ): Option[Vector[String]] =
    val boundedDepth = math.max(1, math.min(maxDepth, 10))
    val result = executeCypher(
      s"""MATCH p = shortestPath(
         |  (a:Researcher {researcherId: $$fromId})-[*1..$boundedDepth]-
         |  (b:Researcher {researcherId: $$toId})
         |) RETURN [n IN nodes(p) | n.researcherId] AS path""".stripMargin,
      Map("fromId" -> ujson.Str(fromId), "toId" -> ujson.Str(toId))
    )
    NeptuneResponseParser.parsePath(result)

  /** Get neighbors by edge types, bounded depth 1-3 and limit 1-500. */
  def getNeighbors(
    nodeId: String,
    edgeTypes: Vector[String],
    depth: Int,
    limit: Int
  ): Vector[GraphNode] =
    val boundedDepth = math.max(1, math.min(depth, 3))
    val boundedLimit = math.max(1, math.min(limit, 500))
    val edgeFilter =
      if edgeTypes.isEmpty then ""
      else ":" + edgeTypes.mkString("|")
    val result = executeCypher(
      s"""MATCH (start {nodeId: $$nid})-[e$edgeFilter*1..$boundedDepth]-(neighbor)
         |RETURN DISTINCT neighbor LIMIT $$lim""".stripMargin,
      Map("nid" -> ujson.Str(nodeId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseGraphNodes(result)

  /** Get members of a graph component. */
  def getComponentMembers(componentId: String, limit: Int): Vector[String] =
    val boundedLimit = math.max(1, math.min(limit, 500))
    val result = executeCypher(
      "MATCH (r:Researcher {componentId: $cid}) RETURN r.researcherId AS rid LIMIT $lim",
      Map("cid" -> ujson.Str(componentId), "lim" -> ujson.Num(boundedLimit.toDouble))
    )
    NeptuneResponseParser.parseStringList(result, "rid")

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

  /** Create a client with standard configuration. */
  def create(config: NeptuneStorageConfig): NeptuneStorageClient =
    val httpClient = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofMillis(config.queryTimeoutMs))
      .build()
    NeptuneStorageClient(
      config.endpoint,
      config.port,
      config.queryTimeoutMs,
      httpClient
    )

/** Parsing helpers for Neptune openCypher JSON responses. */
object NeptuneResponseParser:

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
      val r = results.arr.head.obj("r")
      Some(upickle.default.read[CanonicalResearcher](r))

  /** Parse an optional work from query results. */
  def parseOptionalWork(response: ujson.Value): Option[WorkRecord] =
    val results = extractResults(response)
    if results.arr.isEmpty then None
    else
      val w = results.arr.head.obj("w")
      Some(upickle.default.read[WorkRecord](w))

  /** Parse coauthor edges from query results. */
  def parseCoauthorEdges(response: ujson.Value): Vector[CoauthorEdge] =
    val results = extractResults(response)
    results.arr.map(row => upickle.default.read[CoauthorEdge](row.obj("e"))).toVector

  /** Parse a path as a vector of node IDs. */
  def parsePath(response: ujson.Value): Option[Vector[String]] =
    val results = extractResults(response)
    if results.arr.isEmpty then None
    else
      val path = results.arr.head.obj("path")
      Some(path.arr.map(_.str).toVector)

  /** Parse graph nodes from query results. */
  def parseGraphNodes(response: ujson.Value): Vector[GraphNode] =
    val results = extractResults(response)
    results.arr.map(row => upickle.default.read[GraphNode](row.obj("neighbor"))).toVector

  /** Parse a list of string values from query results. */
  def parseStringList(response: ujson.Value, key: String): Vector[String] =
    val results = extractResults(response)
    results.arr.map(row => row.obj(key).str).toVector
