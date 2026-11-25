package edu.scholarchain.services

import edu.scholarchain.core.*
import edu.scholarchain.rag.*
import edu.scholarchain.services.ServiceTestFakes.*
import edu.scholarchain.storage.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.collection.mutable

/**
 * The aws runtime composed over fake storage transports and fake model clients: no socket is
 * opened. Also covers durable results: the jobs, explain and evidence routes of a fresh `Routes`
 * instance (a "restarted" task) answer from the persisted records alone.
 */
class AwsRuntimeSpec extends AnyFlatSpec with Matchers:

  private val adaId   = "researcher:orcid:0000-0001-0000-0001"
  private val graceId = "researcher:orcid:0000-0001-0000-0002"

  private val extractive = appConfig(
    Map(
      "SCHOLARCHAIN_SYNTHESIS_MODE"      -> "extractive",
      "SCHOLARCHAIN_METRIC_WINDOW_START" -> "1990",
      "SCHOLARCHAIN_METRIC_WINDOW_END"   -> "2020"
    )
  )

  private val readyEnv = Readiness.AwsApiRequiredEnv.map(_ -> "set").toMap

  private def provJson = ujson.write(writeJs(prov))

  private def researcherNode(id: String, name: String) = ujson.Obj(
    "~id"         -> id,
    "~entityType" -> "node",
    "~labels"     -> ujson.Arr("Researcher"),
    "~properties" -> ujson.Obj(
      "researcherId" -> id,
      "displayName"  -> name,
      "provenance"   -> provJson
    )
  )

  private def coauthorRel(a: String, b: String, joint: Int) = ujson.Obj(
    "~id"         -> s"$a|$b",
    "~entityType" -> "relationship",
    "~start"      -> a,
    "~end"        -> b,
    "~type"       -> "COAUTHORED_WITH",
    "~properties" -> ujson.Obj(
      "researcherA"            -> a,
      "researcherB"            -> b,
      "jointPublicationCount"  -> joint,
      "firstCollaborationYear" -> 2016,
      "lastCollaborationYear"  -> 2019,
      "representativeWorkIds"  -> """["work:dblp:x"]""",
      "provenance"             -> provJson
    )
  )

  /** Neptune fake answering the lookups of an Ada/Grace question; records each form body. */
  private def neptuneTransport(forms: mutable.ArrayBuffer[String]) = FakeTransport { call =>
    val form  = URLDecoder.decode(call.bodyString, StandardCharsets.UTF_8)
    val query = form.stripPrefix("query=").takeWhile(_ != '&')
    forms.synchronized(forms += form)
    val rows =
      if query.contains("displayName = $name") && form.contains("Ada Lovelace") then
        ujson.Arr(ujson.Obj("r" -> researcherNode(adaId, "Ada Lovelace")))
      else if query.contains("displayName = $name") && form.contains("Grace Hopper") then
        ujson.Arr(ujson.Obj("r" -> researcherNode(graceId, "Grace Hopper")))
      else if query.contains("RETURN e ORDER BY") then
        ujson.Arr(ujson.Obj("e" -> coauthorRel(adaId, graceId, 7)))
      else if query.contains("ORDER BY length(p)") then
        ujson.Arr(ujson.Obj("path" -> ujson.Arr(adaId, graceId)))
      else ujson.Arr()
    json(200, ujson.Obj("results" -> rows))
  }

  private val noSecrets = new SecretResolver:
    def resolve(ref: SecretRef): String = throw AssertionError("no secret expected")

  final private case class Fixture(
    dynamo: FakeDynamo,
    neptuneT: FakeTransport,
    forms: mutable.ArrayBuffer[String],
    bedrock: FakeBedrock,
    runtime: ServiceRuntime
  ):

    def routes(rt: ServiceRuntime = runtime): Routes =
      Routes(
        rt.workflow,
        rt.graph,
        rt.audit,
        testConfig,
        results = rt.results,
        readiness = rt.readiness
      )

  private def fixture(env: Map[String, String] = readyEnv): Fixture =
    val dynamo  = FakeDynamo()
    val forms   = mutable.ArrayBuffer.empty[String]
    val nt      = neptuneTransport(forms)
    val bedrock = FakeBedrock()
    val clients = AwsStorageClients(
      neptune = neptune(nt),
      dynamo = DynamoDbStorageClient(dynamo, "sc-test"),
      s3 = S3StorageClient(FakeS3()),
      s3Vectors = FakeS3Vectors()
    )
    val models = ModelClients(
      bedrock = _ => bedrock,
      http = () => throw AssertionError("no HTTP model client expected"),
      sleep = _ => ()
    )
    val rt =
      RuntimeComposer.awsRuntime(
        extractive,
        clients,
        noSecrets,
        Duration.ofDays(1),
        env,
        Some(models)
      )
    Fixture(dynamo, nt, forms, bedrock, rt)

  private val question =
    ujson.write(
      ujson.Obj("question" -> "How do \"Ada Lovelace\" and \"Grace Hopper\" collaborate?")
    )

  "RuntimeMode.parse" should "default to stub and accept only stub or aws" in {
    RuntimeMode.parse(None) shouldBe Right(RuntimeMode.Stub)
    RuntimeMode.parse(Some("  ")) shouldBe Right(RuntimeMode.Stub)
    RuntimeMode.parse(Some("AWS")) shouldBe Right(RuntimeMode.Aws)
    RuntimeMode.parse(Some("stub")) shouldBe Right(RuntimeMode.Stub)
    RuntimeMode.parse(Some("prod")).isLeft shouldBe true
  }

  "RuntimeComposer.fromEnv" should "compose stubs when SCHOLARCHAIN_RUNTIME_MODE is unset" in {
    val rt = RuntimeComposer.fromEnv(Map.empty, testConfig).toOption.get
    rt.mode shouldBe RuntimeMode.Stub
    rt.readiness.ready shouldBe true
    rt.results shouldBe a[InMemoryResultStore]
  }

  it should "reject an unknown mode" in {
    RuntimeComposer.fromEnv(Map(RuntimeMode.EnvVar -> "cloud"), testConfig).isLeft shouldBe true
  }

  "RuntimeComposer.awsRuntime" should "compose without any network, model or secret call" in {
    val f = fixture()
    f.runtime.mode shouldBe RuntimeMode.Aws
    f.runtime.results shouldBe a[DynamoResultStore]
    f.runtime.graph shouldBe a[NeptuneServiceGraphStore]
    f.neptuneT.calls.get() shouldBe 0
    f.bedrock.invokeCalls.get() shouldBe 0
    f.dynamo.keys shouldBe empty
  }

  it should "pass the configured metric window to the workflow" in {
    RuntimeComposer.metricWindow(extractive) shouldBe MetricWindow(1990, 2020)
    val f = fixture()
    withRoutes(f.routes()) { base =>
      post(
        base,
        "/v1/query",
        """{"question":"Which ten researchers have the broadest coauthorship networks?"}"""
      )
        .statusCode() shouldBe 200
    }
    val ranking = f.forms.filter(_.contains("$ws"))
    ranking should not be empty
    all(ranking) should (include("\"ws\":1990") and include("\"we\":2020"))
  }

  "/readyz in aws mode" should "report missing configuration names with 503 and make no downstream call" in {
    val f = fixture(env = Map("SCHOLARCHAIN_NEPTUNE_ENDPOINT" -> "n.example"))
    withRoutes(f.routes()) { base =>
      val r = get(base, "/readyz")
      r.statusCode() shouldBe 503
      val body = read[ReadinessResponse](r.body())
      body.status shouldBe "not-ready"
      body.mode shouldBe "aws"
      body.missing shouldBe Vector(
        "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX",
        "SCHOLARCHAIN_S3_VECTORS_BUCKET"
      )
      get(base, "/healthz").statusCode() shouldBe 200
    }
    f.neptuneT.calls.get() shouldBe 0
    f.dynamo.keys shouldBe empty
  }

  it should "be ready when the required configuration is present" in {
    val f = fixture()
    withRoutes(f.routes()) { base =>
      val r = get(base, "/readyz")
      r.statusCode() shouldBe 200
      read[ReadinessResponse](r.body()) shouldBe ReadinessResponse("ready", "aws", Vector.empty)
    }
  }

  "POST /v1/query in aws mode" should "answer from the real workflow and persist result, job and audit" in {
    val f = fixture()
    val (body, resp) = withRoutes(f.routes()) { base =>
      val r = post(base, "/v1/query", question)
      r.statusCode() shouldBe 200
      (r.body(), read[QueryResponse](r.body()))
    }
    resp.answer.get.summary should include("7 joint publications")
    resp.answer.get.summary should not include "Stub answer"
    all(resp.claims.get.map(_.verificationStatus)) shouldBe "Verified"
    f.bedrock.invokeCalls.get() shouldBe 1 // one Titan query embedding; extractive: no LLM
    val qid   = resp.queryId
    val jobId = resp.jobId.get
    jobId shouldBe StoredQuery.jobIdFor(qid)
    f.dynamo.keys shouldBe Set(
      s"sc-test-query-session/result#$qid",
      s"sc-test-query-session/result#job:$jobId",
      s"sc-test-query-session/result#audit:$qid"
    )

    // A fresh Routes over the same table (another task, or a restart) serves everything.
    val restarted = RuntimeComposer.awsRuntime(
      extractive,
      AwsStorageClients(
        neptune(NoNetwork),
        DynamoDbStorageClient(f.dynamo, "sc-test"),
        S3StorageClient(FakeS3()),
        FakeS3Vectors()
      ),
      noSecrets,
      Duration.ofDays(1),
      readyEnv
    )
    withRoutes(f.routes(restarted)) { base =>
      val job = get(base, s"/v1/jobs/$jobId")
      job.statusCode() shouldBe 200
      val jr = read[JobResponse](job.body())
      jr.state shouldBe "SUCCEEDED"
      jr.queryId shouldBe qid
      jr.resultUrl shouldBe Some(s"/v1/jobs/$jobId/result")

      val result = get(base, s"/v1/jobs/$jobId/result")
      result.statusCode() shouldBe 200
      result.body() shouldBe body

      val explain = get(base, s"/v1/explain/$qid")
      explain.statusCode() shouldBe 200
      val ex = read[ExplainResponse](explain.body())
      ex.queryId shouldBe qid
      ex.queryPlan.map(_.source) shouldBe Some(StoredQuery.PlanSource)
      ex.queryPlan.get.filters.values.toSet should contain("Ada Lovelace")
      ex.stages.map(_.name) shouldBe Vector(
        "plan",
        "resolve",
        "metrics",
        "coauthors",
        "paths",
        "community",
        "semantic",
        "evidence",
        "synthesis",
        "verification",
        "audit"
      )
      ex.resolvedIds shouldBe Vector(adaId, graceId)
      ex.promptVersions shouldBe Vector(AnswerSynthesisAgent.ExtractiveVersion)
      ex.synthesisMode shouldBe "extractive"
      ex.evidenceIds should not be empty
      ex.claimVerifications.map(_.verificationStatus).toSet shouldBe Set("Verified")
      ex.abstentionReason shouldBe None
      // identifiers, versions and statuses only: no claim text or prompt
      explain.body() should not include "joint publications"

      val evId = ex.evidenceIds.head
      val ev   = get(base, s"/v1/evidence/$evId")
      ev.statusCode() shouldBe 200
      val evr = read[EvidenceResponse](ev.body())
      evr.evidenceId shouldBe evId
      evr.provenance.textHash shouldBe IdempotencyStore.fingerprint(evr.text)

      val all = get(base, s"/v1/evidence/$qid")
      all.statusCode() shouldBe 200
      read[EvidenceListResponse](all.body()).items.map(_.evidenceId) shouldBe ex.evidenceIds

      get(base, s"/v1/evidence/${IdGen.evidenceId(qid, 9999)}").statusCode() shouldBe 404
      get(base, s"/v1/jobs/$jobId-other").statusCode() shouldBe 404
      get(base, s"/v1/explain/run:missing").statusCode() shouldBe 404
      // reserved record prefixes are not addressable as query IDs
      get(base, s"/v1/explain/audit:$qid").statusCode() shouldBe 404
      get(base, s"/v1/evidence/job:$jobId").statusCode() shouldBe 404
    }
  }

  it should "record an abstention with its reason and skipped stages in explain" in {
    val f = fixture()
    withRoutes(f.routes()) { base =>
      val body = ujson.write(ujson.Obj("question" -> "Who works with \"Nobody Known\"?"))
      val r    = post(base, "/v1/query", body)
      r.statusCode() shouldBe 200
      val qid = read[QueryResponse](r.body()).queryId
      val ex  = read[ExplainResponse](get(base, s"/v1/explain/$qid").body())
      ex.abstentionReason shouldBe Some("unresolved-researcher")
      ex.stages.find(_.name == "resolve").map(_.status) shouldBe Some("abstained")
      ex.stages.find(_.name == "semantic").map(_.status) shouldBe Some("skipped")
      ex.stages.find(_.name == "synthesis").map(_.status) shouldBe Some("skipped")
    }
    f.bedrock.invokeCalls.get() shouldBe 0
  }

  it should "fail with a retryable 502 and no answer when the audit or the result cannot be persisted" in {
    for failing <- Vector("result#audit:", "result#run:", "result#job:").map(p =>
        (k: String) => k.startsWith(p)
      )
    do
      val f = fixture()
      withRoutes(f.routes()) { base =>
        f.dynamo.failKey = failing
        val r = post(base, "/v1/query", question)
        r.statusCode() shouldBe 502
        val err = read[ErrorResponse](r.body())
        err.error shouldBe "DOWNSTREAM_ERROR"
        err.retryable shouldBe true
        r.body() should not include "joint publications"
      }
  }

  "DynamoResultStore" should "keep the first result for a query ID (idempotent put)" in {
    val dynamo     = FakeDynamo()
    val store      = DynamoResultStore(DynamoDbStorageClient(dynamo, "t"), Duration.ofDays(1))
    val (wf, _, _) = RuntimeComposer.composeStubs()
    val first      = wf.execute("first", Map.empty)
    def stored(text: String) = StoredQuery(
      queryId = "q-1",
      jobId = StoredQuery.jobIdFor("q-1"),
      createdAt = "2026-01-01T00:00:00Z",
      response = QueryResponse("q-1", "synchronous", Some(AnswerPayload(text, Vector.empty))),
      workflow = first,
      plan = None
    )
    store.putQuery(stored("one"))
    store.putQuery(stored("two"))
    store.getQuery("q-1").flatMap(_.response.answer).map(_.summary) shouldBe Some("one")
    store.getQuery("job:x") shouldBe None
  }

  "StoredQuery.fitting" should "truncate evidence text to fit the durable item limit" in {
    val (wf, _, _) = RuntimeComposer.composeStubs()
    val base       = wf.execute("q", Map.empty)
    val items = (0 until 40).toVector.map { i =>
      EvidenceItem(
        IdGen.evidenceId("q-big", i),
        EvidenceType.TextChunk,
        "e",
        "x" * 20000,
        0.5,
        prov
      )
    }
    val big = base.copy(response =
      base.response.copy(evidenceBundle = base.response.evidenceBundle.copy(items = items))
    )
    val s = StoredQuery("q-big", "job-x", "t", QueryResponse("q-big", "synchronous"), big, None)
    val (fit, json) = StoredQuery.fitting(s, DynamoDbStorageClient.MaxQueryResultBytes)
    json.getBytes("UTF-8").length should be <= DynamoDbStorageClient.MaxQueryResultBytes
    fit.evidenceContentTruncated shouldBe true
    fit.workflow.response.evidenceBundle.items.map(_.evidenceId) shouldBe items.map(_.evidenceId)
  }
