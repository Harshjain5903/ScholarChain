package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.{Success, Try}

/**
 * Write-path enforcement of the S3 Vectors index contract (human decision 2026-11-22): every index
 * is 1024-dim cosine, populated only by amazon.titan-embed-text-v2:0. No network: S3 Vectors is a
 * recording fake.
 */
class EmbeddingWriteValidationSpec extends AnyFlatSpec with Matchers:

  private val TitanModel  = "amazon.titan-embed-text-v2:0"
  private val VoyageModel = "voyage-3-large"

  private val titanIndex = S3VectorsConfig(1024, DistanceMetric.Cosine, TitanModel)

  /** Fake S3 Vectors client that records every vector it is asked to write. */
  private class RecordingVectors
      extends S3VectorsStorageClient(
        "us-east-1",
        0,
        0L,
        StorageTestAws.NoNetwork,
        StorageTestAws.dummyCredentials
      ):
    val written: mutable.ArrayBuffer[VectorRecord] = mutable.ArrayBuffer.empty
    var calls: Int                                  = 0

    override def putVectors(
      vectorBucket: String,
      indexName: String,
      vectors: Vector[VectorRecord]
    ): PutVectorsResult =
      calls += 1
      written ++= vectors
      PutVectorsResult(vectors.size, 0, Vector.empty)

  private def meta(model: String, hash: String = "h1"): VectorMetadata =
    VectorMetadata(
      documentType = "researcher-profile",
      entityId = "researcher:orcid:0000-0001",
      windowStartYear = Some(2020),
      windowEndYear = Some(2025),
      topicId = None,
      institutionId = None,
      textS3Uri = "s3://bucket/contexts/researcher-profile/r1.json",
      textHash = hash,
      embeddingModel = model,
      runId = "run-001"
    )

  private def rec(key: String, dims: Int, model: String): VectorRecord =
    VectorRecord(key, Vector.fill(dims)(0.01f), meta(model))

  private def writerWith(fake: RecordingVectors, cfg: S3VectorsConfig = titanIndex) =
    new EmbeddingBatchWriter(
      s3Vectors = fake,
      s3 = null,
      vectorBucket = "test-bucket",
      ragBucket = "test-rag",
      indexConfig = cfg,
      maxRetries = 0
    )

  "EmbeddingBatchWriter" should "write a 1024-dim Titan v2 vector" in {
    val fake   = RecordingVectors()
    val result = writerWith(fake).writeBatch(Vector(rec("ok", 1024, TitanModel)), "researcher-profile-v1").get

    result.written shouldBe 1
    result.rejected shouldBe 0
    result.rejections shouldBe empty
    result.rejectedByReason shouldBe empty
    fake.written.map(_.key) shouldBe Seq("ok")
  }

  it should "reject a 256-dim vector with EMBEDDING_DIMENSION_MISMATCH and not write it" in {
    val fake   = RecordingVectors()
    val result = writerWith(fake).writeBatch(Vector(rec("short", 256, TitanModel)), "researcher-profile-v1").get

    result.written shouldBe 0
    result.rejected shouldBe 1
    result.rejections.map(r => (r.key, r.reasonCode)) shouldBe
      Vector("short" -> VectorRejectionReason.EmbeddingDimensionMismatch)
    result.rejectedByReason shouldBe Map("EMBEDDING_DIMENSION_MISMATCH" -> 1)
    result.errors.head should (include("EMBEDDING_DIMENSION_MISMATCH") and include("expected=1024") and
      include("actual=256"))
    fake.calls shouldBe 0
    fake.written shouldBe empty
  }

  it should "reject a Voyage-model vector with EMBEDDING_MODEL_MISMATCH and not write it" in {
    val fake   = RecordingVectors()
    val result = writerWith(fake).writeBatch(Vector(rec("voyage", 1024, VoyageModel)), "paper-abstract-v1").get

    result.written shouldBe 0
    result.rejected shouldBe 1
    result.rejections.map(_.reasonCode) shouldBe Vector("EMBEDDING_MODEL_MISMATCH")
    result.rejections.head.metadata.embeddingModel shouldBe VoyageModel
    result.errors.head should (include(TitanModel) and include(VoyageModel))
    fake.calls shouldBe 0
    fake.written shouldBe empty
  }

  it should "write only the valid records of a mixed batch and report per-reason counts" in {
    val fake = RecordingVectors()
    val batch = Vector(
      rec("ok-1", 1024, TitanModel),
      rec("short", 256, TitanModel),
      rec("voyage", 1024, VoyageModel),
      rec("ok-2", 1024, TitanModel),
      rec("empty", 0, TitanModel),
      rec("long", 1536, TitanModel),
      rec("unversioned", 1024, "amazon.titan-embed-text-v2")
    )
    val result = writerWith(fake).writeBatch(batch, "researcher-profile-v1").get

    fake.calls shouldBe 1
    fake.written.map(_.key) shouldBe Seq("ok-1", "ok-2")
    result.written shouldBe 2
    result.rejected shouldBe 5
    result.rejectedByReason shouldBe Map(
      "EMBEDDING_DIMENSION_MISMATCH" -> 2,
      "EMBEDDING_MODEL_MISMATCH"     -> 2,
      "EMPTY_EMBEDDING"              -> 1
    )
    result.rejections.map(_.key) shouldBe Vector("short", "voyage", "empty", "long", "unversioned")
    result.errors should have size 5
    (result.written + result.rejected) shouldBe batch.size
  }

  it should "reject an empty or unknown embedding model id" in {
    val fake = RecordingVectors()
    val batch = Vector(
      rec("blank", 1024, ""),
      rec("spaces", 1024, "   "),
      rec("unknown", 1024, "some.unknown-model"),
      rec("padded", 1024, s" $TitanModel ")
    )
    val result = writerWith(fake).writeBatch(batch, "researcher-profile-v1").get

    result.written shouldBe 0
    result.rejected shouldBe 4
    result.rejectedByReason shouldBe Map("EMBEDDING_MODEL_MISMATCH" -> 4)
    fake.calls shouldBe 0
  }

  it should "check dimension before model when both mismatch" in {
    EmbeddingBatchWriter
      .validate(rec("both", 256, VoyageModel), titanIndex)
      .left
      .map(_.reasonCode) shouldBe Left("EMBEDDING_DIMENSION_MISMATCH")
  }

  it should "take its index contract from AppConfig rather than hardcoded values" in {
    val cfg  = AppConfig.load().s3Vectors
    val fake = RecordingVectors()
    val result = writerWith(fake, cfg)
      .writeBatch(
        Vector(
          rec("cfg-ok", cfg.dimensions, cfg.embeddingModelId),
          rec("cfg-bad", cfg.dimensions + 1, cfg.embeddingModelId)
        ),
        "researcher-profile-v1"
      )
      .get
    result.written shouldBe 1
    result.rejectedByReason shouldBe Map("EMBEDDING_DIMENSION_MISMATCH" -> 1)
  }

  it should "follow a different configured contract (e.g. a separate index set) exactly" in {
    val otherIndex = S3VectorsConfig(512, DistanceMetric.Cosine, VoyageModel)
    val fake       = RecordingVectors()
    val result = writerWith(fake, otherIndex)
      .writeBatch(Vector(rec("titan", 1024, TitanModel), rec("voy", 512, VoyageModel)), "other-v1")
      .get
    fake.written.map(_.key) shouldBe Seq("voy")
    result.rejectedByReason shouldBe Map("EMBEDDING_DIMENSION_MISMATCH" -> 1)
  }

  // ---- Content-hash skip rule ----

  private val stubQueue = new QueueClient:
    def sendMessage(queueUrl: String, body: String): Try[String] = Success("m")
    def receiveMessages(queueUrl: String, maxMessages: Int): Try[List[(String, String)]] =
      Success(Nil)
    def deleteMessage(queueUrl: String, receiptHandle: String): Try[Unit] = Success(())

  "EmbeddingQueueManager.shouldSkipEmbedding" should
    "skip only when textHash and the configured index model both match" in {
      val manager = EmbeddingQueueManager(stubQueue, "q", mutable.Set.empty[String])

      manager.shouldSkipEmbedding("e1", "hash1", titanIndex).get shouldBe false
      manager.recordEmbedded("e1", "hash1", TitanModel).get
      manager.shouldSkipEmbedding("e1", "hash1", titanIndex).get shouldBe true
      manager.shouldSkipEmbedding("e1", "hash2", titanIndex).get shouldBe false
    }

  it should "not skip unchanged text previously embedded by a different model" in {
    val manager = EmbeddingQueueManager(stubQueue, "q", mutable.Set.empty[String])
    manager.recordEmbedded("e1", "hash1", VoyageModel).get

    manager.shouldSkipEmbedding("e1", "hash1", titanIndex).get shouldBe false
    manager.isDuplicate("e1", "hash1", VoyageModel).get shouldBe true
  }

  it should "use the same key as isDuplicate" in {
    val store   = mutable.Set.empty[String]
    val manager = EmbeddingQueueManager(stubQueue, "q", store)
    manager.recordEmbedded("e1", "h", TitanModel).get
    store shouldBe Set(EmbeddingQueueManager.sha256(s"e1|h|$TitanModel"))
    manager.isDuplicate("e1", "h", TitanModel).get shouldBe true
  }
