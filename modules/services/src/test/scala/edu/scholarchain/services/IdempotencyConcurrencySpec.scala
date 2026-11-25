package edu.scholarchain.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{Callable, CountDownLatch, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import edu.scholarchain.rag.WorkflowResult

/**
 * Deterministic concurrency tests for Idempotency-Key single-flight handling.
 *
 * Each test gates the workflow on a latch and only releases it once every concurrent request is
 * provably either inside the workflow or parked waiting on the in-flight leader. A check-then-act
 * implementation lets all requests into the workflow and fails these tests on every run; it does
 * not depend on thread timing.
 */
class IdempotencyConcurrencySpec extends AnyFlatSpec with Matchers:

  private val Concurrency = 4

  private def config = ServiceConfig(
    port = 0,
    host = "127.0.0.1",
    syncDeadlineMs = 15000L,
    maxRequestBodyBytes = 1048576,
    maxGraphDepth = 3,
    maxGraphLimit = 200
  )

  /** Workflow that counts entries and blocks until the gate opens. */
  final private class GatedWorkflow(delegate: WorkflowPort) extends WorkflowPort:
    val entered = new AtomicInteger(0)
    val gate    = new CountDownLatch(1)

    def execute(question: String, constraints: Map[String, String]): WorkflowResult =
      entered.incrementAndGet()
      if !gate.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("gate never opened")
      delegate.execute(question, constraints)

  /**
   * Polls a condition with a generous safety deadline; the condition, not timing, drives the test.
   */
  private def awaitCondition(what: String)(cond: => Boolean): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while !cond do
      if System.nanoTime() > deadline then fail(s"condition never reached: $what")
      Thread.sleep(5)

  private def withServer[A](workflow: WorkflowPort, store: IdempotencyStore)(
    f: String => A
  ): A =
    val (_, graph, audit) = RuntimeComposer.composeStubs()
    val server            = Routes(workflow, graph, audit, config, Some(store)).start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}")
    finally server.stop(0)

  private val client = HttpClient.newHttpClient()

  private def post(baseUrl: String, body: String, key: String): HttpResponse[String] =
    val req = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$baseUrl/v1/query"))
      .header("Content-Type", "application/json")
      .header("Idempotency-Key", key)
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    client.send(req, HttpResponse.BodyHandlers.ofString())

  "POST /v1/query with a shared Idempotency-Key" should
    "execute the workflow once and return one body when duplicates are forced to overlap" in {
      val (stub, _, _) = RuntimeComposer.composeStubs()
      val workflow     = GatedWorkflow(stub)
      val store        = IdempotencyStore(awaitTimeoutMs = 30000L)
      val key          = s"forced-overlap-${java.util.UUID.randomUUID()}"
      val body         = """{"question":"Who bridges computer vision and NLP?"}"""

      withServer(workflow, store) { baseUrl =>
        val executor = Executors.newFixedThreadPool(Concurrency)
        try
          val futures: Seq[Future[HttpResponse[String]]] = (1 to Concurrency).map { _ =>
            executor.submit(
              new Callable[HttpResponse[String]]:
                def call(): HttpResponse[String] = post(baseUrl, body, key)
            )
          }

          // Barrier: every request is inside the workflow or waiting on the leader.
          awaitCondition("all requests in flight") {
            workflow.entered.get() + store.waiterCount(key) == Concurrency
          }
          workflow.gate.countDown()

          val responses = futures.map(_.get(30, TimeUnit.SECONDS))
          responses.foreach(_.statusCode() shouldBe 200)
          workflow.entered.get() shouldBe 1
          responses.map(_.body()).toSet.size shouldBe 1
        finally executor.shutdownNow()
      }
    }

  it should "return 409 CONFLICT for the same key with a different body (N58)" in {
    val (stub, _, _) = RuntimeComposer.composeStubs()
    val store        = IdempotencyStore()
    val key          = s"diff-body-${java.util.UUID.randomUUID()}"
    withServer(stub, store) { baseUrl =>
      val first  = post(baseUrl, """{"question":"first question"}""", key)
      val second = post(baseUrl, """{"question":"second question"}""", key)
      first.statusCode() shouldBe 200
      second.statusCode() shouldBe 409
      val err = upickle.default.read[ErrorResponse](second.body())
      err.error shouldBe "CONFLICT"
      err.retryable shouldBe false
      // the original request still replays
      post(baseUrl, """{"question":"first question"}""", key).body() shouldBe first.body()
    }
  }

  it should "treat a reformatted but identical body as the same request" in {
    val (stub, _, _) = RuntimeComposer.composeStubs()
    val store        = IdempotencyStore()
    val key          = s"same-body-${java.util.UUID.randomUUID()}"
    withServer(stub, store) { baseUrl =>
      val first = post(baseUrl, """{"question":"q","constraints":{"yearFrom":2015}}""", key)
      val second =
        post(baseUrl, """{ "constraints" : { "yearFrom" : 2015 }, "question" : "q" }""", key)
      second.statusCode() shouldBe 200
      second.body() shouldBe first.body()
    }
  }

  it should "return 400 for an over-long or blank Idempotency-Key" in {
    val (stub, _, _) = RuntimeComposer.composeStubs()
    val store        = IdempotencyStore(maxKeyLength = 16)
    withServer(stub, store) { baseUrl =>
      post(baseUrl, """{"question":"q"}""", "k" * 17).statusCode() shouldBe 400
      post(baseUrl, """{"question":"q"}""", "k" * 16).statusCode() shouldBe 200
    }
    intercept[BadRequestException](store.getOrCompute("   ")("v"))
  }

  "IdempotencyStore" should "compute once and share the value with all concurrent followers" in {
    val store    = IdempotencyStore(awaitTimeoutMs = 30000L)
    val computed = new AtomicInteger(0)
    val gate     = new CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(Concurrency)
    try
      val tasks = (1 to Concurrency).map { i =>
        new Callable[String]:
          def call(): String = store.getOrCompute("k") {
            computed.incrementAndGet()
            gate.await(30, TimeUnit.SECONDS)
            s"value-$i"
          }
      }
      val futures = tasks.map(t => executor.submit(t))
      awaitCondition("leader computing and followers parked") {
        computed.get() + store.waiterCount("k") == Concurrency
      }
      gate.countDown()
      val values = futures.map(_.get(30, TimeUnit.SECONDS))
      computed.get() shouldBe 1
      values.toSet.size shouldBe 1
      store.waiterCount("k") shouldBe 0
    finally executor.shutdownNow()
  }

  it should "propagate a leader failure to waiting followers and not cache it" in {
    val store    = IdempotencyStore(awaitTimeoutMs = 30000L)
    val entered  = new CountDownLatch(1)
    val gate     = new CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try
      val leader = executor.submit(
        new Callable[String]:
          def call(): String = store.getOrCompute("f") {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
            throw DownstreamException("workflow failed")
          }
      )
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      val follower = executor.submit(
        new Callable[String]:
          def call(): String = store.getOrCompute("f")(fail("follower must not compute"))
      )
      awaitCondition("follower parked")(store.waiterCount("f") == 1)
      gate.countDown()

      val leaderErr   = intercept[java.util.concurrent.ExecutionException](leader.get())
      val followerErr = intercept[java.util.concurrent.ExecutionException](follower.get())
      leaderErr.getCause shouldBe a[DownstreamException]
      followerErr.getCause shouldBe a[DownstreamException]

      store.contains("f") shouldBe false
      store.getOrCompute("f")("recovered") shouldBe "recovered"
    finally executor.shutdownNow()
  }

  it should "fail a follower with TimeoutException when the leader exceeds the await deadline" in {
    val store    = IdempotencyStore(awaitTimeoutMs = 50L)
    val entered  = new CountDownLatch(1)
    val gate     = new CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try
      val leader = executor.submit(
        new Callable[String]:
          def call(): String = store.getOrCompute("t") {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
            "late"
          }
      )
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      intercept[TimeoutException](store.getOrCompute("t")("unused"))
      gate.countDown()
      leader.get(30, TimeUnit.SECONDS) shouldBe "late"
      store.getOrCompute("t")("unused") shouldBe "late"
    finally executor.shutdownNow()
  }

  it should "fail a concurrent follower with a different fingerprint with ConflictException" in {
    val store    = IdempotencyStore(awaitTimeoutMs = 30000L)
    val entered  = new CountDownLatch(1)
    val gate     = new CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try
      val leader = executor.submit(
        new Callable[String]:
          def call(): String = store.getOrCompute("c", "fp-1") {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
            "v1"
          }
      )
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      intercept[ConflictException](store.getOrCompute("c", "fp-2")(fail("must not compute")))
      gate.countDown()
      leader.get(30, TimeUnit.SECONDS) shouldBe "v1"
      intercept[ConflictException](store.getOrCompute("c", "fp-2")(fail("must not compute")))
      store.getOrCompute("c", "fp-1")(fail("must replay")) shouldBe "v1"
    finally executor.shutdownNow()
  }

  it should "expire completed entries after the TTL" in {
    var now       = 0L
    val store     = IdempotencyStore(ttlMs = 1000L, nowMs = () => now)
    val calls     = new AtomicInteger(0)
    def compute() = s"v${calls.incrementAndGet()}"
    store.getOrCompute("k", "fp")(compute()) shouldBe "v1"
    now = 999L
    store.getOrCompute("k", "fp")(compute()) shouldBe "v1"
    now = 1000L
    store.getOrCompute("k", "fp")(compute()) shouldBe "v2"
    // after expiry the key may be reused with another body
    now = 2000L
    store.getOrCompute("k", "other")(compute()) shouldBe "v3"
  }

  it should "evict the least recently used completed entry beyond maxEntries" in {
    val store = IdempotencyStore(maxEntries = 2)
    store.getOrCompute("a", "fp")("A")
    store.getOrCompute("b", "fp")("B")
    store.getOrCompute("a", "fp")(fail("a is cached")) shouldBe "A" // a is now most recent
    store.getOrCompute("c", "fp")("C")
    store.size shouldBe 2
    store.contains("b") shouldBe false
    store.contains("a") shouldBe true
    store.contains("c") shouldBe true
  }

  it should "never evict an in-flight entry" in {
    val store    = IdempotencyStore(maxEntries = 1, awaitTimeoutMs = 30000L)
    val entered  = new CountDownLatch(1)
    val gate     = new CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try
      val leader = executor.submit(
        new Callable[String]:
          def call(): String = store.getOrCompute("inflight", "fp") {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
            "slow"
          }
      )
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      store.getOrCompute("other", "fp")("fast") shouldBe "fast"
      store.contains("inflight") shouldBe true
      gate.countDown()
      leader.get(30, TimeUnit.SECONDS) shouldBe "slow"
      store.size shouldBe 1
    finally executor.shutdownNow()
  }

  it should "fail the leader and its followers with TimeoutException at the leader deadline" in {
    val store    = IdempotencyStore(awaitTimeoutMs = 30000L, leaderDeadlineMs = 1000L)
    val release  = new CountDownLatch(1)
    val entered  = new CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try
      val follower = executor.submit(
        new Callable[String]:
          def call(): String =
            entered.await(30, TimeUnit.SECONDS)
            store.getOrCompute("d", "fp")(fail("follower must not compute"))
      )
      val leaderErr = intercept[TimeoutException] {
        store.getOrCompute("d", "fp") {
          entered.countDown()
          // the follower parks on this in-flight entry well within the 1 s deadline
          awaitCondition("follower parked")(store.waiterCount("d") == 1)
          release.await(30, TimeUnit.SECONDS) // interrupted at the deadline
          "never"
        }
      }
      leaderErr.getMessage should include("deadline")
      val followerErr =
        intercept[java.util.concurrent.ExecutionException](follower.get(30, TimeUnit.SECONDS))
      followerErr.getCause shouldBe a[TimeoutException]
      store.contains("d") shouldBe false
      store.getOrCompute("d", "fp")("recovered") shouldBe "recovered"
    finally executor.shutdownNow()
  }

  "POST /v1/query" should "return 504 when the workflow exceeds the sync deadline" in {
    val slow = new WorkflowPort:
      def execute(question: String, constraints: Map[String, String]): WorkflowResult =
        Thread.sleep(10000)
        throw new IllegalStateException("unreachable")
    val (_, graph, audit) = RuntimeComposer.composeStubs()
    val server =
      Routes(slow, graph, audit, config.copy(syncDeadlineMs = 100L)).start()
    try
      val base = s"http://127.0.0.1:${server.getAddress.getPort}"
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(s"$base/v1/query"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"question":"slow"}"""))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      resp.statusCode() shouldBe 504
      upickle.default.read[ErrorResponse](resp.body()).retryable shouldBe true
    finally server.stop(0)
  }
