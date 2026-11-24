package edu.scholarchain.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{Callable, CountDownLatch, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import edu.scholarchain.rag.WorkflowResult

/** Deterministic concurrency tests for Idempotency-Key single-flight handling.
  *
  * Each test gates the workflow on a latch and only releases it once every concurrent request is
  * provably either inside the workflow or parked waiting on the in-flight leader. A
  * check-then-act implementation lets all requests into the workflow and fails these tests on
  * every run; it does not depend on thread timing.
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
  private final class GatedWorkflow(delegate: WorkflowPort) extends WorkflowPort:
    val entered = new AtomicInteger(0)
    val gate    = new CountDownLatch(1)

    def execute(question: String, constraints: Map[String, String]): WorkflowResult =
      entered.incrementAndGet()
      if !gate.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("gate never opened")
      delegate.execute(question, constraints)

  /** Polls a condition with a generous safety deadline; the condition, not timing, drives the test. */
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
            executor.submit(new Callable[HttpResponse[String]]:
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

  it should "keep returning the cached response for the same key with a different body" in {
    val (stub, _, _) = RuntimeComposer.composeStubs()
    val store        = IdempotencyStore()
    val key          = s"diff-body-${java.util.UUID.randomUUID()}"
    withServer(stub, store) { baseUrl =>
      val first  = post(baseUrl, """{"question":"first question"}""", key)
      val second = post(baseUrl, """{"question":"second question"}""", key)
      first.statusCode() shouldBe 200
      second.statusCode() shouldBe 200
      second.body() shouldBe first.body()
    }
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
      val leader = executor.submit(new Callable[String]:
        def call(): String = store.getOrCompute("f") {
          entered.countDown()
          gate.await(30, TimeUnit.SECONDS)
          throw DownstreamException("workflow failed")
        }
      )
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      val follower = executor.submit(new Callable[String]:
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
      val leader = executor.submit(new Callable[String]:
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
