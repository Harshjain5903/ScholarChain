# Phase 8 Prompt

Use the storage-engineer and rag-engineer in separate delegated work orders to build the embedding and S3 Vectors ingestion path.

Storage-engineer: EmbeddingQueueManager, EmbeddingBatchWriter, bounded SQS-style queue, batch vector writes, dedup by SHA-256.

Rag-engineer: EmbeddingTemplates, ChunkingPolicy, EmbeddingModelConfig, SemanticContextFormat, retrieval smoke tests.

Run sbt check. All tests must pass.


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order (2026-11-20T00:40Z)

Session `7aafb09f-5927-4b74-9c74-70dface1b0d2`, message `708a66d6-70bd-4239-a019-dc9c1b21a762`, sent 2026-11-20T00:40:28.994Z.

````text
Use the storage-engineer and rag-engineer agents (in separate delegated work orders) to implement Phase 8: embeddings and S3 Vectors population.

storage-engineer work order:
Owned artifacts: modules/storage/**
Read these first: StorageConfig.scala, S3Client.scala, S3VectorsClient.scala, DynamoDbClient.scala, StorageSpec.scala, all core domain files, build.sbt

Create modules/storage/src/main/scala/edu/scholarchain/storage/EmbeddingQueueManager.scala:
- case class EmbeddingJob with fields: jobId, entityId, documentType, textS3Uri, textHash, embeddingModel, windowStartYear, windowEndYear, runId, retryCount
- documentType values: researcher-profile, collaboration-community, paper-abstract, collaboration-path, topic-institution-summary
- class EmbeddingQueueManager(sqsClient, dynamo, config) with methods:
  - enqueue(job): Try[String]
  - dequeueBatch(maxMessages): Try[List[EmbeddingJob]]
  - markComplete(jobId): Try[Unit]
  - markFailed(jobId, reason): Try[Unit]
  - isDuplicate(entityId, textHash, modelVersion): Try[Boolean]
  - private sha256(text): String using MessageDigest SHA-256
- Use upickle.default.ReadWriter on EmbeddingJob
- All methods return Try, never throw

Create modules/storage/src/main/scala/edu/scholarchain/storage/EmbeddingBatchWriter.scala:
- case class VectorRecord(vectorKey, embedding: Vector[Float], metadata: VectorMetadata)
- case class VectorMetadata(entityId, documentType, windowStartYear, windowEndYear, topicId: Option[String], institutionId: Option[String], textS3Uri, textHash, embeddingModel, runId) with ReadWriter
- case class BatchWriteResult(written, rejected, retried, errors: List[String])
- class EmbeddingBatchWriter(s3Vectors, s3, config) with:
  - IndexNames map: researcher-profile -> researcher-profile-v1, collaboration-community -> collaboration-community-v1, paper-abstract -> paper-abstract-v1, collaboration-path -> collaboration-path-v1, topic-institution-summary -> topic-institution-summary-v1
  - writeBatch(records, indexName): Try[BatchWriteResult]
  - storeTextInS3(entityId, docType, text): Try[String] returns S3 URI
  - private retryWithBackoff[A](maxRetries)(f): Try[A]

Add to StorageSpec.scala (read existing file first, append in correct location):
- EmbeddingQueueManager tests: sha256 is deterministic, isDuplicate detects by entityId+textHash+model, EmbeddingJob serializes round-trip via uPickle
- EmbeddingBatchWriter tests: documentType maps to correct index name, VectorMetadata serializes round-trip, empty embedding is rejected, BatchWriteResult counts are correct

---

rag-engineer work order:
Owned artifacts: modules/rag/**, prompts/**
Read these first: all core domain files, build.sbt, existing rag module files if any

Create modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingTemplates.scala:
- case class PathHop(researcherId, displayName, jointPublications, years: List[Int]) with ReadWriter
- object EmbeddingTemplates with methods that take structured data and return deterministic plain-text strings:
  - researcherProfile(researcherId, displayName, topics, institutions, windowStartYear, windowEndYear, publicationCount, distinctCoauthorCount, representativeTitles): String
  - communityProfile(communityId, dominantTopics, memberCount, windowStartYear, windowEndYear, representativeTitles): String
  - collaborationPath(startResearcherId, endResearcherId, hops: List[PathHop], windowStartYear, windowEndYear): String
  - paperSummary(workId, title, year, venue, abstractText, topics): String
  - topicInstitutionSummary(entityId, entityType, name, description, relatedTopics, windowStartYear, windowEndYear): String
- All methods use string interpolation only, no invented metrics, deterministic output

Create modules/rag/src/main/scala/edu/scholarchain/rag/ChunkingPolicy.scala:
- case class ChunkingConfig(maxTokens, overlapTokens, strategy: ChunkingStrategy)
- enum ChunkingStrategy: SentenceBoundary, ParagraphBoundary, FixedWindow
- object ChunkingPolicy with:
  - DefaultAbstractPolicy: ChunkingConfig = ChunkingConfig(512, 64, SentenceBoundary)
  - DefaultFullTextPolicy: ChunkingConfig = ChunkingConfig(256, 32, ParagraphBoundary)
  - estimateTokens(text): Int = (text.split("\\s+").length * 1.3).toInt
  - chunk(text, config): List[String] — implement real splitting: sentence boundary splits on ". ", paragraph on "\n\n", fixed window on token count

Create modules/rag/src/main/scala/edu/scholarchain/rag/EmbeddingModelConfig.scala:
- case class EmbeddingModelConfig(modelId, modelVersion, dimensions, maxInputTokens, provider)
- object EmbeddingModelConfig:
  - fromEnv(): reads EMBEDDING_MODEL_ID env var, defaults to "amazon.titan-embed-text-v2:0", pattern matches known models for dimensions/version
  - val Default = fromEnv()

Create modules/rag/src/main/scala/edu/scholarchain/rag/SemanticContextFormat.scala:
- case class SemanticContext(contextId, entityId, documentType, text, textS3Uri, vectorScore, embeddingModel, windowStartYear, windowEndYear, provenance: Provenance) with ReadWriter
- case class SemanticContextBundle(queryId, researcherContexts, communityContexts, pathContexts, paperContexts, topicContexts, totalTokenEstimate, truncated) with ReadWriter
- object SemanticContextFormat:
  - trimToTokenBudget(bundle, maxTokens): SemanticContextBundle — sort each list by descending vectorScore, include items until token budget reached
  - rankByRelevance(contexts): List[SemanticContext] = contexts.sortBy(-_.vectorScore)

Create modules/rag/src/test/scala/edu/scholarchain/rag/RetrievalSmokeTest.scala:
- ScalaTest AnyFlatSpec with Matchers
- EmbeddingTemplates: researcherProfile produces non-empty string, includes researcherId; communityProfile includes communityId; collaborationPath mentions start and end IDs; paperSummary includes title; topicInstitutionSummary differs between topic and institution entityType
- ChunkingPolicy: estimateTokens > 0 for non-empty text; chunk returns single item for short text; chunk splits on sentence boundary correctly
- EmbeddingModelConfig: fromEnv returns dimensions > 0, modelId non-empty
- SemanticContextFormat: rankByRelevance sorts descending by vectorScore; trimToTokenBudget result totalTokenEstimate <= maxTokens
- SemanticContext and SemanticContextBundle serialize/deserialize via uPickle round-trip

Run sbt check after all files are written. All tests must pass.

Required report format:
STATUS: COMPLETE | BLOCKED-ON | FAILED
OWNER: storage-engineer / rag-engineer
FILES CREATED:
FILES MODIFIED:
COMMANDS RUN:
TEST RESULTS:
ASSUMPTIONS:
RISKS:
````
