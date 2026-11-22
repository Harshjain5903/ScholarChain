package edu.scholarchain.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import upickle.default.*

class ServiceRoutesSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var server: com.sun.net.httpserver.HttpServer = _
  private var baseUrl: String                           = _
  private val client                                    = HttpClient.newHttpClient()

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

  private def get(path: String): HttpResponse[String] =
    val req = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl$path"))
      .GET()
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofString())

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

  // 1. POST /v1/query valid request returns 200 with queryId and mode=synchronous
  "POST /v1/query" should "return 200 with queryId and synchronous mode for a valid request" in {
    val body =
      """{"question":"Who are the top collaborators in NLP?"}"""
    val resp = post("/v1/query", body)
    resp.statusCode() shouldBe 200
    val parsed = read[QueryResponse](resp.body())
    parsed.queryId should not be empty
    parsed.mode shouldBe "synchronous"
  }

  // 2. POST /v1/query empty body returns 400
  it should "return 400 for an empty body" in {
    val resp = post("/v1/query", "")
    resp.statusCode() shouldBe 400
  }

  // 3. POST /v1/query invalid JSON returns 400
  it should "return 400 for invalid JSON" in {
    val resp = post("/v1/query", "{not valid json")
    resp.statusCode() shouldBe 400
  }

  // 4. POST /v1/query oversized body returns 400
  it should "return 400 for oversized body" in {
    val config = ServiceConfig(
      port = 0,
      host = "127.0.0.1",
      syncDeadlineMs = 15000L,
      maxRequestBodyBytes = 50,
      maxGraphDepth = 3,
      maxGraphLimit = 200
    )
    val (wf, gs, as) = RuntimeComposer.composeStubs()
    val routes       = Routes(wf, gs, as, config)
    val smallServer  = routes.start()
    val smallPort    = smallServer.getAddress.getPort
    val smallBase    = s"http://127.0.0.1:$smallPort"
    val bigBody      = """{"question":"""" + "x" * 100 + "\"}"
    val req = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$smallBase/v1/query"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(bigBody))
      .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    resp.statusCode() shouldBe 400
    smallServer.stop(0)
  }

  // 5. POST /v1/query with Idempotency-Key returns same response on replay
  it should "return cached response for duplicate Idempotency-Key" in {
    val body =
      """{"question":"Who bridges computer vision and NLP?"}"""
    val resp1 =
      post("/v1/query", body, Map("Idempotency-Key" -> "idem-001"))
    resp1.statusCode() shouldBe 200
    val resp2 =
      post("/v1/query", body, Map("Idempotency-Key" -> "idem-001"))
    resp2.statusCode() shouldBe 200
    resp1.body() shouldBe resp2.body()
  }

  // 6. GET /v1/jobs/unknown-id returns 404
  "GET /v1/jobs" should "return 404 for unknown job ID" in {
    val resp = get("/v1/jobs/unknown-id")
    resp.statusCode() shouldBe 404
  }

  // 7. GET /v1/evidence/unknown-id returns 404
  "GET /v1/evidence" should "return 404 for unknown evidence ID" in {
    val resp = get("/v1/evidence/unknown-id")
    resp.statusCode() shouldBe 404
  }

  // 8. GET /v1/graph/nodes/n1/neighbors default params returns 200
  "GET /v1/graph/nodes" should "return 200 for neighbor lookup with default params" in {
    val resp = get("/v1/graph/nodes/n1/neighbors")
    resp.statusCode() shouldBe 200
  }

  // 9. GET /v1/graph/nodes/n1/neighbors?depth=0 returns 400
  it should "return 400 for depth=0" in {
    val resp = get("/v1/graph/nodes/n1/neighbors?depth=0")
    resp.statusCode() shouldBe 400
  }

  // 10. GET /v1/graph/nodes/n1/neighbors?depth=4 returns 400
  it should "return 400 for depth=4 exceeding max" in {
    val resp = get("/v1/graph/nodes/n1/neighbors?depth=4")
    resp.statusCode() shouldBe 400
  }

  // 11. GET /v1/graph/nodes/n1/neighbors?limit=201 returns 400
  it should "return 400 for limit=201 exceeding max" in {
    val resp = get("/v1/graph/nodes/n1/neighbors?limit=201")
    resp.statusCode() shouldBe 400
  }

  // 12. GET /v1/explain/unknown-id returns 404
  "GET /v1/explain" should "return 404 for unknown query ID" in {
    val resp = get("/v1/explain/unknown-id")
    resp.statusCode() shouldBe 404
  }

  // 13. GET /healthz returns 200 with status ok
  "GET /healthz" should "return 200 with status ok" in {
    val resp = get("/healthz")
    resp.statusCode() shouldBe 200
    resp.body() should include("\"status\":\"ok\"")
  }

  // 14. GET /readyz returns 200
  "GET /readyz" should "return 200 with status ready" in {
    val resp = get("/readyz")
    resp.statusCode() shouldBe 200
    resp.body() should include("\"status\":\"ready\"")
  }

  // 15. All responses include X-Trace-Id header
  "All responses" should "include X-Trace-Id header" in {
    val resp1 = get("/healthz")
    resp1.headers().firstValue("X-Trace-Id").isPresent shouldBe true
    resp1
      .headers()
      .firstValue("X-Trace-Id")
      .get() should startWith("trace-")

    val resp2 = get("/v1/jobs/no-such-job")
    resp2.headers().firstValue("X-Trace-Id").isPresent shouldBe true

    val resp3 = post(
      "/v1/query",
      """{"question":"test"}"""
    )
    resp3.headers().firstValue("X-Trace-Id").isPresent shouldBe true
  }

  // 16. Error responses are well-formed JSON
  "Error responses" should "contain well-formed ErrorResponse JSON" in {
    val resp = get("/v1/jobs/missing")
    resp.statusCode() shouldBe 404
    val err = read[ErrorResponse](resp.body())
    err.error shouldBe "NOT_FOUND"
    err.retryable shouldBe false
    err.traceId should startWith("trace-")
  }

  // 17. POST /v1/query with empty question returns 400
  "POST /v1/query" should "return 400 for empty question field" in {
    val resp = post("/v1/query", """{"question":"   "}""")
    resp.statusCode() shouldBe 400
  }

  // 18. Graph neighbors with valid depth and limit
  "GET /v1/graph/nodes" should "return 200 for valid depth and limit" in {
    val resp =
      get("/v1/graph/nodes/n1/neighbors?depth=2&limit=100")
    resp.statusCode() shouldBe 200
  }
