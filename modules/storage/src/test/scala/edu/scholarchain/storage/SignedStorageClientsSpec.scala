package edu.scholarchain.storage

import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import edu.scholarchain.core.GraphSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.io.IOException
import java.net.{InetSocketAddress, URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Success, Try}

/**
 * Phase 12 repair-03 (R4, R5, R12): SigV4-signed S3 Vectors and Neptune requests, the real S3
 * Vectors REST shapes, service-limit batching, credential redaction and the embedding queue URL
 * config key. No AWS calls: fake transports or a loopback HTTP server, dummy static credentials.
 */
class SignedStorageClientsSpec extends AnyFlatSpec with Matchers:

  import StorageTestAws.*

  private val Region = "us-east-1"
  private val Bucket = "scholarchain-dev-vectors"
  private val Index  = S3VectorsIndexes.ResearcherProfile

  private def s3vScope(service: String) =
    s"AWS4-HMAC-SHA256 Credential=$DummyAccessKey/$ScopeDate/$Region/$service/aws4_request"

  private def meta(entity: String = "researcher:orcid:0000-0001") = VectorMetadata(
    documentType = "researcher-profile",
    entityId = entity,
    windowStartYear = Some(2020),
    windowEndYear = None,
    topicId = None,
    institutionId = Some("inst:ror:1"),
    textS3Uri = "s3://scholarchain-dev-rag/contexts/researcher-profile/r1.json",
    textHash = "h1",
    embeddingModel = "amazon.titan-embed-text-v2:0",
    runId = "run-001"
  )

  private def rec(i: Int, dims: Int = 4) = VectorRecord(s"k$i", Vector.fill(dims)(0.25f), meta())

  private def vectorsClient(
    transport: HttpTransport,
    batchSize: Int = S3VectorsStorageClient.MaxVectorsPerPut,
    maxRetries: Int = 0,
    endpointOverride: Option[URI] = None,
    clock: Clock = fixedClock
  ) = S3VectorsStorageClient(
    region = Region,
    maxRetries = maxRetries,
    retryBaseDelayMs = 0L,
    transport = transport,
    credentialsProvider = dummyCredentials,
    endpointOverride = endpointOverride,
    batchSize = batchSize,
    clock = clock
  )

  private def neptuneClient(transport: HttpTransport, baseUri: Option[URI] = None) =
    NeptuneStorageClient(
      endpoint = "scholarchain-dev.cluster-abc.us-east-1.neptune.amazonaws.com",
      port = 8182,
      region = Region,
      queryTimeoutMs = 5000,
      transport = transport,
      credentialsProvider = dummyCredentials,
      clock = fixedClock,
      baseUriOverride = baseUri
    )

  /** Capture log events of `cls` while running `f`. */
  private def captureLogs[A](cls: Class[?])(f: => A): (Try[A], Vector[String]) =
    val logger   = LoggerFactory.getLogger(cls).asInstanceOf[LogbackLogger]
    val appender = ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    try
      val result = Try(f)
      (result, appender.list.asScala.toVector.map(_.getFormattedMessage))
    finally logger.detachAppender(appender)

  /** Clock whose instant only moves when the test advances it. */
  final private class SteppingClock(start: Instant) extends Clock:
    @volatile private var now                  = start
    def advanceSeconds(n: Long): Unit          = now = now.plusSeconds(n)
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = now

  private def signatureOf(call: HttpCall): String =
    call.header("Authorization").get.split("Signature=", 2)(1)

  private def formParams(body: String): Map[String, String] =
    body
      .split("&")
      .toVector
      .map { kv =>
        val Array(k, v) = kv.split("=", 2)
        k -> URLDecoder.decode(v, StandardCharsets.UTF_8)
      }
      .toMap

  // ---------------------------------------------------------------------------
  // S3 Vectors: PutVectors
  // ---------------------------------------------------------------------------

  "S3VectorsStorageClient.putVectors" should
    "send a SigV4-signed POST /PutVectors to s3vectors.<region>.api.aws with the API body shape" in {
      val transport = RecordingTransport(ok())
      val result    = vectorsClient(transport).putVectors(Bucket, Index, Vector(rec(1), rec(2)))

      result shouldBe PutVectorsResult(2, 0, Vector.empty)
      transport.calls should have size 1
      val call = transport.calls.head
      call.method shouldBe "POST"
      call.uri.getScheme shouldBe "https"
      call.uri.getHost shouldBe "s3vectors.us-east-1.api.aws"
      call.uri.getPath shouldBe "/PutVectors"
      call.header("Host") shouldBe Some("s3vectors.us-east-1.api.aws")
      call.header("Authorization").get should startWith(s3vScope("s3vectors"))
      call.header("Authorization").get should include("SignedHeaders=")
      call.header("X-Amz-Date") shouldBe Some(s"${ScopeDate}T120000Z")
      call.header("Content-Type") shouldBe Some("application/json")

      val body = ujson.read(call.bodyString)
      body.obj.keySet shouldBe Set("vectorBucketName", "indexName", "vectors")
      body("vectorBucketName").str shouldBe Bucket
      body("indexName").str shouldBe Index
      val v0 = body("vectors")(0)
      v0.obj.keySet shouldBe Set("key", "data", "metadata")
      v0("key").str shouldBe "k1"
      v0("data").obj.keySet shouldBe Set("float32")
      v0("data")("float32").arr.map(_.num) shouldBe Seq.fill(4)(0.25)
      // researcher-profile vectors carry the entity under the compact `researcherId` key
      v0("metadata")("researcherId").str shouldBe "researcher:orcid:0000-0001"
      v0("metadata").obj.contains("entityId") shouldBe false
      v0("metadata")("embeddingModel").str shouldBe "amazon.titan-embed-text-v2:0"
      v0("metadata")("windowStartYear").num shouldBe 2020
      // None-valued metadata is omitted (S3 Vectors metadata has no null type)
      v0("metadata").obj.contains("windowEndYear") shouldBe false
      v0("metadata").obj.contains("topicId") shouldBe false
    }

  it should "split writes into PutVectors calls of at most 500 vectors (AWS limit)" in {
    val transport = RecordingTransport(ok())
    val result = vectorsClient(transport).putVectors(Bucket, Index, Vector.tabulate(1201)(rec(_)))

    result.succeeded shouldBe 1201
    transport.calls.map(c => ujson.read(c.bodyString)("vectors").arr.size) shouldBe
      Seq(500, 500, 201)
  }

  it should "use the configured batch size and clamp it to 1..500" in {
    val t100 = RecordingTransport(ok())
    vectorsClient(t100, batchSize = 100).putVectors(Bucket, Index, Vector.tabulate(250)(rec(_)))
    t100.calls.map(c => ujson.read(c.bodyString)("vectors").arr.size) shouldBe Seq(100, 100, 50)

    vectorsClient(RecordingTransport(ok()), batchSize = 5000).putBatchSize shouldBe 500
    vectorsClient(RecordingTransport(ok()), batchSize = 0).putBatchSize shouldBe 1
  }

  it should "not call the service for an empty input" in {
    vectorsClient(NoNetwork).putVectors(Bucket, Index, Vector.empty) shouldBe
      PutVectorsResult(0, 0, Vector.empty)
  }

  it should "retry throttling/5xx and re-sign each attempt" in {
    // N10: the clock advances 7 seconds after every send, so a retry that reused the first
    // signature would carry the old X-Amz-Date and an identical Authorization header.
    val clock = SteppingClock(Instant.parse("2026-11-22T12:00:00Z"))
    val recording =
      RecordingTransport(HttpReply(503, Map.empty, """{"message":"slow down"}"""), ok())
    val transport = new HttpTransport:
      def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
        try recording.execute(call, timeout)
        finally clock.advanceSeconds(7)
    val result =
      vectorsClient(transport, maxRetries = 2, clock = clock)
        .putVectors(Bucket, Index, Vector(rec(1)))
    result shouldBe PutVectorsResult(1, 0, Vector.empty)
    recording.calls should have size 2
    recording.calls.foreach(_.header("Authorization").get should startWith(s3vScope("s3vectors")))
    val Seq(first, second) = recording.calls.toSeq
    first.header("X-Amz-Date") shouldBe Some(s"${ScopeDate}T120000Z")
    second.header("X-Amz-Date") shouldBe Some(s"${ScopeDate}T120007Z")
    signatureOf(second) should not be signatureOf(first)
    second.bodyString shouldBe first.bodyString
  }

  it should "fail the whole batch without retry on a 400 ValidationException" in {
    val transport = RecordingTransport(
      HttpReply(
        400,
        Map("x-amzn-errortype" -> "ValidationException:http://internal.amazon.com/"),
        """{"message":"Invalid dimension"}"""
      )
    )
    val result =
      vectorsClient(transport, maxRetries = 3).putVectors(Bucket, Index, Vector(rec(1), rec(2)))
    transport.calls should have size 1
    result.succeeded shouldBe 0
    result.failed shouldBe 2
    result.errors.head should include("status=400")
    result.errors.head should include("type=ValidationException")
    result.errors.head should include("keys=k1..k2")
  }

  // ---------------------------------------------------------------------------
  // S3 Vectors: QueryVectors
  // ---------------------------------------------------------------------------

  private val queryResponse =
    """{
      |  "distanceMetric": "cosine",
      |  "vectors": [
      |    {"key": "r-1", "distance": 0.125, "metadata": {
      |      "documentType": "researcher-profile", "entityId": "researcher:orcid:1",
      |      "windowStartYear": 2019, "windowEndYear": 2024, "topicId": "T10",
      |      "textS3Uri": "s3://rag/contexts/r1.json", "textHash": "hA",
      |      "embeddingModel": "amazon.titan-embed-text-v2:0", "runId": "run-7"}},
      |    {"key": "r-2", "distance": 0.5, "metadata": {
      |      "documentType": "researcher-profile", "entityId": "researcher:orcid:2",
      |      "textS3Uri": "s3://rag/contexts/r2.json", "textHash": "hB",
      |      "embeddingModel": "amazon.titan-embed-text-v2:0", "runId": "run-7"}}
      |  ]
      |}""".stripMargin

  "S3VectorsStorageClient.queryVectors" should
    "send a signed POST /QueryVectors with queryVector.float32, topK, filter and return flags" in {
      val transport = RecordingTransport(ok(queryResponse))
      val filter    = ujson.Obj("documentType" -> ujson.Obj("$eq" -> "researcher-profile"))
      vectorsClient(transport).queryVectors(Bucket, Index, Vector(0.1f, 0.2f), 5, Some(filter))

      val call = transport.calls.head
      call.method shouldBe "POST"
      call.uri.getHost shouldBe "s3vectors.us-east-1.api.aws"
      call.uri.getPath shouldBe "/QueryVectors"
      call.header("Authorization").get should startWith(s3vScope("s3vectors"))
      val body = ujson.read(call.bodyString)
      body.obj.keySet shouldBe Set(
        "vectorBucketName",
        "indexName",
        "queryVector",
        "topK",
        "filter",
        "returnMetadata",
        "returnDistance"
      )
      body("vectorBucketName").str shouldBe Bucket
      body("indexName").str shouldBe Index
      body("queryVector")("float32").arr.map(_.num.toFloat) shouldBe Seq(0.1f, 0.2f)
      body("topK").num shouldBe 5
      body("filter") shouldBe filter
      body("returnMetadata").bool shouldBe true
      body("returnDistance").bool shouldBe true
    }

  it should "parse keys, distances and metadata from the QueryVectors response" in {
    val results = vectorsClient(RecordingTransport(ok(queryResponse)))
      .queryVectors(Bucket, Index, Vector(0.1f), 5, None)

    results.map(_.key) shouldBe Vector("r-1", "r-2")
    results.map(_.distance) shouldBe Vector(Some(0.125), Some(0.5))
    results.map(_.score) shouldBe Vector(0.875, 0.5) // cosine: 1 - distance
    results.head.metadata shouldBe VectorMetadata(
      documentType = "researcher-profile",
      entityId = "researcher:orcid:1",
      windowStartYear = Some(2019),
      windowEndYear = Some(2024),
      topicId = Some("T10"),
      institutionId = None,
      textS3Uri = "s3://rag/contexts/r1.json",
      textHash = "hA",
      embeddingModel = "amazon.titan-embed-text-v2:0",
      runId = "run-7"
    )
    results(1).metadata.windowStartYear shouldBe None
  }

  it should "send exactly one request without filter or nextToken and keep at most topK results" in {
    // N4: QueryVectors has no pagination. Even if a response carried a nextToken-like field, the
    // client must not issue a follow-up request.
    val vs = Seq("a", "b", "c", "d").map(k =>
      ujson.Obj("key" -> k, "distance" -> 0.1, "metadata" -> S3VectorsMetadataCodec.toJson(meta(k)))
    )
    val reply = ujson.Obj(
      "distanceMetric" -> "cosine",
      "vectors"        -> ujson.Arr.from(vs),
      "nextToken"      -> "ignored"
    )
    val transport = RecordingTransport(ok(ujson.write(reply)))
    val results   = vectorsClient(transport).queryVectors(Bucket, Index, Vector(0.1f), 3, None)

    results.map(_.key) shouldBe Vector("a", "b", "c")
    transport.calls should have size 1
    val body = ujson.read(transport.calls.head.bodyString)
    body.obj.contains("filter") shouldBe false
    body.obj.contains("nextToken") shouldBe false
    body("topK").num shouldBe 3
  }

  it should "accept topK at the AWS limit of 100 in a single request" in {
    val transport = RecordingTransport(ok(queryResponse))
    vectorsClient(transport).queryVectors(Bucket, Index, Vector(0.1f), 100, None)
    transport.calls should have size 1
    ujson.read(transport.calls.head.bodyString)("topK").num shouldBe 100
  }

  it should "reject topK outside 1..100 (AWS QueryVectors limit) without calling the service" in {
    S3VectorsStorageClient.MaxTopK shouldBe 100
    val client = vectorsClient(NoNetwork)
    val tooLarge = the[IllegalArgumentException] thrownBy
      client.queryVectors(Bucket, Index, Vector(0.1f), 101, None)
    tooLarge.getMessage should include("between 1 and 100")
    tooLarge.getMessage should include("got 101")
    an[IllegalArgumentException] should be thrownBy
      client.queryVectors(Bucket, Index, Vector(0.1f), 500, None)
    an[IllegalArgumentException] should be thrownBy
      client.queryVectors(Bucket, Index, Vector(0.1f), 10000, None)
    an[IllegalArgumentException] should be thrownBy
      client.queryVectors(Bucket, Index, Vector(0.1f), 0, None)
  }

  it should "throw S3VectorsRequestException with status and error type on a non-retryable error" in {
    val transport = RecordingTransport(
      HttpReply(403, Map("x-amzn-errortype" -> "AccessDeniedException"), """{"message":"no"}""")
    )
    val e = the[S3VectorsRequestException] thrownBy
      vectorsClient(transport, maxRetries = 3).queryVectors(Bucket, Index, Vector(0.1f), 1, None)
    e.operation shouldBe "QueryVectors"
    e.status shouldBe 403
    e.getMessage should include("AccessDeniedException")
    transport.calls should have size 1
  }

  // ---------------------------------------------------------------------------
  // S3 Vectors: GetVectors
  // ---------------------------------------------------------------------------

  "S3VectorsStorageClient.getVectors" should
    "send signed POST /GetVectors in batches of at most 100 keys and parse data and metadata" in {
      val reply = ok(
        ujson.write(
          ujson.Obj(
            "vectors" -> ujson.Arr(
              ujson.Obj(
                "key"      -> "k1",
                "data"     -> ujson.Obj("float32" -> ujson.Arr(0.5, 0.25)),
                "metadata" -> S3VectorsMetadataCodec.toJson(meta())
              )
            )
          )
        )
      )
      val transport = RecordingTransport(reply)
      val records =
        vectorsClient(transport).getVectors(
          Bucket,
          Index,
          Vector.tabulate(250)(i => s"k$i"),
          returnData = true
        )

      transport.calls.map(c => ujson.read(c.bodyString)("keys").arr.size) shouldBe Seq(100, 100, 50)
      val call = transport.calls.head
      call.uri.getPath shouldBe "/GetVectors"
      call.header("Authorization").get should startWith(s3vScope("s3vectors"))
      val body = ujson.read(call.bodyString)
      body("vectorBucketName").str shouldBe Bucket
      body("indexName").str shouldBe Index
      body("returnData").bool shouldBe true
      body("returnMetadata").bool shouldBe true
      records.head shouldBe VectorRecord("k1", Vector(0.5f, 0.25f), meta())
    }

  // ---------------------------------------------------------------------------
  // Loopback HTTP server: real JavaHttpTransport, signed Host matches the wire Host
  // ---------------------------------------------------------------------------

  "JavaHttpTransport" should "deliver the signed headers to a local server with a matching Host" in {
    val server   = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val received = mutable.ArrayBuffer.empty[(String, String, Option[String], Option[String])]
    server.createContext(
      "/",
      ex =>
        val h = ex.getRequestHeaders
        received += ((
          ex.getRequestMethod,
          ex.getRequestURI.getPath,
          Option(h.getFirst("Authorization")),
          Option(h.getFirst("Host"))
        ))
        ex.sendResponseHeaders(200, -1)
        ex.close()
    )
    server.start()
    try
      val port            = server.getAddress.getPort
      val recordingSigned = mutable.ArrayBuffer.empty[HttpCall]
      val real            = JavaHttpTransport.create(Duration.ofSeconds(5))
      val spy = new HttpTransport:
        def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
          recordingSigned += call
          real.execute(call, timeout)
      val client =
        vectorsClient(spy, endpointOverride = Some(URI.create(s"http://127.0.0.1:$port")))
      client.putVectors(Bucket, Index, Vector(rec(1))) shouldBe PutVectorsResult(1, 0, Vector.empty)

      received should have size 1
      val (method, path, auth, host) = received.head
      method shouldBe "POST"
      path shouldBe "/PutVectors"
      auth.get should startWith(s3vScope("s3vectors"))
      host shouldBe recordingSigned.head.header("Host")
    finally server.stop(0)
  }

  // ---------------------------------------------------------------------------
  // Neptune (R5)
  // ---------------------------------------------------------------------------

  "NeptuneStorageClient" should "SigV4-sign openCypher requests with service neptune-db" in {
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    neptuneClient(transport).getResearcher("researcher:orcid:0000-0002") shouldBe None

    val call = transport.calls.head
    call.method shouldBe "POST"
    call.uri.toString shouldBe
      "https://scholarchain-dev.cluster-abc.us-east-1.neptune.amazonaws.com:8182/openCypher"
    call.header("Authorization").get should startWith(s3vScope("neptune-db"))
    call.header("X-Amz-Date") shouldBe Some(s"${ScopeDate}T120000Z")
    call.header("Host").get should startWith(
      "scholarchain-dev.cluster-abc.us-east-1.neptune.amazonaws.com"
    )
    call.header("Content-Type") shouldBe Some("application/x-www-form-urlencoded")
  }

  it should "send values as openCypher parameters, never inside the query text" in {
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    val hostile   = "x'}) MATCH (n) DETACH DELETE n //"
    neptuneClient(transport).getResearcher(hostile)

    val form = formParams(transport.calls.head.bodyString)
    form.keySet shouldBe Set("query", "parameters")
    form("query") should include("$rid")
    form("query") should not include hostile
    ujson.read(form("parameters")) shouldBe ujson.Obj("rid" -> hostile)
  }

  it should "find shortest paths with a bounded variable-length match, not shortestPath()" in {
    // N11: Neptune openCypher has no shortestPath(); the query orders bounded paths by length.
    val transport = RecordingTransport(ok("""{"results":[{"path":["r1","r2","r3"]}]}"""))
    val hostile   = "r9'}) MATCH (n) DETACH DELETE n //"
    neptuneClient(transport).shortestPath(
      "r1",
      hostile,
      4,
      Vector(GraphSchema.EdgeType.Coauthor)
    ) shouldBe
      Some(Vector("r1", "r2", "r3"))

    transport.calls should have size 1
    val form  = formParams(transport.calls.head.bodyString)
    val query = form("query")
    query should not include "shortestPath"
    query should include(
      "MATCH p = (a:Researcher {researcherId: $fromId})-[:COAUTHORED_WITH*1..4]-" +
        "(b:Researcher {researcherId: $toId})"
    )
    // N35: order and limit while p is in scope (WITH), then project in RETURN.
    query should include("WITH p ORDER BY length(p) ASC LIMIT 1")
    query.trim should endWith("RETURN [n IN nodes(p) | n.researcherId] AS path")
    query.indexOf("WITH p ORDER BY") should be < query.indexOf("RETURN")
    query should not include hostile
    ujson.read(form("parameters")) shouldBe ujson.Obj("fromId" -> "r1", "toId" -> hostile)
  }

  it should "clamp shortest-path depth to 1..10 and allow several explicit edge types" in {
    val (deep, _) = NeptuneStorageClient.shortestPathQuery("a", "b", 50)
    deep should include("-[:COAUTHORED_WITH*1..10]-")
    val (shallow, _) = NeptuneStorageClient.shortestPathQuery("a", "b", 0)
    shallow should include("-[:COAUTHORED_WITH*1..1]-")
    val (multi, params) =
      NeptuneStorageClient.shortestPathQuery(
        "a",
        "b",
        3,
        Vector(GraphSchema.EdgeType.Coauthor, GraphSchema.EdgeType.Authored)
      )
    multi should include("-[:COAUTHORED_WITH|AUTHORED*1..3]-")
    params shouldBe Map("fromId" -> ujson.Str("a"), "toId" -> ujson.Str("b"))
  }

  it should "default shortest-path traversal to coauthorship edges, never an untyped match" in {
    // N35: an untyped undirected *1..d match over every edge type is unbounded in practice.
    NeptuneStorageClient.DefaultPathEdgeTypes shouldBe Vector(GraphSchema.EdgeType.Coauthor)
    NeptuneStorageClient.MaxPathDepth shouldBe 10
    val (query, params) = NeptuneStorageClient.shortestPathQuery("a", "b", 6)
    query should include("-[:COAUTHORED_WITH*1..6]-")
    query should not include "-[*"
    params shouldBe Map("fromId" -> ujson.Str("a"), "toId" -> ujson.Str("b"))

    // The client's no-edge-type call (the rag PathFinderAgent path) sends the same restricted query.
    val transport = RecordingTransport(ok("""{"results":[]}"""))
    neptuneClient(transport).shortestPath("a", "b", 6) shouldBe None
    val sent = formParams(transport.calls.head.bodyString)("query")
    sent should include("-[:COAUTHORED_WITH*1..6]-")
    sent should include("WITH p ORDER BY length(p) ASC LIMIT 1")
  }

  it should "reject non-identifier edge types in shortestPath before sending anything" in {
    an[IllegalArgumentException] should be thrownBy
      neptuneClient(NoNetwork).shortestPath("a", "b", 3, Vector("COAUTHOR*]-() DELETE"))
  }

  it should "reject non-identifier edge types before sending anything" in {
    an[IllegalArgumentException] should be thrownBy
      neptuneClient(NoNetwork).getNeighbors("n1", Vector("COAUTHOR]-() DELETE"), 1, 10)
  }

  it should "raise NeptuneRequestException on an HTTP error instead of parsing it as results" in {
    val transport = RecordingTransport(
      HttpReply(403, Map.empty, """{"code":"AccessDeniedException","detailedMessage":"denied"}""")
    )
    val e = the[NeptuneRequestException] thrownBy neptuneClient(transport).getWork("work:1")
    e.status shouldBe 403
    e.getMessage should include("AccessDeniedException")
  }

  // ---------------------------------------------------------------------------
  // Secrets never in logs, exceptions or toString
  // ---------------------------------------------------------------------------

  "Signed storage clients" should "never expose the secret key in logs, errors or exceptions" in {
    val leakyMessage = s"connection reset; secret=$DummySecretKey Signature=${"ab" * 32}"
    val failing = new HttpTransport:
      def execute(call: HttpCall, timeout: Option[Duration]): HttpReply =
        throw IOException(leakyMessage)
    val echoing = RecordingTransport(
      HttpReply(500, Map.empty, s"""{"message":"echo $DummySecretKey"}"""),
      HttpReply(400, Map.empty, s"""{"message":"bad $DummySecretKey"}""")
    )

    val (putResult, putLogs) = captureLogs(classOf[S3VectorsStorageClient]) {
      vectorsClient(failing, maxRetries = 1).putVectors(Bucket, Index, Vector(rec(1)))
    }
    val (queryResult, queryLogs) = captureLogs(classOf[S3VectorsStorageClient]) {
      vectorsClient(echoing, maxRetries = 1).queryVectors(Bucket, Index, Vector(0.1f), 1, None)
    }
    val (neptuneResult, neptuneLogs) = captureLogs(classOf[NeptuneStorageClient]) {
      neptuneClient(failing).getWork("work:1")
    }

    val errors   = putResult.get.errors
    val queryErr = queryResult.failed.get
    val nepErr   = neptuneResult.failed.get
    queryErr shouldBe a[S3VectorsRequestException]
    nepErr shouldBe a[NeptuneRequestException]

    val everything =
      errors ++ putLogs ++ queryLogs ++ neptuneLogs ++
        Seq(queryErr.getMessage, nepErr.getMessage, queryErr.toString, nepErr.toString) ++
        Seq(
          vectorsClient(NoNetwork).toString,
          neptuneClient(NoNetwork).toString,
          SigV4Signer(dummyCredentials, Region, "s3vectors").toString
        )
    putLogs should not be empty
    neptuneLogs should not be empty
    everything.foreach { text =>
      text should not include DummySecretKey
      text should not include ("ab" * 32)
    }
    errors.head should include(SigV4Signer.Redacted)
  }

  it should "never expose a session token in our logs, errors or the SDK signer logs" in {
    val token = "DummySessionToken0123456789ZZZ"
    val session = software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
      software.amazon.awssdk.auth.credentials.AwsSessionCredentials
        .create(DummyAccessKey, DummySecretKey, token)
    )
    val echoing = RecordingTransport(
      HttpReply(400, Map.empty, s"""{"message":"bad token $token key $DummySecretKey"}""")
    )
    val client    = S3VectorsStorageClient(Region, 0, 0L, echoing, session, clock = fixedClock)
    val sdkLogger = LoggerFactory.getLogger("software.amazon.awssdk").asInstanceOf[LogbackLogger]
    val sdkLogs   = ListAppender[ILoggingEvent]()
    sdkLogs.start()
    sdkLogger.addAppender(sdkLogs)
    val (result, ourLogs) =
      try
        captureLogs(classOf[S3VectorsStorageClient])(
          client.putVectors(Bucket, Index, Vector(rec(1)))
        )
      finally sdkLogger.detachAppender(sdkLogs)

    // the token is signed into the request itself...
    echoing.calls.head.header("X-Amz-Security-Token") shouldBe Some(token)
    // ...but never appears in anything we log or return
    val texts = result.get.errors ++ ourLogs ++ sdkLogs.list.asScala.map(_.getFormattedMessage)
    texts should not be empty
    texts.foreach { t =>
      t should not include token
      t should not include DummySecretKey
    }
  }

  "SigV4Signer" should "warn when the SDK signer logger is at DEBUG (it would log session tokens)" in {
    val sdkSigner =
      LoggerFactory.getLogger(SigV4Signer.SdkSignerLoggerName).asInstanceOf[LogbackLogger]
    val previous = sdkSigner.getLevel
    sdkSigner.setLevel(ch.qos.logback.classic.Level.DEBUG)
    val call =
      HttpCall("POST", URI.create("https://x.example:8182/openCypher"), Vector.empty, Array())
    val (_, logs) =
      try
        captureLogs(classOf[SigV4Signer])(
          SigV4Signer(dummyCredentials, Region, "neptune-db").sign(call)
        )
      finally sdkSigner.setLevel(previous)
    // `contain`, not equality: suites run in parallel and may sign while DEBUG is on.
    logs should contain(SigV4Signer.SdkDebugWarning)

    val (_, quiet) =
      captureLogs(classOf[SigV4Signer])(
        SigV4Signer(dummyCredentials, Region, "neptune-db").sign(call)
      )
    quiet shouldBe empty
  }

  it should "reject a malformed region (no host injection)" in {
    an[IllegalArgumentException] should be thrownBy
      SigV4Signer(dummyCredentials, "evil.com/x", "s3vectors")
    an[IllegalArgumentException] should be thrownBy
      S3VectorsStorageClient.defaultEndpoint("us-east-1.attacker.net#")
  }

  // ---------------------------------------------------------------------------
  // R4: embedding queue URL from config / env (without the real process env)
  // ---------------------------------------------------------------------------

  private val QueueUrl = "https://sqs.us-east-1.amazonaws.com/123456789012/scholarchain-dev-embed"

  private class RecordingQueue extends QueueClient:
    val sentTo: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

    def sendMessage(queueUrl: String, body: String): Try[String] =
      sentTo += queueUrl; Success("m-1")

    def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[ReceivedMessage]] =
      sentTo += queueUrl; Success(Nil)
    def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] = Success(())

  "StorageModuleConfig regions" should "default to us-east-2 for Neptune, DynamoDB and S3 Vectors" in {
    val cfg = StorageModuleConfig.loadWithEnv(Map.empty)
    cfg.neptune.region shouldBe "us-east-2"
    cfg.dynamoDb.region shouldBe "us-east-2"
    cfg.s3Vectors.region shouldBe "us-east-2"
  }

  they should "be overridable per service from the environment" in {
    val cfg = StorageModuleConfig.loadWithEnv(
      Map(
        "SCHOLARCHAIN_NEPTUNE_REGION"    -> "eu-west-1",
        "SCHOLARCHAIN_DYNAMODB_REGION"   -> "us-west-2",
        "SCHOLARCHAIN_S3_VECTORS_REGION" -> "ap-southeast-2"
      )
    )
    cfg.neptune.region shouldBe "eu-west-1"
    cfg.dynamoDb.region shouldBe "us-west-2"
    cfg.s3Vectors.region shouldBe "ap-southeast-2"
  }

  "StorageModuleConfig.embeddingQueueUrl" should "be unset by default (no hardcoded queue URL)" in {
    StorageModuleConfig.loadWithEnv(Map.empty).embeddingQueueUrl shouldBe None
  }

  it should "come from SCHOLARCHAIN_EMBEDDING_QUEUE_URL via an injected environment" in {
    val cfg =
      StorageModuleConfig.loadWithEnv(Map(StorageModuleConfig.EmbeddingQueueUrlEnv -> QueueUrl))
    StorageModuleConfig.EmbeddingQueueUrlEnv shouldBe "SCHOLARCHAIN_EMBEDDING_QUEUE_URL"
    cfg.embeddingQueueUrl shouldBe Some(QueueUrl)
  }

  it should "come from the scholarchain.storage.embedding-queue-url key" in {
    val cfg = StorageModuleConfig.load(
      ConfigFactory
        .parseString(s"""scholarchain.storage.embedding-queue-url = "$QueueUrl"""")
        .withFallback(ConfigFactory.parseResources("reference.conf"))
        .resolve(com.typesafe.config.ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    )
    cfg.embeddingQueueUrl shouldBe Some(QueueUrl)
  }

  it should "resolve the other infra-wired env vars the same way" in {
    val cfg = StorageModuleConfig.loadWithEnv(
      Map(
        "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX" -> "scholarchain-dev",
        "SCHOLARCHAIN_S3_RAG_BUCKET"         -> "scholarchain-dev-rag",
        "SCHOLARCHAIN_NEPTUNE_ENDPOINT"      -> "cluster.example",
        "SCHOLARCHAIN_NEPTUNE_PORT"          -> "8183",
        "SCHOLARCHAIN_S3_VECTORS_BUCKET"     -> "scholarchain-dev-vectors"
      )
    )
    cfg.dynamoDb.tablePrefix shouldBe "scholarchain-dev"
    cfg.s3.ragBucket shouldBe "scholarchain-dev-rag"
    cfg.neptune.endpoint shouldBe "cluster.example"
    cfg.neptune.port shouldBe 8183
    cfg.s3Vectors.vectorBucket shouldBe "scholarchain-dev-vectors"
  }

  "EmbeddingQueueManager.fromConfig" should "use the configured queue URL for every queue call" in {
    val cfg =
      StorageModuleConfig.loadWithEnv(Map(StorageModuleConfig.EmbeddingQueueUrlEnv -> QueueUrl))
    val queue   = RecordingQueue()
    val manager = EmbeddingQueueManager.fromConfig(queue, cfg, mutable.Set.empty).get
    manager.queueUrl shouldBe QueueUrl
    val job =
      EmbeddingJob("j1", "e1", "researcher-profile", "s3://b/k", "h", "m", None, None, "r", 0)
    manager.enqueue(job).get shouldBe "m-1"
    manager.dequeueBatch(10).get shouldBe Nil
    queue.sentTo.toSeq shouldBe Seq(QueueUrl, QueueUrl)
  }

  it should "fail clearly when the queue URL is not configured" in {
    val result =
      EmbeddingQueueManager.fromConfig(
        RecordingQueue(),
        StorageModuleConfig.loadWithEnv(Map.empty),
        mutable.Set.empty
      )
    result.isFailure shouldBe true
    result.failed.get.getMessage should include("SCHOLARCHAIN_EMBEDDING_QUEUE_URL")
  }

  "EmbeddingQueueManager" should "reject a blank queue URL" in {
    an[IllegalArgumentException] should be thrownBy
      EmbeddingQueueManager(RecordingQueue(), "  ", mutable.Set.empty)
  }
