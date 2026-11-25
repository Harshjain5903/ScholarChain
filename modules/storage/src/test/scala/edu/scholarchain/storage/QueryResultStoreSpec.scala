package edu.scholarchain.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.http.{
  AbortableInputStream,
  ExecutableHttpRequest,
  HttpExecuteRequest,
  HttpExecuteResponse,
  SdkHttpClient,
  SdkHttpResponse
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient => AwsDynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model.*

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * STO-P13-01: durable query results (`putQueryResult` / `getQueryResult`) in the query-session
 * table, keyed by `queryId` as provisioned in `infra/terraform/modules/storage` (hash key
 * `queryId`, type S). No AWS calls: an in-memory DynamoDB fake and a fake SDK HTTP client.
 */
class QueryResultStoreSpec extends AnyFlatSpec with Matchers:

  private val Prefix = "scholarchain-dev"
  private val Table  = s"$Prefix-query-session"

  final private class SteppingClock(var now: Instant) extends Clock:
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = now

  /**
   * In-memory DynamoDB for single-hash-key tables. Supports `attribute_not_exists(<hashKey>)`
   * conditions the way DynamoDB does: the put fails with ConditionalCheckFailedException when an
   * item with that key already exists.
   */
  private class InMemoryDynamo(hashKeys: Map[String, String]) extends AwsDynamoDbClient:

    val tables: mutable.Map[String, mutable.Map[String, Map[String, AttributeValue]]] =
      mutable.Map.empty
    val puts: mutable.ArrayBuffer[PutItemRequest] = mutable.ArrayBuffer.empty
    val gets: mutable.ArrayBuffer[GetItemRequest] = mutable.ArrayBuffer.empty

    override def serviceName(): String = "dynamodb"
    override def close(): Unit         = ()

    private def hashKey(table: String): String =
      hashKeys.getOrElse(table, throw ResourceNotFoundException.builder().message(table).build())

    override def putItem(request: PutItemRequest): PutItemResponse =
      puts += request
      val hk   = hashKey(request.tableName())
      val item = request.item().asScala.toMap
      val key  = item(hk).s()
      val t    = tables.getOrElseUpdate(request.tableName(), mutable.Map.empty)
      Option(request.conditionExpression()).foreach { cond =>
        cond shouldBe s"attribute_not_exists($hk)"
        if t.contains(key) then
          throw ConditionalCheckFailedException
            .builder()
            .message("The conditional request failed")
            .build()
      }
      t(key) = item
      PutItemResponse.builder().build()

    override def getItem(request: GetItemRequest): GetItemResponse =
      gets += request
      val hk  = hashKey(request.tableName())
      val key = request.key().get(hk).s()
      tables.get(request.tableName()).flatMap(_.get(key)) match
        case Some(item) => GetItemResponse.builder().item(item.asJava).build()
        case None       => GetItemResponse.builder().build()

  private val start = Instant.parse("2026-11-24T12:00:00Z")

  private def fixture() =
    val dynamo = InMemoryDynamo(Map(Table -> "queryId"))
    val clock  = SteppingClock(start)
    (dynamo, clock, DynamoDbStorageClient(dynamo, Prefix, clock))

  private val resultJson =
    """{"queryId":"q-1","answer":"Ada and Grace coauthored 3 works.",""" +
      """"claims":[{"text":"...","evidenceIds":["ev-1"]}],""" +
      """"provenance":{"producer":"QueryService"}}"""

  "DynamoDbStorageClient.putQueryResult" should "write to the query-session table under result#<queryId>" in {
    val (dynamo, _, client) = fixture()
    client.putQueryResult("q-1", resultJson, Duration.ofHours(24)) shouldBe
      ConditionalPutOutcome.Written

    val req = dynamo.puts.head
    req.tableName() shouldBe Table
    req.conditionExpression() shouldBe "attribute_not_exists(queryId)"
    val item = req.item().asScala
    item("queryId").s() shouldBe "result#q-1"
    item("resultQueryId").s() shouldBe "q-1"
    item("recordType").s() shouldBe "query-result"
    item("jsonData").s() shouldBe resultJson
    item("createdAt").s() shouldBe "2026-11-24T12:00:00Z"
    item("ttl").n() shouldBe (start.getEpochSecond + 24 * 3600).toString
  }

  it should "round-trip through getQueryResult with a consistent read" in {
    val (dynamo, _, client) = fixture()
    client.putQueryResult("q-1", resultJson, Duration.ofHours(1))
    client.getQueryResult("q-1") shouldBe Some(resultJson)
    dynamo.gets.head.consistentRead() shouldBe true
    dynamo.gets.head.tableName() shouldBe Table
  }

  it should "be idempotent: a second put keeps the first result and reports AlreadyExists" in {
    val (dynamo, _, client) = fixture()
    client.putQueryResult("q-1", resultJson, Duration.ofHours(1)) shouldBe
      ConditionalPutOutcome.Written
    client.putQueryResult("q-1", """{"different":true}""", Duration.ofHours(1)) shouldBe
      ConditionalPutOutcome.AlreadyExists
    client.getQueryResult("q-1") shouldBe Some(resultJson)
    dynamo.puts should have size 2
  }

  it should "not collide with the query-session item stored under the bare query ID" in {
    val (_, _, client) = fixture()
    val session = QuerySessionEntry(
      queryId = "q-1",
      question = "Who coauthored with Ada?",
      status = "COMPLETED",
      createdAt = "2026-11-24T12:00:00Z",
      updatedAt = "2026-11-24T12:00:01Z",
      resultRef = Some(DynamoDbStorageClient.queryResultKey("q-1")),
      provenance = edu.scholarchain.core.Provenance("q", "q-1", "run-1", "1.0.0", "t")
    )
    client.putQuerySession(session)
    client.putQueryResult("q-1", resultJson, Duration.ofHours(1)) shouldBe
      ConditionalPutOutcome.Written
    client.getQuerySession("q-1").map(_.question) shouldBe Some("Who coauthored with Ada?")
    client.getQueryResult("q-1") shouldBe Some(resultJson)
  }

  it should "reject invalid input before any request" in {
    val (dynamo, _, client) = fixture()
    an[IllegalArgumentException] should be thrownBy
      client.putQueryResult("", resultJson, Duration.ofHours(1))
    an[IllegalArgumentException] should be thrownBy
      client.putQueryResult("q-1", "{not json", Duration.ofHours(1))
    an[IllegalArgumentException] should be thrownBy
      client.putQueryResult("q-1", resultJson, Duration.ZERO)
    an[IllegalArgumentException] should be thrownBy
      client.putQueryResult("q-1", resultJson, Duration.ofSeconds(-5))
    val huge = ujson.write(ujson.Str("x" * (DynamoDbStorageClient.MaxQueryResultBytes + 1)))
    an[IllegalArgumentException] should be thrownBy
      client.putQueryResult("q-1", huge, Duration.ofHours(1))
    dynamo.puts shouldBe empty
  }

  "DynamoDbStorageClient.getQueryResult" should "return None for an unknown query ID" in {
    val (_, _, client) = fixture()
    client.getQueryResult("missing") shouldBe None
  }

  it should "return None once the ttl has passed, even before DynamoDB deletes the item" in {
    val (dynamo, clock, client) = fixture()
    client.putQueryResult("q-1", resultJson, Duration.ofMinutes(10))
    clock.now = start.plusSeconds(599)
    client.getQueryResult("q-1") shouldBe Some(resultJson)
    clock.now = start.plusSeconds(600)
    client.getQueryResult("q-1") shouldBe None
    dynamo.tables(Table) should contain key "result#q-1"
  }

  // ---- Real SDK marshalling against a fake HTTP client ----

  /** Fake SDK HTTP client: records request bodies and answers with one canned response. */
  final private class FakeSdkHttp(status: Int, body: String) extends SdkHttpClient:
    val bodies: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

    override def prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest =
      val sent = request
        .contentStreamProvider()
        .map(p => String(p.newStream().readAllBytes(), StandardCharsets.UTF_8))
        .orElse("")
      bodies += sent
      new ExecutableHttpRequest:
        override def call(): HttpExecuteResponse =
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          HttpExecuteResponse
            .builder()
            .response(
              SdkHttpResponse
                .builder()
                .statusCode(status)
                .putHeader("Content-Type", "application/x-amz-json-1.0")
                .putHeader("Content-Length", bytes.length.toString)
                .build()
            )
            .responseBody(AbortableInputStream.create(ByteArrayInputStream(bytes)))
            .build()
        override def abort(): Unit = ()

    override def close(): Unit = ()

  private def sdkClient(http: FakeSdkHttp) =
    AwsDynamoDbClient
      .builder()
      .region(Region.US_EAST_2)
      .credentialsProvider(StorageTestAws.dummyCredentials)
      .endpointOverride(URI("http://localhost:1"))
      .httpClient(http)
      .build()

  "putQueryResult over the real SDK" should "send the conditional PutItem request" in {
    val http   = FakeSdkHttp(200, "{}")
    val client = DynamoDbStorageClient(sdkClient(http), Prefix, SteppingClock(start))
    client.putQueryResult("q-1", resultJson, Duration.ofHours(1)) shouldBe
      ConditionalPutOutcome.Written
    val sent = ujson.read(http.bodies.head)
    sent("TableName").str shouldBe Table
    sent("ConditionExpression").str shouldBe "attribute_not_exists(queryId)"
    sent("Item")("queryId")("S").str shouldBe "result#q-1"
    sent("Item")("ttl")("N").str shouldBe (start.getEpochSecond + 3600).toString
  }

  it should "map DynamoDB's ConditionalCheckFailedException to AlreadyExists" in {
    val http = FakeSdkHttp(
      400,
      """{"__type":"com.amazonaws.dynamodb.v20120810#ConditionalCheckFailedException",""" +
        """"message":"The conditional request failed"}"""
    )
    val client = DynamoDbStorageClient(sdkClient(http), Prefix, SteppingClock(start))
    client.putQueryResult("q-1", resultJson, Duration.ofHours(1)) shouldBe
      ConditionalPutOutcome.AlreadyExists
    http.bodies should have size 1
  }

  it should "propagate other DynamoDB errors" in {
    val http = FakeSdkHttp(
      400,
      """{"__type":"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException",""" +
        """"message":"Requested resource not found"}"""
    )
    val client = DynamoDbStorageClient(sdkClient(http), Prefix, SteppingClock(start))
    a[ResourceNotFoundException] should be thrownBy
      client.putQueryResult("q-1", resultJson, Duration.ofHours(1))
  }
