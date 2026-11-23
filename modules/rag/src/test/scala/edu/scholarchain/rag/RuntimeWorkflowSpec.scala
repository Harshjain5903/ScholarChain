package edu.scholarchain.rag

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeWorkflowSpec extends AnyFlatSpec with Matchers:

  private val TestGenerationModel = "test-generation-model"

  private val testProv =
    Provenance("test", "rec-1", "run-1", "v1", "2026-01-01T00:00:00Z")

  private val aliceResearcher = CanonicalResearcher(
    researcherId = "R001",
    displayName = "Alice Smith",
    orcid = Some("0000-0001-0001-0001"),
    openAlexId = None,
    semanticScholarId = None,
    alternateNames = Vector("A. Smith"),
    provenance = testProv
  )

  private val bobResearcher = CanonicalResearcher(
    researcherId = "R002",
    displayName = "Bob Jones",
    orcid = Some("0000-0001-0001-0002"),
    openAlexId = None,
    semanticScholarId = None,
    alternateNames = Vector("B. Jones"),
    provenance = testProv
  )

  private val aliceMetrics = ResearcherMetrics(
    researcherId = "R001",
    publicationCount = 42,
    distinctCoauthorCount = 10,
    totalCoauthorOccurrences = 50,
    averageCoauthorsPerPaper = 2.5,
    maximumCoauthorsOnOnePaper = 8,
    weightedDegree = 15.0,
    componentId = Some("comp-1"),
    communityId = Some("comm-1"),
    bridgeScore = 0.3,
    coreness = 3,
    windowStartYear = 2015,
    windowEndYear = 2025,
    provenance = testProv
  )

  private val bobMetrics = ResearcherMetrics(
    researcherId = "R002",
    publicationCount = 30,
    distinctCoauthorCount = 8,
    totalCoauthorOccurrences = 35,
    averageCoauthorsPerPaper = 2.0,
    maximumCoauthorsOnOnePaper = 5,
    weightedDegree = 10.0,
    componentId = Some("comp-1"),
    communityId = Some("comm-1"),
    bridgeScore = 0.1,
    coreness = 2,
    windowStartYear = 2015,
    windowEndYear = 2025,
    provenance = testProv
  )

  // --- Fake implementations ---

  class FakeResearcherLookup(data: Map[String, Vector[CanonicalResearcher]])
      extends ResearcherLookup:

    def findByName(name: String): Vector[CanonicalResearcher] =
      data.getOrElse(name, Vector.empty)

    def findById(id: String): Option[CanonicalResearcher] =
      data.values.flatten.find(_.researcherId == id)

  class FakeMetricsStore(data: Map[String, ResearcherMetrics]) extends MetricsStore:

    def getMetrics(
      researcherId: String,
      windowStart: Int,
      windowEnd: Int
    ): Option[ResearcherMetrics] =
      data.get(researcherId)

  class FakeGraphStore(
    coauthors: Map[String, Vector[CoauthorEdge]] = Map.empty,
    paths: Map[(String, String), Vector[String]] = Map.empty,
    members: Map[String, Vector[String]] = Map.empty
  ) extends GraphStore:

    def getDirectCoauthors(
      researcherId: String,
      limit: Int
    ): Vector[CoauthorEdge] =
      coauthors.getOrElse(researcherId, Vector.empty).take(limit)

    def shortestPath(
      fromId: String,
      toId: String,
      maxDepth: Int
    ): Option[Vector[String]] =
      paths.get((fromId, toId))

    def getNeighbors(
      nodeId: String,
      edgeTypes: Vector[String],
      depth: Int,
      limit: Int
    ): Vector[GraphNode] =
      Vector.empty

    def getComponentMembers(
      componentId: String,
      limit: Int
    ): Vector[String] =
      members.getOrElse(componentId, Vector.empty).take(limit)

  class FakeVectorStore extends VectorStore:

    def query(
      indexName: String,
      queryVector: Vector[Float],
      topK: Int,
      filters: Option[ujson.Value]
    ): Vector[VectorSearchResult] =
      Vector(
        VectorSearchResult("key1", 0.9, "researcher", "R001", "s3://bucket/text1")
      )

  class FakeTextStore extends TextStore:

    def getText(s3Uri: String): Option[String] =
      Some("Sample text for testing semantic context retrieval.")

  class FakeLlmClient(response: String) extends LlmClient:
    def generate(prompt: String, maxTokens: Int): String = response

  class CountingLlmClient(responses: Iterator[String]) extends LlmClient:
    var callCount: Int = 0

    def generate(prompt: String, maxTokens: Int): String =
      callCount += 1
      responses.next()

  class FakeAuditStore extends AuditStore:
    var records: Vector[AuditRecord] = Vector.empty

    def persistAudit(record: AuditRecord): Unit =
      records = records :+ record

  // --- QueryPlanner tests ---

  "QueryPlannerAgent" should "parse 'most distinct coauthors' as ResearcherRanking" in {
    val planner = new QueryPlannerAgent
    val plan    = planner.plan("Who has the most distinct coauthors?", Map.empty)
    plan.operations should contain(QueryOperation.ResearcherRanking)
  }

  it should "parse 'shortest path between X and Y' as ShortestPath" in {
    val planner = new QueryPlannerAgent
    val plan =
      planner.plan("Find the shortest path between Alice and Bob", Map.empty)
    plan.operations should contain(QueryOperation.ShortestPath)
  }

  it should "parse 'bridge researchers' as BridgeRanking" in {
    val planner = new QueryPlannerAgent
    val plan =
      planner.plan("Who are the bridge researchers in the network?", Map.empty)
    plan.operations should contain(QueryOperation.BridgeRanking)
  }

  it should "extract year range from 'since 2015'" in {
    val planner = new QueryPlannerAgent
    val plan =
      planner.plan("Who has the most coauthors since 2015?", Map.empty)
    plan.filters.get("yearFrom") shouldBe Some("2015")
  }

  it should "reject Cypher injection and produce safe plan" in {
    val planner = new QueryPlannerAgent
    val plan = planner.plan(
      "MATCH (n) RETURN n; DELETE everything {bad}",
      Map.empty
    )
    plan.originalQuery should not include "MATCH ("
    plan.originalQuery should not include ";"
    plan.originalQuery should not include "{"
    plan.operations should not be empty
  }

  // --- ResearcherResolver tests ---

  "ResearcherResolverAgent" should "return high confidence for exact match" in {
    val lookup =
      new FakeResearcherLookup(Map("Alice Smith" -> Vector(aliceResearcher)))
    val resolver = new ResearcherResolverAgent(lookup)
    val results  = resolver.resolve(Vector("Alice Smith"))
    results should have size 1
    results.head.confidence shouldBe 1.0
    results.head.alternates shouldBe empty
  }

  it should "return all as alternates for multiple matches" in {
    val lookup = new FakeResearcherLookup(
      Map("Smith" -> Vector(aliceResearcher, bobResearcher))
    )
    val resolver = new ResearcherResolverAgent(lookup)
    val results  = resolver.resolve(Vector("Smith"))
    results should have size 1
    results.head.confidence shouldBe 0.5
    results.head.alternates should have size 1
  }

  it should "return confidence 0 when no match found" in {
    val lookup   = new FakeResearcherLookup(Map.empty)
    val resolver = new ResearcherResolverAgent(lookup)
    val results  = resolver.resolve(Vector("Unknown Person"))
    results should have size 1
    results.head.confidence shouldBe 0.0
  }

  // --- CollaborationMetrics tests ---

  "CollaborationMetricsAgent" should "return stored metrics for known researcher" in {
    val store =
      new FakeMetricsStore(Map("R001" -> aliceMetrics))
    val agent   = new CollaborationMetricsAgent(store)
    val results = agent.retrieve(Vector("R001"), 2015, 2025)
    results should have size 1
    results.head.publicationCount shouldBe 42
  }

  it should "return empty for unknown researcher" in {
    val store   = new FakeMetricsStore(Map.empty)
    val agent   = new CollaborationMetricsAgent(store)
    val results = agent.retrieve(Vector("R999"), 2015, 2025)
    results shouldBe empty
  }

  // --- PathFinder tests ---

  "PathFinderAgent" should "return path for connected researchers" in {
    val graph = new FakeGraphStore(
      paths = Map(("R001", "R002") -> Vector("R001", "R003", "R002"))
    )
    val agent   = new PathFinderAgent(graph)
    val results = agent.findPaths(Vector(("R001", "R002")))
    results should have size 1
    results.head.path shouldBe Some(Vector("R001", "R003", "R002"))
  }

  it should "return None for disconnected pair" in {
    val graph   = new FakeGraphStore()
    val agent   = new PathFinderAgent(graph)
    val results = agent.findPaths(Vector(("R001", "R999")))
    results should have size 1
    results.head.path shouldBe None
  }

  // --- CommunityAnalyst tests ---

  "CommunityAnalystAgent" should "return community info with member count" in {
    val metricsStore =
      new FakeMetricsStore(Map("R001" -> aliceMetrics, "R002" -> bobMetrics))
    val graphStore = new FakeGraphStore(
      members = Map("comm-1" -> Vector("R001", "R002", "R003"))
    )
    val agent   = new CommunityAnalystAgent(metricsStore, graphStore)
    val results = agent.analyze(Vector("R001", "R002"), 2015, 2025)
    results should have size 1
    results.head.communityId shouldBe "comm-1"
    results.head.memberCount shouldBe 3
  }

  // --- EvidenceBuilder tests ---

  "EvidenceBuilderAgent" should "build bundle with correct item count" in {
    val builder = new EvidenceBuilderAgent
    val semanticBundle = SemanticContextBundle(
      queryId = "q1",
      researcherContexts = List.empty,
      communityContexts = List.empty,
      pathContexts = List.empty,
      paperContexts = List.empty,
      topicContexts = List.empty,
      totalTokenEstimate = 0,
      truncated = false
    )
    val bundle = builder.build(
      queryId = "q1",
      metrics = Vector(aliceMetrics),
      paths = Vector(
        PathResult("R001", "R002", Some(Vector("R001", "R002")), Vector.empty)
      ),
      communities = Vector.empty,
      semanticContext = semanticBundle,
      resolvedResearchers = Vector.empty,
      runId = "run-test"
    )
    bundle.items should have size 2 // 1 metric + 1 path
  }

  it should "respect max items budget" in {
    val builder = new EvidenceBuilderAgent
    val manyMetrics = (1 to 10).map { i =>
      aliceMetrics.copy(
        researcherId = s"R$i",
        provenance = testProv
      )
    }.toVector
    val semanticBundle = SemanticContextBundle(
      queryId = "q1",
      researcherContexts = List.empty,
      communityContexts = List.empty,
      pathContexts = List.empty,
      paperContexts = List.empty,
      topicContexts = List.empty,
      totalTokenEstimate = 0,
      truncated = false
    )
    val bundle = builder.build(
      queryId = "q1",
      metrics = manyMetrics,
      paths = Vector.empty,
      communities = Vector.empty,
      semanticContext = semanticBundle,
      resolvedResearchers = Vector.empty,
      runId = "run-test",
      maxItems = 5
    )
    bundle.items should have size 5
  }

  // --- AnswerSynthesis tests ---

  "AnswerSynthesisAgent" should "generate claims with evidence IDs from valid LLM JSON" in {
    val validJson =
      """[{"claimId":"c1","statement":"Alice has 42 publications.","confidence":0.95,"supportingEvidence":["ev-metric-R001"]}]"""
    val llm   = new FakeLlmClient(validJson)
    val agent = new AnswerSynthesisAgent(llm)

    val evidence = EvidenceBundle(
      bundleId = "b1",
      queryId = "q1",
      items = Vector(
        EvidenceItem(
          evidenceId = "ev-metric-R001",
          evidenceType = EvidenceType.MetricRecord,
          sourceEntityId = "R001",
          content = "publications=42",
          score = 1.0,
          provenance = testProv
        )
      ),
      totalScore = 1.0,
      provenance = testProv
    )

    val plan = QueryPlan(
      queryId = "q1",
      originalQuery = "How many publications does Alice have?",
      operations = Vector(QueryOperation.ResearcherRanking),
      filters = Map("yearFrom" -> "2015", "yearTo" -> "2025"),
      provenance = testProv
    )

    val claims = agent.synthesize(plan, evidence, Vector.empty)
    claims should have size 1
    claims.head.supportingEvidence should contain("ev-metric-R001")
  }

  it should "retry on malformed LLM output and eventually abstain" in {
    val responses = Iterator("not json", "still not json", "nope")
    val llm       = new CountingLlmClient(responses)
    val agent     = new AnswerSynthesisAgent(llm)

    val evidence = EvidenceBundle(
      bundleId = "b1",
      queryId = "q1",
      items = Vector(
        EvidenceItem(
          evidenceId = "ev1",
          evidenceType = EvidenceType.MetricRecord,
          sourceEntityId = "R001",
          content = "test",
          score = 1.0,
          provenance = testProv
        )
      ),
      totalScore = 1.0,
      provenance = testProv
    )

    val plan = QueryPlan(
      queryId = "q1",
      originalQuery = "test question",
      operations = Vector(QueryOperation.ResearcherRanking),
      filters = Map.empty,
      provenance = testProv
    )

    val claims = agent.synthesize(plan, evidence, Vector.empty)
    claims should have size 1
    claims.head.statement should include("Insufficient evidence")
    llm.callCount shouldBe 3 // initial + 2 retries
  }

  // --- ClaimVerification tests ---

  "ClaimVerificationAgent" should "mark claim as Verified when all evidence exists" in {
    val agent = new ClaimVerificationAgent
    val claim = AnswerClaim(
      claimId = "c1",
      queryId = "q1",
      statement = "Alice has 42 publications.",
      confidence = 0.9,
      supportingEvidence = Vector("ev1"),
      verificationStatus = VerificationStatus.Unsupported,
      provenance = testProv
    )
    val evidence = EvidenceBundle(
      bundleId = "b1",
      queryId = "q1",
      items = Vector(
        EvidenceItem("ev1", EvidenceType.MetricRecord, "R001", "test", 1.0, testProv)
      ),
      totalScore = 1.0,
      provenance = testProv
    )
    val results = agent.verify(Vector(claim), evidence)
    results.head.verificationStatus shouldBe VerificationStatus.Verified
  }

  it should "mark as Unsupported when evidence IDs are missing" in {
    val agent = new ClaimVerificationAgent
    val claim = AnswerClaim(
      claimId = "c1",
      queryId = "q1",
      statement = "A claim with no evidence.",
      confidence = 0.5,
      supportingEvidence = Vector("ev-nonexistent"),
      verificationStatus = VerificationStatus.Unsupported,
      provenance = testProv
    )
    val evidence = EvidenceBundle(
      bundleId = "b1",
      queryId = "q1",
      items = Vector.empty,
      totalScore = 0.0,
      provenance = testProv
    )
    val results = agent.verify(Vector(claim), evidence)
    results.head.verificationStatus shouldBe VerificationStatus.Unsupported
  }

  it should "mark as PartiallySupported when some evidence missing" in {
    val agent = new ClaimVerificationAgent
    val claim = AnswerClaim(
      claimId = "c1",
      queryId = "q1",
      statement = "A partial claim.",
      confidence = 0.7,
      supportingEvidence = Vector("ev1", "ev-missing"),
      verificationStatus = VerificationStatus.Unsupported,
      provenance = testProv
    )
    val evidence = EvidenceBundle(
      bundleId = "b1",
      queryId = "q1",
      items = Vector(
        EvidenceItem("ev1", EvidenceType.MetricRecord, "R001", "test", 1.0, testProv)
      ),
      totalScore = 1.0,
      provenance = testProv
    )
    val results = agent.verify(Vector(claim), evidence)
    results.head.verificationStatus shouldBe VerificationStatus.PartiallySupported
  }

  // --- RuntimeWorkflow end-to-end tests ---

  "RuntimeWorkflow" should "produce WorkflowResult with verified claims end-to-end" in {
    val lookup = new FakeResearcherLookup(
      Map("Alice Smith" -> Vector(aliceResearcher))
    )
    val metricsStore =
      new FakeMetricsStore(Map("R001" -> aliceMetrics))
    val graphStore = new FakeGraphStore(
      members = Map("comm-1" -> Vector("R001"))
    )
    val vectorStore = new FakeVectorStore
    val textStore   = new FakeTextStore
    val validJson =
      """[{"claimId":"c1","statement":"Alice has 42 publications.","confidence":0.95,"supportingEvidence":["ev-metric-R001"]}]"""
    val llmClient  = new FakeLlmClient(validJson)
    val auditStore = new FakeAuditStore

    val workflow = new RuntimeWorkflow(
      planner = new QueryPlannerAgent,
      resolver = new ResearcherResolverAgent(lookup),
      metricsAgent = new CollaborationMetricsAgent(metricsStore),
      pathFinder = new PathFinderAgent(graphStore),
      community = new CommunityAnalystAgent(metricsStore, graphStore),
      semantic = new SemanticContextAgent(
        vectorStore,
        textStore,
        new TestEmbedders.FakeQueryEmbedder(),
        TestEmbedders.defaultEmbeddingConfig
      ),
      evidenceBuilder = new EvidenceBuilderAgent,
      synthesis = new AnswerSynthesisAgent(llmClient),
      verification = new ClaimVerificationAgent,
      audit = auditStore,
      generationModelId = TestGenerationModel
    )

    val result = workflow.execute(
      "How many coauthors does \"Alice Smith\" have since 2015?",
      Map.empty
    )

    result.response.claims should not be empty
    result.auditRecord.queryId should not be empty
    auditStore.records should have size 1
  }

  it should "abstain when no researchers are resolved" in {
    val lookup       = new FakeResearcherLookup(Map.empty)
    val metricsStore = new FakeMetricsStore(Map.empty)
    val graphStore   = new FakeGraphStore()
    val vectorStore  = new FakeVectorStore
    val textStore    = new FakeTextStore
    val validJson =
      """[{"claimId":"c1","statement":"test","confidence":0.5,"supportingEvidence":[]}]"""
    val llmClient  = new FakeLlmClient(validJson)
    val auditStore = new FakeAuditStore

    val workflow = new RuntimeWorkflow(
      planner = new QueryPlannerAgent,
      resolver = new ResearcherResolverAgent(lookup),
      metricsAgent = new CollaborationMetricsAgent(metricsStore),
      pathFinder = new PathFinderAgent(graphStore),
      community = new CommunityAnalystAgent(metricsStore, graphStore),
      semantic = new SemanticContextAgent(
        vectorStore,
        textStore,
        new TestEmbedders.FakeQueryEmbedder(),
        TestEmbedders.defaultEmbeddingConfig
      ),
      evidenceBuilder = new EvidenceBuilderAgent,
      synthesis = new AnswerSynthesisAgent(llmClient),
      verification = new ClaimVerificationAgent,
      audit = auditStore,
      generationModelId = TestGenerationModel
    )

    val result = workflow.execute(
      "How many coauthors does \"Nobody\" have?",
      Map.empty
    )

    result.response.claims should not be empty
    result.auditRecord.resolvedIds shouldBe empty
  }

  private def workflowWith(
    semantic: SemanticContextAgent,
    auditStore: FakeAuditStore
  ): RuntimeWorkflow =
    val lookup       = new FakeResearcherLookup(Map("Alice Smith" -> Vector(aliceResearcher)))
    val metricsStore = new FakeMetricsStore(Map("R001" -> aliceMetrics))
    val graphStore   = new FakeGraphStore(members = Map("comm-1" -> Vector("R001")))
    val validJson =
      """[{"claimId":"c1","statement":"Alice has 42 publications.","confidence":0.95,"supportingEvidence":["ev-metric-R001"]}]"""
    new RuntimeWorkflow(
      planner = new QueryPlannerAgent,
      resolver = new ResearcherResolverAgent(lookup),
      metricsAgent = new CollaborationMetricsAgent(metricsStore),
      pathFinder = new PathFinderAgent(graphStore),
      community = new CommunityAnalystAgent(metricsStore, graphStore),
      semantic = semantic,
      evidenceBuilder = new EvidenceBuilderAgent,
      synthesis = new AnswerSynthesisAgent(new FakeLlmClient(validJson)),
      verification = new ClaimVerificationAgent,
      audit = auditStore,
      generationModelId = TestGenerationModel
    )

  it should "record the configured generation model id in the audit record" in {
    val auditStore = new FakeAuditStore
    val semantic = new SemanticContextAgent(
      new FakeVectorStore,
      new FakeTextStore,
      new TestEmbedders.FakeQueryEmbedder(),
      TestEmbedders.defaultEmbeddingConfig
    )
    val result = workflowWith(semantic, auditStore)
      .execute("How many coauthors does \"Alice Smith\" have?", Map.empty)
    result.auditRecord.modelVersion shouldBe TestGenerationModel
    result.auditRecord.verificationSummary should not include "semanticAbstained"
  }

  it should "exclude vector neighbours and audit the reason on embedding model mismatch" in {
    val auditStore  = new FakeAuditStore
    val vectorStore = new TestEmbedders.RecordingVectorStore
    val semantic = new SemanticContextAgent(
      vectorStore,
      new FakeTextStore,
      new TestEmbedders.FakeQueryEmbedder(),
      TestEmbedders.config(queryModel = "voyage-x")
    )
    val result = workflowWith(semantic, auditStore)
      .execute("How many coauthors does \"Alice Smith\" have?", Map.empty)

    vectorStore.queries shouldBe empty
    result.response.evidenceBundle.items
      .map(_.evidenceType) should not contain EvidenceType.TextChunk
    result.auditRecord.verificationSummary should include(
      "semanticAbstained=EMBEDDING_MODEL_MISMATCH"
    )
  }
