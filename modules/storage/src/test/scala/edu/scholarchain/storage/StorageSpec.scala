package edu.scholarchain.storage

import edu.scholarchain.core.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import upickle.default.*

class StorageSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val testProvenance = Provenance(
    sourceDataset = "test-dataset",
    sourceRecordId = "rec-001",
    pipelineRunId = "run-001",
    schemaVersion = "1.0.0",
    timestamp = "2026-01-01T00:00:00Z"
  )

  // ---- 1. RunLedgerEntry serialization round-trip ----

  "RunLedgerEntry" should "serialize and deserialize via upickle" in {
    val entry = RunLedgerEntry(
      runId = "run-001",
      status = "COMPLETED",
      startTime = "2026-01-01T00:00:00Z",
      endTime = Some("2026-01-01T01:00:00Z"),
      config = """{"key":"value"}""",
      outputPaths = Vector("s3://bucket/path1", "s3://bucket/path2"),
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[RunLedgerEntry](json)
    restored shouldBe entry
  }

  it should "handle None endTime" in {
    val entry = RunLedgerEntry(
      runId = "run-002",
      status = "RUNNING",
      startTime = "2026-01-01T00:00:00Z",
      endTime = None,
      config = "{}",
      outputPaths = Vector.empty,
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[RunLedgerEntry](json)
    restored.endTime shouldBe None
  }

  // ---- 2. EntityLinkCacheEntry serialization round-trip ----

  "EntityLinkCacheEntry" should "serialize and deserialize via upickle" in {
    val entry = EntityLinkCacheEntry(
      sourceId = "src-001",
      targetId = "tgt-001",
      decision = "Accepted",
      confidence = 0.95,
      ttl = 1700000000L,
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[EntityLinkCacheEntry](json)
    restored shouldBe entry
  }

  // ---- 3. QuerySessionEntry serialization round-trip ----

  "QuerySessionEntry" should "serialize and deserialize via upickle" in {
    val entry = QuerySessionEntry(
      queryId = "q-001",
      question = "Who are the top collaborators?",
      status = "COMPLETED",
      createdAt = "2026-01-01T00:00:00Z",
      updatedAt = "2026-01-01T00:05:00Z",
      resultRef = Some("s3://bucket/results/q-001.json"),
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[QuerySessionEntry](json)
    restored shouldBe entry
  }

  it should "handle None resultRef" in {
    val entry = QuerySessionEntry(
      queryId = "q-002",
      question = "Query?",
      status = "PENDING",
      createdAt = "2026-01-01T00:00:00Z",
      updatedAt = "2026-01-01T00:00:00Z",
      resultRef = None,
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[QuerySessionEntry](json)
    restored.resultRef shouldBe None
  }

  // ---- 4. EvidenceBundleEntry serialization round-trip ----

  "EvidenceBundleEntry" should "serialize and deserialize via upickle" in {
    val entry = EvidenceBundleEntry(
      bundleId = "bundle-001",
      queryId = "q-001",
      itemCount = 5,
      totalScore = 3.75,
      s3Uri = "s3://bucket/bundles/bundle-001.json",
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[EvidenceBundleEntry](json)
    restored shouldBe entry
  }

  // ---- 5. ArtifactRegistryEntry serialization round-trip ----

  "ArtifactRegistryEntry" should "serialize and deserialize via upickle" in {
    val entry = ArtifactRegistryEntry(
      artifactId = "artifact-001",
      artifactType = "graph-snapshot",
      version = "1.0.0",
      s3Uri = "s3://bucket/artifacts/graph-v1.json",
      checksum = "abc123def456",
      provenance = testProvenance
    )
    val json     = write(entry)
    val restored = read[ArtifactRegistryEntry](json)
    restored shouldBe entry
  }

  // ---- 6. VectorMetadata serialization round-trip ----

  "VectorMetadata" should "serialize and deserialize via upickle" in {
    val meta = VectorMetadata(
      documentType = "researcher-profile",
      entityId = "researcher:orcid:0000-0001",
      windowStartYear = Some(2020),
      windowEndYear = Some(2025),
      topicId = Some("topic-ml"),
      institutionId = Some("inst-mit"),
      textS3Uri = "s3://bucket/text/chunk-001.txt",
      textHash = "sha256hash",
      embeddingModel = "amazon.titan-embed-text-v2",
      runId = "run-001"
    )
    val json     = write(meta)
    val restored = read[VectorMetadata](json)
    restored shouldBe meta
  }

  it should "handle None optional fields" in {
    val meta = VectorMetadata(
      documentType = "paper-abstract",
      entityId = "work:openalex:W123",
      windowStartYear = None,
      windowEndYear = None,
      topicId = None,
      institutionId = None,
      textS3Uri = "s3://bucket/text/abstract-001.txt",
      textHash = "hash123",
      embeddingModel = "amazon.titan-embed-text-v2",
      runId = "run-002"
    )
    val json     = write(meta)
    val restored = read[VectorMetadata](json)
    restored shouldBe meta
  }

  // ---- 7. VectorRecord serialization round-trip ----

  "VectorRecord" should "serialize and deserialize via upickle" in {
    val record = VectorRecord(
      key = "vec-001",
      data = Vector(0.1f, 0.2f, 0.3f),
      metadata = VectorMetadata(
        documentType = "researcher-profile",
        entityId = "researcher:orcid:0000-0001",
        windowStartYear = Some(2020),
        windowEndYear = Some(2025),
        topicId = None,
        institutionId = None,
        textS3Uri = "s3://bucket/text/chunk-001.txt",
        textHash = "hash",
        embeddingModel = "amazon.titan-embed-text-v2",
        runId = "run-001"
      )
    )
    val json     = write(record)
    val restored = read[VectorRecord](json)
    restored.key shouldBe record.key
    restored.metadata shouldBe record.metadata
    // Float precision: compare with tolerance
    restored.data.zip(record.data).foreach { case (a, b) =>
      a shouldBe (b +- 0.001f)
    }
  }

  // ---- 8. VectorResult serialization round-trip ----

  "VectorResult" should "serialize and deserialize via upickle" in {
    val result = VectorResult(
      key = "vec-001",
      score = 0.95,
      metadata = VectorMetadata(
        documentType = "paper-abstract",
        entityId = "work:openalex:W456",
        windowStartYear = None,
        windowEndYear = None,
        topicId = Some("topic-nlp"),
        institutionId = None,
        textS3Uri = "s3://bucket/text/abstract.txt",
        textHash = "hash456",
        embeddingModel = "amazon.titan-embed-text-v2",
        runId = "run-003"
      )
    )
    val json     = write(result)
    val restored = read[VectorResult](json)
    restored shouldBe result
  }

  // ---- 9. All 5 S3 Vectors index names present and valid ----

  "S3VectorsIndexes" should "contain exactly 5 index names" in {
    S3VectorsIndexes.all.size shouldBe 5
  }

  it should "contain all expected index names" in {
    S3VectorsIndexes.all should contain("researcher-profile-v1")
    S3VectorsIndexes.all should contain("collaboration-community-v1")
    S3VectorsIndexes.all should contain("paper-abstract-v1")
    S3VectorsIndexes.all should contain("collaboration-path-v1")
    S3VectorsIndexes.all should contain("topic-institution-summary-v1")
  }

  it should "have index names matching the v1 naming convention" in {
    S3VectorsIndexes.all.foreach { name =>
      name should endWith("-v1")
      name should fullyMatch regex """[a-z][a-z0-9-]+-v1"""
    }
  }

  // ---- 10. MatchDecision confidence range validation ----

  "MatchDecision" should "reject confidence outside [0, 1]" in {
    an[IllegalArgumentException] should be thrownBy {
      MatchDecision(
        sourceId = "src",
        targetId = "tgt",
        features = Map("f1" -> 0.5),
        decision = MatchOutcome.Accepted,
        confidence = 1.5,
        reasonCodes = Vector("high-name-sim"),
        policyVersion = "v1",
        runId = "run-001"
      )
    }
  }

  it should "accept confidence at boundary values" in {
    noException should be thrownBy {
      MatchDecision(
        sourceId = "src",
        targetId = "tgt",
        features = Map.empty,
        decision = MatchOutcome.Accepted,
        confidence = 0.0,
        reasonCodes = Vector.empty,
        policyVersion = "v1",
        runId = "run-001"
      )
    }
    noException should be thrownBy {
      MatchDecision(
        sourceId = "src",
        targetId = "tgt",
        features = Map.empty,
        decision = MatchOutcome.Accepted,
        confidence = 1.0,
        reasonCodes = Vector.empty,
        policyVersion = "v1",
        runId = "run-001"
      )
    }
  }

  // ---- 11. CoauthorEdge pair ordering enforcement ----

  "CoauthorEdge" should "enforce researcherA < researcherB ordering" in {
    val edge = CoauthorEdge(
      researcherA = "researcher:z",
      researcherB = "researcher:a",
      jointPublicationCount = 3,
      firstCollaborationYear = 2020,
      lastCollaborationYear = 2023,
      representativeWorkIds = Vector("w1"),
      provenance = testProvenance
    )
    edge.researcherA should be < edge.researcherB
  }

  it should "reject self-edges" in {
    an[IllegalArgumentException] should be thrownBy {
      CoauthorEdge(
        researcherA = "researcher:same",
        researcherB = "researcher:same",
        jointPublicationCount = 1,
        firstCollaborationYear = 2020,
        lastCollaborationYear = 2020,
        representativeWorkIds = Vector.empty,
        provenance = testProvenance
      )
    }
  }

  // ---- 12. ResearcherMetrics windowStartYear <= windowEndYear ----

  "ResearcherMetrics" should "reject windowStartYear > windowEndYear" in {
    an[IllegalArgumentException] should be thrownBy {
      ResearcherMetrics(
        researcherId = "researcher:test",
        publicationCount = 10,
        distinctCoauthorCount = 5,
        totalCoauthorOccurrences = 20,
        averageCoauthorsPerPaper = 2.0,
        maximumCoauthorsOnOnePaper = 5,
        weightedDegree = 3.5,
        componentId = None,
        communityId = None,
        bridgeScore = 0.5,
        coreness = 2,
        windowStartYear = 2025,
        windowEndYear = 2020,
        provenance = testProvenance
      )
    }
  }

  it should "accept equal windowStartYear and windowEndYear" in {
    noException should be thrownBy {
      ResearcherMetrics(
        researcherId = "researcher:test",
        publicationCount = 1,
        distinctCoauthorCount = 0,
        totalCoauthorOccurrences = 0,
        averageCoauthorsPerPaper = 0.0,
        maximumCoauthorsOnOnePaper = 0,
        weightedDegree = 0.0,
        componentId = None,
        communityId = None,
        bridgeScore = 0.0,
        coreness = 0,
        windowStartYear = 2023,
        windowEndYear = 2023,
        provenance = testProvenance
      )
    }
  }

  // ---- 13. GraphEdge provenance completeness ----

  "GraphEdge" should "require non-empty provenance fields" in {
    val edge = GraphEdge(
      edgeId = "edge-001",
      sourceNodeId = "node-a",
      targetNodeId = "node-b",
      edgeType = "COAUTHOR",
      properties = Map("weight" -> "5"),
      provenance = testProvenance
    )
    edge.provenance.sourceDataset should not be empty
    edge.provenance.pipelineRunId should not be empty
    edge.provenance.schemaVersion should not be empty
  }

  it should "reject empty provenance sourceDataset" in {
    an[IllegalArgumentException] should be thrownBy {
      GraphEdge(
        edgeId = "edge-002",
        sourceNodeId = "node-a",
        targetNodeId = "node-b",
        edgeType = "COAUTHOR",
        properties = Map.empty,
        provenance = Provenance(
          sourceDataset = "",
          sourceRecordId = "rec",
          pipelineRunId = "run",
          schemaVersion = "1.0",
          timestamp = "2026-01-01T00:00:00Z"
        )
      )
    }
  }

  // ---- 14. EvidenceItem score range [0, 1] ----

  "EvidenceItem" should "reject score outside [0, 1]" in {
    an[IllegalArgumentException] should be thrownBy {
      EvidenceItem(
        evidenceId = "ev-001",
        evidenceType = EvidenceType.TextChunk,
        sourceEntityId = "entity-001",
        content = "Some evidence text",
        score = 1.5,
        provenance = testProvenance
      )
    }
    an[IllegalArgumentException] should be thrownBy {
      EvidenceItem(
        evidenceId = "ev-002",
        evidenceType = EvidenceType.TextChunk,
        sourceEntityId = "entity-002",
        content = "Some evidence text",
        score = -0.1,
        provenance = testProvenance
      )
    }
  }

  it should "accept score at boundaries" in {
    noException should be thrownBy {
      EvidenceItem(
        evidenceId = "ev-003",
        evidenceType = EvidenceType.GraphPath,
        sourceEntityId = "entity-003",
        content = "content",
        score = 0.0,
        provenance = testProvenance
      )
    }
    noException should be thrownBy {
      EvidenceItem(
        evidenceId = "ev-004",
        evidenceType = EvidenceType.MetricRecord,
        sourceEntityId = "entity-004",
        content = "content",
        score = 1.0,
        provenance = testProvenance
      )
    }
  }

  // ---- 15. Neptune response parsing ----

  "NeptuneResponseParser" should "extract results from a Cypher JSON response" in {
    val response = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj("rid" -> ujson.Str("researcher:orcid:0000-0001")),
        ujson.Obj("rid" -> ujson.Str("researcher:orcid:0000-0002"))
      )
    )
    val results = NeptuneResponseParser.extractResults(response)
    results.arr.size shouldBe 2
  }

  it should "return empty array for missing results key" in {
    val response = ujson.Obj("status" -> ujson.Str("ok"))
    val results  = NeptuneResponseParser.extractResults(response)
    results.arr.size shouldBe 0
  }

  it should "parse string list from query results" in {
    val response = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj("rid" -> ujson.Str("r1")),
        ujson.Obj("rid" -> ujson.Str("r2")),
        ujson.Obj("rid" -> ujson.Str("r3"))
      )
    )
    val ids = NeptuneResponseParser.parseStringList(response, "rid")
    ids shouldBe Vector("r1", "r2", "r3")
  }

  it should "parse a path from query results" in {
    val response = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "path" -> ujson.Arr(
            ujson.Str("r1"),
            ujson.Str("r2"),
            ujson.Str("r3")
          )
        )
      )
    )
    val path = NeptuneResponseParser.parsePath(response)
    path.value shouldBe Vector("r1", "r2", "r3")
  }

  it should "return None for empty path results" in {
    val response = ujson.Obj("results" -> ujson.Arr())
    NeptuneResponseParser.parsePath(response) shouldBe None
  }

  // ---- 16. DynamoDB conditional write helper ----

  "DynamoDbStorageClient" should "produce correct conditional expression for partition key" in {
    val expr = DynamoDbStorageClient.conditionalPutExpression("runId")
    expr shouldBe "attribute_not_exists(runId)"
  }

  it should "produce correct conditional expression for other keys" in {
    DynamoDbStorageClient.conditionalPutExpression("queryId") shouldBe
      "attribute_not_exists(queryId)"
    DynamoDbStorageClient.conditionalPutExpression("artifactId") shouldBe
      "attribute_not_exists(artifactId)"
  }

  // ---- 17. S3 content hash computation ----

  "S3StorageClient" should "compute correct SHA-256 hash" in {
    val bytes = "hello world".getBytes("UTF-8")
    val hash  = S3StorageClient.sha256Hex(bytes)
    // Known SHA-256 of "hello world"
    hash shouldBe "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
  }

  it should "produce different hashes for different content" in {
    val hash1 = S3StorageClient.sha256Hex("content-a".getBytes("UTF-8"))
    val hash2 = S3StorageClient.sha256Hex("content-b".getBytes("UTF-8"))
    hash1 should not equal hash2
  }

  it should "produce consistent hash for same content" in {
    val bytes = "deterministic".getBytes("UTF-8")
    val hash1 = S3StorageClient.sha256Hex(bytes)
    val hash2 = S3StorageClient.sha256Hex(bytes)
    hash1 shouldBe hash2
  }

  // ---- 18. StorageModuleConfig loading ----

  "StorageModuleConfig" should "load from reference.conf" in {
    val config = StorageModuleConfig.load()
    config.s3.rawBucket shouldBe "scholarchain-raw"
    config.s3.curatedBucket shouldBe "scholarchain-curated"
    config.s3.graphBucket shouldBe "scholarchain-graph"
    config.s3.ragBucket shouldBe "scholarchain-rag"
    config.neptune.endpoint shouldBe "localhost"
    config.neptune.port shouldBe 8182
    config.dynamoDb.tablePrefix shouldBe "scholarchain"
    config.s3Vectors.vectorBucket shouldBe "scholarchain-vectors"
    config.s3Vectors.batchSize shouldBe 100
    config.s3Vectors.maxRetries shouldBe 3
  }

  // ---- 19. PutVectorsResult aggregation ----

  "PutVectorsResult" should "represent batch operation outcomes" in {
    val result = PutVectorsResult(
      succeeded = 95,
      failed = 5,
      errors = Vector("key=k1 status=500", "key=k2 status=503")
    )
    result.succeeded shouldBe 95
    result.failed shouldBe 5
    result.errors.size shouldBe 2
  }
