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

/**
 * RAG-P09-R02: Neptune-backed researcher metrics, allowlisted rankings, README answer structure and
 * metric claim verification. Neptune is a fake transport returning typed `Researcher` vertices
 * (JSON numbers for `:Int`/`:Double` columns, JSON text for work IDs and provenance). No network,
 * no model calls.
 */
class MetricsRankingSpec extends AnyFlatSpec with Matchers:

  private val K = GraphSchema.PropertyKey

  private val creds =
    StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secretexample"))

  private val metricProv = Provenance(
    sourceDataset = "dblp",
    sourceRecordId = "pid/42/1234",
    pipelineRunId = "run-20261124-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-11-24T08:00:00Z",
    transformationStep = Some("collaboration-metrics"),
    producer = Some("spark-analytics/CollaborationMetricsJob")
  )

  private val Ada   = "researcher:orcid:0000-0001-0000-0001"
  private val Grace = "researcher:orcid:0000-0002-0000-0002"
  private val Alan  = "researcher:orcid:0000-0003-0000-0003"

  private val AdaWorks = Vector("work:dblp:conf/kdd/Smith19", "work:dblp:conf/www/Smith21")

  private case class Row(
    id: String,
    pubs: Int,
    coauthors: Int,
    maxTeam: Int,
    works: Vector[String],
    bridge: Double,
    method: String,
    coreness: Int = 4,
    window: (Int, Int) = (2015, 2024)
  )

  private val adaRow =
    Row(Ada, 12, 48, 6, AdaWorks, 1.0e-4, BridgeScoreMethod.Sampled)

  private val graceRow =
    Row(Grace, 30, 31, 9, Vector("work:dblp:journals/cacm/Hopper20"), 0.25, BridgeScoreMethod.Exact)

  private val alanRow =
    Row(Alan, 7, 12, 3, Vector("work:dblp:conf/icml/Turing18"), 0.0, BridgeScoreMethod.NotComputed)

  /** A `Researcher` vertex as Neptune serializes typed bulk-load columns. */
  private def vertex(r: Row): ujson.Value =
    ujson.Obj(
      "~id"         -> r.id,
      "~entityType" -> "node",
      "~labels"     -> ujson.Arr("Researcher"),
      "~properties" -> ujson.Obj(
        K.ResearcherId               -> r.id,
        K.DisplayName                -> "Someone",
        K.Provenance                 -> upickle.default.write(metricProv),
        K.PublicationCount           -> ujson.Num(r.pubs),
        K.DistinctCoauthorCount      -> ujson.Num(r.coauthors),
        K.TotalCoauthorOccurrences   -> ujson.Num(r.coauthors * 2),
        K.AverageCoauthorsPerPaper   -> ujson.Num(2.5),
        K.MaximumCoauthorsOnOnePaper -> ujson.Num(r.maxTeam),
        K.MaximumTeamWorkIds         -> ujson.write(ujson.Arr.from(r.works.map(ujson.Str(_)))),
        K.WeightedDegree             -> ujson.Num(7.0),
        K.ComponentId                -> "component:7",
        K.ComponentSize              -> ujson.Num(1540),
        K.CommunityId                -> "community:3",
        K.BridgeScore                -> ujson.Num(r.bridge),
        K.BridgeScoreMethod          -> r.method,
        K.Coreness                   -> ujson.Num(r.coreness),
        K.WindowStartYear            -> ujson.Num(r.window._1),
        K.WindowEndYear              -> ujson.Num(r.window._2)
      )
    )

  /** Query text and parameters of one openCypher form request. */
  private case class Cypher(query: String, params: ujson.Value)

  private def decode(call: HttpCall): Cypher =
    val parts = call.bodyString
      .split("&")
      .map { kv =>
        val i = kv.indexOf('=')
        kv.take(i) -> URLDecoder.decode(kv.drop(i + 1), StandardCharsets.UTF_8)
      }
      .toMap
    Cypher(parts("query"), ujson.read(parts.getOrElse("parameters", "{}")))

  /**
   * Fake Neptune over `rows`: single and batch metric lookups by ID, and rankings that apply the
   * window and the ORDER BY property named in the query text (as Neptune would).
   */
  private class FakeNeptune(rows: Vector[Row]):
    val requests = mutable.ArrayBuffer.empty[Cypher]

    val transport: FakeTransport = FakeTransport { call =>
      val c = decode(call)
      synchronized(requests += c)
      val result: Vector[Row] =
        if c.query.contains("IN $rids") then
          val ids = c.params("rids").arr.map(_.str).toSet
          rows.filter(r => ids.contains(r.id))
        else if c.query.contains("$rid}") then rows.filter(_.id == c.params("rid").str)
        else if c.query.contains("ORDER BY") && c.query.contains("$ws") then
          val ws  = c.params("ws").num.toInt
          val we  = c.params("we").num.toInt
          val lim = c.params("lim").num.toInt
          val key = """ORDER BY r\.(\w+) DESC""".r.findFirstMatchIn(c.query).get.group(1)
          def value(r: Row): Double = key match
            case "distinctCoauthorCount"      => r.coauthors
            case "publicationCount"           => r.pubs
            case "maximumCoauthorsOnOnePaper" => r.maxTeam
            case "bridgeScore"                => r.bridge
            case "coreness"                   => r.coreness
            case "weightedDegree"             => 7.0
            case other                        => fail(s"unexpected ranking key $other")
          rows
            .filter(_.window == (ws, we))
            .filterNot(r => c.params.obj.get("excludedMethod").exists(m => r.method == m.str))
            .sortBy(r => (-value(r), r.id))
            .take(lim)
        else Vector.empty
      json(
        200,
        ujson.Obj("results" -> ujson.Arr.from(result.map(r => ujson.Obj("r" -> vertex(r)))))
      )
    }

    val client: NeptuneStorageClient = NeptuneStorageClient(
      "neptune.local",
      8182,
      "us-east-2",
      1000,
      transport,
      creds,
      baseUriOverride = Some(URI.create("http://neptune.local:8182"))
    )

  private val extractive = appConfig(Map("SCHOLARCHAIN_SYNTHESIS_MODE" -> "extractive"))

  private class FakeDynamo extends AwsDynamoDbClient:
    val items = mutable.Map.empty[String, java.util.Map[String, AttributeValue]]
    override def serviceName(): String = "dynamodb"
    override def close(): Unit         = ()

    override def putItem(r: PutItemRequest): PutItemResponse =
      items(r.item().get("queryId").s()) = r.item()
      PutItemResponse.builder().build()

  private object NoS3 extends AwsS3Client:
    override def serviceName(): String = "s3"
    override def close(): Unit         = ()

  /** The production composition over the fake Neptune, metric window 2015-2024, no model calls. */
  private def workflowOver(neptune: FakeNeptune, config: AppConfig = extractive): RuntimeWorkflow =
    val vectors = FakeTransport(_ => json(200, ujson.Obj("vectors" -> ujson.Arr())))
    val storage = RuntimeStorage(
      neptune.client,
      DynamoDbStorageClient(FakeDynamo(), "sc-test"),
      S3StorageClient(NoS3),
      S3VectorsStorageClient("us-east-2", 0, 0, vectors, creds),
      metricWindow = MetricWindow(2015, 2024)
    )
    val clients = ModelClients(
      bedrock = _ => fail("no Bedrock call expected"),
      http = () => FakeTransport(_ => fail("no HTTP model call expected")),
      sleep = noSleep
    )
    val noSecrets = new SecretResolver:
      def resolve(ref: SecretRef): String = fail("no secret expected")
    RuntimeComposition.fromConfig(config, storage, noSecrets, clients)

  // ---- NeptuneMetricsStore ----

  "NeptuneMetricsStore" should "return the exact stored metrics from typed Neptune vertices" in {
    val neptune = FakeNeptune(Vector(adaRow))
    val agent   = CollaborationMetricsAgent(NeptuneMetricsStore(neptune.client))
    val m       = agent.retrieve(Vector(Ada), 2015, 2024).head

    m.publicationCount shouldBe 12          // P_r
    m.distinctCoauthorCount shouldBe 48     // C_r
    m.totalCoauthorOccurrences shouldBe 96  // T_r
    m.averageCoauthorsPerPaper shouldBe 2.5 // A_r
    m.maximumCoauthorsOnOnePaper shouldBe 6 // M_r
    m.maximumTeamWorkIds shouldBe AdaWorks  // work IDs responsible for M_r
    m.weightedDegree shouldBe 7.0           // W_r
    m.componentId shouldBe Some("component:7")
    m.componentSize shouldBe Some(1540L)
    m.communityId shouldBe Some("community:3")
    m.bridgeScore shouldBe 1.0e-4
    m.bridgeScoreMethod shouldBe BridgeScoreMethod.Sampled
    m.coreness shouldBe 4
    (m.windowStartYear, m.windowEndYear) shouldBe (2015, 2024)
    m.provenance.pipelineRunId shouldBe "run-20261124-001"
    m.provenance.schemaVersion shouldBe "1.0.0"
    neptune.requests.map(_.query).head should include("IN $rids")
  }

  it should "never present metrics stored for another window" in {
    val neptune = FakeNeptune(Vector(adaRow))
    val store   = NeptuneMetricsStore(neptune.client)
    store.getMetrics(Ada, 2015, 2024).map(_.publicationCount) shouldBe Some(12)
    store.getMetrics(Ada, 2016, 2024) shouldBe None
    store.getMetricsBatch(Vector(Ada), 2015, 2025) shouldBe empty
    store.topResearchers(RankableResearcherMetric.Coreness, 2018, 2024, 10) shouldBe empty
  }

  it should "chunk batch lookups at the storage batch limit" in {
    val neptune = FakeNeptune(Vector(adaRow, graceRow))
    val ids     = Vector(Ada, Grace) ++ (1 to 148).map(i => f"researcher:sc:$i%04d")
    val found   = NeptuneMetricsStore(neptune.client).getMetricsBatch(ids, 2015, 2024)
    found.keySet shouldBe Set(Ada, Grace)
    neptune.requests.map(_.params("rids").arr.size) shouldBe Vector(100, 50)
  }

  it should "rank only by the allowlisted property, with window and limit as parameters" in {
    val neptune = FakeNeptune(Vector(adaRow, graceRow, alanRow))
    val ranked = NeptuneMetricsStore(neptune.client)
      .topResearchers(RankableResearcherMetric.DistinctCoauthorCount, 2015, 2024, 500)
    ranked.map(_.researcherId) shouldBe Vector(Ada, Grace, Alan)
    val req = neptune.requests.head
    req.query should include("ORDER BY r.distinctCoauthorCount DESC")
    req.params("ws").num shouldBe 2015
    req.params("we").num shouldBe 2024
    req.params("lim").num shouldBe NeptuneStorageClient.MaxRankingLimit
  }

  "CommunityAnalystAgent" should "read metrics in batches, not one lookup per coauthor" in {
    var single = 0
    var batch  = 0
    val data   = Map(Ada -> metrics(Ada, 1, 1), Grace -> metrics(Grace, 1, 1))
    val store = new MetricsStore:
      def getMetrics(id: String, s: Int, e: Int): Option[ResearcherMetrics] =
        single += 1; data.get(id)
      override def getMetricsBatch(ids: Vector[String], s: Int, e: Int) =
        batch += 1; ids.flatMap(id => data.get(id).map(id -> _)).toMap
    val graph =
      MapGraph(coauthors = Map(Ada -> Vector(edge(Ada, Grace, 1), edge(Ada, Alan, 1))))
    CommunityAnalystAgent(store, graph).analyze(Vector(Ada), 2015, 2024).map(_.communityId) shouldBe
      Vector("comm-1")
    single shouldBe 0
    batch shouldBe 2
  }

  // ---- QueryPlanner: README example questions ----

  private val planner = QueryPlannerAgent()

  "QueryPlannerAgent" should "extract the ranking metric, size and window from the README breadth question" in {
    val p = planner.plan(
      "Which ten researchers have the broadest coauthorship networks in computer science since 2015?",
      Map.empty
    )
    p.filters(QueryPlannerAgent.RankingMetricKey) shouldBe "distinctCoauthorCount"
    p.filters(QueryPlannerAgent.RankingLimitKey) shouldBe "10"
    p.filters("yearFrom") shouldBe "2015"
    p.filters.get("yearTo") shouldBe None
  }

  it should "map the largest author teams question to maximumCoauthorsOnOnePaper" in {
    val p = planner.plan(
      "Which researchers worked in the largest author teams between 2015 and 2024?",
      Map.empty
    )
    p.filters(QueryPlannerAgent.RankingMetricKey) shouldBe "maximumCoauthorsOnOnePaper"
    p.filters(QueryPlannerAgent.RankingLimitKey) shouldBe "10"
    (p.filters("yearFrom"), p.filters("yearTo")) shouldBe ("2015", "2024")
  }

  it should "map the connecting-separate-communities question to bridgeScore" in {
    val p = planner.plan(
      "Which researchers are most important for connecting otherwise separate collaboration " +
        "communities?",
      Map.empty
    )
    p.filters(QueryPlannerAgent.RankingMetricKey) shouldBe "bridgeScore"
    p.operations should contain(QueryOperation.BridgeRanking)
  }

  it should "map the other allowlisted metrics and requested sizes" in {
    def metricOf(q: String) = planner.plan(q, Map.empty).filters.get("rankingMetric")
    metricOf("Top 5 researchers by coreness since 2018") shouldBe Some("coreness")
    metricOf("Which researchers have the most publications?") shouldBe Some("publicationCount")
    metricOf("Which 3 researchers have the highest weighted degree?") shouldBe
      Some("weightedDegree")
    metricOf("Which researchers have the strongest bridges?") shouldBe Some("bridgeScore")
    planner.plan("Top 5 researchers by coreness", Map.empty).filters("rankingLimit") shouldBe "5"
    planner
      .plan("Which 500 researchers have the most publications?", Map.empty)
      .filters("rankingLimit") shouldBe "100"
  }

  it should "not treat a question about a named researcher as a ranking" in {
    val p = planner.plan("How many distinct coauthors does \"Ada Lovelace\" have?", Map.empty)
    p.filters.keySet should not contain QueryPlannerAgent.RankingRequestedKey
  }

  it should "only ever emit allowlisted metric names, whatever the question says" in {
    val hostile = Vector(
      "Which researchers have the highest password_hash?",
      "Which researchers rank highest by r.secret) DETACH DELETE r //?",
      "List researchers ordered by displayName",
      "Which researchers have the most citations since 2015?"
    )
    hostile.foreach { q =>
      planner.plan(q, Map.empty).filters.get(QueryPlannerAgent.RankingMetricKey) match
        case Some(name) => RankableResearcherMetric.AllowedNames should contain(name)
        case None       => succeed
    }
  }

  // ---- ranking end to end (extractive) ----

  "A ranking question (extractive)" should "answer end to end in the README structure with verified claims" in {
    val neptune = FakeNeptune(Vector(alanRow, graceRow, adaRow))
    val result = workflowOver(neptune).execute(
      "Which ten researchers have the broadest coauthorship networks in computer science since 2015?",
      Map.empty
    )
    val queryId = result.response.queryId
    val items   = result.response.evidenceBundle.items

    // one metric evidence item per ranked researcher, IDs from IdGen.evidenceId
    items.map(_.sourceEntityId) shouldBe Vector(Ada, Grace, Alan)
    items.map(_.evidenceId) shouldBe items.indices.map(IdGen.evidenceId(queryId, _)).toVector
    all(items.map(_.evidenceType)) shouldBe EvidenceType.MetricRecord
    items.head.provenance.pipelineRunId shouldBe "run-20261124-001"

    val claims = result.response.claims
    claims should have size 3
    all(claims.map(_.verificationStatus)) shouldBe VerificationStatus.Verified
    claims.map(_.supportingEvidence) shouldBe items.map(i => Vector(i.evidenceId))

    val first = claims.head.statement
    first should startWith(s"Rank 1 by distinct coauthors: Researcher $Ada had 12 publications")
    first should include("48 distinct coauthors in 2015-2024")
    first should include("average coauthors per paper 2.5")
    first should include(
      "maximum coauthors on one paper 6, on work:dblp:conf/kdd/Smith19, work:dblp:conf/www/Smith21"
    )
    first should include("weighted degree 7.0")
    first should include("component component:7, component size 1540")
    first should include("community community:3")
    first should include("bridge score 0.0001, method sampled (approximate betweenness")
    first should include("coreness 4")
    first should include("metric version 1.0.0, run run-20261124-001")
    claims(1).statement should include("bridge score 0.25, method exact")
    claims(2).statement should include("bridge score not computed, method not-computed")

    val ranking = neptune.requests.find(_.query.contains("ORDER BY")).get
    ranking.query should include("ORDER BY r.distinctCoauthorCount DESC")
    ranking.params("lim").num shouldBe 10
    (ranking.params("ws").num, ranking.params("we").num) shouldBe (2015.0, 2024.0)

    result.auditRecord.promptVersion shouldBe AnswerSynthesisAgent.ExtractiveVersion
    result.auditRecord.evidenceIds shouldBe items.map(_.evidenceId)
    result.auditRecord.verificationSummary should include(
      "ranking=distinctCoauthorCount; window=2015-2024; ranked=3"
    )
  }

  it should "rank the largest author teams and report the responsible work IDs" in {
    val neptune = FakeNeptune(Vector(adaRow, graceRow, alanRow))
    val result = workflowOver(neptune).execute(
      "Which researchers worked in the largest author teams between 2015 and 2024?",
      Map.empty
    )
    val first = result.response.claims.head.statement
    first should startWith(s"Rank 1 by maximum coauthors on one paper: Researcher $Grace")
    first should include("maximum coauthors on one paper 9, on work:dblp:journals/cacm/Hopper20")
    all(result.response.claims.map(_.verificationStatus)) shouldBe VerificationStatus.Verified
  }

  it should "exclude not-computed bridge scores from a bridge ranking instead of ranking them as zero" in {
    val neptune = FakeNeptune(Vector(adaRow, graceRow, alanRow))
    val result = workflowOver(neptune).execute(
      "Which researchers are most important for connecting otherwise separate collaboration " +
        "communities since 2015?",
      Map.empty
    )
    result.response.evidenceBundle.items.map(_.sourceEntityId) shouldBe Vector(Grace, Ada)
    result.response.generatedText should not include Alan
    result.response.claims(1).statement should include("method sampled (approximate")
    neptune.requests.find(_.query.contains("ORDER BY")).get.params("excludedMethod").str shouldBe
      BridgeScoreMethod.NotComputed
  }

  it should "say so explicitly when no bridge score was computed" in {
    val neptune = FakeNeptune(Vector(alanRow))
    val result = workflowOver(neptune).execute(
      "Which researchers have the strongest bridges since 2015?",
      Map.empty
    )
    result.response.claims should have size 1
    val claim = result.response.claims.head
    AnswerSynthesisAgent.isAbstention(claim) shouldBe true
    claim.statement should include("not computed are excluded, not ranked as zero")
    result.auditRecord.abstentionReason shouldBe Some("no-ranked-metrics")
  }

  it should "drop a not-computed row a store returns for a bridge ranking" in {
    val leaky = new MetricsStore:
      def getMetrics(id: String, s: Int, e: Int): Option[ResearcherMetrics] = None
      override def topResearchers(m: RankableResearcherMetric, s: Int, e: Int, l: Int) =
        Vector(
          metrics(Alan, 1, 1)
            .copy(bridgeScore = 0.0, bridgeScoreMethod = BridgeScoreMethod.NotComputed),
          metrics(Ada, 1, 1)
        )
    CollaborationMetricsAgent(leaky)
      .rank(RankableResearcherMetric.BridgeScore, 2015, 2024, 10)
      .ranked
      .map(_.researcherId) shouldBe Vector(Ada)
  }

  // ---- allowlist ----

  "The ranking allowlist" should "reject an arbitrary metric name from user input before any query" in {
    val hostile = Vector(
      "displayName",
      "distinctCoauthorCount DESC, r.secret",
      "DistinctCoauthorCount",
      "r.password} RETURN r //"
    )
    hostile.foreach { name =>
      val neptune = FakeNeptune(Vector(adaRow))
      val result = workflowOver(neptune).execute(
        "Which researchers rank highest?",
        Map(QueryPlannerAgent.RankingMetricKey -> name)
      )
      neptune.requests shouldBe empty
      val claim = result.response.claims.head
      AnswerSynthesisAgent.isAbstention(claim) shouldBe true
      result.auditRecord.abstentionReason shouldBe Some("unsupported-ranking-metric")
      claim.statement should include(RankableResearcherMetric.AllowedNames.mkString(", "))
      claim.statement should not include name
      result.response.evidenceBundle.items shouldBe empty
    }
  }

  it should "abstain on a ranking question whose measure is not allowlisted" in {
    val neptune = FakeNeptune(Vector(adaRow))
    val result = workflowOver(neptune).execute(
      "Which ten researchers have the most citations since 2015?",
      Map.empty
    )
    neptune.requests shouldBe empty
    result.auditRecord.abstentionReason shouldBe Some("unsupported-ranking-metric")
  }

  it should "reject unknown names in the storage text overload too" in {
    val neptune = FakeNeptune(Vector(adaRow))
    an[IllegalArgumentException] should be thrownBy
      neptune.client.topResearchersBy("displayName", MetricWindow(2015, 2024), 10)
    neptune.requests shouldBe empty
  }

  // ---- verification of metric claims ----

  private val rankedItem = item(
    "evidence:q1:0000",
    EvidenceContent.metric(
      metrics(Ada, 12, 48).copy(
        maximumTeamWorkIds = AdaWorks,
        componentSize = Some(1540L),
        bridgeScore = 1.0e-4,
        bridgeScoreMethod = BridgeScoreMethod.Sampled,
        provenance = metricProv
      ),
      Some(1),
      Some("distinctCoauthorCount")
    ),
    source = Ada
  )

  private val notComputedItem = item(
    "evidence:q1:0001",
    EvidenceContent.metric(
      metrics(Alan, 7, 12)
        .copy(bridgeScore = 0.0, bridgeScoreMethod = BridgeScoreMethod.NotComputed)
    ),
    source = Alan
  )

  private val evidence = bundle(rankedItem, notComputedItem)

  private def check(statement: String, ids: String*): ClaimCheck =
    ClaimVerificationAgent()
      .verifyDetailed(
        Vector(
          AnswerClaim(
            "c1",
            "q1",
            statement,
            0.9,
            ids.toVector,
            VerificationStatus.Unsupported,
            prov
          )
        ),
        evidence
      )
      .head

  "ClaimVerificationAgent (metrics)" should "verify a correct README-structure statement" in {
    val statement = AnswerSynthesisAgent.metricStatement(
      EvidenceContent.parseMetric(rankedItem.content).get,
      identity
    )
    check(statement, "evidence:q1:0000").status shouldBe VerificationStatus.Verified
  }

  it should "mark a fabricated metric number as CONFLICTING" in {
    val c = check(s"$Ada had 480 distinct coauthors in 2015-2024.", "evidence:q1:0000")
    c.status shouldBe VerificationStatus.Conflicting
    c.reasons.head should include("stated 480")
  }

  it should "mark a value copied from another field as CONFLICTING" in {
    // 6 is M_r in the evidence, not C_r
    check(s"$Ada had 6 distinct coauthors.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Conflicting
  }

  it should "check component size, rank and coreness against the evidence" in {
    check(
      s"$Ada is in component component:7, component size 1541.",
      "evidence:q1:0000"
    ).status shouldBe
      VerificationStatus.Conflicting
    check(s"Rank 2 by distinct coauthors: $Ada.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Conflicting
    check(s"$Ada has coreness 3 and component size 1540.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Verified
  }

  it should "check the M_r work IDs" in {
    check(
      s"$Ada had maximum coauthors on one paper 6, on work:dblp:conf/kdd/Smith19.",
      "evidence:q1:0000"
    ).status shouldBe VerificationStatus.Verified
    val c = check(
      s"$Ada had maximum coauthors on one paper 6, on work:dblp:conf/kdd/Fake99.",
      "evidence:q1:0000"
    )
    c.status shouldBe VerificationStatus.Conflicting
    c.reasons.mkString should include("work:dblp:conf/kdd/Fake99")
  }

  it should "check component and community IDs" in {
    check(s"$Ada is in community community:9.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Conflicting
    check(s"$Ada is in community comm-1.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Verified
  }

  it should "reject a numeric or zero bridge score where the evidence says not computed" in {
    check(s"$Alan has bridge score 0.", "evidence:q1:0001").status shouldBe
      VerificationStatus.Conflicting
    check(s"$Alan has bridge score 0.25.", "evidence:q1:0001").status shouldBe
      VerificationStatus.Conflicting
    check(s"$Alan's bridge score was not computed.", "evidence:q1:0001").status shouldBe
      VerificationStatus.Verified
  }

  it should "reject a wrong bridge method or a false not-computed statement" in {
    check(s"$Ada has bridge score 0.0001, method exact.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Conflicting
    check(s"$Ada's bridge score was not computed.", "evidence:q1:0000").status shouldBe
      VerificationStatus.Conflicting
  }

  "A ranking answer (llm)" should "remove a fabricated metric number as CONFLICTING" in {
    val neptune = FakeNeptune(Vector(adaRow, graceRow))
    val llm = new LlmClient:
      def generate(p: String, m: Int): String =
        val id = "id=\"(evidence:[^\"]+)\"".r.findFirstMatchIn(p).get.group(1)
        claimsJson(
          s"$Ada has 48 distinct coauthors in 2015-2024." -> Seq(id),
          s"$Ada has 480 distinct coauthors."             -> Seq(id)
        )
    val config = appConfig()
    val ports = RuntimePorts(
      MapLookup(Map.empty),
      NeptuneMetricsStore(neptune.client),
      MapGraph(),
      EmptyVectors,
      ConstText(""),
      MemoryAudit()
    )
    val result = RuntimeComposition
      .fromPorts(config, ports, llm, TestEmbedders.FakeQueryEmbedder(), MetricWindow(2015, 2024))
      .execute("Which researchers have the broadest coauthorship networks since 2015?", Map.empty)
    result.response.claims.map(_.statement) shouldBe Vector(
      s"$Ada has 48 distinct coauthors in 2015-2024."
    )
    result.response.generatedText should not include "480"
    result.auditRecord.verificationSummary should include("Conflicting=1")
    result.auditRecord.removedClaimIds should have size 1
  }
