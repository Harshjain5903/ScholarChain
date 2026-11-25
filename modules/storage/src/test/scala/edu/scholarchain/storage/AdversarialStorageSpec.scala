package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

class AdversarialStorageSpec extends AnyFlatSpec with Matchers:

  private val testProvenance = Provenance(
    sourceDataset = "test-dataset",
    sourceRecordId = "rec-001",
    pipelineRunId = "run-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-01-01T00:00:00Z"
  )

  // --- 1. Missing provenance on a record ---

  "Provenance" should "reject empty sourceDataset" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance(
        sourceDataset = "",
        sourceRecordId = "rec-1",
        pipelineRunId = "run-1",
        schemaVersion = "1.0",
        timestamp = "2026-01-01T00:00:00Z"
      )
    }
  }

  it should "reject empty pipelineRunId" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance(
        sourceDataset = "test",
        sourceRecordId = "rec-1",
        pipelineRunId = "",
        schemaVersion = "1.0",
        timestamp = "2026-01-01T00:00:00Z"
      )
    }
  }

  it should "reject empty schemaVersion" in {
    an[IllegalArgumentException] should be thrownBy {
      Provenance(
        sourceDataset = "test",
        sourceRecordId = "rec-1",
        pipelineRunId = "run-1",
        schemaVersion = "",
        timestamp = "2026-01-01T00:00:00Z"
      )
    }
  }

  // --- 2. Duplicate vector keys handled idempotently ---

  "EmbeddingQueueManager" should "detect duplicate vector keys by entityId-textHash-model triple" in {
    val dedupStore = scala.collection.mutable.Set.empty[String]
    val stubQueue = new QueueClient:
      def sendMessage(queueUrl: String, body: String): scala.util.Try[String] =
        scala.util.Success("msg-1")
      def receiveMessages(
        queueUrl: String,
        maxMessages: Int
      ): scala.util.Try[List[ReceivedMessage]] = scala.util.Success(Nil)
      def deleteMessage(queueUrl: String, receiptHandle: String): scala.util.Try[Unit] =
        scala.util.Success(())

    val manager =
      EmbeddingQueueManager(stubQueue, "http://queue", dedupStore)

    // First submission is not a duplicate
    manager.isDuplicate("entity1", "hash1", "v1").get shouldBe false

    // Add the dedup key
    val key = EmbeddingQueueManager.sha256("entity1|hash1|v1")
    dedupStore.add(key)

    // Second submission with same triple is a duplicate
    manager.isDuplicate("entity1", "hash1", "v1").get shouldBe true

    // Different hash is not a duplicate
    manager.isDuplicate("entity1", "hash2", "v1").get shouldBe false
  }

  // --- 3. Malformed Neptune query response ---

  "NeptuneResponseParser" should "return empty results for a response with no results key" in {
    val malformedResponse = ujson.Obj("error" -> ujson.Str("internal error"))
    val results           = NeptuneResponseParser.extractResults(malformedResponse)
    results.arr.size shouldBe 0
  }

  it should "return None for researcher lookup on empty results" in {
    val emptyResponse = ujson.Obj("results" -> ujson.Arr())
    val researcher    = NeptuneResponseParser.parseOptionalResearcher(emptyResponse)
    researcher shouldBe None
  }

  it should "return None for work lookup on malformed response" in {
    val malformedResponse = ujson.Obj("unexpected" -> ujson.Str("data"))
    val work              = NeptuneResponseParser.parseOptionalWork(malformedResponse)
    work shouldBe None
  }

  it should "return empty vector for coauthor edges on missing results" in {
    val noResults = ujson.Obj("results" -> ujson.Arr())
    val edges     = NeptuneResponseParser.parseCoauthorEdges(noResults)
    edges shouldBe empty
  }

  // --- 4. DynamoDB conditional write conflict simulation ---

  "DynamoDbStorageClient" should "generate conditional expression that prevents duplicate writes" in {
    val expr = DynamoDbStorageClient.conditionalPutExpression("queryId")
    expr shouldBe "attribute_not_exists(queryId)"
    // This expression causes ConditionalCheckFailedException on duplicate key
    // which the application should handle gracefully
  }

  // --- 5. EmbeddingBatchWriter rejects empty vectors ---

  "EmbeddingBatchWriter" should "reject vector records with zero-length embedding data" in {
    val emptyMeta = VectorMetadata(
      documentType = "researcher-profile",
      entityId = "researcher:test:empty",
      windowStartYear = None,
      windowEndYear = None,
      topicId = None,
      institutionId = None,
      textS3Uri = "s3://bucket/text.txt",
      textHash = "h1",
      embeddingModel = "amazon.titan-embed-text-v2",
      runId = "run-001"
    )
    val emptyRecord = VectorRecord("empty-vec", Vector.empty[Float], emptyMeta)

    val stubVectors = new S3VectorsStorageClient(
      "us-east-1",
      0,
      0L,
      StorageTestAws.NoNetwork,
      StorageTestAws.dummyCredentials
    ) {
      override def putVectors(
        vectorBucket: String,
        indexName: String,
        vectors: Vector[VectorRecord]
      ): PutVectorsResult =
        PutVectorsResult(vectors.size, 0, Vector.empty)
    }

    val writer = new EmbeddingBatchWriter(
      s3Vectors = stubVectors,
      s3 = null,
      vectorBucket = "test-bucket",
      ragBucket = "test-rag",
      indexConfig = S3VectorsConfig(1024, DistanceMetric.Cosine, "amazon.titan-embed-text-v2:0"),
      maxRetries = 0
    )
    val result = writer.writeBatch(Vector(emptyRecord), "researcher-profile-v1")
    result.isSuccess shouldBe true
    val bwr = result.get
    bwr.rejected shouldBe 1
    bwr.written shouldBe 0
    bwr.errors should have size 1
    bwr.errors.head should include("Empty embedding")
  }
