package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.storage.*
import org.slf4j.LoggerFactory
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.control.NonFatal

/**
 * [[ResearcherLookup]] over Neptune. Name lookup is an exact, case-sensitive match on the
 * `displayName` property (passed as a parameter, never spliced), capped at [[MaxCandidates]] so an
 * ambiguous name returns several candidates instead of an unbounded scan.
 */
final class NeptuneResearcherLookup(neptune: NeptuneStorageClient) extends ResearcherLookup:
  import NeptuneResearcherLookup.*

  def findByName(name: String): Vector[CanonicalResearcher] =
    if name.trim.isEmpty then Vector.empty
    else
      val response = neptune.executeCypher(
        ByNameQuery,
        Map("name" -> ujson.Str(name.trim), "lim" -> ujson.Num(MaxCandidates.toDouble))
      )
      NeptuneResponseParser
        .extractResults(response)
        .arr
        .toVector
        .flatMap(_.objOpt.flatMap(_.get(NeptuneResponseParser.Column.Researcher)))
        .map(NeptuneResponseParser.researcherFromEntity)

  def findById(id: String): Option[CanonicalResearcher] =
    if id.trim.isEmpty then None else neptune.getResearcher(id)

object NeptuneResearcherLookup:
  val MaxCandidates: Int = 10

  private val Key   = GraphSchema.PropertyKey
  private val Label = GraphSchema.VertexLabel
  private val Col   = NeptuneResponseParser.Column

  /** Parameters `$name`, `$lim`. */
  val ByNameQuery: String =
    s"MATCH (${Col.Researcher}:${Label.Researcher}) WHERE ${Col.Researcher}.${Key.DisplayName} = $$name " +
      s"RETURN ${Col.Researcher} ORDER BY ${Col.Researcher}.${Key.ResearcherId} LIMIT $$lim"

/**
 * [[GraphStore]] over Neptune, restricted to coauthorship. Paths and neighbor traversals only
 * follow `COAUTHORED_WITH` ([[GraphSchema.EdgeType.Coauthor]]) whatever the caller asks for; path
 * depth is clamped to `1..maxDepth`, neighbor depth to `1..MaxNeighborDepth`, limits to
 * `1..maxLimit`.
 */
final class NeptuneGraphStore(neptune: NeptuneStorageClient, maxDepth: Int, maxLimit: Int = 100)
    extends GraphStore:
  import NeptuneGraphStore.*

  private val depthBound = math.max(1, math.min(maxDepth, NeptuneStorageClient.MaxPathDepth))
  private val limitBound = math.max(1, math.min(maxLimit, 500))

  private def lim(n: Int): Int = math.max(1, math.min(n, limitBound))

  def getDirectCoauthors(researcherId: String, limit: Int): Vector[CoauthorEdge] =
    neptune.getDirectCoauthors(researcherId, lim(limit))

  def shortestPath(fromId: String, toId: String, maxDepth: Int): Option[Vector[String]] =
    neptune.shortestPath(fromId, toId, math.max(1, math.min(maxDepth, depthBound)), CoauthorOnly)

  def getNeighbors(
    nodeId: String,
    edgeTypes: Vector[String],
    depth: Int,
    limit: Int
  ): Vector[GraphNode] =
    neptune.getNeighbors(
      nodeId,
      CoauthorOnly,
      math.max(1, math.min(depth, math.min(MaxNeighborDepth, depthBound))),
      lim(limit)
    )

  def getComponentMembers(componentId: String, limit: Int): Vector[String] =
    neptune.getComponentMembers(componentId, lim(limit))

object NeptuneGraphStore:
  val CoauthorOnly: Vector[String] = Vector(GraphSchema.EdgeType.Coauthor)
  val MaxNeighborDepth: Int        = 2

/**
 * [[MetricsStore]] over the Spark-computed metrics stored on Neptune `Researcher` vertices, via
 * `NeptuneStorageClient.getResearcherMetrics`, `getResearcherMetricsBatch` (chunks of at most
 * `MaxMetricsBatchSize` IDs) and `topResearchersBy` (allowlisted [[RankableResearcherMetric]]
 * only).
 *
 * Windows are matched exactly: a vertex stores metrics for one window, and they are returned only
 * when that window is `windowStart..windowEnd`. Metrics computed over another window are never
 * presented as an answer for the requested one. Malformed stored metrics propagate as
 * `NeptuneResponseFormatException`; the workflow records the step as unavailable.
 */
final class NeptuneMetricsStore(neptune: NeptuneStorageClient) extends MetricsStore:

  private def inWindow(m: ResearcherMetrics, start: Int, end: Int): Boolean =
    m.windowStartYear == start && m.windowEndYear == end

  def getMetrics(
    researcherId: String,
    windowStart: Int,
    windowEnd: Int
  ): Option[ResearcherMetrics] =
    if researcherId.trim.isEmpty then None
    else neptune.getResearcherMetrics(researcherId).filter(inWindow(_, windowStart, windowEnd))

  override def getMetricsBatch(
    researcherIds: Vector[String],
    windowStart: Int,
    windowEnd: Int
  ): Map[String, ResearcherMetrics] =
    researcherIds
      .filter(_.trim.nonEmpty)
      .distinct
      .grouped(NeptuneStorageClient.MaxMetricsBatchSize)
      .flatMap(chunk => neptune.getResearcherMetricsBatch(chunk))
      .filter((_, m) => inWindow(m, windowStart, windowEnd))
      .toMap

  override def topResearchers(
    metric: RankableResearcherMetric,
    windowStart: Int,
    windowEnd: Int,
    limit: Int
  ): Vector[ResearcherMetrics] =
    if windowStart > windowEnd then Vector.empty
    else
      neptune
        .topResearchersBy(
          metric,
          MetricWindow(windowStart, windowEnd),
          CollaborationMetricsAgent.clampLimit(limit)
        )
        .filter(inWindow(_, windowStart, windowEnd))

/** [[VectorStore]] over S3 Vectors `QueryVectors`; `topK` is clamped to the service limit. */
final class S3VectorsVectorStore(client: S3VectorsStorageClient, vectorBucket: String)
    extends VectorStore:

  def query(
    indexName: String,
    queryVector: Vector[Float],
    topK: Int,
    filters: Option[ujson.Value]
  ): Vector[VectorSearchResult] =
    val k = math.max(1, math.min(topK, S3VectorsStorageClient.MaxTopK))
    client.queryVectors(vectorBucket, indexName, queryVector, k, filters).map { r =>
      VectorSearchResult(
        key = r.key,
        score = if r.score.isNaN then 0.0 else math.max(0.0, math.min(1.0, r.score)),
        documentType = r.metadata.documentType,
        entityId = r.metadata.entityId,
        textS3Uri = r.metadata.textS3Uri
      )
    }

/**
 * [[TextStore]] over S3. Reads `s3://bucket/key` as UTF-8, truncated to `maxChars`. A missing or
 * unreadable object is `None` (the chunk is skipped), never an error for the whole query.
 */
final class S3TextStore(s3: S3StorageClient, maxChars: Int = 8000) extends TextStore:
  private val log = LoggerFactory.getLogger(classOf[S3TextStore])

  def getText(s3Uri: String): Option[String] =
    S3TextStore.parse(s3Uri).flatMap { (bucket, key) =>
      try Some(String(s3.getObject(bucket, key), StandardCharsets.UTF_8).take(maxChars))
      catch
        case NonFatal(e) =>
          log.warn(s"text chunk unavailable: ${e.getClass.getSimpleName}")
          None
    }

object S3TextStore:

  def parse(uri: String): Option[(String, String)] =
    if !uri.startsWith("s3://") then None
    else
      val rest = uri.stripPrefix("s3://")
      val i    = rest.indexOf('/')
      if i <= 0 || i == rest.length - 1 then None else Some(rest.take(i) -> rest.drop(i + 1))

/**
 * [[AuditStore]] over DynamoDB `putQueryResult`, so the audit record is durable and readable with
 * [[getAudit]]. Stored under the query ID `audit:<queryId>` (key `result#audit:<queryId>` in the
 * query-session table) so it never collides with the service's own `putQueryResult(queryId, ...)`.
 * The write is idempotent; a failure propagates (no answer without an audit record).
 */
final class DynamoAuditStore(dynamo: DynamoDbStorageClient, ttl: Duration) extends AuditStore:

  def persistAudit(record: AuditRecord): Unit =
    dynamo.putQueryResult(DynamoAuditStore.key(record.queryId), write(record), ttl)

  def getAudit(queryId: String): Option[AuditRecord] =
    dynamo.getQueryResult(DynamoAuditStore.key(queryId)).map(read[AuditRecord](_))

object DynamoAuditStore:
  def key(queryId: String): String = s"audit:$queryId"

/**
 * A metrics source with no data: every lookup is `None` and rankings are empty, so metric claims
 * are never fabricated (the workflow answers from graph and text evidence or abstains). Used in
 * tests and when metrics are deliberately disabled; production wiring uses [[NeptuneMetricsStore]].
 */
object UnavailableMetricsStore extends MetricsStore:

  def getMetrics(
    researcherId: String,
    windowStart: Int,
    windowEnd: Int
  ): Option[ResearcherMetrics] =
    None
