package edu.scholarchain.rag

import edu.scholarchain.core.*
import edu.scholarchain.rag.RagFixtures.*
import edu.scholarchain.storage.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient => AwsDynamoDbClient}
import software.amazon.awssdk.services.dynamodb.model.*
import software.amazon.awssdk.services.s3.{S3Client => AwsS3Client}

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class RuntimeCompositionSpec extends AnyFlatSpec with Matchers:

  private val extractive = appConfig(Map("SCHOLARCHAIN_SYNTHESIS_MODE" -> "extractive"))

  private val ada   = researcher(adaId, "Ada Lovelace")
  private val grace = researcher(graceId, "Grace Hopper")

  private def fixturePorts(
    lookup: ResearcherLookup = MapLookup(
      Map("Ada Lovelace" -> Vector(ada), "Grace Hopper" -> Vector(grace))
    ),
    metricsStore: MetricsStore = MapMetrics(
      Map(adaId -> metrics(adaId, 42, 10), graceId -> metrics(graceId, 30, 8))
    ),
    graph: GraphStore = MapGraph(
      coauthors = Map(adaId -> Vector(edge(adaId, graceId, 7))),
      paths = Map((adaId, graceId) -> Vector(adaId, graceId))
    ),
    vectors: VectorStore = EmptyVectors,
    audit: MemoryAudit = MemoryAudit()
  ): RuntimePorts =
    RuntimePorts(lookup, metricsStore, graph, vectors, ConstText("chunk"), audit)

  "RuntimeComposition (extractive)" should "answer end to end from fixtures with verified claims and no LLM" in {
    val audit = MemoryAudit()
    val workflow = RuntimeComposition.fromPorts(
      extractive,
      fixturePorts(audit = audit),
      NoLlmClient,
      TestEmbedders.FakeQueryEmbedder()
    )
    val result = workflow.execute(
      "How do \"Ada Lovelace\" and \"Grace Hopper\" collaborate since 2015?",
      Map.empty
    )

    val claims = result.response.claims
    claims should not be empty
    all(claims.map(_.verificationStatus)) shouldBe VerificationStatus.Verified
    claims.map(_.statement).mkString(" ") should include("Ada Lovelace")
    result.response.generatedText should include("42 publications")
    result.response.generatedText should include("7 joint publications")
    result.response.generatedText should not include "Stub answer"
    val ids = result.response.evidenceBundle.items.map(_.evidenceId)
    ids shouldBe ids.indices.map(i => IdGen.evidenceId(result.response.queryId, i)).toVector
    claims.flatMap(_.supportingEvidence).toSet.subsetOf(ids.toSet) shouldBe true
    result.auditRecord.promptVersion shouldBe AnswerSynthesisAgent.ExtractiveVersion
    result.auditRecord.synthesisMode shouldBe "extractive"
    result.auditRecord.resolvedIds shouldBe Vector(adaId, graceId)
    audit.records should have size 1
  }

  it should "bound path depth by retrieval.max-hops" in {
    val graph = MapGraph(paths = Map((adaId, graceId) -> Vector(adaId, graceId)))
    RuntimeComposition
      .fromPorts(
        extractive,
        fixturePorts(graph = graph),
        NoLlmClient,
        TestEmbedders.FakeQueryEmbedder()
      )
      .execute("Path between \"Ada Lovelace\" and \"Grace Hopper\"", Map.empty)
    graph.pathDepths shouldBe Vector(extractive.retrieval.maxHops)
  }

  it should "abstain without retrieval when a researcher name is ambiguous" in {
    val other = researcher("researcher:orcid:0000-0009-0000-0009", "Ada Lovelace")
    val graph = MapGraph()
    val result = RuntimeComposition
      .fromPorts(
        extractive,
        fixturePorts(lookup = MapLookup(Map("Ada Lovelace" -> Vector(ada, other))), graph = graph),
        NoLlmClient,
        TestEmbedders.FakeQueryEmbedder()
      )
      .execute("How many coauthors does \"Ada Lovelace\" have?", Map.empty)
    result.response.claims should have size 1
    result.response.claims.head.statement should include("ambiguous")
    result.response.claims.head.statement should include(other.researcherId)
    result.response.evidenceBundle.items shouldBe empty
    result.auditRecord.abstentionReason shouldBe Some("ambiguous-researcher")
    graph.pathDepths shouldBe empty
  }

  it should "resolve an explicit researcherId constraint" in {
    val result = RuntimeComposition
      .fromPorts(extractive, fixturePorts(), NoLlmClient, TestEmbedders.FakeQueryEmbedder())
      .execute("How many coauthors?", Map("researcherId" -> adaId))
    result.auditRecord.resolvedIds shouldBe Vector(adaId)
    result.response.generatedText should include("42 publications")
  }

  it should "abstain when the researcher resolves but no evidence exists" in {
    val result = RuntimeComposition
      .fromPorts(
        extractive,
        fixturePorts(metricsStore = UnavailableMetricsStore, graph = MapGraph()),
        NoLlmClient,
        TestEmbedders.FakeQueryEmbedder()
      )
      .execute("How many coauthors does \"Ada Lovelace\" have?", Map.empty)
    result.response.claims should have size 1
    AnswerSynthesisAgent.isAbstention(result.response.claims.head) shouldBe true
    result.response.claims.head.statement should include("Insufficient evidence")
  }

  "RuntimeWorkflow (llm)" should "remove UNSUPPORTED and CONFLICTING claims and label partial ones" in {
    val llm = new LlmClient:
      def generate(p: String, m: Int): String =
        val id = "id=\"(evidence:[^\"]+)\"".r.findFirstMatchIn(p).get.group(1)
        claimsJson(
          s"$adaId had 42 publications."                   -> Seq(id),
          s"$adaId had 420 publications."                  -> Seq(id),
          "Ada won 17 awards."                             -> Seq(id),
          s"$adaId had 10 distinct coauthors and 11 cats." -> Seq(id)
        )
    val result = RuntimeComposition
      .fromPorts(appConfig(), fixturePorts(), llm, TestEmbedders.FakeQueryEmbedder())
      .execute("How many publications does \"Ada Lovelace\" have?", Map.empty)
    result.response.claims.map(_.verificationStatus) shouldBe Vector(
      VerificationStatus.Verified,
      VerificationStatus.PartiallySupported
    )
    result.response.generatedText should include("[partially supported]")
    result.response.generatedText should not include "420"
    result.auditRecord.removedClaimIds should have size 2
    result.auditRecord.verificationSummary should include("Conflicting=1")
    result.auditRecord.promptVersion shouldBe SynthesisPrompt.Version
    result.auditRecord.promptHash shouldBe SynthesisPrompt.hash
    result.auditRecord.modelVersion shouldBe "us.anthropic.claude-haiku-5-5"
  }

  // ---- fromConfig over storage clients with fake transports ----

  private val creds =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample"))

  private def provJson = ujson.write(upickle.default.writeJs(prov))

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
      "lastCollaborationYear"  -> 2022,
      "representativeWorkIds"  -> """["work:dblp:x"]""",
      "provenance"             -> provJson
    )
  )

  private class FakeDynamo extends AwsDynamoDbClient:
    val items = mutable.Map.empty[String, java.util.Map[String, AttributeValue]]
    override def serviceName(): String = "dynamodb"
    override def close(): Unit         = ()

    override def putItem(r: PutItemRequest): PutItemResponse =
      items(r.item().get("queryId").s()) = r.item()
      PutItemResponse.builder().build()

    override def getItem(r: GetItemRequest): GetItemResponse =
      items.get(r.key().get("queryId").s()) match
        case Some(item) => GetItemResponse.builder().item(item).build()
        case None       => GetItemResponse.builder().build()

  private object NoS3 extends AwsS3Client:
    override def serviceName(): String = "s3"
    override def close(): Unit         = ()

  "RuntimeComposition.fromConfig" should "wire Neptune, S3 Vectors, Titan and DynamoDB audit end to end" in {
    val cypher = mutable.ArrayBuffer.empty[String]
    val neptuneTransport = FakeTransport { call =>
      val form  = URLDecoder.decode(call.bodyString, StandardCharsets.UTF_8)
      val query = form.stripPrefix("query=").takeWhile(_ != '&')
      cypher += query
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
    val vectorsTransport = FakeTransport(_ => json(200, ujson.Obj("vectors" -> ujson.Arr())))
    val neptune = NeptuneStorageClient(
      "neptune.local",
      8182,
      "us-east-2",
      1000,
      neptuneTransport,
      creds,
      baseUriOverride = Some(URI.create("http://neptune.local:8182"))
    )
    val s3v     = S3VectorsStorageClient("us-east-2", 0, 0, vectorsTransport, creds)
    val dynamo  = FakeDynamo()
    val bedrock = FakeBedrock()
    val storage =
      RuntimeStorage(neptune, DynamoDbStorageClient(dynamo, "sc-test"), S3StorageClient(NoS3), s3v)
    val clients = ModelClients(
      bedrock = _ => bedrock,
      http = () => FakeTransport(_ => fail("no HTTP model call expected")),
      sleep = noSleep
    )
    val noSecrets = new SecretResolver:
      def resolve(ref: SecretRef): String = fail("no secret expected")

    val workflow = RuntimeComposition.fromConfig(extractive, storage, noSecrets, clients)
    bedrock.invokeCalls.get() shouldBe 0 // nothing built or called at composition time

    val result =
      workflow.execute("How do \"Ada Lovelace\" and \"Grace Hopper\" collaborate?", Map.empty)

    result.response.generatedText should include("7 joint publications")
    result.response.generatedText should include("coauthorship path of 1 hops")
    all(result.response.claims.map(_.verificationStatus)) shouldBe VerificationStatus.Verified
    bedrock.invokeCalls.get() shouldBe 1   // one Titan query embedding
    bedrock.converseCalls.get() shouldBe 0 // extractive: no LLM
    vectorsTransport.calls should have size S3VectorsIndexes.all.size
    cypher.filter(_.contains("*1..")).foreach { q =>
      q should include("COAUTHORED_WITH")
      q should include(s"*1..${extractive.retrieval.maxHops}]")
    }
    val stored = DynamoDbStorageClient(dynamo, "sc-test").getQueryResult(
      DynamoAuditStore.key(result.response.queryId)
    )
    stored.map(upickle.default.read[AuditRecord](_)) shouldBe Some(result.auditRecord)
    DynamoAuditStore(DynamoDbStorageClient(dynamo, "sc-test"), java.time.Duration.ofDays(1))
      .getAudit(result.response.queryId) shouldBe Some(result.auditRecord)
  }

  "NeptuneGraphStore" should "restrict neighbor traversal to COAUTHORED_WITH with bounded depth and limit" in {
    val queries = mutable.ArrayBuffer.empty[String]
    val t = FakeTransport { call =>
      queries += URLDecoder.decode(call.bodyString, StandardCharsets.UTF_8)
      json(200, ujson.Obj("results" -> ujson.Arr()))
    }
    val neptune = NeptuneStorageClient(
      "n",
      8182,
      "us-east-2",
      1000,
      t,
      creds,
      baseUriOverride = Some(URI.create("http://n:8182"))
    )
    val g = NeptuneGraphStore(neptune, maxDepth = 3)
    g.getNeighbors(adaId, Vector("AUTHORED"), depth = 9, limit = 10000)
    queries.head should include(":COAUTHORED_WITH*1..2]")
    queries.head should not include "AUTHORED*"
    queries.head should include("\"lim\":100")
    g.shortestPath(adaId, graceId, 10)
    queries(1) should include(":COAUTHORED_WITH*1..3]")
  }
