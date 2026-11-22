package edu.scholarchain.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import upickle.default.*

class AdversarialServiceSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll:

  private var server: com.sun.net.httpserver.HttpServer = _
  private var baseUrl: String                           = _
  private val client = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val config = ServiceConfig(
      port = 0,
      host = "127.0.0.1",
      syncDeadlineMs = 15000L,
      maxRequestBodyBytes = 1048576,
      maxGraphDepth = 3,
      maxGraphLimit = 200
    )
    val (workflow, graph, audit) = RuntimeComposer.composeStubs()
    val routes                   = Routes(workflow, graph, audit, config)
    server = routes.start()
    val port = server.getAddress.getPort
    baseUrl = s"http://127.0.0.1:$port"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  private def post(
      path: String,
      body: String,
      headers: Map[String, String] = Map.empty
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach { case (k, v) => builder.header(k, v) }
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private def postRaw(
      path: String,
      body: String,
      contentType: Option[String] = None
  ): HttpResponse[String] =
    val builder = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .POST(HttpRequest.BodyPublishers.ofString(body))
    contentType.foreach(ct => builder.header("Content-Type", ct))
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  // --- 1. Extremely long question string (10KB+) ---

  "POST /v1/query" should "handle a very long question string (10KB+) without crashing" in {
    val longQuestion = "a" * 10240
    val body         = s"""{"question":"$longQuestion"}"""
    val resp         = post("/v1/query", body)
    // Should return 200 (accepted) or 400 (rejected), but NOT crash
    Set(200, 400) should contain(resp.statusCode())
  }

  // --- 2. Nested JSON bomb (deeply nested objects) ---

  it should "reject or handle deeply nested JSON objects gracefully" in {
    // Build 50-level deeply nested JSON
    val depth   = 50
    val opening = "{\"question\":" * depth
    val closing = "}" * depth
    val body    = opening + "\"deep\"" + closing

    val resp = post("/v1/query", body)
    // Should return 400 for invalid structure or 200 if it somehow processes
    // Key assertion: should not crash with StackOverflowError
    Set(200, 400) should contain(resp.statusCode())
  }

  // --- 3. Missing Content-Type header on POST ---

  it should "handle POST without Content-Type header" in {
    val body = """{"question":"Who are the top collaborators?"}"""
    val resp = postRaw("/v1/query", body, contentType = None)
    // Server should still try to process the body or return 400
    // Key: does not crash
    resp.statusCode() should (be >= 200 and be < 600)
  }

  // --- 4. Concurrent duplicate Idempotency-Key requests ---

  it should "return consistent responses for concurrent duplicate Idempotency-Key" in {
    import java.util.concurrent.{Executors, Callable, Future}

    val executor = Executors.newFixedThreadPool(4)
    val body =
      """{"question":"Who bridges computer vision and NLP?"}"""
    val idempotencyKey = s"concurrent-idem-${java.util.UUID.randomUUID()}"

    val tasks = (1 to 4).map { _ =>
      new Callable[HttpResponse[String]]:
        def call(): HttpResponse[String] =
          post("/v1/query", body, Map("Idempotency-Key" -> idempotencyKey))
    }

    import scala.jdk.CollectionConverters.*
    val futures: java.util.List[Future[HttpResponse[String]]] =
      executor.invokeAll(tasks.asJava)

    val responses = futures.asScala.map(_.get())
    executor.shutdown()

    // All responses should succeed
    responses.foreach(_.statusCode() shouldBe 200)

    // All responses should have the same body (idempotent)
    val bodies = responses.map(_.body()).toSet
    bodies.size shouldBe 1
  }

  // --- 5. POST with valid JSON but wrong schema ---

  it should "return 400 for JSON with wrong schema" in {
    val wrongSchema = """{"notAQuestion": "this field is wrong"}"""
    val resp        = post("/v1/query", wrongSchema)
    resp.statusCode() shouldBe 400
  }

  // --- 6. POST /v1/query with question containing only whitespace ---

  it should "return 400 for question that is only whitespace" in {
    val resp = post("/v1/query", """{"question":"     "}""")
    resp.statusCode() shouldBe 400
    val err = read[ErrorResponse](resp.body())
    err.error shouldBe "BAD_REQUEST"
  }
