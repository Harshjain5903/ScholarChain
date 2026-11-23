package edu.scholarchain.rag

import edu.scholarchain.core.*
import scala.util.control.NonFatal

/**
 * Retrieves and assembles semantic context from vector indexes and S3 text.
 *
 * The query text is embedded through `embedder` with the configured query model. Before any vector
 * index is queried, the agent checks that the query model matches the index model and that the
 * vector has the index dimension. On any mismatch or embedding failure it queries nothing and
 * returns an empty bundle carrying a typed `SemanticRetrievalFailure`; it never substitutes a
 * placeholder vector.
 */
class SemanticContextAgent(
  vectors: VectorStore,
  text: TextStore,
  embedder: QueryEmbedder,
  embeddingConfig: EmbeddingModelConfig
):

  private val defaultTokenBudget = 4000

  def retrieve(
    researcherIds: Vector[String],
    communityIds: Vector[String],
    queryText: String,
    topK: Int = 10
  ): SemanticContextBundle =
    val queryId = IdGen.runId()
    val now     = java.time.Instant.now().toString

    embedQuery(queryText) match
      case Left(failure) => abstain(queryId, failure)
      case Right(embedding) =>
        val queryVector = embedding.vector
        val modelId     = embedding.modelId

        def q(indexName: String, docType: String): List[SemanticContext] =
          queryIndex(indexName, queryVector, modelId, topK, docType, queryId, now)

        val bundle = SemanticContextBundle(
          queryId = queryId,
          researcherContexts = q("researcher-profile-v1", "researcher"),
          communityContexts = q("collaboration-community-v1", "community"),
          pathContexts = q("collaboration-path-v1", "path"),
          paperContexts = q("paper-abstract-v1", "paper"),
          topicContexts = q("topic-institution-summary-v1", "topic"),
          totalTokenEstimate = 0,
          truncated = false
        )
        SemanticContextFormat.trimToTokenBudget(bundle, defaultTokenBudget)

  /** Embed and validate; Left means no vector index may be queried. */
  private[rag] def embedQuery(
    queryText: String
  ): Either[SemanticRetrievalFailure, QueryEmbedding] =
    val index      = embeddingConfig.index
    val queryModel = embeddingConfig.queryModelId
    if !index.isCompatibleModel(queryModel) then
      Left(modelMismatch(s"configured query model '$queryModel'", index.embeddingModelId))
    else
      val embedded =
        try Right(embedder.embed(queryText, queryModel))
        catch
          case NonFatal(e) =>
            Left(
              SemanticRetrievalFailure(
                SemanticFailureReason.EmbeddingUnavailable,
                s"embedding with model '$queryModel' failed: ${e.getClass.getSimpleName}"
              )
            )
      embedded.flatMap { emb =>
        if !index.isCompatibleModel(emb.modelId) then
          Left(modelMismatch(s"embedder returned model '${emb.modelId}'", index.embeddingModelId))
        else if !index.isCompatibleVector(emb.vector.length) then
          Left(
            SemanticRetrievalFailure(
              SemanticFailureReason.EmbeddingDimensionMismatch,
              s"query vector length ${emb.vector.length} != index dimension ${index.dimensions}"
            )
          )
        else Right(emb)
      }

  private def modelMismatch(what: String, indexModel: String): SemanticRetrievalFailure =
    SemanticRetrievalFailure(
      SemanticFailureReason.EmbeddingModelMismatch,
      s"$what is not the index model '$indexModel'; " +
        "a different model requires a separate, re-embedded index set"
    )

  private def abstain(queryId: String, failure: SemanticRetrievalFailure): SemanticContextBundle =
    SemanticContextBundle(
      queryId = queryId,
      researcherContexts = Nil,
      communityContexts = Nil,
      pathContexts = Nil,
      paperContexts = Nil,
      topicContexts = Nil,
      totalTokenEstimate = 0,
      truncated = false,
      failure = Some(failure)
    )

  private def queryIndex(
    indexName: String,
    queryVector: Vector[Float],
    embeddingModelId: String,
    topK: Int,
    docType: String,
    queryId: String,
    timestamp: String
  ): List[SemanticContext] =
    val results = vectors.query(indexName, queryVector, topK, None)
    results.flatMap { r =>
      val textOpt = text.getText(r.textS3Uri)
      textOpt.map { txt =>
        SemanticContext(
          contextId = s"ctx-${r.key}",
          entityId = r.entityId,
          documentType = docType,
          text = txt,
          textS3Uri = r.textS3Uri,
          vectorScore = r.score,
          embeddingModel = embeddingModelId,
          windowStartYear = 2000,
          windowEndYear = 2026,
          provenance = Provenance(
            sourceDataset = indexName,
            sourceRecordId = r.key,
            pipelineRunId = queryId,
            schemaVersion = "v1",
            timestamp = timestamp
          )
        )
      }
    }.toList
