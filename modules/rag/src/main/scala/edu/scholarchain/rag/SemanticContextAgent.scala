package edu.scholarchain.rag

import edu.scholarchain.core.*

/** Retrieves and assembles semantic context from vector indexes and S3 text. */
class SemanticContextAgent(vectors: VectorStore, text: TextStore):

  private val defaultTokenBudget = 4000

  def retrieve(
      researcherIds: Vector[String],
      communityIds: Vector[String],
      queryText: String,
      topK: Int = 10
  ): SemanticContextBundle =
    val queryVector = simpleQueryVector(queryText)
    val queryId     = IdGen.runId()
    val now         = java.time.Instant.now().toString

    val researcherCtxs = queryIndex(
      "researcher-profile-v1",
      queryVector,
      topK,
      "researcher",
      queryId,
      now
    )
    val communityCtxs = queryIndex(
      "collaboration-community-v1",
      queryVector,
      topK,
      "community",
      queryId,
      now
    )
    val pathCtxs =
      queryIndex("collaboration-path-v1", queryVector, topK, "path", queryId, now)
    val paperCtxs =
      queryIndex("paper-abstract-v1", queryVector, topK, "paper", queryId, now)
    val topicCtxs = queryIndex(
      "topic-institution-summary-v1",
      queryVector,
      topK,
      "topic",
      queryId,
      now
    )

    val bundle = SemanticContextBundle(
      queryId = queryId,
      researcherContexts = researcherCtxs,
      communityContexts = communityCtxs,
      pathContexts = pathCtxs,
      paperContexts = paperCtxs,
      topicContexts = topicCtxs,
      totalTokenEstimate = 0,
      truncated = false
    )
    SemanticContextFormat.trimToTokenBudget(bundle, defaultTokenBudget)

  private def queryIndex(
      indexName: String,
      queryVector: Vector[Float],
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
          embeddingModel = "titan-embed-text-v2",
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

  /** Placeholder vector generation. In production this calls the embedding model. */
  private def simpleQueryVector(queryText: String): Vector[Float] =
    val hash = queryText.hashCode
    (0 until 256).map(i => ((hash + i) % 100).toFloat / 100.0f).toVector
