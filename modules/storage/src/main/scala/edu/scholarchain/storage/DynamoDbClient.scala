package edu.scholarchain.storage

import edu.scholarchain.core.Provenance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient => AwsDynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model.*
import upickle.default.*

import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration}
import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
// DynamoDB entry case classes
// ---------------------------------------------------------------------------

/** Pipeline run ledger entry. PK = runId. */
case class RunLedgerEntry(
  runId: String,
  status: String,
  startTime: String,
  endTime: Option[String],
  config: String,
  outputPaths: Vector[String],
  provenance: Provenance
) derives ReadWriter

/**
 * Entity-link cache entry. PK = sourceId, SK = targetId, with TTL.
 *
 * `ttl` is the expiry time in Unix epoch SECONDS (not milliseconds). It is written as the DynamoDB
 * Number attribute `ttl`, which the table's TTL setting reads; DynamoDB TTL only honours
 * epoch-second values, so a millisecond value would never expire.
 */
case class EntityLinkCacheEntry(
  sourceId: String,
  targetId: String,
  decision: String,
  confidence: Double,
  ttl: Long,
  provenance: Provenance
) derives ReadWriter

/** Query session entry. PK = queryId. */
case class QuerySessionEntry(
  queryId: String,
  question: String,
  status: String,
  createdAt: String,
  updatedAt: String,
  resultRef: Option[String],
  provenance: Provenance
) derives ReadWriter

/** Evidence bundle entry. PK = bundleId. */
case class EvidenceBundleEntry(
  bundleId: String,
  queryId: String,
  itemCount: Int,
  totalScore: Double,
  s3Uri: String,
  provenance: Provenance
) derives ReadWriter

/** Artifact registry entry. PK = artifactId. */
case class ArtifactRegistryEntry(
  artifactId: String,
  artifactType: String,
  version: String,
  s3Uri: String,
  checksum: String,
  provenance: Provenance
) derives ReadWriter

// ---------------------------------------------------------------------------
// DynamoDB adapter
// ---------------------------------------------------------------------------

/** Result of an idempotent conditional put. */
enum ConditionalPutOutcome:

  /** The item did not exist and was written. */
  case Written

  /** An item with the same key already existed; it was left unchanged. */
  case AlreadyExists

/**
 * Adapter for Amazon DynamoDB using AWS SDK v2.
 *
 * @param clock
 *   source of "now" for query-result expiry; injectable for tests
 */
class DynamoDbStorageClient(
  client: AwsDynamoDbClient,
  tablePrefix: String,
  clock: Clock = Clock.systemUTC()
):

  private def tableName(suffix: String): String = s"$tablePrefix-$suffix"

  // ---- Query Result (durable /v1/query responses; Smoke Test 4) ----

  /**
   * Durably store the JSON result of a query, idempotently.
   *
   * Stored in the query-session table (`<prefix>-query-session`, partition key `queryId`) under the
   * key `result#<queryId>` ([[DynamoDbStorageClient.queryResultKey]]), so it never collides with
   * the session item that [[putQuerySession]] writes under the bare query ID. Attributes: `queryId`
   * (the derived key), `resultQueryId`, `recordType = "query-result"`, `jsonData`, `createdAt`
   * (ISO-8601) and `ttl` (expiry, Unix epoch SECONDS, Number).
   *
   * The put is conditional on `attribute_not_exists(queryId)`: a second put for the same query ID
   * leaves the first result in place and returns [[ConditionalPutOutcome.AlreadyExists]]. Other
   * DynamoDB errors propagate.
   *
   * @param json
   *   the result document; must be valid JSON and at most
   *   [[DynamoDbStorageClient.MaxQueryResultBytes]] UTF-8 bytes
   * @param ttl
   *   time to keep the result; must be positive
   * @throws IllegalArgumentException
   *   before any request for an empty query ID, invalid JSON, an oversized document or a
   *   non-positive ttl
   */
  def putQueryResult(queryId: String, json: String, ttl: Duration): ConditionalPutOutcome =
    require(queryId != null && queryId.nonEmpty, "queryId must be non-empty")
    require(json != null, "query result json must not be null")
    require(ttl != null && !ttl.isNegative && !ttl.isZero, "query result ttl must be positive")
    val size = json.getBytes(StandardCharsets.UTF_8).length
    require(
      size <= DynamoDbStorageClient.MaxQueryResultBytes,
      s"query result is $size bytes; the limit is ${DynamoDbStorageClient.MaxQueryResultBytes}"
    )
    require(scala.util.Try(ujson.read(json)).isSuccess, "query result must be valid JSON")
    val now       = clock.instant()
    val expiresAt = now.plus(ttl).getEpochSecond
    val item = Map(
      "queryId"       -> AttributeValue.fromS(DynamoDbStorageClient.queryResultKey(queryId)),
      "resultQueryId" -> AttributeValue.fromS(queryId),
      "recordType"    -> AttributeValue.fromS(DynamoDbStorageClient.QueryResultRecordType),
      "jsonData"      -> AttributeValue.fromS(json),
      "createdAt"     -> AttributeValue.fromS(now.toString),
      "ttl"           -> AttributeValue.fromN(expiresAt.toString)
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("query-session"))
      .item(item.asJava)
      .conditionExpression(DynamoDbStorageClient.conditionalPutExpression("queryId"))
      .build()
    try
      client.putItem(request)
      ConditionalPutOutcome.Written
    catch case _: ConditionalCheckFailedException => ConditionalPutOutcome.AlreadyExists

  /**
   * Read a stored query result written by [[putQueryResult]], with a strongly consistent read.
   * Returns None when there is no result, or when its `ttl` has passed (DynamoDB TTL deletion is
   * best-effort and can lag expiry by days).
   */
  def getQueryResult(queryId: String): Option[String] =
    require(queryId != null && queryId.nonEmpty, "queryId must be non-empty")
    val key =
      Map("queryId" -> AttributeValue.fromS(DynamoDbStorageClient.queryResultKey(queryId)))
    val request = GetItemRequest
      .builder()
      .tableName(tableName("query-session"))
      .key(key.asJava)
      .consistentRead(true)
      .build()
    val response = client.getItem(request)
    if !response.hasItem || !response.item().containsKey("jsonData") then None
    else
      val item = response.item()
      val expired = Option(item.get("ttl"))
        .flatMap(v => Option(v.n()))
        .flatMap(_.toLongOption)
        .exists(_ <= clock.instant().getEpochSecond)
      if expired then None else Some(item.get("jsonData").s())

  // ---- Run Ledger ----

  def putRunLedger(entry: RunLedgerEntry): Unit =
    val item = Map(
      "runId"    -> AttributeValue.fromS(entry.runId),
      "jsonData" -> AttributeValue.fromS(write(entry))
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("run-ledger"))
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(runId)")
      .build()
    client.putItem(request)

  def getRunLedger(runId: String): Option[RunLedgerEntry] =
    val key = Map("runId" -> AttributeValue.fromS(runId))
    val request = GetItemRequest
      .builder()
      .tableName(tableName("run-ledger"))
      .key(key.asJava)
      .build()
    val response = client.getItem(request)
    if response.hasItem && response.item().containsKey("jsonData") then
      Some(read[RunLedgerEntry](response.item().get("jsonData").s()))
    else None

  // ---- Entity Link Cache ----

  def putEntityLinkCache(entry: EntityLinkCacheEntry): Unit =
    val item = Map(
      "sourceId" -> AttributeValue.fromS(entry.sourceId),
      "targetId" -> AttributeValue.fromS(entry.targetId),
      "jsonData" -> AttributeValue.fromS(write(entry)),
      "ttl"      -> AttributeValue.fromN(entry.ttl.toString)
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("entity-link-cache"))
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(sourceId)")
      .build()
    client.putItem(request)

  def getEntityLinkCache(
    sourceId: String,
    targetId: String
  ): Option[EntityLinkCacheEntry] =
    val key = Map(
      "sourceId" -> AttributeValue.fromS(sourceId),
      "targetId" -> AttributeValue.fromS(targetId)
    )
    val request = GetItemRequest
      .builder()
      .tableName(tableName("entity-link-cache"))
      .key(key.asJava)
      .build()
    val response = client.getItem(request)
    if response.hasItem && response.item().containsKey("jsonData") then
      Some(read[EntityLinkCacheEntry](response.item().get("jsonData").s()))
    else None

  // ---- Query Session ----

  def putQuerySession(entry: QuerySessionEntry): Unit =
    val item = Map(
      "queryId"  -> AttributeValue.fromS(entry.queryId),
      "jsonData" -> AttributeValue.fromS(write(entry))
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("query-session"))
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(queryId)")
      .build()
    client.putItem(request)

  def getQuerySession(queryId: String): Option[QuerySessionEntry] =
    val key = Map("queryId" -> AttributeValue.fromS(queryId))
    val request = GetItemRequest
      .builder()
      .tableName(tableName("query-session"))
      .key(key.asJava)
      .build()
    val response = client.getItem(request)
    if response.hasItem && response.item().containsKey("jsonData") then
      Some(read[QuerySessionEntry](response.item().get("jsonData").s()))
    else None

  // ---- Evidence Bundle ----

  def putEvidenceBundle(entry: EvidenceBundleEntry): Unit =
    val item = Map(
      "bundleId" -> AttributeValue.fromS(entry.bundleId),
      "jsonData" -> AttributeValue.fromS(write(entry))
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("evidence-bundle"))
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(bundleId)")
      .build()
    client.putItem(request)

  def getEvidenceBundle(bundleId: String): Option[EvidenceBundleEntry] =
    val key = Map("bundleId" -> AttributeValue.fromS(bundleId))
    val request = GetItemRequest
      .builder()
      .tableName(tableName("evidence-bundle"))
      .key(key.asJava)
      .build()
    val response = client.getItem(request)
    if response.hasItem && response.item().containsKey("jsonData") then
      Some(read[EvidenceBundleEntry](response.item().get("jsonData").s()))
    else None

  // ---- Artifact Registry ----

  def putArtifactRegistry(entry: ArtifactRegistryEntry): Unit =
    val item = Map(
      "artifactId" -> AttributeValue.fromS(entry.artifactId),
      "jsonData"   -> AttributeValue.fromS(write(entry))
    )
    val request = PutItemRequest
      .builder()
      .tableName(tableName("artifact-registry"))
      .item(item.asJava)
      .conditionExpression("attribute_not_exists(artifactId)")
      .build()
    client.putItem(request)

  def getArtifactRegistry(artifactId: String): Option[ArtifactRegistryEntry] =
    val key = Map("artifactId" -> AttributeValue.fromS(artifactId))
    val request = GetItemRequest
      .builder()
      .tableName(tableName("artifact-registry"))
      .key(key.asJava)
      .build()
    val response = client.getItem(request)
    if response.hasItem && response.item().containsKey("jsonData") then
      Some(read[ArtifactRegistryEntry](response.item().get("jsonData").s()))
    else None

object DynamoDbStorageClient:

  /** Create a client for the given configuration. */
  def create(config: DynamoDbStorageConfig): DynamoDbStorageClient =
    val awsClient = AwsDynamoDbClient
      .builder()
      .region(Region.of(config.region))
      .build()
    DynamoDbStorageClient(awsClient, config.tablePrefix)

  /** `recordType` attribute value of a stored query result. */
  val QueryResultRecordType: String = "query-result"

  /** Prefix of the query-session partition key under which a query result is stored. */
  val QueryResultKeyPrefix: String = "result#"

  /**
   * Largest accepted query-result document, in UTF-8 bytes. DynamoDB items are limited to 400 KB
   * including attribute names and the other attributes; this leaves headroom for them.
   */
  val MaxQueryResultBytes: Int = 350 * 1024

  /** Query-session partition key value of the stored result for `queryId`. */
  def queryResultKey(queryId: String): String = QueryResultKeyPrefix + queryId

  /** Build a conditional put expression for idempotent writes. */
  def conditionalPutExpression(partitionKeyName: String): String =
    s"attribute_not_exists($partitionKeyName)"
