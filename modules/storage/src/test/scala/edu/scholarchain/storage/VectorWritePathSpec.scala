package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

import scala.collection.mutable

/**
 * STO-P13-03: S3 Vectors worker write path -- stable keys, compact metadata, metadata limits,
 * skip-unchanged and rejected-vector records. No network: S3 Vectors and S3 are fakes.
 */
class VectorWritePathSpec extends AnyFlatSpec with Matchers:

  private val Model = "amazon.titan-embed-text-v2:0"
  private val cfg   = S3VectorsConfig(4, DistanceMetric.Cosine, Model)

  private def meta(
    docType: String = "researcher-profile",
    entity: String = "researcher:orcid:0000-0001",
    hash: String = "h1",
    uri: String = "s3://rag/contexts/researcher-profile/r1.json",
    topic: Option[String] = None
  ) =
    VectorMetadata(docType, entity, Some(2015), Some(2024), topic, None, uri, hash, Model, "run-7")

  private def rec(m: VectorMetadata, key: Option[String] = None) =
    VectorRecord(key.getOrElse(VectorKeys.forMetadata(m)), Vector.fill(4)(0.5f), m)

  /** Fake S3 Vectors holding stored metadata by key. */
  private class FakeVectors(stored: Map[String, VectorMetadata], failGet: Boolean = false)
      extends S3VectorsStorageClient(
        "us-east-2",
        0,
        0L,
        StorageTestAws.NoNetwork,
        StorageTestAws.dummyCredentials
      ):
    val written: mutable.ArrayBuffer[VectorRecord] = mutable.ArrayBuffer.empty
    val gets: mutable.ArrayBuffer[Vector[String]]  = mutable.ArrayBuffer.empty

    override def putVectors(b: String, i: String, v: Vector[VectorRecord]): PutVectorsResult =
      written ++= v; PutVectorsResult(v.size, 0, Vector.empty)

    override def getVectors(
      b: String,
      i: String,
      keys: Vector[String],
      returnData: Boolean
    ): Vector[VectorRecord] =
      gets += keys
      if failGet then throw S3VectorsRequestException("GetVectors", 503, "down")
      keys.flatMap(k => stored.get(k).map(m => VectorRecord(k, Vector.empty, m)))

  private class FakeS3 extends S3StorageClient(null):
    val puts: mutable.ArrayBuffer[(String, String, String)] = mutable.ArrayBuffer.empty

    override def putObject(b: String, k: String, bytes: Array[Byte], ct: String): String =
      puts += ((b, k, String(bytes, "UTF-8"))); "etag"

  private def writer(v: FakeVectors, s3: S3StorageClient = null) =
    EmbeddingBatchWriter(v, s3, "vec", "rag", cfg, maxRetries = 0)

  // ---- stable keys ----

  "VectorKeys.stable" should "be deterministic and independent of text hash and model" in {
    val a = VectorKeys.forMetadata(meta(hash = "h1"))
    a shouldBe "researcher-profile|researcher:orcid:0000-0001|2015-2024"
    VectorKeys.forMetadata(meta(hash = "h2")) shouldBe a
    VectorKeys.stable("paper-abstract", "w1", None, None) shouldBe "paper-abstract|w1|all-all"
  }

  it should "hash keys that exceed the S3 Vectors key length" in {
    val k = VectorKeys.stable("paper-abstract", "x" * 2000, None, None)
    k should startWith("sha256:")
    k.length should be <= S3VectorsLimits.MaxKeyLength
    VectorKeys.stable("paper-abstract", "x" * 2000, None, None) shouldBe k
  }

  // ---- compact metadata ----

  "S3VectorsMetadataCodec" should "write researcherId / communityId by document type and round-trip" in {
    val r = S3VectorsMetadataCodec.toJson(meta())
    r.value.keySet shouldBe Set(
      "documentType",
      "researcherId",
      "windowStartYear",
      "windowEndYear",
      "textS3Uri",
      "textHash",
      "embeddingModel",
      "runId"
    )
    val c = S3VectorsMetadataCodec.toJson(meta("collaboration-community", "community:3"))
    c("communityId").str shouldBe "community:3"
    S3VectorsMetadataCodec
      .toJson(meta("paper-abstract", "work:1"))("entityId")
      .str shouldBe "work:1"
    for m <- Seq(meta(), meta("collaboration-community", "community:3"), meta("paper-abstract")) do
      S3VectorsMetadataCodec.fromJson(S3VectorsMetadataCodec.toJson(m)) shouldBe m
  }

  it should "still decode legacy metadata written with entityId" in {
    val legacy = S3VectorsMetadataCodec.toJson(meta("paper-abstract", "researcher:orcid:1"))
    legacy("documentType") = "researcher-profile"
    legacy.value.contains("entityId") shouldBe true
    S3VectorsMetadataCodec.fromJson(legacy).entityId shouldBe "researcher:orcid:1"
  }

  // ---- metadata limits ----

  "EmbeddingBatchWriter" should "reject filterable metadata over 2 KB but allow a long non-filterable URI" in {
    val v        = FakeVectors(Map.empty)
    val bigTopic = meta(entity = "r-big", topic = Some("t" * 2100))
    val longUri  = meta(entity = "r-uri", uri = "s3://rag/" + "u" * 3000)
    val result   = writer(v).writeBatch(Vector(rec(bigTopic), rec(longUri)), "idx").get
    result.written shouldBe 1
    v.written.map(_.metadata.entityId).toList shouldBe List("r-uri")
    result.rejections.map(_.reasonCode) shouldBe
      Vector(VectorRejectionReason.FilterableMetadataTooLarge)
    result.rejectedByReason shouldBe Map(VectorRejectionReason.FilterableMetadataTooLarge -> 1)
  }

  it should "reject metadata over the 40 KB total limit" in {
    val huge = meta(uri = "s3://rag/" + "u" * (41 * 1024))
    EmbeddingBatchWriter.validate(rec(huge), cfg).left.toOption.get.reasonCode shouldBe
      VectorRejectionReason.MetadataTooLarge
  }

  it should "treat every key as filterable when no non-filterable keys are configured" in {
    val longUri = meta(uri = "s3://rag/" + "u" * 3000)
    EmbeddingBatchWriter
      .validate(rec(longUri), cfg, Set.empty)
      .left
      .toOption
      .get
      .reasonCode shouldBe
      VectorRejectionReason.FilterableMetadataTooLarge
  }

  it should "reject an empty or over-long vector key" in {
    EmbeddingBatchWriter.validate(rec(meta(), Some("")), cfg).left.toOption.get.reasonCode shouldBe
      VectorRejectionReason.InvalidVectorKey
    EmbeddingBatchWriter
      .validate(rec(meta(), Some("k" * 1025)), cfg)
      .left
      .toOption
      .get
      .reasonCode shouldBe VectorRejectionReason.InvalidVectorKey
  }

  it should "accept the largest metadata shape the codec produces (10 keys)" in {
    val full = meta(topic = Some("topic:1")).copy(institutionId = Some("inst:1"))
    S3VectorsMetadataCodec.toJson(full).value.size shouldBe S3VectorsLimits.MaxMetadataKeys
    EmbeddingBatchWriter.validate(rec(full), cfg).isRight shouldBe true
  }

  // ---- skip unchanged ----

  "EmbeddingBatchWriter.writeChanged" should "skip records whose stored textHash and model match" in {
    val same    = meta(entity = "r-same", hash = "h1")
    val changed = meta(entity = "r-changed", hash = "h2")
    val newOne  = meta(entity = "r-new")
    val stored = Map(
      VectorKeys.forMetadata(same)    -> same,
      VectorKeys.forMetadata(changed) -> changed.copy(textHash = "h-old")
    )
    val v      = FakeVectors(stored)
    val result = writer(v).writeChanged(Vector(rec(same), rec(changed), rec(newOne)), "idx").get
    result.skipped shouldBe Vector(VectorKeys.forMetadata(same))
    result.written shouldBe 2
    v.written.map(_.metadata.entityId).toSet shouldBe Set("r-changed", "r-new")
  }

  it should "re-embed when the stored vector came from a different model" in {
    val m = meta(entity = "r1")
    val v = FakeVectors(Map(VectorKeys.forMetadata(m) -> m.copy(embeddingModel = "voyage-3-large")))
    writer(v).unchangedKeys("idx", Vector((VectorKeys.forMetadata(m), "h1", Model))) shouldBe empty
  }

  it should "fail open (write everything) when GetVectors fails" in {
    val m      = meta(entity = "r1")
    val v      = FakeVectors(Map(VectorKeys.forMetadata(m) -> m), failGet = true)
    val result = writer(v).writeChanged(Vector(rec(m)), "idx").get
    result.skipped shouldBe empty
    result.written shouldBe 1
  }

  it should "not call GetVectors for an empty batch" in {
    val v = FakeVectors(Map.empty)
    writer(v).unchangedKeys("idx", Vector.empty) shouldBe empty
    v.gets shouldBe empty
  }

  // ---- rejected-vector record ----

  "EmbeddingBatchWriter.storeRejections" should "write one JSON line per rejection under the run" in {
    val v      = FakeVectors(Map.empty)
    val s3     = FakeS3()
    val w      = writer(v, s3)
    val bad    = VectorRecord("k-empty", Vector.empty, meta())
    val result = w.writeBatch(Vector(bad), "researcher-profile-v1").get
    w.storeRejections("run-7", "researcher-profile-v1", result.rejections).get shouldBe
      Some("s3://rag/rejected-vectors/run-7/researcher-profile-v1.jsonl")
    val (bucket, key, body) = s3.puts.head
    bucket shouldBe "rag"
    key shouldBe "rejected-vectors/run-7/researcher-profile-v1.jsonl"
    val lines = body.split("\n").toVector
    lines should have size 1
    val parsed = read[RejectedVector](lines.head)
    parsed.key shouldBe "k-empty"
    parsed.reasonCode shouldBe VectorRejectionReason.EmptyEmbedding
    parsed.metadata.runId shouldBe "run-7"
  }

  it should "write nothing when there are no rejections and sanitise path segments" in {
    val s3 = FakeS3()
    writer(FakeVectors(Map.empty), s3).storeRejections("r", "i", Vector.empty).get shouldBe None
    s3.puts shouldBe empty
    EmbeddingBatchWriter.rejectionKey("../run 1", "idx/x") shouldBe
      "rejected-vectors/__run_1/idx_x.jsonl"
  }
