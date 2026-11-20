package edu.scholarchain.storage

import edu.scholarchain.core.Provenance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient => AwsDynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model.*
import upickle.default.*

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

/** Entity-link cache entry. PK = sourceId, SK = targetId, with TTL. */
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

/** Adapter for Amazon DynamoDB using AWS SDK v2. */
class DynamoDbStorageClient(
  client: AwsDynamoDbClient,
  tablePrefix: String
):

  private def tableName(suffix: String): String = s"$tablePrefix-$suffix"

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

  /** Build a conditional put expression for idempotent writes. */
  def conditionalPutExpression(partitionKeyName: String): String =
    s"attribute_not_exists($partitionKeyName)"
