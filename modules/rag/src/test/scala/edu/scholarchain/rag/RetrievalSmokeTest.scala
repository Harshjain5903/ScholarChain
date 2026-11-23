package edu.scholarchain.rag

import edu.scholarchain.core.Provenance
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

class RetrievalSmokeTest extends AnyFlatSpec with Matchers:

  private val testProvenance =
    Provenance("test", "rec-1", "run-1", "v1", "2026-01-01T00:00:00Z")

  // --- EmbeddingTemplates ---

  "EmbeddingTemplates.researcherProfile" should "produce non-empty string containing the researcherId" in {
    val result = EmbeddingTemplates.researcherProfile(
      researcherId = "R001",
      displayName = "Alice",
      topics = List("NLP", "IR"),
      institutions = List("MIT"),
      windowStartYear = 2020,
      windowEndYear = 2025,
      publicationCount = 42,
      distinctCoauthorCount = 10,
      representativeTitles = List("Paper A")
    )
    result should not be empty
    result should include("R001")
  }

  "EmbeddingTemplates.communityProfile" should "include the communityId" in {
    val result = EmbeddingTemplates.communityProfile(
      communityId = "C100",
      dominantTopics = List("ML"),
      memberCount = 15,
      windowStartYear = 2018,
      windowEndYear = 2024,
      representativeTitles = List("Title X")
    )
    result should include("C100")
  }

  "EmbeddingTemplates.collaborationPath" should "mention start and end researcher IDs" in {
    val hops = List(
      PathHop("R001", "Alice", 3, List(2020, 2021)),
      PathHop("R002", "Bob", 2, List(2021))
    )
    val result = EmbeddingTemplates.collaborationPath(
      startResearcherId = "R001",
      endResearcherId = "R003",
      hops = hops,
      windowStartYear = 2019,
      windowEndYear = 2023
    )
    result should include("R001")
    result should include("R003")
  }

  "EmbeddingTemplates.paperSummary" should "include the title" in {
    val result = EmbeddingTemplates.paperSummary(
      workId = "W001",
      title = "Graph RAG Systems",
      year = 2024,
      venue = Some("SIGIR"),
      abstractText = Some("We present a system."),
      topics = List("RAG")
    )
    result should include("Graph RAG Systems")
  }

  "EmbeddingTemplates.topicInstitutionSummary" should "differ between topic and institution entityType" in {
    val topicResult = EmbeddingTemplates.topicInstitutionSummary(
      entityId = "T1",
      entityType = "topic",
      name = "Machine Learning",
      description = "A broad field.",
      relatedTopics = List("AI"),
      windowStartYear = 2020,
      windowEndYear = 2025
    )
    val instResult = EmbeddingTemplates.topicInstitutionSummary(
      entityId = "I1",
      entityType = "institution",
      name = "MIT",
      description = "A university.",
      relatedTopics = List("CS"),
      windowStartYear = 2020,
      windowEndYear = 2025
    )
    topicResult should include("topic")
    instResult should include("institution")
    topicResult should not equal instResult
  }

  // --- ChunkingPolicy ---

  "ChunkingPolicy.estimateTokens" should "return > 0 for non-empty text" in {
    ChunkingPolicy.estimateTokens("hello world") should be > 0
  }

  it should "return 0 for empty string" in {
    ChunkingPolicy.estimateTokens("") shouldBe 0
  }

  "ChunkingPolicy.chunk" should "return single item for short text below maxTokens" in {
    val result =
      ChunkingPolicy.chunk("Short text.", ChunkingPolicy.DefaultAbstractPolicy)
    result should have size 1
    result.head shouldBe "Short text."
  }

  it should "split correctly on sentence boundary" in {
    val longText =
      (1 to 200).map(i => s"Sentence number $i is here.").mkString(" ")
    val result =
      ChunkingPolicy.chunk(longText, ChunkingPolicy.DefaultAbstractPolicy)
    result.size should be > 1
    result.foreach { chunk =>
      chunk should not be empty
    }
  }

  // --- EmbeddingModelConfig ---

  "EmbeddingModelConfig.load" should "return dimensions > 0" in {
    val config = EmbeddingModelConfig.load()
    config.dimensions should be > 0
  }

  it should "return non-empty modelId" in {
    val config = EmbeddingModelConfig.load()
    config.modelId should not be empty
  }

  "EmbeddingModelConfig.Default" should "match load" in {
    EmbeddingModelConfig.Default shouldBe EmbeddingModelConfig.load()
  }

  // --- SemanticContextFormat ---

  private def mkContext(
    id: String,
    score: Double,
    text: String
  ): SemanticContext =
    SemanticContext(
      contextId = id,
      entityId = "e1",
      documentType = "researcher",
      text = text,
      textS3Uri = "s3://bucket/key",
      vectorScore = score,
      embeddingModel = "titan-v2",
      windowStartYear = 2020,
      windowEndYear = 2025,
      provenance = testProvenance
    )

  "SemanticContextFormat.rankByRelevance" should "sort descending by vectorScore" in {
    val contexts = List(
      mkContext("c1", 0.5, "text a"),
      mkContext("c2", 0.9, "text b"),
      mkContext("c3", 0.7, "text c")
    )
    val ranked = SemanticContextFormat.rankByRelevance(contexts)
    ranked.map(_.contextId) shouldBe List("c2", "c3", "c1")
  }

  "SemanticContextFormat.trimToTokenBudget" should "produce totalTokenEstimate <= maxTokens" in {
    val bundle = SemanticContextBundle(
      queryId = "q1",
      researcherContexts = List(
        mkContext("c1", 0.9, "some text here for testing"),
        mkContext("c2", 0.8, "another piece of text")
      ),
      communityContexts = List.empty,
      pathContexts = List.empty,
      paperContexts = List.empty,
      topicContexts = List.empty,
      totalTokenEstimate = 0,
      truncated = false
    )
    val trimmed = SemanticContextFormat.trimToTokenBudget(bundle, 100)
    trimmed.totalTokenEstimate should be <= 100
  }

  it should "set truncated=true when items are dropped" in {
    val bundle = SemanticContextBundle(
      queryId = "q1",
      researcherContexts = List(
        mkContext("c1", 0.9, "word " * 100),
        mkContext("c2", 0.8, "word " * 100)
      ),
      communityContexts = List.empty,
      pathContexts = List.empty,
      paperContexts = List.empty,
      topicContexts = List.empty,
      totalTokenEstimate = 0,
      truncated = false
    )
    val trimmed = SemanticContextFormat.trimToTokenBudget(bundle, 10)
    trimmed.truncated shouldBe true
  }

  // --- Serialization round-trips ---

  "SemanticContext" should "round-trip via uPickle" in {
    val ctx      = mkContext("c1", 0.85, "sample text")
    val json     = write(ctx)
    val restored = read[SemanticContext](json)
    restored shouldBe ctx
  }

  "SemanticContextBundle" should "round-trip via uPickle" in {
    val bundle = SemanticContextBundle(
      queryId = "q1",
      researcherContexts = List(mkContext("c1", 0.9, "text")),
      communityContexts = List.empty,
      pathContexts = List.empty,
      paperContexts = List.empty,
      topicContexts = List.empty,
      totalTokenEstimate = 10,
      truncated = false
    )
    val json     = write(bundle)
    val restored = read[SemanticContextBundle](json)
    restored shouldBe bundle
  }

  "PathHop" should "round-trip via uPickle" in {
    val hop      = PathHop("R001", "Alice", 5, List(2020, 2021))
    val json     = write(hop)
    val restored = read[PathHop](json)
    restored shouldBe hop
  }
