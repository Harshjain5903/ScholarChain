package edu.scholarchain.rag

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import TestEmbedders.*

class SemanticContextAgentSpec extends AnyFlatSpec with Matchers:

  private val AllIndexes = Vector(
    "researcher-profile-v1",
    "collaboration-community-v1",
    "collaboration-path-v1",
    "paper-abstract-v1",
    "topic-institution-summary-v1"
  )

  private def agent(
    embedder: QueryEmbedder,
    cfg: EmbeddingModelConfig = config(TitanV2)
  ): (SemanticContextAgent, RecordingVectorStore) =
    val store = new RecordingVectorStore
    (new SemanticContextAgent(store, new ConstTextStore, embedder, cfg), store)

  // --- happy path ---

  "SemanticContextAgent" should "query with a 1024-length vector produced by the embedder, not a hash" in {
    val embedder         = new FakeQueryEmbedder()
    val (sut, store)     = agent(embedder)
    val bundle           = sut.retrieve(Vector("R001"), Vector.empty, "who works on graphs?")
    val expectedFromFake = fixedVector(1024)

    embedder.calls shouldBe Vector("who works on graphs?" -> TitanV2)
    store.queries.map(_._1) shouldBe AllIndexes
    store.queries.foreach { case (_, v) =>
      v.length shouldBe 1024
      v shouldBe expectedFromFake
    }
    bundle.failure shouldBe None
  }

  it should "pass the embedded vector to the vector port unchanged for every index" in {
    val custom = Vector.tabulate(1024)(i => if i % 2 == 0 then -i.toFloat else i.toFloat / 3f)
    val embedder = new QueryEmbedder:
      def embed(text: String, modelId: String): QueryEmbedding = QueryEmbedding(custom, modelId)
    val (sut, store) = agent(embedder)
    val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")

    store.queries should have size 5
    store.queries.foreach { case (_, v) => v shouldBe custom }
    val all = bundle.researcherContexts ++ bundle.communityContexts ++ bundle.pathContexts ++
      bundle.paperContexts ++ bundle.topicContexts
    all should not be empty
    all.foreach(_.embeddingModel shouldBe TitanV2)
  }

  it should "use the same embedder vector for different query texts (no text-hash vector)" in {
    val (sut, store) = agent(new FakeQueryEmbedder())
    sut.retrieve(Vector.empty, Vector.empty, "alpha")
    sut.retrieve(Vector.empty, Vector.empty, "a completely different question")
    store.queries.map(_._2).distinct shouldBe Vector(fixedVector(1024))
  }

  // --- model mismatch ---

  it should "not query and return EMBEDDING_MODEL_MISMATCH when query model is voyage and index is Titan" in {
    val embedder     = new FakeQueryEmbedder()
    val (sut, store) = agent(embedder, config(queryModel = "voyage-x"))
    val bundle       = sut.retrieve(Vector("R001"), Vector.empty, "q")

    store.queries shouldBe empty
    embedder.calls shouldBe empty // no embedding call is spent on an unusable model
    bundle.abstained shouldBe true
    bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingModelMismatch)
    bundle.failure.get.reason.code shouldBe "EMBEDDING_MODEL_MISMATCH"
    bundle.failure.get.detail should include("voyage-x")
    bundle.researcherContexts shouldBe empty
    bundle.paperContexts shouldBe empty
  }

  it should "not query when the embedder reports a different model than the index model" in {
    val embedder     = new FakeQueryEmbedder(reportedModel = Some("voyage-x"))
    val (sut, store) = agent(embedder)
    val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")

    store.queries shouldBe empty
    bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingModelMismatch)
  }

  // --- dimension mismatch ---

  it should "not query and return EMBEDDING_DIMENSION_MISMATCH when the embedder returns 256 values" in {
    val (sut, store) = agent(new FakeQueryEmbedder(dimensions = 256))
    val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")

    store.queries shouldBe empty
    bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingDimensionMismatch)
    bundle.failure.get.reason.code shouldBe "EMBEDDING_DIMENSION_MISMATCH"
    bundle.failure.get.detail should include("256")
    bundle.failure.get.detail should include("1024")
  }

  // --- embedder failure ---

  it should "not query and return EMBEDDING_UNAVAILABLE when the embedder throws" in {
    val (sut, store) = agent(new FakeQueryEmbedder(fail = true))
    val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")

    store.queries shouldBe empty
    bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingUnavailable)
  }

  // --- unknown models ---

  it should "treat an unknown query model id as incompatible" in {
    val unknowns =
      Vector("some-unknown-model", "amazon.titan-embed-text-v2:0-custom", "titan-embed-text-v2", "")
    unknowns.foreach { m =>
      val cfg = config(queryModel = m)
      cfg.isQueryModelCompatible shouldBe false
      val (sut, store) = agent(new FakeQueryEmbedder(), cfg)
      val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")
      store.queries shouldBe empty
      bundle.failure.map(_.reason) shouldBe Some(SemanticFailureReason.EmbeddingModelMismatch)
    }
  }

  // --- explicit fallback policy ---

  it should "allow a fallback model only against an index set declared as embedded by that model" in {
    // Voyage query vs Titan index: refused.
    config(queryModel = "voyage-3", indexModel = TitanV2).isQueryModelCompatible shouldBe false
    // Voyage query vs a separate, re-embedded Voyage index set: allowed.
    val voyageCfg = config(queryModel = "voyage-3", indexModel = "voyage-3")
    voyageCfg.isQueryModelCompatible shouldBe true
    val (sut, store) = agent(new FakeQueryEmbedder(), voyageCfg)
    val bundle       = sut.retrieve(Vector.empty, Vector.empty, "q")
    bundle.failure shouldBe None
    store.queries should have size 5
  }

  // --- serialization of the typed failure ---

  it should "round-trip a failed bundle through JSON with the reason code" in {
    val (sut, _) = agent(new FakeQueryEmbedder(dimensions = 256))
    val bundle   = sut.retrieve(Vector.empty, Vector.empty, "q")
    val json     = write(bundle)
    json should include("EMBEDDING_DIMENSION_MISMATCH")
    read[SemanticContextBundle](json) shouldBe bundle
  }

  // --- config ---

  "EmbeddingModelConfig" should "default to Titan v2, 1024 dimensions and cosine from core config" in {
    val cfg = defaultEmbeddingConfig
    cfg.queryModelId shouldBe TitanV2
    cfg.index.embeddingModelId shouldBe TitanV2
    cfg.index.dimensions shouldBe 1024
    cfg.index.distanceMetric shouldBe DistanceMetric.Cosine
    cfg.isQueryModelCompatible shouldBe true
  }

  it should "respect SCHOLARCHAIN_EMBEDDING_MODEL_ID and become incompatible with the Titan index" in {
    val cfg = EmbeddingModelConfig.load(
      referenceConfig(Map("SCHOLARCHAIN_EMBEDDING_MODEL_ID" -> "voyage-3"))
    )
    cfg.queryModelId shouldBe "voyage-3"
    cfg.index.embeddingModelId shouldBe TitanV2
    cfg.isQueryModelCompatible shouldBe false
  }

  it should "ignore the legacy unprefixed EMBEDDING_MODEL_ID name" in {
    val cfg = EmbeddingModelConfig.load(referenceConfig(Map("EMBEDDING_MODEL_ID" -> "voyage-3")))
    cfg.queryModelId shouldBe TitanV2
  }

  it should "respect SCHOLARCHAIN_S3_VECTORS_* overrides for the index contract" in {
    val cfg = EmbeddingModelConfig.load(
      referenceConfig(
        Map(
          "SCHOLARCHAIN_EMBEDDING_MODEL_ID"            -> "voyage-3",
          "SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID" -> "voyage-3",
          "SCHOLARCHAIN_S3_VECTORS_DIMENSIONS"         -> "512"
        )
      )
    )
    cfg.isQueryModelCompatible shouldBe true
    cfg.dimensions shouldBe 512
    cfg.index.isCompatibleVector(1024) shouldBe false
  }
